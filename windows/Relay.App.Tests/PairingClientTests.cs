using System.Text.Json;
using Relay.Core.Net;
using Xunit;

namespace Relay.App.Tests;

public class PairingClientTests
{
    private static JsonElement PairingExchangeVectors =>
        SharedContracts.Json("test-vectors.json").RootElement.GetProperty("pairingExchange");

    [Fact]
    public void Emits_the_request_in_the_shared_contract()
    {
        var expected = PairingExchangeVectors.GetProperty("request").GetString() + "\n";
        var actual = PairingClient.BuildRequest("MAHDI-LAPTOP");
        Assert.Equal(expected, actual);
    }

    [Fact]
    public void Parses_the_allowed_response_in_the_shared_contract()
    {
        var allowed = PairingExchangeVectors.GetProperty("allowed");
        var responseJson = allowed.GetProperty("response").GetString()!;
        var expectedHost = allowed.GetProperty("host").GetString()!;
        var expectedPort = allowed.GetProperty("port").GetInt32();
        var expectedWg = allowed.GetProperty("wg");

        var result = PairingClient.ParseResponse(responseJson);
        Assert.True(result.Ok);
        Assert.NotNull(result.Payload);
        Assert.Equal(expectedHost, result.Payload!.Host);
        Assert.Equal(expectedPort, result.Payload.Port);
        Assert.NotNull(result.Payload.Wg);
        Assert.Equal(expectedWg.GetProperty("serverPublicKey").GetString(), result.Payload.Wg!.ServerPublicKey);
        Assert.Equal(expectedWg.GetProperty("clientPrivateKey").GetString(), result.Payload.Wg.ClientPrivateKey);
        Assert.Equal(expectedWg.GetProperty("allowedIps").GetString(), result.Payload.Wg.AllowedIps);
        Assert.Equal(expectedWg.GetProperty("endpointPort").GetInt32(), result.Payload.Wg.EndpointPort);
        Assert.Equal(expectedWg.GetProperty("dns").GetString(), result.Payload.Wg.Dns);
    }

    [Fact]
    public void Parses_the_denied_response()
    {
        var deniedJson = PairingExchangeVectors.GetProperty("denied").GetString()!;
        var result = PairingClient.ParseResponse(deniedJson);
        Assert.False(result.Ok);
        Assert.Equal("ERR_PAIRING_DENIED", result.ErrorCode);
    }

    [Fact]
    public void Parses_the_version_mismatch_response()
    {
        var versionJson = PairingExchangeVectors.GetProperty("versionMismatch").GetProperty("response").GetString()!;
        var result = PairingClient.ParseResponse(versionJson);
        Assert.False(result.Ok);
        Assert.Equal("ERR_PAIRING_VERSION", result.ErrorCode);
    }

    [Fact]
    public void Rejects_malformed_responses()
    {
        var malformed = PairingExchangeVectors.GetProperty("malformedResponses").GetProperty("responses");
        foreach (var item in malformed.EnumerateArray())
        {
            var json = item.GetString()!;
            var result = PairingClient.ParseResponse(json);
            Assert.False(result.Ok);
            Assert.Equal("ERR_QR_INVALID", result.ErrorCode);
        }
    }
}
