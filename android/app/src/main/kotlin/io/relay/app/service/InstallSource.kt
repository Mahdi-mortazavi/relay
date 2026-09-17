package io.relay.app.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Who installed this copy of Relay, and therefore whose job updates are.
 *
 * Relay checks GitHub for a newer release and offers to install it. That is
 * right for someone who downloaded the APK, and wrong for everybody else: if
 * you installed Relay from an app repository, that repository already updates
 * it, and a banner asking you to sideload a file instead quietly pulls you out
 * of the channel you chose. It also fails IzzyOnDroid's inclusion policy, which
 * requires that fetching executable binaries be opt-in and say plainly that
 * doing so bypasses the repository's own checks.
 *
 * So the rule is: **if something else installed us, something else updates us.**
 *
 * The list below is the case that matters -- the F-Droid family, whose clients
 * are what serves the IzzyOnDroid repo, plus Play and Obtainium. It is a list
 * rather than a check for "is this a store", because Android offers no such
 * question.
 *
 * **It fails safe.** An id that is wrong or missing means the banner shows, which
 * is what every release up to 2.8.9 did for everyone. A wrong entry here can
 * cost someone a redundant banner; it can never break an install or an update.
 */
object InstallSource {

    /**
     * Installers that manage their own updates.
     *
     * Not exhaustive and not meant to be: see the class note on failing safe.
     */
    val MANAGED: Set<String> = setOf(
        "org.fdroid.fdroid",        // F-Droid, and the client most IzzyOnDroid users run
        "org.fdroid.basic",         // F-Droid Basic
        "com.looker.droidify",      // Droid-ify
        "com.machiav3lli.fdroid",   // Neo Store
        "nya.kitsunyan.foxydroid",  // Foxy Droid
        "dev.imranr.obtainium",     // Obtainium, which watches the releases page itself
        "com.android.vending",      // Play
    )

    /**
     * The package that installed Relay, or null when nothing claims it — a
     * sideload, an `adb install`, or a platform that will not say.
     */
    fun installerOf(context: Context): String? = try {
        val packages = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packages.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packages.getInstallerPackageName(context.packageName)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        // Asking about ourselves cannot really fail, but the platform declares
        // that it can, and an updater must never be why the app will not start.
        null
    } catch (_: Exception) {
        null
    }

    /** Whether *this* app should be offering its own updates. */
    fun weOwnUpdates(context: Context): Boolean = ownsUpdates(installerOf(context))

    /**
     * The decision, separated from the platform call so it can be tested.
     *
     * Public for that reason and no other.
     */
    fun ownsUpdates(installer: String?): Boolean =
        installer.isNullOrBlank() || installer !in MANAGED
}
