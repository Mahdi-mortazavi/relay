using System.Text.Json;
using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

public class QrPayloadCodecTests
{
    private static readonly JsonElement Vectors =
        SharedContracts.Json("test-vectors.json").RootElement;

    [Fact]
    public void Valid_vectors_decode_to_the_expected_payload()
    {
        foreach (var vector in Vectors.GetProperty("valid").EnumerateArray())
        {
            var expected = vector.GetProperty("payload").Deserialize<QrPayload>()!;
            var result = QrPayloadCodec.Decode(vector.GetProperty("encoded").GetString()!);
            Assert.True(result.IsOk, $"{vector.GetProperty("description")}: {result.Reason}");
            Assert.Equal(expected, result.Payload);
        }
    }

    [Fact]
    public void Valid_vectors_round_trip_through_encode()
    {
        foreach (var vector in Vectors.GetProperty("valid").EnumerateArray())
        {
            var payload = vector.GetProperty("payload").Deserialize<QrPayload>()!;
            var result = QrPayloadCodec.Decode(QrPayloadCodec.Encode(payload));
            Assert.True(result.IsOk);
            Assert.Equal(payload, result.Payload);
        }
    }

    [Fact]
    public void Invalid_payloads_are_rejected_with_the_exact_shared_reason()
    {
        foreach (var vector in Vectors.GetProperty("invalid").EnumerateArray())
        {
            var result = QrPayloadCodec.Validate(vector.GetProperty("payload"));
            Assert.False(result.IsOk, vector.GetProperty("description").GetString());
            Assert.Equal(vector.GetProperty("reason").GetString(), result.Reason);
        }
    }

    [Fact]
    public void Malformed_encodings_are_rejected_as_decode_error()
    {
        foreach (var vector in Vectors.GetProperty("invalidEncoded").EnumerateArray())
        {
            var result = QrPayloadCodec.Decode(vector.GetProperty("encoded").GetString()!);
            Assert.False(result.IsOk, vector.GetProperty("description").GetString());
            Assert.Equal(vector.GetProperty("reason").GetString(), result.Reason);
        }
    }

    [Fact]
    public void Qr_prefix_is_accepted_and_produced()
    {
        var encoded = Vectors.GetProperty("valid")[0].GetProperty("encoded").GetString()!;
        var bare = QrPayloadCodec.Decode(encoded);
        var prefixed = QrPayloadCodec.Decode(QrPayloadCodec.QrPrefix + encoded);
        Assert.Equal(bare.Payload, prefixed.Payload);
        Assert.StartsWith(QrPayloadCodec.QrPrefix, QrPayloadCodec.EncodeForQr(bare.Payload!));
    }
}
