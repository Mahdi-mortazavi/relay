using System.Text.Json;
using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// The PC's half of "Always allow" (/shared/pairing-beacon.md → "Trusted
/// computers"; ADR-0012), against the same <c>trustedComputers</c> vectors the
/// phone's suite asserts. That shared origin is the point: the key, the message
/// layout and the <c>r ‖ s</c> signature format are all things two independent
/// implementations can silently disagree about while each passes its own tests.
/// </summary>
public class TrustedComputerTest
{
    private static readonly JsonElement Vectors = Load();

    private static JsonElement Load()
    {
        using var document = SharedContracts.Json("test-vectors.json");
        return document.RootElement.GetProperty("trustedComputers").Clone();
    }

    private static byte[]? Bytes(JsonElement element, string name)
    {
        try { return Convert.FromBase64String(element.GetProperty(name).GetString()!); }
        catch (FormatException) { return null; }
    }

    private static bool Verdict(JsonElement vector)
    {
        var key = Bytes(vector, "key");
        var challenge = Bytes(vector, "challenge");
        var proof = Bytes(vector, "proof");
        return key is not null && challenge is not null && proof is not null
            && DeviceIdentity.Verify(key, challenge, proof);
    }

    [Fact]
    public void EveryValidVectorVerifies()
    {
        foreach (var vector in Vectors.GetProperty("valid").EnumerateArray())
            Assert.True(Verdict(vector), vector.GetProperty("name").GetString());
    }

    [Fact]
    public void EveryInvalidVectorIsRefused()
    {
        foreach (var vector in Vectors.GetProperty("invalid").EnumerateArray())
            Assert.False(Verdict(vector), vector.GetProperty("name").GetString());
    }

    [Fact]
    public void TheDomainAndMessageLayoutAreTheContractsOwn()
    {
        Assert.Equal(Vectors.GetProperty("domain").GetString(), DeviceIdentity.Domain);

        var key = Enumerable.Repeat((byte)7, 65).ToArray();
        var challenge = Enumerable.Repeat((byte)9, 32).ToArray();
        var message = DeviceIdentity.Message(challenge, key);
        Assert.Equal(15 + 32 + 65, message.Length);
        Assert.Equal("relay-trust-v1\n", System.Text.Encoding.ASCII.GetString(message[..15]));
        Assert.Equal(challenge, message[15..47]);
        Assert.Equal(key, message[47..]);
    }

    [Fact]
    public void TheFingerprintIsTheVectors()
    {
        var fingerprint = Vectors.GetProperty("fingerprint");
        Assert.Equal(
            fingerprint.GetProperty("value").GetString(),
            DeviceIdentity.FingerprintOf(Bytes(fingerprint, "key")!));
    }

    [Fact]
    public void TheRequestCarryingAKeyIsTheOneTheContractPins()
    {
        var key = Vectors.GetProperty("valid")[0].GetProperty("key").GetString();
        Assert.Equal(
            Vectors.GetProperty("request").GetString(),
            PairingClient.Request("MAHDI-LAPTOP", key));
    }

    [Fact]
    public void TheProofLineIsTheOneTheContractPins()
    {
        var proof = Bytes(Vectors.GetProperty("valid")[0], "proof")!;
        Assert.Equal(Vectors.GetProperty("proofLine").GetString(), PairingClient.ProofLine(proof));
    }

    [Fact]
    public void TheChallengeLineIsReadBackToItsBytes()
    {
        var expected = Bytes(Vectors.GetProperty("valid")[0], "challenge");
        Assert.Equal(expected, PairingClient.ReadChallenge(Vectors.GetProperty("challengeLine").GetString()!));
    }

    [Theory]
    [InlineData("""{"v":1,"ok":1}""")]
    [InlineData("""{"v":2,"challenge":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="}""")]
    [InlineData("""{"v":1,"challenge":"AAAA"}""")]
    [InlineData("""{"v":1,"challenge":7}""")]
    [InlineData("""{"v":1,"challenge":"not base64!!"}""")]
    [InlineData("not json")]
    [InlineData("")]
    public void ReplyThatIsNotAChallengeIsNotTreatedAsOne(string reply)
    {
        Assert.Null(PairingClient.ReadChallenge(reply));
    }

    [Fact]
    public void AKeyThatIsNotExactlyAKeyIsLeftOutOfTheRequest()
    {
        // The request is built by hand so the key needs no escaping; that is only
        // safe because anything that is not exactly 65 bytes of base64 never gets in.
        Assert.Equal("""{"v":1,"pair":1}""", PairingClient.Request(null, "AAAA"));
        Assert.Equal("""{"v":1,"pair":1}""", PairingClient.Request(null, "\"},\"x\":{\""));
        Assert.Equal("""{"v":1,"pair":1}""", PairingClient.Request(null, null));
    }

    [Fact]
    public void ASignatureThisPcMakesVerifiesAndIsInTheFixedWidthForm()
    {
        using var identity = DeviceIdentity.Create();
        var challenge = Enumerable.Range(0, 32).Select(i => (byte)(i * 3)).ToArray();

        var proof = identity.Sign(challenge);
        var key = Convert.FromBase64String(identity.PublicKey);

        Assert.Equal(DeviceIdentity.ProofBytes, proof.Length); // r || s, not DER
        Assert.Equal(DeviceIdentity.KeyBytes, key.Length);
        Assert.Equal(0x04, key[0]);
        Assert.True(DeviceIdentity.Verify(key, challenge, proof));

        // And it does not carry over to another challenge or another key.
        Assert.False(DeviceIdentity.Verify(key, challenge.Select(b => (byte)(b ^ 1)).ToArray(), proof));
        using var other = DeviceIdentity.Create();
        Assert.False(DeviceIdentity.Verify(Convert.FromBase64String(other.PublicKey), challenge, proof));
    }

    [Fact]
    public void ARequestMadeFromARealIdentityCarriesItsKey()
    {
        using var identity = DeviceIdentity.Create();
        using var json = JsonDocument.Parse(PairingClient.Request("PC", identity.PublicKey));
        Assert.Equal(identity.PublicKey, json.RootElement.GetProperty("key").GetString());
    }

    // ---- keeping the key ----

    private static string TempPath() =>
        Path.Combine(Path.GetTempPath(), "relay-identity-" + Guid.NewGuid().ToString("N"), "id.p8");

    [Fact]
    public void TheIdentityIsKeptAcrossRuns()
    {
        var path = TempPath();
        try
        {
            using var first = DeviceIdentity.TryLoadOrCreate(path)!;
            using var second = DeviceIdentity.TryLoadOrCreate(path)!;
            Assert.Equal(first.PublicKey, second.PublicKey);
            Assert.Equal(first.Fingerprint, second.Fingerprint);
            Assert.False(File.Exists(path + ".tmp"), "the temporary file is moved into place");
        }
        finally { Directory.Delete(Path.GetDirectoryName(path)!, recursive: true); }
    }

    [Fact]
    public void AFileThatIsNotAKeyIsReplacedRatherThanTrusted()
    {
        var path = TempPath();
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllText(path, "this is not a private key");

            using var identity = DeviceIdentity.TryLoadOrCreate(path)!;
            Assert.NotNull(identity);

            // The replacement was saved, so the next run sees the same key.
            using var again = DeviceIdentity.TryLoadOrCreate(path)!;
            Assert.Equal(identity.PublicKey, again.PublicKey);
        }
        finally { Directory.Delete(Path.GetDirectoryName(path)!, recursive: true); }
    }

    [Fact]
    public void WithNowhereToKeepAKeyThereIsNoIdentityAtAll()
    {
        // A key made fresh every run would let someone tap "Always allow" for a
        // computer that is never recognised again, so no key is sent instead.
        var blocker = Path.GetTempFileName();
        try
        {
            Assert.Null(DeviceIdentity.TryLoadOrCreate(Path.Combine(blocker, "sub", "id.p8")));
        }
        finally { File.Delete(blocker); }
    }

    // ---- the conversation ----

    /// <summary>A phone that says a fixed series of things, and records what it was told.</summary>
    private sealed class Conversation(params string?[] replies) : PairingClient.IConnector, PairingClient.IExchange
    {
        private readonly Queue<string?> _replies = new(replies);
        public List<string> Sent { get; } = [];

        public PairingClient.IExchange Open(string host, int port, TimeSpan timeout) => this;
        public void Send(string line) => Sent.Add(line);
        public string? Receive(TimeSpan timeout) => _replies.Count > 0 ? _replies.Dequeue() : null;
        public void Dispose() { }
    }

    private static string Allowed() =>
        JsonDocument.Parse(File.ReadAllText(Path.Combine(SharedContracts.Dir, "test-vectors.json")))
            .RootElement.GetProperty("pairingExchange").GetProperty("allowed").GetProperty("response").GetString()!;

    private static string ChallengeLine(byte[] challenge) =>
        $$"""{"v":1,"challenge":"{{Convert.ToBase64String(challenge)}}"}""";

    [Fact]
    public void ThePhoneChallengesAndThisPcAnswersWithAProofOfItsKey()
    {
        using var identity = DeviceIdentity.Create();
        var challenge = Enumerable.Range(100, 32).Select(i => (byte)i).ToArray();
        var phone = new Conversation(ChallengeLine(challenge), Allowed());

        var result = new PairingClient(phone, identity).Fetch("192.168.1.14", 47655, "MAHDI-LAPTOP");

        Assert.True(result.Ok);
        Assert.Equal(2, phone.Sent.Count);

        // First: the request, with the key.
        using var request = JsonDocument.Parse(phone.Sent[0]);
        Assert.Equal(identity.PublicKey, request.RootElement.GetProperty("key").GetString());

        // Second: a proof the phone can verify against that key and that challenge.
        using var proofLine = JsonDocument.Parse(phone.Sent[1]);
        var proof = Convert.FromBase64String(proofLine.RootElement.GetProperty("proof").GetString()!);
        Assert.True(DeviceIdentity.Verify(Convert.FromBase64String(identity.PublicKey), challenge, proof));
    }

    [Fact]
    public void APhoneThatDoesNotKnowAboutKeysIsServedExactlyAsBefore()
    {
        // It ignores the field and never challenges; the first reply is the answer.
        using var identity = DeviceIdentity.Create();
        var phone = new Conversation(Allowed());

        var result = new PairingClient(phone, identity).Fetch("192.168.1.14", 47655, "MAHDI-LAPTOP");

        Assert.True(result.Ok);
        Assert.Single(phone.Sent);
    }

    [Fact]
    public void WithNoIdentityNothingIsSentButTheOldRequest()
    {
        var phone = new Conversation(Allowed());
        var result = new PairingClient(phone).Fetch("192.168.1.14", 47655, "MAHDI-LAPTOP");

        Assert.True(result.Ok);
        Assert.Equal(PairingClient.Request("MAHDI-LAPTOP"), Assert.Single(phone.Sent));
        Assert.DoesNotContain("key", phone.Sent[0]);
    }

    [Fact]
    public void AChallengeWeNeverAskedForIsNotAnswered()
    {
        // No identity, so no key was sent; a phone that challenges anyway is not
        // one to hand a signature to. It is a reply this client cannot use.
        var phone = new Conversation(ChallengeLine(new byte[32]));
        var result = new PairingClient(phone).Fetch("192.168.1.14", 47655);

        Assert.False(result.Ok);
        Assert.Equal("ERR_QR_INVALID", result.ErrorCode);
        Assert.Single(phone.Sent);
    }

    [Fact]
    public void AMalformedChallengeIsNotSignedAndIsNotAPairing()
    {
        using var identity = DeviceIdentity.Create();
        var phone = new Conversation("""{"v":1,"challenge":"AAAA"}""");
        var result = new PairingClient(phone, identity).Fetch("192.168.1.14", 47655);

        Assert.False(result.Ok);
        Assert.Equal("ERR_QR_INVALID", result.ErrorCode);
        Assert.Single(phone.Sent);
    }

    [Fact]
    public void APhoneThatHangsUpAfterChallengingIsTheLinkGoingAway()
    {
        using var identity = DeviceIdentity.Create();
        var phone = new Conversation(ChallengeLine(new byte[32]), null);
        var result = new PairingClient(phone, identity).Fetch("192.168.1.14", 47655);

        Assert.Equal("ERR_HOST_UNREACHABLE", result.ErrorCode);
    }

    [Fact]
    public void ADenialAfterTheProofIsStillADenial()
    {
        using var identity = DeviceIdentity.Create();
        var phone = new Conversation(ChallengeLine(new byte[32]), """{"v":1,"error":"ERR_PAIRING_DENIED"}""");
        var result = new PairingClient(phone, identity).Fetch("192.168.1.14", 47655);

        Assert.Equal("ERR_PAIRING_DENIED", result.ErrorCode);
    }
}
