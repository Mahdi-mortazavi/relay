package io.relay.app.e2e

import androidx.test.platform.app.InstrumentationRegistry
import io.relay.app.service.Settings
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Marks the first-run walkthrough as already seen, before the activity starts.
 *
 * These tests are about the sharing journey, not about onboarding: they open
 * the app and reach for "Start Sharing". On a fresh install that button is
 * behind the walkthrough, so without this every one of them fails looking for
 * a node that is real but not on screen — which is exactly how they failed
 * when onboarding landed.
 *
 * It has to be a rule rather than an @Before: ActivityScenarioRule launches the
 * activity while the rules are being applied, which is before any @Before runs,
 * so a preference written there would arrive one screen too late. Ordering puts
 * this outside the compose rule.
 *
 * The walkthrough itself is not left untested by this — universal-apk-e2e.sh
 * drives a real install through it on a real image, which is the only place it
 * can be tested as a first run, because it only happens once per install.
 */
class SkipOnboarding : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                Settings(context).onboarded = true
                base.evaluate()
            }
        }
}
