using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Relay.Core;

/// <summary>
/// Why the list of phones is empty.
///
/// "Open Relay on your phone and tap Start Sharing" is the right thing to say
/// to someone whose phone is not sharing yet. Said to someone whose PC never
/// managed to listen, or who has not joined the phone's hotspot, it is advice
/// for a problem they do not have — and they will follow it, watch nothing
/// happen, and conclude the two apps cannot see each other.
///
/// This is the PC-side twin of the phone's <c>LinkSnapshot</c>: one empty state
/// covering three situations, each with a different next action.
///
/// The reason discovery can fail silently at all is that failing is survivable —
/// the QR and the eight-character code both still work — so it was logged rather
/// than surfaced. That is right about the severity and wrong about the silence:
/// the fallbacks only help someone who knows to reach for them.
/// </summary>
public static class DiscoveryHealth
{
    public enum State
    {
        /// <summary>Listening, on a network, and nothing has answered yet.</summary>
        Listening,

        /// <summary>
        /// The discovery socket never came up — port 47654 taken by another
        /// program, or a policy that forbids the bind. Two digits cannot work;
        /// the QR and the long code still can.
        /// </summary>
        NotListening,

        /// <summary>
        /// This PC has no address on any ordinary network, so there is nothing
        /// for a phone to be found *on*. Overwhelmingly: they started sharing on
        /// the phone and have not joined its hotspot yet.
        /// </summary>
        NoNetwork,
    }

    /// <summary>
    /// Ordered by what the person has to do about it, most specific first.
    ///
    /// <paramref name="onANetwork"/> is a callback, not a value, because
    /// answering it walks every adapter on the machine and a PC that could not
    /// listen at all has already been diagnosed — the question does not arise.
    /// This runs on the UI thread each time an empty list is drawn, so "does not
    /// arise" should also mean "is not asked".
    /// </summary>
    public static State Diagnose(bool listening, Func<bool> onANetwork) => !listening
        ? State.NotListening
        : !onANetwork()
            ? State.NoNetwork
            : State.Listening;

    /// <summary>
    /// A <c>Strings</c> key, not a sentence.
    ///
    /// Relay.Core has no language. It returned English words once, and they went
    /// straight onto the Persian UI where nothing could translate them.
    /// </summary>
    public static string StringKey(State state) => state switch
    {
        State.NotListening => "IdleCannotListen",
        State.NoNetwork => "IdleNoNetwork",
        _ => "IdleBody",
    };

    /// <summary>
    /// Whether this PC has any address a phone on the same link could reach.
    ///
    /// Loopback and link-local (169.254/16, which is what Windows assigns when
    /// DHCP never answered) both mean "not on a network" however up the adapter
    /// claims to be — an APIPA address is the shape of a cable plugged into
    /// something that is not serving addresses.
    /// </summary>
    public static bool OnANetwork()
    {
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

                foreach (var address in nic.GetIPProperties().UnicastAddresses)
                {
                    if (address.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (IPAddress.IsLoopback(address.Address)) continue;
                    if (IsLinkLocal(address.Address)) continue;
                    return true;
                }
            }
        }
        catch (NetworkInformationException)
        {
            // Unable to ask is not the same as "no". Claiming NoNetwork here
            // would send someone to join a network they are already on.
            return true;
        }
        return false;
    }

    /// <summary>169.254.0.0/16 — Windows' "DHCP never answered" address.</summary>
    private static bool IsLinkLocal(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        return bytes.Length == 4 && bytes[0] == 169 && bytes[1] == 254;
    }
}
