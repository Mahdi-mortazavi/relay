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

    /// <summary>
    /// The code box validates a keystroke and then hands the box to the decoder,
    /// so whatever it normalises with has to agree with the decoder on every
    /// input — including the ones neither of them accepts. It once stripped any
    /// non-alphanumeric character, so "ABCD.EFGH" validated as ready and was
    /// then rejected as invalid by the decoder a line later.
    /// </summary>
    [Fact]
    public void Normalize_agrees_with_Decode_on_separators_outside_the_contract()
    {
        var code = TypedCodes.GetProperty("valid")[0].GetProperty("code").GetString()!;

        // In the contract: stripped, so the code still decodes.
        Assert.Equal(code, TypedCode.Normalize($" {code.ToLowerInvariant()} ".Insert(5, "-")));

        // Outside it: kept, so the box sees a character the alphabet lacks and
        // says so, instead of accepting a code the decoder will refuse.
        foreach (var separator in new[] { '.', '_', '/', '+' })
        {
            var typed = code.Insert(4, separator.ToString());
            var normalized = TypedCode.Normalize(typed);

            Assert.Contains(separator, normalized);
            Assert.Null(TypedCode.Decode(typed));
            // The box's own verdict, reached the same way it reaches it.
            Assert.Contains(normalized, c => !TypedCode.Alphabet.Contains(c));
        }
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
