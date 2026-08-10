package io.relay.app.e2e

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.screenshot.Screenshot
import org.json.JSONObject
import java.io.File

/**
 * Where the device half of the E2E writes the evidence the workflow collects
 * (`.github/workflows/e2e.yml` pulls this directory and uploads it as an
 * artifact). Everything a human would need to judge a failed run without a
 * phone in hand: screenshots of each state, and the exact pairing payload the
 * app issued so the Windows-side decoder can be run against the real thing.
 */
object DeviceEvidence {

    private val directory: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "e2e").apply { mkdirs() }
    }

    /** Screenshot of whatever is on screen, named `NN-label.png` in capture order. */
    @Synchronized
    fun screenshot(label: String) {
        val index = (counter++).toString().padStart(2, '0')
        val capture = Screenshot.capture()
        File(directory, "$index-$label.png").outputStream().use { out ->
            capture.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /**
     * The real pairing payload, for the cross-platform leg: the Windows-side
     * codec decodes exactly this string, so a drift between the two
     * implementations fails the run rather than being discovered by a user.
     */
    fun recordPairing(qrText: String, host: String, port: Int, typedCode: String?) {
        val json = JSONObject()
            .put("qr", qrText)
            .put("host", host)
            .put("port", port)
            .put("typedCode", typedCode ?: JSONObject.NULL)
        File(directory, "pairing.json").writeText(json.toString())
    }

    /** A line of narrative, so the artifact explains what the run did. */
    @Synchronized
    fun note(message: String) {
        File(directory, "journal.txt").appendText("$message\n")
    }

    private var counter = 1
}
