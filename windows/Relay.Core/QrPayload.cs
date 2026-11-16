using System.Text.Json.Serialization;

namespace Relay.Core;

/// <summary>
/// Versioned pairing payload. Wire contract: /shared/qr-payload.schema.json.
/// Any change happens there first.
/// </summary>
public sealed record QrPayload
{
    public const string ModeSocks5 = "socks5";
    public const string ModeWireguard = "wireguard";
    public const string TransportLan = "lan";
    public const string TransportWifiDirect = "wifi_direct";
    public const string TransportAdb = "adb";

    [JsonPropertyName("v")] public required int V { get; init; }
    [JsonPropertyName("mode")] public required string Mode { get; init; }
    [JsonPropertyName("host")] public required string Host { get; init; }
    [JsonPropertyName("port")] public required int Port { get; init; }
    [JsonPropertyName("name")] public string? Name { get; init; }
    [JsonPropertyName("issuedAt")] public long? IssuedAt { get; init; }
    [JsonPropertyName("transport")] public string? Transport { get; init; }
    [JsonPropertyName("wg")] public WgParams? Wg { get; init; }
}

public sealed record WgParams
{
    [JsonPropertyName("serverPublicKey")] public required string ServerPublicKey { get; init; }
    [JsonPropertyName("clientPrivateKey")] public required string ClientPrivateKey { get; init; }
    [JsonPropertyName("allowedIps")] public required string AllowedIps { get; init; }
    [JsonPropertyName("endpointPort")] public required int EndpointPort { get; init; }
    [JsonPropertyName("dns")] public required string Dns { get; init; }
    [JsonPropertyName("clientAddress")] public string? ClientAddress { get; init; }
    [JsonPropertyName("clientIpv6")] public string? ClientIpv6 { get; init; }
    [JsonPropertyName("dnsIpv6")] public string? DnsIpv6 { get; init; }
    [JsonPropertyName("dohUrl")] public string? DohUrl { get; init; }
}
