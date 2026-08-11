using System.Globalization;
using System.Net;
using System.Text;

namespace Relay.Core;

/// <summary>
/// Turns the QR's <c>wg</c> block into the configuration a WireGuard client
/// needs, in both the forms that matter: the <c>wg-quick</c> INI a person can
/// read, and the flat IPC form wireguard-go actually consumes.
///
/// Kept pure and separate from anything that opens an adapter. Standing up a
/// WinTun device needs elevation and a real Windows machine; assembling the
/// text does not, and the assembly is where a tunnel silently fails to carry
/// traffic — a missing AllowedIPs, a key in the wrong encoding, a port that
/// never made it across. Those are worth having under test on every commit,
/// separately from the part only hardware can prove.
/// </summary>
public static class WgClientConfig
{
    /// <summary>The address the phone's endpoint answers on (/wg, tunnelAddress).</summary>
    public const string ServerTunnelAddress = "10.13.37.1";

    /// <summary>The address this client takes inside the tunnel.</summary>
    public const string ClientTunnelAddress = "10.13.37.2/32";

    /// <summary>Matches the endpoint's MTU; a mismatch shows up as large replies vanishing.</summary>
    public const int Mtu = 1420;

    /// <summary>
    /// Builds the <c>wg-quick</c> INI. <paramref name="host"/> is the phone's
    /// address from the payload, which is what the client dials.
    /// </summary>
    /// <exception cref="ArgumentException">If the payload could not produce a usable tunnel.</exception>
    public static string ToIni(WgParams wg, string host)
    {
        Validate(wg, host);

        var config = new StringBuilder();
        config.AppendLine("[Interface]");
        config.AppendLine($"PrivateKey = {wg.ClientPrivateKey}");
        config.AppendLine($"Address = {ClientTunnelAddress}");
        config.AppendLine($"DNS = {wg.Dns}");
        config.AppendLine($"MTU = {Mtu}");
        config.AppendLine();
        config.AppendLine("[Peer]");
        config.AppendLine($"PublicKey = {wg.ServerPublicKey}");
        config.AppendLine($"AllowedIPs = {wg.AllowedIps}");
        config.AppendLine($"Endpoint = {host}:{wg.EndpointPort.ToString(CultureInfo.InvariantCulture)}");
        // The phone is behind whatever NAT its network has. Without a keepalive
        // the mapping expires while idle and the tunnel goes quiet in one
        // direction until the client happens to send something.
        config.AppendLine("PersistentKeepalive = 25");
        return config.ToString();
    }

    /// <summary>
    /// Builds the flat <c>key=value</c> form wireguard-go's IPC takes. Keys are
    /// hex here, not base64 — the one difference between the two forms, and the
    /// one that produces a tunnel that handshakes with nothing and reports no
    /// error at all.
    /// </summary>
    /// <exception cref="ArgumentException">If the payload could not produce a usable tunnel.</exception>
    public static string ToIpc(WgParams wg, string host)
    {
        Validate(wg, host);

        var config = new StringBuilder();
        config.Append("private_key=").Append(ToHex(wg.ClientPrivateKey)).Append('\n');
        config.Append("public_key=").Append(ToHex(wg.ServerPublicKey)).Append('\n');
        config.Append("endpoint=").Append(host).Append(':')
              .Append(wg.EndpointPort.ToString(CultureInfo.InvariantCulture)).Append('\n');
        foreach (var allowed in wg.AllowedIps.Split(',', StringSplitOptions.RemoveEmptyEntries))
        {
            config.Append("allowed_ip=").Append(allowed.Trim()).Append('\n');
        }
        config.Append("persistent_keepalive_interval=25\n");
        return config.ToString();
    }

    /// <summary>
    /// Converts a base64 WireGuard key to the hex the IPC form wants.
    /// </summary>
    /// <exception cref="ArgumentException">If it is not a 32-byte key.</exception>
    public static string ToHex(string base64Key)
    {
        Span<byte> key = stackalloc byte[32];
        if (!Convert.TryFromBase64String(base64Key, key, out var written) || written != 32)
        {
            throw new ArgumentException($"not a 32-byte WireGuard key: '{base64Key}'", nameof(base64Key));
        }
        return Convert.ToHexString(key).ToLowerInvariant();
    }

    private static void Validate(WgParams wg, string host)
    {
        if (string.IsNullOrWhiteSpace(host) || !IPAddress.TryParse(host, out _))
        {
            throw new ArgumentException($"not an address to dial: '{host}'", nameof(host));
        }
        if (wg.EndpointPort is < 1 or > 65535)
        {
            throw new ArgumentException($"port out of range: {wg.EndpointPort}", nameof(wg));
        }
        if (string.IsNullOrWhiteSpace(wg.AllowedIps))
        {
            // An empty AllowedIPs builds a tunnel that comes up and carries
            // nothing, which is the hardest kind of failure to diagnose from
            // the outside: everything looks connected.
            throw new ArgumentException("no allowed IPs — the tunnel would carry nothing", nameof(wg));
        }
        if (string.IsNullOrWhiteSpace(wg.Dns))
        {
            throw new ArgumentException("no DNS server — names would not resolve inside the tunnel", nameof(wg));
        }
        // Throws with a precise message if either key is malformed.
        ToHex(wg.ClientPrivateKey);
        ToHex(wg.ServerPublicKey);
    }
}
