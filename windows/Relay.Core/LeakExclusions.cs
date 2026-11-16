using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Relay.Core;

/// <summary>
/// Explicit exclusions and strictness knobs for Full Mode WFP leak protection
/// (<c>relaywg-client</c>).
///
/// Defaults are strictly fail-closed: LAN, LAN discovery beacons (UDP 47654),
/// non-tunnel apps, and non-tunnel CIDRs are all blocked outside the WinTun
/// interface, and <see cref="StrictLeakBlock"/> refuses to bring up the tunnel
/// if WFP filters fail to install.
/// </summary>
public sealed record LeakExclusions
{
    public static readonly LeakExclusions Default = new();

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
        WriteIndented = true,
    };

    private static readonly HashSet<string> DangerousAppNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "svchost.exe",
        "cmd.exe",
        "powershell.exe",
        "pwsh.exe",
        "conhost.exe",
        "rundll32.exe",
        "regsvr32.exe",
        "mshta.exe",
        "wscript.exe",
        "cscript.exe",
        "services.exe",
        "lsass.exe",
        "csrss.exe",
        "explorer.exe",
        "backgroundtaskhost.exe",
    };

    /// <summary>Allow RFC1918 + link-local IPv4 LAN access outside the tunnel (DNS/mDNS/LLMNR remain blocked).</summary>
    [JsonPropertyName("allowLan")]
    public bool AllowLan { get; init; }

    /// <summary>Allow Relay UI UDP port 47654 on physical NICs for pairing beacons and roaming updates.</summary>
    [JsonPropertyName("allowDiscovery")]
    public bool AllowDiscovery { get; init; }

    /// <summary>Fail closed and refuse to connect if WFP filters fail to install (recommended: true).</summary>
    [JsonPropertyName("strictLeakBlock")]
    public bool StrictLeakBlock { get; init; } = true;

    /// <summary>Persist kill-switch WFP lockdown rules across app/tunnel crashes until explicit disconnect or recovery.</summary>
    [JsonPropertyName("persistKillSwitch")]
    public bool PersistKillSwitch { get; init; }

    /// <summary>Absolute .exe paths permitted to bypass the tunnel on IPv4 (DNS/mDNS/LLMNR remain blocked).</summary>
    [JsonPropertyName("excludeApps")]
    public IReadOnlyList<string> ExcludeApps { get; init; } = Array.Empty<string>();

    /// <summary>IPv4/IPv6 CIDR prefixes permitted outside the tunnel (/0 rejected; DNS/mDNS/LLMNR remain blocked).</summary>
    [JsonPropertyName("excludeCidrs")]
    public IReadOnlyList<string> ExcludeCidrs { get; init; } = Array.Empty<string>();

    /// <summary>Default per-user exclusions config path: <c>%LOCALAPPDATA%\Relay\exclusions.json</c>.</summary>
    public static string DefaultFilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Relay",
        "exclusions.json");

    /// <summary>
    /// Loads exclusions from <paramref name="path"/> (or <see cref="DefaultFilePath"/>),
    /// returning safe fail-closed defaults on missing or corrupt files.
    /// </summary>
    public static LeakExclusions LoadOrDefault(string? path = null, Action<string>? log = null)
    {
        var target = path ?? DefaultFilePath;
        try
        {
            if (!File.Exists(target)) return Default;
            var json = File.ReadAllText(target);
            var parsed = JsonSerializer.Deserialize<LeakExclusions>(json, JsonOptions);
            if (parsed is null)
            {
                log?.Invoke($"Ignored empty exclusion config at {target}");
                return Default;
            }
            return parsed.Sanitize(log);
        }
        catch (Exception ex)
        {
            log?.Invoke($"Could not load leak exclusions from {target} ({ex.Message}); using strict defaults");
            return Default;
        }
    }

    /// <summary>Validates and deduplicates app paths and CIDR prefixes, dropping unsafe entries.</summary>
    public LeakExclusions Sanitize(Action<string>? log = null)
    {
        var apps = new List<string>();
        var seenApps = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var raw in ExcludeApps ?? Array.Empty<string>())
        {
            if (!TryValidateAppPath(raw, out var normalized, out var reason))
            {
                log?.Invoke($"Ignored excluded app '{raw}': {reason}");
                continue;
            }
            if (seenApps.Add(normalized)) apps.Add(normalized);
        }

        var cidrs = new List<string>();
        var seenCidrs = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var raw in ExcludeCidrs ?? Array.Empty<string>())
        {
            if (!TryValidateCidr(raw, out var normalized, out var reason))
            {
                log?.Invoke($"Ignored excluded CIDR '{raw}': {reason}");
                continue;
            }
            if (seenCidrs.Add(normalized)) cidrs.Add(normalized);
        }

        return this with
        {
            ExcludeApps = apps,
            ExcludeCidrs = cidrs,
        };
    }

    public static bool TryValidateAppPath(string? raw, out string normalized, out string reason)
    {
        normalized = string.Empty;
        reason = string.Empty;
        if (string.IsNullOrWhiteSpace(raw))
        {
            reason = "empty path";
            return false;
        }

        var trimmed = raw.Trim().Trim('"');
        if (trimmed.Contains(',') || trimmed.Contains('"'))
        {
            reason = "path contains disallowed delimiter characters";
            return false;
        }

        var expanded = Environment.ExpandEnvironmentVariables(trimmed);
        if (!Path.IsPathFullyQualified(expanded))
        {
            reason = "executable path must be fully qualified";
            return false;
        }

        var full = Path.GetFullPath(expanded);
        if (!string.Equals(Path.GetExtension(full), ".exe", StringComparison.OrdinalIgnoreCase))
        {
            reason = "only .exe binaries may be excluded";
            return false;
        }

        var fileName = Path.GetFileName(full);
        if (DangerousAppNames.Contains(fileName))
        {
            reason = $"system host binary '{fileName}' cannot be safely excluded";
            return false;
        }

        normalized = full;
        return true;
    }

    public static bool TryValidateCidr(string? raw, out string normalized, out string reason)
    {
        normalized = string.Empty;
        reason = string.Empty;
        if (string.IsNullOrWhiteSpace(raw))
        {
            reason = "empty CIDR";
            return false;
        }

        var trimmed = raw.Trim();
        var slash = trimmed.IndexOf('/');
        if (slash <= 0 || slash == trimmed.Length - 1)
        {
            reason = "missing /prefixLength";
            return false;
        }

        var addrPart = trimmed[..slash];
        var lenPart = trimmed[(slash + 1)..];
        if (!IPAddress.TryParse(addrPart, out var ip) ||
            ip.AddressFamily is not (AddressFamily.InterNetwork or AddressFamily.InterNetworkV6))
        {
            reason = "invalid IP address";
            return false;
        }

        if (!int.TryParse(lenPart, System.Globalization.NumberStyles.None,
                System.Globalization.CultureInfo.InvariantCulture, out var prefixLen))
        {
            reason = "invalid prefix length";
            return false;
        }

        var maxBits = ip.AddressFamily == AddressFamily.InterNetwork ? 32 : 128;
        if (prefixLen == 0)
        {
            reason = "default /0 prefix would disable leak protection";
            return false;
        }
        if (prefixLen < 0 || prefixLen > maxBits)
        {
            reason = $"prefix length must be between 1 and {maxBits}";
            return false;
        }

        var masked = MaskAddress(ip, prefixLen);
        normalized = $"{masked}/{prefixLen}";
        return true;
    }

    private static IPAddress MaskAddress(IPAddress ip, int prefixLen)
    {
        var bytes = ip.GetAddressBytes();
        var fullBytes = prefixLen / 8;
        var remBits = prefixLen % 8;
        for (var i = 0; i < bytes.Length; i++)
        {
            if (i < fullBytes) continue;
            if (i == fullBytes && remBits > 0)
            {
                var mask = (byte)(0xff << (8 - remBits));
                bytes[i] = (byte)(bytes[i] & mask);
            }
            else
            {
                bytes[i] = 0;
            }
        }
        return new IPAddress(bytes);
    }

    /// <summary>Splits user-entered app paths or CIDR prefixes on newlines, commas, or semicolons.</summary>
    public static IReadOnlyList<string> SplitEntries(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return Array.Empty<string>();
        return text
            .Split(['\r', '\n', ',', ';'], StringSplitOptions.RemoveEmptyEntries)
            .Select(s => s.Trim().Trim('"'))
            .Where(s => !string.IsNullOrWhiteSpace(s))
            .ToArray();
    }

    /// <summary>
    /// Validates and writes <paramref name="exclusions"/> atomically to
    /// <paramref name="path"/> (or <see cref="DefaultFilePath"/>).
    /// </summary>
    public static (bool Ok, LeakExclusions Saved, IReadOnlyList<string> Warnings) Save(
        LeakExclusions exclusions,
        string? path = null,
        Action<string>? log = null)
    {
        var warnings = new List<string>();
        var clean = exclusions.Sanitize(msg =>
        {
            warnings.Add(msg);
            log?.Invoke(msg);
        });

        var target = path ?? DefaultFilePath;
        try
        {
            var dir = Path.GetDirectoryName(target);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);

            var tmp = target + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(clean, JsonOptions));
            File.Move(tmp, target, overwrite: true);
            return (true, clean, warnings);
        }
        catch (Exception ex)
        {
            var msg = $"Could not save leak exclusions to {target}: {ex.Message}";
            warnings.Add(msg);
            log?.Invoke(msg);
            return (false, clean, warnings);
        }
    }

    public string ToJson() => JsonSerializer.Serialize(Sanitize(), JsonOptions);
}
