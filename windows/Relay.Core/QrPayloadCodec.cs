using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace Relay.Core;

/// <summary>
/// Decode outcome. <see cref="Reason"/> values are the stable strings used in
/// /shared/test-vectors.json.
/// </summary>
public sealed record DecodeResult
{
    public QrPayload? Payload { get; private init; }
    public string? Reason { get; private init; }
    public bool IsOk => Reason is null;

    public static DecodeResult Ok(QrPayload payload) => new() { Payload = payload };
    public static DecodeResult Invalid(string reason) => new() { Reason = reason };
}

/// <summary>
/// Encodes/decodes the QR pairing payload: compact JSON -> UTF-8 -> base64url
/// (no padding). Validation mirrors the Android codec and /shared vectors.
/// </summary>
public static partial class QrPayloadCodec
{
    public const int SupportedVersion = 1;
    public const string QrPrefix = "relay://p/";

    private static readonly JsonSerializerOptions SerializeOptions = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    [GeneratedRegex(@"^((25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$")]
    private static partial Regex Ipv4Regex();

    /// <summary>base64 of a 32-byte Curve25519 key (matches /shared/qr-payload.schema.json).</summary>
    [GeneratedRegex(@"^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw480]=$")]
    private static partial Regex WgKeyRegex();

    public static string Encode(QrPayload payload)
    {
        var json = JsonSerializer.Serialize(payload, SerializeOptions);
        return Convert.ToBase64String(Encoding.UTF8.GetBytes(json))
            .TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }

    /// <summary>The exact string the phone renders into the QR image.</summary>
    public static string EncodeForQr(QrPayload payload) => QrPrefix + Encode(payload);

    public static DecodeResult Decode(string encoded)
    {
        var bare = encoded.StartsWith(QrPrefix, StringComparison.Ordinal)
            ? encoded[QrPrefix.Length..]
            : encoded;

        byte[] bytes;
        try
        {
            var base64 = bare.Replace('-', '+').Replace('_', '/');
            bytes = Convert.FromBase64String(base64.PadRight((base64.Length + 3) / 4 * 4, '='));
        }
        catch (FormatException)
        {
            return DecodeResult.Invalid("decode-error");
        }

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(Encoding.UTF8.GetString(bytes));
        }
        catch (JsonException)
        {
            return DecodeResult.Invalid("decode-error");
        }

        using (document)
        {
            if (document.RootElement.ValueKind != JsonValueKind.Object)
            {
                return DecodeResult.Invalid("decode-error");
            }
            return Validate(document.RootElement);
        }
    }

    /// <summary>Structural validation of an already-parsed JSON object.</summary>
    public static DecodeResult Validate(JsonElement obj)
    {
        if (!TryGetInt(obj, "v", out var version)) return DecodeResult.Invalid("missing-required-field");
        if (version != SupportedVersion) return DecodeResult.Invalid("unknown-version");

        if (!TryGetString(obj, "mode", out var mode)) return DecodeResult.Invalid("missing-required-field");
        if (!TryGetString(obj, "host", out var host)) return DecodeResult.Invalid("missing-required-field");
        if (!TryGetInt(obj, "port", out var port)) return DecodeResult.Invalid("missing-required-field");

        if (mode is not (QrPayload.ModeSocks5 or QrPayload.ModeWireguard))
        {
            return DecodeResult.Invalid("invalid-mode");
        }
        if (port is < 1 or > 65535) return DecodeResult.Invalid("invalid-port");
        if (!Ipv4Regex().IsMatch(host)) return DecodeResult.Invalid("invalid-host");

        var hasWg = obj.TryGetProperty("wg", out var wgElement);
        if (mode == QrPayload.ModeWireguard && !hasWg) return DecodeResult.Invalid("missing-wg-block");
        if (mode == QrPayload.ModeSocks5 && hasWg) return DecodeResult.Invalid("unexpected-wg-block");
        if (mode == QrPayload.ModeWireguard)
        {
            var wgResult = ValidateWg(wgElement);
            if (wgResult is not null) return wgResult;
        }

        try
        {
            var payload = obj.Deserialize<QrPayload>();
            return payload is null
                ? DecodeResult.Invalid("decode-error")
                : DecodeResult.Ok(payload);
        }
        catch (JsonException)
        {
            return DecodeResult.Invalid("decode-error");
        }
    }

    /// <summary>Validates the wg sub-object; returns an Invalid result on the first problem, else null.</summary>
    private static DecodeResult? ValidateWg(JsonElement wg)
    {
        if (wg.ValueKind != JsonValueKind.Object) return DecodeResult.Invalid("missing-wg-block");
        foreach (var field in new[] { "serverPublicKey", "clientPrivateKey", "allowedIps", "endpointPort", "dns" })
        {
            if (!wg.TryGetProperty(field, out _)) return DecodeResult.Invalid("missing-wg-field");
        }
        if (!TryGetString(wg, "serverPublicKey", out var serverKey) || !WgKeyRegex().IsMatch(serverKey))
        {
            return DecodeResult.Invalid("invalid-wg-key");
        }
        if (!TryGetString(wg, "clientPrivateKey", out var clientKey) || !WgKeyRegex().IsMatch(clientKey))
        {
            return DecodeResult.Invalid("invalid-wg-key");
        }
        if (!TryGetInt(wg, "endpointPort", out var endpointPort) || endpointPort is < 1 or > 65535)
        {
            return DecodeResult.Invalid("invalid-wg-port");
        }
        return null;
    }

    private static bool TryGetInt(JsonElement obj, string name, out int value)
    {
        value = 0;
        return obj.TryGetProperty(name, out var property)
            && property.ValueKind == JsonValueKind.Number
            && property.TryGetInt32(out value);
    }

    private static bool TryGetString(JsonElement obj, string name, out string value)
    {
        value = string.Empty;
        if (obj.TryGetProperty(name, out var property) && property.ValueKind == JsonValueKind.String)
        {
            value = property.GetString()!;
            return true;
        }
        return false;
    }
}
