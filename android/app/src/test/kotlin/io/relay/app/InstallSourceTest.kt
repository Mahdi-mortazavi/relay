package io.relay.app

import io.relay.app.service.InstallSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whose job it is to update Relay.
 *
 * Relay checks GitHub and offers to install a newer APK. That is right for
 * someone who downloaded the APK and wrong for everybody else: an app
 * repository already updates what it installed, and a banner asking someone to
 * sideload a file instead pulls them out of the channel they chose. It also
 * fails IzzyOnDroid's inclusion policy, which requires fetching executable
 * binaries to be opt-in.
 *
 * The decision is pure and the platform call is not, which is why
 * [InstallSource.ownsUpdates] takes the installer's name rather than a Context.
 */
class InstallSourceTest {

    @Test
    fun `a sideloaded copy updates itself`() {
        // Nothing claims a sideload, so this is the null case -- and it is the
        // behaviour of every release up to 2.8.9, for everyone. Getting this
        // wrong would silently switch the updater off for the people it exists
        // for, and nothing on screen would say so.
        assertTrue(InstallSource.ownsUpdates(null))
        assertTrue(InstallSource.ownsUpdates(""))
        assertTrue(InstallSource.ownsUpdates("   "))
    }

    @Test
    fun `a copy from an app repository leaves updates to the repository`() {
        // org.fdroid.fdroid is the one that matters: it is the client most
        // people use to reach the IzzyOnDroid repo, so it is the case the
        // policy is actually about.
        assertFalse(InstallSource.ownsUpdates("org.fdroid.fdroid"))
        assertFalse(InstallSource.ownsUpdates("org.fdroid.basic"))
        assertFalse(InstallSource.ownsUpdates("com.looker.droidify"))
        assertFalse(InstallSource.ownsUpdates("com.machiav3lli.fdroid"))
        assertFalse(InstallSource.ownsUpdates("nya.kitsunyan.foxydroid"))
        assertFalse(InstallSource.ownsUpdates("dev.imranr.obtainium"))
        assertFalse(InstallSource.ownsUpdates("com.android.vending"))
    }

    @Test
    fun `an installer nobody listed fails toward showing the banner`() {
        // The safe direction, and deliberate. The list cannot be exhaustive --
        // Android offers no way to ask "is this a store" -- so an id that is
        // missing or misspelled has to cost a redundant banner rather than a
        // missed update. A test here because the inverse is the tempting way to
        // write it, and the inverse is silent.
        assertTrue(InstallSource.ownsUpdates("com.android.shell"))          // adb install
        assertTrue(InstallSource.ownsUpdates("com.android.packageinstaller"))
        assertTrue(InstallSource.ownsUpdates("com.example.unknown.store"))
        assertTrue(InstallSource.ownsUpdates("org.fdroid.fdroid.privileged")) // not the client
    }

    @Test
    fun `every listed installer is a plausible package name`() {
        // A typo in this list is invisible: the entry simply never matches and
        // the banner keeps showing, which looks exactly like working.
        InstallSource.MANAGED.forEach {
            assertTrue("'$it' is not a package name", it.matches(Regex("[a-z0-9_]+(\\.[a-z0-9_]+)+")))
            assertFalse("'$it' has stray whitespace", it != it.trim())
        }
        assertTrue(
            "the F-Droid client is the one this feature exists for",
            InstallSource.MANAGED.contains("org.fdroid.fdroid"),
        )
    }
}
