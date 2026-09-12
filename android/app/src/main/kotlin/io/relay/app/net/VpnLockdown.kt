package io.relay.app.net

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import io.relay.app.core.WarningCode

/**
 * Whether Android's **"Block connections without VPN"** is switched on.
 *
 * That switch — lockdown mode — is the single most common cause of the fault
 * reported in [#126](https://github.com/Mahdi-mortazavi/relay/issues/126): the
 * PC finds the phone, the phone never answers, and nothing on either screen
 * explains why. It drops everything that is not the tunnel, **including replies
 * to the phone's own LAN**, which is precisely what Relay needs to send.
 *
 * Reading it is possible, and only just. `always_on_vpn_lockdown` is annotated
 * `@Readable` in AOSP's `Settings.java` with no `maxTargetSdk`, so an ordinary
 * app may read it at any API level. Its two neighbours are not: both
 * `always_on_vpn_app` and `always_on_vpn_lockdown_whitelist` throw
 * `SecurityException` for a modern `targetSdk`. That contrast is the whole
 * reason this class is only about the one boolean — the platform deliberately
 * left this open and closed the rest.
 *
 * The name is `@hide`, so the string is spelled out rather than referenced. This
 * is not reflection and not a hidden-API call: the key is passed to the ordinary
 * public [Settings.Secure.getInt].
 *
 * ### What this is not
 *
 * **It is not the signal that something is wrong.** That is
 * [io.relay.app.core.HandshakeWatch] — a PC took the settings and no handshake
 * came back — and it stays the signal, because a plain full-tunnel VPN with no
 * always-on configured produces exactly the same fault while this reads `OFF`.
 * This only makes the sentence more precise once the fault is already known.
 *
 * **It does not say which app.** `always_on_vpn_app` is closed, so Relay can say
 * a setting is on and where to find it, and cannot name the VPN.
 */
object VpnLockdown {

    /**
     * Three answers, never two.
     *
     * [UNKNOWN] exists because the key is `@hide` and could be closed in a
     * future release exactly as its neighbours were. Folding that into "off"
     * would turn a platform change into Relay quietly telling people their
     * settings are fine.
     */
    enum class State { ON, OFF, UNKNOWN }

    /**
     * Reads the switch. Never throws.
     *
     * A key that has never been written reads as [OFF][State.OFF], which is
     * correct: lockdown cannot be on without having been turned on. A
     * `SecurityException` reads as [UNKNOWN][State.UNKNOWN], because that means
     * the platform stopped answering, not that the answer is no.
     */
    fun read(context: Context): State = try {
        if (Settings.Secure.getInt(context.contentResolver, KEY, 0) != 0) State.ON else State.OFF
    } catch (e: SecurityException) {
        // Google closed the key, as they did for the two beside it.
        Log.d(TAG, "lockdown state is not readable: ${e.message}")
        State.UNKNOWN
    } catch (e: Exception) {
        Log.d(TAG, "could not read lockdown state: ${e.message}")
        State.UNKNOWN
    }

    /** For a log line or a diagnostic report: `on`, `off` or `unknown`. */
    fun describe(state: State): String = when (state) {
        State.ON -> "on"
        State.OFF -> "off"
        State.UNKNOWN -> "unknown"
    }

    /**
     * Which warning to raise, once a PC has already gone unanswered.
     *
     * The fault is known before this is called; the only question is how
     * precisely Relay is allowed to describe it. With the switch confirmed on,
     * it can name the setting and offer the screen. Otherwise it says what it
     * knows — the replies are not getting out — and lists the two things worth
     * trying, because a full-tunnel VPN with no always-on configured causes the
     * identical fault while this reads [OFF][State.OFF].
     *
     * [UNKNOWN][State.UNKNOWN] takes the same path as `OFF` deliberately.
     * Telling somebody to turn off a switch that may not even be on sends them
     * looking for something that is not there, and the general message is
     * already true in every case.
     */
    fun warningFor(state: State): WarningCode = when (state) {
        State.ON -> WarningCode.VPN_LOCKDOWN_ON
        State.OFF, State.UNKNOWN -> WarningCode.PC_GOT_NO_REPLY
    }

    /**
     * What the beacon should carry in its `blocked` field, or null for nothing.
     *
     * The broadcast is the only channel that survives a capture — a link-scoped
     * broadcast bypasses the phone's own VPN, while a unicast answer is routed
     * into it — so this is the phone's one chance to tell a PC that a pairing
     * will not complete. See /shared/pairing-beacon.md → "`blocked`: the one
     * thing the phone can still say".
     *
     * Two grounds, both of them facts rather than forecasts. Lockdown drops
     * every non-tunnel packet **by definition**, LAN replies included; and a PC
     * that took a configuration and never handshaked is direct evidence.
     *
     * [State.UNKNOWN] is not a ground. Absent means "nothing known to be wrong",
     * which is exactly the truth when the setting could not be read, and a
     * warning broadcast on a guess is worse than silence.
     */
    fun blockedValue(lockdown: State, pcWentUnanswered: Boolean): String? =
        if (lockdown == State.ON || pcWentUnanswered) BLOCKED_REPLIES else null

    /**
     * The single value the contract defines. Deliberately not `lockdown` or
     * `vpn`: the beacon is unauthenticated and read by everything on the link,
     * and naming the cause would tell a whole café that this phone's owner runs
     * a VPN. The PC explains it from its own local strings, to one person.
     */
    const val BLOCKED_REPLIES = "replies"

    /**
     * Opens the VPN screen in Settings, falling back until something opens.
     *
     * **There is no deep link to the per-app page** — the one with the switch
     * actually on it. AOSP's Settings app exports exactly one VPN activity,
     * `Settings$VpnSettingsActivity`, behind [Settings.ACTION_VPN_SETTINGS];
     * `AppManagementFragment`, which draws the switch, has no exported activity
     * at all. So this gets the person one tap away and the message has to name
     * the last tap itself. Saying "⚙ beside your VPN's name" is not padding,
     * it is the part the platform will not do.
     *
     * Same shape as [UsbLink.openTetheringSettings], and for the same reason:
     * a settings action that no OEM build happens to handle must degrade to the
     * top of Settings rather than to a button that visibly does nothing.
     */
    fun openVpnSettings(context: Context) {
        for (action in listOf(Settings.ACTION_VPN_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next one down.
            } catch (e: Exception) {
                Log.d(TAG, "could not open $action: ${e.message}")
            }
        }
        Log.d(TAG, "no settings screen would open")
    }

    /**
     * `Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN`, which is `@hide`, so spelled out
     * here. Written by `Vpn.saveAlwaysOnPackage` as `mAlwaysOn && mLockdown`,
     * which is exactly the switch the user sees.
     */
    private const val KEY = "always_on_vpn_lockdown"

    private const val TAG = "RelayVpnLockdown"
}
