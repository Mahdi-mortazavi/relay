using System.Net.Sockets;
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
///   RELAY_PAIRING_PORT  local port that adb-forwards to the phone's pairing port
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
        Assert.Equal(QrPayload.ModeWireguard, payload.Mode);
        Assert.NotNull(payload.Wg);
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
    /// The assertion this whole leg exists for: the shipping Windows client and
    /// the shipping phone completing the pairing exchange against each other.
    ///
    /// Both sides are asserted against /shared/test-vectors.json in their own
    /// suites, which catches a platform drifting from the contract. It cannot
    /// catch the contract being wrong, or both sides reading it the same wrong
    /// way. This can: nothing here is a fixture, the bytes cross a real socket
    /// into a real app, and the phone answers with keys it really minted.
    /// </summary>
    [Fact]
    public void TheWindowsClientPairsWithTheLivePhone()
    {
        var result = new PairingClient().Fetch("127.0.0.1", Config.PairingPort, "cross-platform-e2e");

        Assert.True(result.Ok, $"pairing failed: {result.ErrorCode}");
        Assert.NotNull(result.Wg);

        // What came back has to be the tunnel the QR describes, or the two ways
        // in are two different products.
        var fromQr = QrPayloadCodec.Decode(Device.Qr).Payload!.Wg!;
        Assert.Equal(fromQr.ServerPublicKey, result.Wg!.ServerPublicKey);
        Assert.Equal(fromQr.ClientPrivateKey, result.Wg.ClientPrivateKey);
        Assert.Equal(fromQr.EndpointPort, result.Wg.EndpointPort);
        Assert.Equal(fromQr.AllowedIps, result.Wg.AllowedIps);
    }

    /// <summary>
    /// A key is only ever handed to a request the phone accepted, and a request
    /// it never understood is not accepted. The phone must say so rather than
    /// hang up, or a newer PC learns nothing and reports the phone as missing.
    /// </summary>
    [Fact]
    public void APhoneRefusesAVersionItDoesNotSpeak()
    {
        using var socket = new TcpClient();
        socket.Connect("127.0.0.1", Config.PairingPort);
        socket.ReceiveTimeout = 15_000;

        var writer = new StreamWriter(socket.GetStream())
        {
            AutoFlush = true,
            NewLine = ((char)10).ToString(),
        };
        writer.WriteLine("""{"v":2,"pair":1}""");
        var reply = new StreamReader(socket.GetStream()).ReadLine();

        Assert.Equal("ERR_PAIRING_VERSION", PairingClient.Parse(reply!).ErrorCode);
        Assert.DoesNotContain("clientPrivateKey", reply, StringComparison.Ordinal);
    }

    private static class Config
    {
        public static int PairingPort => int.Parse(Require("RELAY_PAIRING_PORT"));

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
}
