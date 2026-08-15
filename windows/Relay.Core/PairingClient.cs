using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace Relay.Core;

/// <summary>
/// Asks a phone for a tunnel configuration, so two digits are enough to connect
/// to a Full Mode phone without a camera.
///
/// Contract: /shared/pairing-beacon.md → "The pairing exchange"; ADR-0009. The
/// phone holds the request until the person holding it allows the connection,
/// so this can sit waiting for up to a minute and that is the normal case, not
/// a fault.
///
/// Everything that comes back is untrusted until the tunnel handshakes. A phone
/// is whatever answered on that address, and the beacon that named the address
/// was an unauthenticated broadcast — so a malformed or incomplete
/// configuration is a failure to report, never something to hand to the tunnel
/// and hope.
/// </summary>
public sealed class PairingClient(PairingClient.IConnector? connector = null)
{
    /// <summary>The exchange's outcome, with a stable code from docs/errors.md.</summary>
    public sealed record Result(WgParams? Wg, string? Host, int Port, string? ErrorCode)
    {
        public bool Ok => Wg is not null && ErrorCode is null;
        public static Result Fail(string code) => new(null, null, 0, code);
    }

    /// <summary>
    /// The connection, behind an interface so the exchange can be tested
    /// without a phone. A test that needs real hardware to check "what happens
    /// when the person taps Deny" is a test nobody runs.
    /// </summary>
    public interface IConnector
    {
        /// <summary>Opens a line to the phone. Throws if it cannot be reached.</summary>
        IExchange Open(string host, int port, TimeSpan timeout);
    }

    public interface IExchange : IDisposable
    {
        void Send(string line);

        /// <summary>The phone's single reply, or null if it closed without one.</summary>
        string? Receive(TimeSpan timeout);
    }

    /// <summary>
    /// How long to wait for the person to answer. Matches the phone's own
    /// 60-second fail-closed timeout, plus room for the round trip: giving up
    /// first would show a failure on the PC while the phone still has a prompt
    /// on screen, which is the most confusing outcome available.
    /// </summary>
    public static readonly TimeSpan ApprovalTimeout = TimeSpan.FromSeconds(75);

    /// <summary>How long to wait for the socket itself. A phone on the LAN answers at once.</summary>
    public static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(5);

    public const int Version = 1;

    private readonly IConnector _connector = connector ?? new TcpConnector();

    /// <summary>
    /// Asks <paramref name="host"/> for a configuration, identifying this PC by
    /// <paramref name="clientName"/> so the prompt on the phone names a computer
    /// rather than only an address.
    /// </summary>
    public Result Fetch(string host, int port, string? clientName = null)
    {
        IExchange exchange;
        try
        {
            exchange = _connector.Open(host, port, ConnectTimeout);
        }
        catch (Exception)
        {
            // The beacon said this phone was here a moment ago, so the useful
            // reading is "it went away", not "pairing is broken".
            return Result.Fail("ERR_HOST_UNREACHABLE");
        }

        using (exchange)
        {
            try
            {
                exchange.Send(Request(clientName));
                var reply = exchange.Receive(ApprovalTimeout);
                if (reply is null) return Result.Fail("ERR_HOST_UNREACHABLE");
                return Parse(reply);
            }
            catch (Exception)
            {
                return Result.Fail("ERR_HOST_UNREACHABLE");
            }
        }
    }

    /// <summary>The request from the contract. Asserted byte-for-byte by the shared vectors.</summary>
    public static string Request(string? clientName = null)
    {
        var name = Sanitize(clientName);
        return name is null
            ? $$"""{"v":{{Version}},"pair":1}"""
            : $$"""{"v":{{Version}},"pair":1,"name":{{JsonSerializer.Serialize(name)}}}""";
    }

    /// <summary>≤ 32 chars and no control characters; this is display data on someone else's phone.</summary>
    private static string? Sanitize(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) return null;
        var clean = new string(name.Where(ch => !char.IsControl(ch)).ToArray()).Trim();
        if (clean.Length == 0) return null;
        return clean.Length > 32 ? clean[..32] : clean;
    }

    /// <summary>
    /// Turns the phone's reply into a configuration or a reason.
    ///
    /// Public because the interesting behaviour of this class is what it does
    /// with the bytes on the wire, and every rejection here is a case the
    /// shared vectors pin.
    /// </summary>
    public static Result Parse(string reply)
    {
        JsonElement root;
        try
        {
            using var json = JsonDocument.Parse(reply);
            root = json.RootElement.Clone();
        }
        catch (JsonException)
        {
            return Result.Fail("ERR_QR_INVALID");
        }

        if (root.ValueKind != JsonValueKind.Object) return Result.Fail("ERR_QR_INVALID");

        // A refusal names itself, and is the only thing a denied client learns.
        if (root.TryGetProperty("error", out var error) && error.ValueKind == JsonValueKind.String)
        {
            return Result.Fail(error.GetString() switch
            {
                "ERR_PAIRING_DENIED" => "ERR_PAIRING_DENIED",
                "ERR_PAIRING_VERSION" => "ERR_PAIRING_VERSION",
                _ => "ERR_QR_INVALID",
            });
        }

        if (!root.TryGetProperty("wg", out var wg) || wg.ValueKind != JsonValueKind.Object)
            return Result.Fail("ERR_QR_INVALID");

        var host = root.TryGetProperty("host", out var h) ? h.GetString() : null;
        if (string.IsNullOrWhiteSpace(host)) return Result.Fail("ERR_QR_INVALID");

        if (!root.TryGetProperty("port", out var p) || p.ValueKind != JsonValueKind.Number)
            return Result.Fail("ERR_QR_INVALID");
        var port = p.GetInt32();
        if (port is < 1 or > 65535) return Result.Fail("ERR_QR_INVALID");

        // Same failure as a QR that cannot build a tunnel, so the same code: the
        // phone described something this client cannot dial.
        var parameters = ReadWg(wg);
        return parameters is null
            ? Result.Fail("ERR_QR_INVALID")
            : new Result(parameters, host, port, null);
    }

    /// <summary>
    /// Reads the `wg` block, which is the one from qr-payload.schema.json. Every
    /// field is required: a tunnel missing any of them handshakes with nobody,
    /// and finding that out as a timeout is far worse than finding it out here.
    /// </summary>
    private static WgParams? ReadWg(JsonElement wg)
    {
        var serverPublicKey = Text(wg, "serverPublicKey");
        var clientPrivateKey = Text(wg, "clientPrivateKey");
        var allowedIps = Text(wg, "allowedIps");
        var dns = Text(wg, "dns");
        if (serverPublicKey is null || clientPrivateKey is null || allowedIps is null || dns is null)
            return null;

        if (!wg.TryGetProperty("endpointPort", out var ep) || ep.ValueKind != JsonValueKind.Number)
            return null;
        var endpointPort = ep.GetInt32();
        if (endpointPort is < 1 or > 65535) return null;

        return new WgParams
        {
            ServerPublicKey = serverPublicKey,
            ClientPrivateKey = clientPrivateKey,
            AllowedIps = allowedIps,
            EndpointPort = endpointPort,
            Dns = dns,
        };
    }

    private static string? Text(JsonElement parent, string name) =>
        parent.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() is { Length: > 0 } s ? s : null
            : null;

    /// <summary>The real connector: one line out, one line back, then closed.</summary>
    private sealed class TcpConnector : IConnector
    {
        public IExchange Open(string host, int port, TimeSpan timeout)
        {
            var client = new TcpClient();
            if (!client.ConnectAsync(host, port).Wait(timeout))
            {
                client.Dispose();
                throw new TimeoutException($"{host}:{port} did not answer");
            }
            return new TcpExchange(client);
        }

        private sealed class TcpExchange(TcpClient client) : IExchange
        {
            private readonly StreamWriter _writer =
                new(client.GetStream(), new UTF8Encoding(false)) { AutoFlush = true, NewLine = "\n" };
            private readonly StreamReader _reader = new(client.GetStream(), new UTF8Encoding(false));

            public void Send(string line) => _writer.WriteLine(line);

            public string? Receive(TimeSpan timeout)
            {
                client.ReceiveTimeout = (int)timeout.TotalMilliseconds;
                return _reader.ReadLine();
            }

            public void Dispose()
            {
                try { _reader.Dispose(); } catch { }
                try { _writer.Dispose(); } catch { }
                try { client.Dispose(); } catch { }
            }
        }
    }
}
