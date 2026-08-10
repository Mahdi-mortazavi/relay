using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Relay.Core.Net;

/// <summary>
/// Answers "is the phone's hotspot host on a network this PC is actually joined
/// to?" — used to give the actionable `ERR_WRONG_NETWORK` message instead of a
/// generic timeout when the laptop isn't on the hotspot Wi-Fi.
/// </summary>
public static class NetworkCheck
{
    public static bool IsHostOnLocalSubnet(string host)
    {
        if (!IPAddress.TryParse(host, out var target) ||
            target.AddressFamily != AddressFamily.InterNetwork)
        {
            return false;
        }

        foreach (var nic in SafeInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            // An adapter can disappear mid-enumeration, and some VPN/TAP and
            // virtual-switch adapters throw here rather than returning empty.
            // One bad adapter must not decide the answer for all the others.
            UnicastIPAddressInformationCollection addresses;
            try
            {
                addresses = nic.GetIPProperties().UnicastAddresses;
            }
            catch (NetworkInformationException) { continue; }
            catch (PlatformNotSupportedException) { continue; }

            foreach (var ua in addresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                var mask = ua.IPv4Mask;
                if (mask is null || Equals(mask, IPAddress.Any)) continue;
                if (SameSubnet(ua.Address, target, mask)) return true;
            }
        }
        return false;
    }

    private static bool SameSubnet(IPAddress a, IPAddress b, IPAddress mask)
    {
        var ba = a.GetAddressBytes();
        var bb = b.GetAddressBytes();
        var bm = mask.GetAddressBytes();
        for (var i = 0; i < 4; i++)
        {
            if ((ba[i] & bm[i]) != (bb[i] & bm[i])) return false;
        }
        return true;
    }

    private static IEnumerable<NetworkInterface> SafeInterfaces()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces();
        }
        catch (NetworkInformationException)
        {
            return [];
        }
    }
}
