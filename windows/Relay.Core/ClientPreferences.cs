using System.Net;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Relay.Core;

/// <summary>
/// Per-user Windows client power preferences stored in %LOCALAPPDATA%\Relay\preferences.json.
/// </summary>
public sealed record ClientPreferences
{
    public const string DefaultAdbTcpEndpoint = "127.0.0.1:51821";
    public const int DefaultAdbPairingPort = 47655;

    [JsonPropertyName("enableIpv6Tunnel")]
    public bool EnableIpv6Tunnel { get; init; } = true;

    [JsonPropertyName("customDnsOverride")]
    public string? CustomDnsOverride { get; init; }

    public static readonly ClientPreferences Default = new();

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public static string DefaultPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Relay",
        "preferences.json");

    public ClientPreferences Sanitize(Action<string>? warn = null)
    {
        string? dns = null;
        if (!string.IsNullOrWhiteSpace(CustomDnsOverride))
        {
            var candidate = CustomDnsOverride.Trim();
            if (IPAddress.TryParse(candidate, out var addr) &&
                addr.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
            {
                dns = addr.ToString();
            }
            else
            {
                warn?.Invoke($"Ignored invalid custom DNS override '{candidate}' (expected IPv4 address)");
            }
        }
        return new ClientPreferences
        {
            EnableIpv6Tunnel = EnableIpv6Tunnel,
            CustomDnsOverride = dns,
        };
    }

    public static ClientPreferences LoadOrDefault(string? path = null, Action<string>? warn = null)
    {
        var target = path ?? DefaultPath;
        try
        {
            if (!File.Exists(target)) return Default;
            var json = File.ReadAllText(target);
            var parsed = JsonSerializer.Deserialize<ClientPreferences>(json, JsonOptions);
            return parsed is null ? Default : parsed.Sanitize(warn);
        }
        catch (Exception ex)
        {
            warn?.Invoke($"Failed to read preferences ({ex.Message}); using defaults");
            return Default;
        }
    }

    public static (bool Ok, ClientPreferences Saved, List<string> Warnings) Save(
        ClientPreferences preferences,
        string? path = null,
        Action<string>? log = null)
    {
        var target = path ?? DefaultPath;
        var warnings = new List<string>();
        var sanitized = preferences.Sanitize(msg =>
        {
            warnings.Add(msg);
            log?.Invoke(msg);
        });
        try
        {
            var dir = Path.GetDirectoryName(target);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            var temp = target + ".tmp";
            File.WriteAllText(temp, JsonSerializer.Serialize(sanitized, JsonOptions));
            File.Move(temp, target, overwrite: true);
            return (true, sanitized, warnings);
        }
        catch (Exception ex)
        {
            log?.Invoke($"Failed to save preferences: {ex.Message}");
            return (false, sanitized, warnings);
        }
    }

    /// <summary>
    /// Applies client overrides (custom DNS override / dual-stack IPv6 opt-out)
    /// without mutating the phone's original <see cref="WgParams"/>.
    /// </summary>
    public WgParams ApplyTo(WgParams wg)
    {
        var updated = wg;
        if (!string.IsNullOrWhiteSpace(CustomDnsOverride))
        {
            updated = updated with { Dns = CustomDnsOverride.Trim() };
        }
        if (!EnableIpv6Tunnel)
        {
            var v4Only = string.Join(", ",
                updated.AllowedIps.Split(',', StringSplitOptions.RemoveEmptyEntries)
                    .Select(s => s.Trim())
                    .Where(s => s.Length > 0 && !s.Contains(':')));
            updated = updated with
            {
                AllowedIps = string.IsNullOrWhiteSpace(v4Only) ? "0.0.0.0/0" : v4Only,
                ClientIpv6 = null,
                DnsIpv6 = null,
            };
        }
        return updated;
    }
}
