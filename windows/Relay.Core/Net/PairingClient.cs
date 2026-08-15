using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace Relay.Core.Net;

/// <summary>
/// Implements the client side of the TCP pairing exchange from /shared/pairing-beacon.md (ADR-0009).
/// When pairing with a two-digit code in WireGuard mode, this connects to the phone's pairingPort,
/// requests the WireGuard configuration, waits for the user to tap "Allow" on the phone,
/// and returns the decoded QrPayload.
/// </summary>
public static class PairingClient
{
    public const int Version = 1;
    public static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(5);
    public static readonly TimeSpan ApprovalTimeout = TimeSpan.FromSeconds(65);

    public sealed record Result(bool Ok, QrPayload? Payload, string? ErrorCode);

    /// <summary>
    /// Builds the JSON request payload sent to the phone.
    /// Format: {"v":1,"pair":1,"name":"<client_name>"}
    /// </summary>
    public static string BuildRequest(string? clientName)
    {
        var name = string.IsNullOrWhiteSpace(clientName) ? null : clientName.Trim();
        if (name is { Length: > 32 }) name = name[..32];

        if (name is not null)
        {
            var escapedName = JsonSerializer.Serialize(name);
            return $"{{\"v\":{Version},\"pair\":1,\"name\":{escapedName}}}\n";
        }
        return $"{{\"v\":{Version},\"pair\":1}}\n";
    }

    /// <summary>
    /// Parses the JSON response received from the phone.
    /// Handles allowed, denied, version mismatch, and malformed responses.
    /// </summary>
    public static Result ParseResponse(string jsonResponse)
    {
        if (string.IsNullOrWhiteSpace(jsonResponse))
        {
            return new Result(false, null, "ERR_QR_INVALID");
        }

        try
        {
            using var doc = JsonDocument.Parse(jsonResponse);
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                return new Result(false, null, "ERR_QR_INVALID");
            }

            if (root.TryGetProperty("error", out var errProp) && errProp.ValueKind == JsonValueKind.String)
            {
                var error = errProp.GetString();
                return new Result(false, null, string.IsNullOrEmpty(error) ? "ERR_QR_INVALID" : error);
            }

            if (!root.TryGetProperty("ok", out var okProp) ||
                (okProp.ValueKind == JsonValueKind.Number && okProp.GetInt32() != 1) ||
                (okProp.ValueKind == JsonValueKind.True && !okProp.GetBoolean()) ||
                (okProp.ValueKind != JsonValueKind.Number && okProp.ValueKind != JsonValueKind.True))
            {
                return new Result(false, null, "ERR_QR_INVALID");
            }

            var host = root.TryGetProperty("host", out var hProp) ? hProp.GetString() : null;
            if (string.IsNullOrWhiteSpace(host))
            {
                return new Result(false, null, "ERR_QR_INVALID");
            }

            if (!root.TryGetProperty("port", out var portProp) || portProp.ValueKind != JsonValueKind.Number)
            {
                return new Result(false, null, "ERR_QR_INVALID");
            }
            var port = portProp.GetInt32();
            if (port is < 1 or > 65535)
            {
                return new Result(false, null, "ERR_QR_INVALID");
            }

            if (!root.TryGetProperty("wg", out var wgProp) || wgProp.ValueKind != JsonValueKind.Object)
            {
                return new Result(false, null, "ERR_QR_INVALID");
            }

            var serverPublicKey = wgProp.TryGetProperty("serverPublicKey", out var spkProp) ? spkProp.GetString() : null;
            var clientPrivateKey = wgProp.TryGetProperty("clientPrivateKey", out var cpkProp) ? cpkProp.GetString() : null;
            var allowedIps = wgProp.TryGetProperty("allowedIps", out var aipsProp) ? aipsProp.GetString() : null;
            var dns = wgProp.TryGetProperty("dns", out var dnsProp) ? dnsProp.GetString() : null;

            if (!wgProp.TryGetProperty("endpointPort", out var epProp) || epProp.ValueKind != JsonValueKind.Number)
            {
                return new Result(false, null, "ERR_QR_INVALID");
            }
            var endpointPort = epProp.GetInt32();

            // Validate WireGuard key lengths and presence
            if (string.IsNullOrWhiteSpace(serverPublicKey) ||
                string.IsNullOrWhiteSpace(clientPrivateKey) ||
                string.IsNullOrWhiteSpace(allowedIps) ||
                string.IsNullOrWhiteSpace(dns) ||
                endpointPort is < 1 or > 65535)
            {
                return new Result(false, null, "ERR_QR_INVALID");
            }

            var wg = new WgParams
            {
                ServerPublicKey = serverPublicKey,
                ClientPrivateKey = clientPrivateKey,
                AllowedIps = allowedIps,
                EndpointPort = endpointPort,
                Dns = dns,
            };

            var name = root.TryGetProperty("name", out var nProp) ? nProp.GetString() : null;

            var payload = new QrPayload
            {
                V = Version,
                Mode = QrPayload.ModeWireguard,
                Host = host,
                Port = port,
                Name = name,
                Wg = wg,
            };

            return new Result(true, payload, null);
        }
        catch (JsonException)
        {
            return new Result(false, null, "ERR_QR_INVALID");
        }
    }

    /// <summary>
    /// Connects to the phone over TCP, sends the pairing request, and awaits approval.
    /// </summary>
    public static async Task<Result> RequestConfigurationAsync(
        string host,
        int pairingPort,
        string? clientName = null,
        CancellationToken cancellationToken = default)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        cts.CancelAfter(ApprovalTimeout);

        try
        {
            using var client = new TcpClient();
            
            var connectTask = client.ConnectAsync(host, pairingPort, cts.Token).AsTask();
            var timeoutTask = Task.Delay(ConnectTimeout, cts.Token);
            var completed = await Task.WhenAny(connectTask, timeoutTask).ConfigureAwait(false);

            if (completed != connectTask || !client.Connected)
            {
                return new Result(false, null, "ERR_HOST_UNREACHABLE");
            }

            using var stream = client.GetStream();
            stream.ReadTimeout = (int)ApprovalTimeout.TotalMilliseconds;
            stream.WriteTimeout = (int)ConnectTimeout.TotalMilliseconds;

            var request = BuildRequest(clientName);
            var requestBytes = Encoding.UTF8.GetBytes(request);
            await stream.WriteAsync(requestBytes, cts.Token).ConfigureAwait(false);
            await stream.FlushAsync(cts.Token).ConfigureAwait(false);

            using var reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: false, bufferSize: 1024, leaveOpen: true);
            var responseLine = await reader.ReadLineAsync(cts.Token).ConfigureAwait(false);

            if (responseLine is null)
            {
                return new Result(false, null, "ERR_PAIRING_DENIED");
            }

            return ParseResponse(responseLine);
        }
        catch (OperationCanceledException)
        {
            return new Result(false, null, cancellationToken.IsCancellationRequested ? "ERR_CONNECTION_LOST" : "ERR_PAIRING_DENIED");
        }
        catch (SocketException)
        {
            return new Result(false, null, "ERR_HOST_UNREACHABLE");
        }
        catch (Exception)
        {
            return new Result(false, null, "ERR_QR_INVALID");
        }
    }
}
