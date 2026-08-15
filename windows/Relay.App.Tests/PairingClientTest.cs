using System.Text.Json;
using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// The PC's half of the pairing exchange, against the same
/// /shared/test-vectors.json the phone's suite asserts on. That shared origin is
/// the point: this is the only thing stopping the two implementations from
/// drifting into a pair of apps that each pass their own tests and cannot talk
/// to each other.
///
/// Everything the phone sends is untrusted until the tunnel handshakes — the
/// beacon that named the address was an unauthenticated broadcast — so most of
/// what matters here is what this client *refuses*.
/// </summary>
public class PairingClientTest
{
    // Cloned so it outlives the document it was read from.
    private static readonly JsonElement Vectors = LoadVectors();

    private static JsonElement LoadVectors()
    {
        using var document = SharedContracts.Json("test-vectors.json");
        return document.RootElement.GetProperty("pairingExchange").Clone();
    }

    /// <summary>A phone that says exactly one thing, without a socket in sight.</summary>
    private sealed class Scripted(string? reply) : PairingClient.IConnector, PairingClient.IExchange
    {
        public string? Sent;
        public bool Disposed;

        public PairingClient.IExchange Open(string host, int port, TimeSpan timeout) => this;
        public void Send(string line) => Sent = line;
        public string? Receive(TimeSpan timeout) => reply;
        public void Dispose() => Disposed = true;
    }

    private sealed class Unreachable : PairingClient.IConnector
    {
        public PairingClient.IExchange Open(string host, int port, TimeSpan timeout) =>
            throw new TimeoutException("nothing there");
    }

    [Fact]
    public void TheRequestIsTheOneTheContractPins()
    {
        var expected = Vectors.GetProperty("request").GetString();
        Assert.Equal(expected, PairingClient.Request("MAHDI-LAPTOP"));

        // The name is optional, and the phone's suite accepts it either way.
        Assert.Equal("""{"v":1,"pair":1}""", PairingClient.Request(null));
    }

    [Fact]
    public void ANameIsTrimmedBecauseItIsShownOnSomeoneElsesPhone()
    {
        var request = PairingClient.Request(new string('x', 80));
        using var json = JsonDocument.Parse(request);
        Assert.Equal(32, json.RootElement.GetProperty("name").GetString()!.Length);

        // Control characters would be interpolated into a prompt on the phone.
        var withNewline = "desk" + (char)10 + "top";
        using var cleaned = JsonDocument.Parse(PairingClient.Request(withNewline));
        var name = cleaned.RootElement.GetProperty("name").GetString()!;
        Assert.Equal("desktop", name);
        Assert.DoesNotContain(name, char.IsControl);
    }

    [Fact]
    public void AnAllowedExchangeYieldsTheConfigurationFromTheVector()
    {
        var allowed = Vectors.GetProperty("allowed");
        var result = PairingClient.Parse(allowed.GetProperty("response").GetString()!);

        Assert.True(result.Ok);
        Assert.Equal(allowed.GetProperty("host").GetString(), result.Host);
        Assert.Equal(allowed.GetProperty("port").GetInt32(), result.Port);

        // Field for field against the vector's wg block — the same block the QR
        // path produces, so both ways in build the same tunnel.
        var wg = allowed.GetProperty("wg");
        Assert.Equal(wg.GetProperty("serverPublicKey").GetString(), result.Wg!.ServerPublicKey);
        Assert.Equal(wg.GetProperty("clientPrivateKey").GetString(), result.Wg.ClientPrivateKey);
        Assert.Equal(wg.GetProperty("allowedIps").GetString(), result.Wg.AllowedIps);
        Assert.Equal(wg.GetProperty("endpointPort").GetInt32(), result.Wg.EndpointPort);
        Assert.Equal(wg.GetProperty("dns").GetString(), result.Wg.Dns);
    }

    [Fact]
    public void ADenialIsReportedAsItselfRatherThanAsABrokenPhone()
    {
        // "Could not reach the phone" would send someone to their router over a
        // prompt they declined two seconds earlier.
        var denied = Vectors.GetProperty("denied").GetString()!;
        var result = PairingClient.Parse(denied);

        Assert.False(result.Ok);
        Assert.Equal("ERR_PAIRING_DENIED", result.ErrorCode);
        Assert.Null(result.Wg);
    }

    [Fact]
    public void AnOlderPhoneIsNamedAsOlder()
    {
        var response = Vectors.GetProperty("versionMismatch").GetProperty("response").GetString()!;
        Assert.Equal("ERR_PAIRING_VERSION", PairingClient.Parse(response).ErrorCode);
    }

    [Fact]
    public void EveryMalformedResponseTheContractListsIsRefused()
    {
        var responses = Vectors.GetProperty("malformedResponses").GetProperty("responses");
        Assert.NotEmpty(responses.EnumerateArray());

        foreach (var response in responses.EnumerateArray())
        {
            var raw = response.GetString()!;
            var result = PairingClient.Parse(raw);
            Assert.False(result.Ok, $"should have refused: {raw}");
            Assert.Equal("ERR_QR_INVALID", result.ErrorCode);
            // The dangerous outcome is a half-built tunnel, so nothing partial
            // may escape a rejection.
            Assert.Null(result.Wg);
        }
    }

    [Fact]
    public void TheExchangeSendsTheRequestAndClosesTheConnection()
    {
        var phone = new Scripted(Vectors.GetProperty("allowed").GetProperty("response").GetString());
        var result = new PairingClient(phone).Fetch("192.168.1.14", 47655, "MAHDI-LAPTOP");

        Assert.True(result.Ok);
        Assert.Equal(Vectors.GetProperty("request").GetString(), phone.Sent);
        // The phone allows one connection at a time; leaving this open would
        // block the next computer.
        Assert.True(phone.Disposed, "the connection must be closed");
    }

    [Fact]
    public void APhoneThatHangsUpWithoutAnsweringIsNotAPairingFailure()
    {
        // Silence is the phone going away, not a refusal — and the difference
        // decides whether the user retries or goes looking for the prompt.
        var result = new PairingClient(new Scripted(null)).Fetch("192.168.1.14", 47655);
        Assert.Equal("ERR_HOST_UNREACHABLE", result.ErrorCode);
    }

    [Fact]
    public void APhoneThatIsNotThereSaysSo()
    {
        var result = new PairingClient(new Unreachable()).Fetch("192.168.1.14", 47655);
        Assert.Equal("ERR_HOST_UNREACHABLE", result.ErrorCode);
    }
}
