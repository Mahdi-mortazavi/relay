package io.relay.app.service

import android.content.Context
import android.content.Intent
import android.os.Build
import io.relay.app.core.ConnectionState

/**
 * Turns "it doesn't work" into something a developer can act on.
 *
 * Deliberately built as text the person can read before they send it. Nothing
 * is uploaded from here and no token for any service is embedded: a bot token
 * shipped inside an app is a token anyone can extract, and silent uploads would
 * break the promise the log has always made — that it stays on the device
 * unless its owner decides otherwise.
 *
 * Where it goes is Android's decision and the person's: [shareIntent] hands the
 * text to the share sheet, where Telegram, mail, a notes app or GitHub are all
 * equally available.
 */
object DiagnosticReport {

    /**
     * Builds the report. [redactNames] drops the device name, which is often a
     * person's own name; addresses are kept because they are private-network
     * addresses and the most common faults are addressing faults.
     */
    fun build(
        state: ConnectionState,
        entries: List<LocalLog.Entry>,
        /** Passed in rather than read from BuildConfig, which this module does
         *  not generate, and which would make this function need a build to
         *  test at all. */
        appVersion: String = "unknown",
        redactNames: Boolean = true,
    ): String = buildString {
        appendLine("Relay diagnostic report")
        appendLine("=======================")
        appendLine()
        // Every Build field is read defensively. They are platform types, not
        // guaranteed non-null, and this is the one function in the app that must
        // never throw: it runs when something has already gone wrong, and a
        // report that crashes instead of printing leaves the person with
        // nothing to send. SUPPORTED_ABIS is null outside a device — a JVM unit
        // test is the honest case, an unusual OEM image the paranoid one.
        appendLine("App:      $appVersion")
        appendLine("Android:  ${Build.VERSION.RELEASE ?: UNKNOWN} (API ${Build.VERSION.SDK_INT})")
        val model = Build.MODEL ?: UNKNOWN
        appendLine("Device:   ${if (redactNames) "${Build.MANUFACTURER ?: UNKNOWN} $model" else model}")
        appendLine("ABIs:     ${Build.SUPPORTED_ABIS?.joinToString(", ") ?: UNKNOWN}")
        appendLine("State:    ${describe(state)}")
        appendLine()
        appendLine("Log (most recent last, times in seconds since sharing started)")
        appendLine("-------------------------------------------------------------")
        if (entries.isEmpty()) {
            appendLine("(empty)")
        } else {
            for (entry in entries) {
                appendLine("%8.2f  %s".format(entry.elapsedMs / 1000.0, entry.message))
            }
        }
        appendLine()
        appendLine("This report was assembled on the device and shared by hand.")
        appendLine("It contains no account data and nothing was uploaded automatically.")
    }

    /** A one-line summary that says what went wrong, not just which screen it is on. */
    private fun describe(state: ConnectionState): String = when (state) {
        is ConnectionState.Error -> "Error: ${state.code}"
        is ConnectionState.Connected ->
            "Connected (${state.clientCount} client(s)" +
                (if (state.reconnecting) ", reconnecting" else "") + ")"
        is ConnectionState.Advertising ->
            "Waiting for a PC" + (if (state.reconnecting) " (reconnecting)" else "")
        else -> state.stateName
    }

    /**
     * A share sheet carrying the report. The chooser is what makes this safe:
     * the person sees the text and picks the destination, so nothing leaves the
     * phone without a deliberate act.
     */
    fun shareIntent(context: Context, report: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Relay diagnostic report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        return Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * A GitHub issue with the report already in the body.
     *
     * Truncated to keep the whole thing inside a URL: browsers and servers both
     * give up somewhere past 8k, and a link that silently fails to open is
     * worse than a report missing its oldest lines. The newest lines are the
     * ones that explain a failure, so the front is what gets dropped.
     */
    fun issueUrl(report: String): String {
        val body = if (report.length <= BODY_LIMIT) report
        else "(earlier lines trimmed to fit)\n" + report.takeLast(BODY_LIMIT)
        return "$ISSUES_URL/new?title=${encode("Connection problem")}&body=${encode(body)}"
    }

    private fun encode(text: String): String =
        java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private const val UNKNOWN = "unknown"
    private const val BODY_LIMIT = 6000
    private const val ISSUES_URL = "https://github.com/Mahdi-mortazavi/relay/issues"
}
