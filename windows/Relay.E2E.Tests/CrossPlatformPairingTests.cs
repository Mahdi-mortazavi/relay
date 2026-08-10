using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using Relay.Core;
using Xunit;

namespace Relay.E2E.Tests;

/// <summary>
/// The host half of the cross-platform E2E. A real Relay app is running on a
/// real Android image next to this process; it has advertised a pairing payload
/// and is holding the session open (see
/// <c>android/.../e2e/CrossPlatformSessionTest.kt</c>). Everything here runs the
/// shipping <see cref="Relay.Core"/> code the Windows app uses — if the two
/// platforms ever drift, this fails instead of a user.
///
/// Configured entirely by environment, set by <c>.github/workflows/e2e.yml</c>:
///   RELAY_PAIRING_FILE  pairing.json pulled off the device
///   RELAY_SOCKS_PORT    local port that adb-forwards to the phone's SOCKS server
///   RELAY_HOST_ALIAS    address the phone uses to reach this runner (10.0.2.2)
/// </summary>
[Collection("e2e")]
public sealed class CrossPlatformPairingTests
{
    private static readonly Pairing Device = Pairing.Load();

    /// <summary>
    /// The single most important cross-platform assertion: the Windows decoder
    /// accepts the exact string the phone put in its QR code.
    /// </summary>
    [Fact]
    public void WindowsDecodesThePayloadThePhoneActuallyIssued()
    {
        var result = QrPayloadCodec.Decode(Device.Qr);

        Assert.True(result.IsOk, $"Windows rejected the phone's QR payload: {result.Reason}");
        var payload = result.Payload!;
        Assert.Equal(QrPayloadCodec.SupportedVersion, payload.V);
        Assert.Equal(QrPayload.ModeSocks5, payload.Mode);
        Assert.Equal(Device.Host, payload.Host);
        Assert.Equal(Device.Port, payload.Port);
        Assert.False(string.IsNullOrWhiteSpace(payload.Name));
    }

    /// <summary>A payload that round-trips must survive re-encoding unchanged.</summary>
    [Fact]
    public void ThePayloadRoundTripsThroughTheWindowsCodec()
    {
        var payload = QrPayloadCodec.Decode(Device.Qr).Payload!;
        var reEncoded = QrPayloadCodec.EncodeForQr(payload);

        Assert.True(QrPayloadCodec.Decode(reEncoded).IsOk);
        Assert.Equal(payload, QrPayloadCodec.Decode(reEncoded).Payload);
    }

    /// <summary>
    /// Both platforms must agree about when the typed-code fallback exists at
    /// all — it only covers 192.168.0.0/16 (shared/typed-code.md, backlog #18).
    /// </summary>
    [Fact]
    public void TypedCodeAvailabilityMatchesTheDevice()
    {
        var windowsCode = TypedCode.Encode(Device.Host, Device.Port);

        Assert.Equal(Device.TypedCode, windowsCode);
        if (windowsCode is not null)
        {
            var decoded = TypedCode.Decode(windowsCode);
            Assert.NotNull(decoded);
            Assert.Equal(Device.Host, decoded!.Value.Host);
            Assert.Equal(Device.Port, decoded.Value.Port);
        }
    }

    /// <summary>
    /// The whole point of the product, end to end and off-device: this process
    /// pushes an HTTP request through the phone's SOCKS5 server and back out to
    /// a destination the phone dials on its own.
    /// </summary>
    [Fact]
    public async Task RealTrafficFlowsThroughThePhone()
    {
        const string body = "relay-cross-platform-ok";
        using var destination = new HttpProbeServer(body);

        using var proxy = await SocksClient.ConnectAsync(
            Config.SocksPort, Config.HostAlias, destination.Port);

        var response = await proxy.RequestAsync("GET /through-the-phone HTTP/1.1\r\nHost: relay\r\n\r\n", body);

        Assert.Contains("HTTP/1.1 200", response);
        Assert.Contains(body, response);
        Assert.True(destination.WasHit, "the phone never dialled the destination");
    }

    /// <summary>
    /// Domain destinations are resolved <em>on the phone</em> — that is what puts
    /// DNS inside the phone's VPN instead of leaking it from the PC.
    /// </summary>
    [Fact]
    public async Task ThePhoneResolvesDomainNamesForTheClient()
    {
        using var proxy = await SocksClient.ConnectByNameAsync(Config.SocksPort, "example.com", 80);
        var response = await proxy.RequestAsync(
            "GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n", "</html>");

        Assert.StartsWith("HTTP/1.1", response);
    }

    /// <summary>Red team: the proxy must reject what it does not implement, not hang or die.</summary>
    [Fact]
    public async Task ThePhoneRejectsUnsupportedSocksRequests()
    {
        // BIND is not implemented; the reply must say so.
        var reply = await SocksClient.RawRequestAsync(
            Config.SocksPort, command: 0x02, host: "127.0.0.1", port: 80);
        Assert.Equal((byte)0x05, reply[0]);
        Assert.Equal((byte)0x07, reply[1]); // REP_COMMAND_NOT_SUPPORTED

        // An unknown address type, likewise.
        var atyp = await SocksClient.RawRequestAsync(
            Config.SocksPort, command: 0x01, host: "127.0.0.1", port: 80, addressType: 0x09);
        Assert.Equal((byte)0x08, atyp[1]); // REP_ADDRESS_TYPE_NOT_SUPPORTED

        // And it is still serving afterwards.
        const string body = "still-alive";
        using var destination = new HttpProbeServer(body);
        using var proxy = await SocksClient.ConnectAsync(
            Config.SocksPort, Config.HostAlias, destination.Port);
        Assert.Contains(body, await proxy.RequestAsync("GET / HTTP/1.1\r\nHost: relay\r\n\r\n", body));
    }

    /// <summary>Several clients at once is the normal case for a browser.</summary>
    [Fact]
    public async Task ThePhoneServesConcurrentTunnels()
    {
        const string body = "concurrent-ok";
        using var destination = new HttpProbeServer(body);

        var requests = Enumerable.Range(0, 8).Select(async _ =>
        {
            using var proxy = await SocksClient.ConnectAsync(
                Config.SocksPort, Config.HostAlias, destination.Port);
            return await proxy.RequestAsync("GET / HTTP/1.1\r\nHost: relay\r\n\r\n", body);
        });

        foreach (var response in await Task.WhenAll(requests))
        {
            Assert.Contains(body, response);
        }
    }

    // --- fixtures -------------------------------------------------------------

    private static class Config
    {
        public static int SocksPort => int.Parse(Require("RELAY_SOCKS_PORT"));

        /// <summary>The emulator's alias for the host loopback.</summary>
        public static string HostAlias => Environment.GetEnvironmentVariable("RELAY_HOST_ALIAS") ?? "10.0.2.2";

        public static string Require(string name) =>
            Environment.GetEnvironmentVariable(name)
            ?? throw new InvalidOperationException(
                $"{name} is not set — this project is only meaningful inside the e2e workflow.");
    }

    private sealed record Pairing(string Qr, string Host, int Port, string? TypedCode)
    {
        public static Pairing Load()
        {
            var path = Config.Require("RELAY_PAIRING_FILE");
            using var json = JsonDocument.Parse(File.ReadAllText(path));
            var root = json.RootElement;
            return new Pairing(
                root.GetProperty("qr").GetString()!,
                root.GetProperty("host").GetString()!,
                root.GetProperty("port").GetInt32(),
                root.GetProperty("typedCode").ValueKind == JsonValueKind.Null
                    ? null
                    : root.GetProperty("typedCode").GetString());
        }
    }

    /// <summary>A destination on this runner that the phone has to dial back to.</summary>
    private sealed class HttpProbeServer : IDisposable
    {
        private readonly TcpListener _listener;
        private readonly CancellationTokenSource _cts = new();

        public HttpProbeServer(string body)
        {
            _listener = new TcpListener(IPAddress.Any, 0);
            _listener.Start();
            Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
            _ = ServeAsync(body, _cts.Token);
        }

        public int Port { get; }
        public bool WasHit { get; private set; }

        private async Task ServeAsync(string body, CancellationToken token)
        {
            var response = Encoding.ASCII.GetBytes(
                $"HTTP/1.1 200 OK\r\nContent-Length: {body.Length}\r\nConnection: close\r\n\r\n{body}");
            while (!token.IsCancellationRequested)
            {
                TcpClient client;
                try { client = await _listener.AcceptTcpClientAsync(token); }
                catch (OperationCanceledException) { return; }
                catch (SocketException) { return; }
                catch (ObjectDisposedException) { return; }

                WasHit = true;
                _ = Task.Run(async () =>
                {
                    using (client)
                    {
                        try
                        {
                            var stream = client.GetStream();
                            await stream.ReadAsync(new byte[4096], token);
                            await stream.WriteAsync(response, token);
                            await stream.FlushAsync(token);
                        }
                        catch (Exception) { /* the peer went away; nothing to do */ }
                    }
                }, token);
            }
        }

        public void Dispose()
        {
            _cts.Cancel();
            _listener.Stop();
            _cts.Dispose();
        }
    }
}
