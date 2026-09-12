package io.relay.app.core

/**
 * How long to wait for a usable link before saying there isn't one.
 *
 * Sharing used to fail the instant [io.relay.app.net.LocalAddress] found no
 * advertisable address. That is the wrong answer for the most common way people
 * start: they turn USB tethering on, or join a Wi-Fi network, and then open
 * Relay. `rndis0` takes a few seconds to come up and get an address; a Wi-Fi
 * reassociation takes longer. In between, the phone has no advertisable address
 * and the old code called that a failure.
 *
 * A real report (2.8.1) shows what that felt like: seven "no usable interface"
 * failures in eighteen seconds, one per tap, before the interface finally
 * appeared and the eighth tap worked. The phone knew how to do this all along —
 * it just refused to wait two seconds for it.
 *
 * Unlike [ReconnectPolicy] this is not a contract: nothing on the Windows side
 * can observe it. It is one platform's answer to "how long is a link allowed to
 * take to show up".
 */
object LinkWait {
    /**
     * Delay *before* each look, in order. The first is zero, so a phone that is
     * already on Wi-Fi starts sharing with no added latency at all — which is
     * the common case and must not be made slower to fix the uncommon one.
     *
     * The gaps widen because the two failure shapes are different: a link that
     * is nearly up appears within a few hundred milliseconds, and one that is
     * still negotiating takes seconds. Eight looks spread over eight seconds
     * covers both without holding the screen on "Preparing…" long enough to
     * read as a hang.
     */
    val lookDelaysMs = longArrayOf(0, 300, 500, 700, 1000, 1500, 2000, 2000)

    val looks: Int get() = lookDelaysMs.size

    /** The whole window. After this, "there is no link" is an honest answer. */
    val totalBoundMs: Long get() = lookDelaysMs.sum()
}
