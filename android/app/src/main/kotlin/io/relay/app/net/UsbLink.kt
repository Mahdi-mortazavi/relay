package io.relay.app.net

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log

/**
 * The cable, as far as this app is allowed to know about it.
 *
 * A cable is the steadiest link Relay has: nothing shares the medium with it,
 * and it costs the phone no battery holding an access point up. (Whether it is
 * *faster* than Wi-Fi is unmeasured and deliberately unclaimed — a good 5 GHz
 * link can beat USB 2.0's RNDIS throughput.) It is also the one link the app
 * cannot switch on for itself: USB tethering is a system setting, and turning it
 * on needs a permission only system apps hold. So the whole feature, from this side, is: notice that a
 * cable is there, say what it would be worth, and open the one screen that can
 * act on it.
 */
object UsbLink {

    /** What the cable is doing, as far as we can tell from public APIs. */
    enum class Cable {
        /** No cable, or one we have no reason to mention. */
        Absent,

        /** A cable to a computer, with tethering still off — worth offering. */
        Offered,

        /** Tethering is on and this phone is reachable over it. */
        Carrying,
    }

    /**
     * What to put on screen, once a dismissal is taken into account.
     *
     * A rule small enough to look obviously right and easy enough to get wrong
     * that it is worth asserting: a dismissal covers the situation that was
     * dismissed and not the next one. Someone who waves the card away while
     * charging from a wall socket must still be offered the cable on the day
     * they plug into their PC — the signal behind the offer is a guess, so a
     * dismissal has to be cheap to undo.
     *
     * Not persisted across launches, for the same reason.
     */
    class Offer {
        private var dismissedWhile: Cable? = null

        fun observe(now: Cable): Cable {
            if (now != dismissedWhile) dismissedWhile = null
            return if (now == dismissedWhile) Cable.Absent else now
        }

        /** Hides the offer until the cable situation changes. */
        fun dismiss() {
            dismissedWhile = Cable.Offered
        }
    }

    fun read(context: Context): Cable = when {
        tethered() -> Cable.Carrying
        cableToComputer(context) -> Cable.Offered
        else -> Cable.Absent
    }

    /**
     * Whether USB tethering is up *and carrying an address*, which is the only
     * form of it that helps.
     *
     * Asked of the interface list rather than of any tethering API: the public
     * ones are either gated behind system permissions or need a callback
     * registration that fails silently on some OEM builds, and the interface is
     * the thing the beacon actually announces on anyway. If [LocalAddress] can
     * see a USB link, so can the laptop.
     */
    fun tethered(): Boolean = LocalAddress.activeLinkKinds().contains("usb")

    /**
     * Whether the cable in the phone looks like it goes to a computer.
     *
     * A guess, and knowingly so. `BATTERY_PLUGGED_USB` distinguishes a host
     * port from a wall charger, which almost always reports `BATTERY_PLUGGED_AC`
     * — but a cheap charger can report USB, and there is no public API that
     * says "a host is on the other end of this". `UsbManager.ACTION_USB_STATE`
     * carries exactly that in a `host_connected` extra and is `@hide`, so it is
     * not something to build a shipped feature on.
     *
     * Which is why what this drives is an *offer* and not a claim: the copy asks
     * whether the cable goes to the PC rather than announcing that it does, and
     * the card can be dismissed. Being wrong costs one ignorable card.
     *
     * The battery broadcast is sticky, so this reads the last one rather than
     * registering a live receiver — no receiver to leak, and an answer
     * immediately instead of on the next battery change.
     */
    fun cableToComputer(context: Context): Boolean {
        val battery = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            Log.d(TAG, "battery state unavailable: ${e.message}")
            null
        } ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) == BatteryManager.BATTERY_PLUGGED_USB
    }

    /**
     * Opens the screen with the USB tethering switch on it.
     *
     * Tried in order, because the first one is not public API. The tethering
     * screen's action string has been stable across every Android release this
     * app supports and is what every tethering app uses, but it is `@hide`, so
     * an OEM is within its rights to have removed it — and landing the person
     * in Settings somewhere near the switch beats an unhandled crash. Each
     * fallback is a real public constant.
     *
     * Not gated on `resolveActivity`: package visibility on API 30+ hides
     * Settings from that query unless the manifest declares it, and catching
     * the throw needs no manifest change to stay correct.
     */
    fun openTetheringSettings(context: Context) {
        for (action in listOf(TETHER_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)) {
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

    /** `Settings.ACTION_TETHER_SETTINGS`, which is @hide, so spelled out here. */
    private const val TETHER_SETTINGS = "android.settings.TETHER_SETTINGS"

    private const val TAG = "RelayUsbLink"
}
