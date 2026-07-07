using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

public class TypedCodeTests
{
    private static readonly System.Text.Json.JsonElement TypedCodes =
        SharedContracts.Json("test-vectors.json").RootElement.GetProperty("typedCodes");

    [Fact]
    public void Valid_vectors_encode_to_the_canonical_code()
    {
        foreach (var vector in TypedCodes.GetProperty("valid").EnumerateArray())
        {
            Assert.Equal(
                vector.GetProperty("code").GetString(),
                TypedCode.Encode(
                    vector.GetProperty("host").GetString()!,
                    vector.GetProperty("port").GetInt32()));
        }
    }

    [Fact]
    public void Valid_vectors_decode_back_to_host_and_port()
    {
        foreach (var vector in TypedCodes.GetProperty("valid").EnumerateArray())
        {
            var decoded = TypedCode.Decode(vector.GetProperty("code").GetString()!);
            Assert.NotNull(decoded);
            Assert.Equal(vector.GetProperty("host").GetString(), decoded.Value.Host);
            Assert.Equal(vector.GetProperty("port").GetInt32(), decoded.Value.Port);
        }
    }

    [Fact]
    public void Input_is_case_insensitive_and_separator_tolerant()
    {
        var code = TypedCodes.GetProperty("valid")[0].GetProperty("code").GetString()!;
        var relaxed = string.Join("-", code.ToLowerInvariant().Chunk(4).Select(c => new string(c)));
        Assert.Equal(TypedCode.Decode(code), TypedCode.Decode(relaxed));
    }

    [Fact]
    public void Invalid_codes_are_rejected()
    {
        foreach (var vector in TypedCodes.GetProperty("invalid").EnumerateArray())
        {
            Assert.Null(TypedCode.Decode(vector.GetProperty("code").GetString()!));
        }
    }

    [Fact]
    public void Hosts_outside_192_168_are_not_encodable()
    {
        Assert.Null(TypedCode.Encode("10.0.0.1", 1080));
        Assert.Null(TypedCode.Encode("172.16.5.1", 1080));
    }
}
