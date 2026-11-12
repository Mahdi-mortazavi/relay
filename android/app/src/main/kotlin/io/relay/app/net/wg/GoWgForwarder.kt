package io.relay.app.net.wg

import io.relay.app.core.WgConfig
import java.lang.reflect.Method

/**
 * Bridges [WgForwarder] to the gomobile-built `relaywg` AAR (`/wg`).
 * Optional methods degrade safely when paired against an older AAR build.
 */
class GoWgForwarder : WgForwarder {

    private val startEndpoint: Method
    private val startEndpointVia: Method?
    private val stopEndpoint: Method
    private val setEndpointUpstream: Method?
    private val isRunning: Method
    private val lastHandshakeUnix: Method
    private val bytesReceived: Method
    private val bytesSent: Method

    private val addPeerMethod: Method?
    private val removePeerMethod: Method?
    private val peerCountMethod: Method?
    private val peersStatsJsonMethod: Method?
    private val activeFlowsJsonMethod: Method?
    private val setTunnelDnsMethod: Method?
    private val setTrafficLimitsMethod: Method?
    private val quotaUsedBytesMethod: Method?
    private val quotaExceededMethod: Method?
    private val setTetherTtlMethod: Method?
    private val enableIcmpRelayMethod: Method?
    private val addInboundForwardMethod: Method?
    private val removeInboundForwardMethod: Method?
    private val inboundForwardsJsonMethod: Method?
    private val startWgTcpBridgeMethod: Method?
    private val stopWgTcpBridgeMethod: Method?

    init {
        val relaywg = try {
            Class.forName(RELAYWG_CLASS)
        } catch (e: Throwable) {
            throw WgForwarderException(
                "Full Mode is not included in this build (the relaywg library is missing)", e,
            )
        }
        startEndpoint = relaywg.getMethod("startEndpoint", String::class.java)
        startEndpointVia = opt(relaywg, "startEndpointVia", String::class.java, String::class.java)
        stopEndpoint = relaywg.getMethod("stopEndpoint")
        setEndpointUpstream = opt(relaywg, "setEndpointUpstream", String::class.java)
        isRunning = relaywg.getMethod("isRunning")
        lastHandshakeUnix = relaywg.getMethod("lastHandshakeUnix")
        bytesReceived = relaywg.getMethod("bytesReceived")
        bytesSent = relaywg.getMethod("bytesSent")

        addPeerMethod = opt(relaywg, "addPeer", String::class.java, String::class.java)
        removePeerMethod = opt(relaywg, "removePeer", String::class.java)
        peerCountMethod = opt(relaywg, "peerCount")
        peersStatsJsonMethod = optAny(relaywg, listOf("peersStatsJSON", "peersStatsJson"))
        activeFlowsJsonMethod = optAny(relaywg, listOf("activeFlowsJSON", "activeFlowsJson"))
        setTunnelDnsMethod = optAny(relaywg, listOf("setTunnelDNS", "setTunnelDns"), String::class.java, String::class.java)
        setTrafficLimitsMethod = opt(
            relaywg, "setTrafficLimits",
            Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
        )
        quotaUsedBytesMethod = opt(relaywg, "quotaUsedBytes")
        quotaExceededMethod = opt(relaywg, "quotaExceeded")
        setTetherTtlMethod = optAny(relaywg, listOf("setTetherTTL", "setTetherTtl"), Long::class.javaPrimitiveType!!)
            ?: optAny(relaywg, listOf("setTetherTTL", "setTetherTtl"), Int::class.javaPrimitiveType!!)
        enableIcmpRelayMethod = optAny(relaywg, listOf("enableICMPRelay", "enableIcmpRelay"), Boolean::class.javaPrimitiveType!!)
        addInboundForwardMethod = opt(
            relaywg, "addInboundForward",
            Long::class.javaPrimitiveType!!, String::class.java, Long::class.javaPrimitiveType!!, String::class.java,
        ) ?: opt(
            relaywg, "addInboundForward",
            Int::class.javaPrimitiveType!!, String::class.java, Int::class.javaPrimitiveType!!, String::class.java,
        )
        removeInboundForwardMethod = opt(
            relaywg, "removeInboundForward", Long::class.javaPrimitiveType!!, String::class.java,
        ) ?: opt(
            relaywg, "removeInboundForward", Int::class.javaPrimitiveType!!, String::class.java,
        )
        inboundForwardsJsonMethod = optAny(relaywg, listOf("inboundForwardsJSON", "inboundForwardsJson"))
        startWgTcpBridgeMethod = opt(
            relaywg, "startWgTcpBridge", Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
        ) ?: opt(
            relaywg, "startWgTcpBridge", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
        )
        stopWgTcpBridgeMethod = opt(relaywg, "stopWgTcpBridge")
    }

    override fun start(config: String, upstreamProxy: String) {
        try {
            if (upstreamProxy.isEmpty()) {
                startEndpoint.invoke(null, config)
            } else {
                val via = startEndpointVia ?: throw WgForwarderException(
                    "This build's tunnel library is too old to forward through a proxy",
                )
                via.invoke(null, config, upstreamProxy)
            }
        } catch (e: Throwable) {
            throw WgForwarderException(
                "The WireGuard endpoint did not start: ${e.cause?.message ?: e.message}", e,
            )
        }
        if (!running()) {
            throw WgForwarderException("The WireGuard endpoint reported no error but is not running")
        }
    }

    override fun stop() {
        runCatching { stopEndpoint.invoke(null) }
        stopWgTcpBridge()
    }

    override fun setUpstream(upstreamProxy: String): Boolean {
        val method = setEndpointUpstream ?: return false
        return runCatching { method.invoke(null, upstreamProxy) }.isSuccess
    }

    fun running(): Boolean = runCatching { isRunning.invoke(null) as Boolean }.getOrDefault(false)

    override fun lastHandshakeUnix(): Long = number(lastHandshakeUnix)

    override fun bytesReceived(): Long = number(bytesReceived)

    override fun bytesSent(): Long = number(bytesSent)

    override fun addPeer(publicKeyBase64OrHex: String, allowedIps: String): Boolean {
        val m = addPeerMethod ?: return false
        val hex = toHexKey(publicKeyBase64OrHex) ?: return false
        return runCatching { m.invoke(null, hex, allowedIps) }.isSuccess
    }

    override fun removePeer(publicKeyBase64OrHex: String): Boolean {
        val m = removePeerMethod ?: return false
        val hex = toHexKey(publicKeyBase64OrHex) ?: return false
        return runCatching { m.invoke(null, hex) }.isSuccess
    }

    override fun peerCount(): Int {
        val m = peerCountMethod ?: return 0
        return runCatching { (m.invoke(null) as Number).toInt() }.getOrDefault(0)
    }

    override fun peersStatsJson(): String {
        val m = peersStatsJsonMethod ?: return "[]"
        return runCatching { m.invoke(null) as? String ?: "[]" }.getOrDefault("[]")
    }

    override fun activeFlowsJson(): String {
        val m = activeFlowsJsonMethod ?: return "[]"
        return runCatching { m.invoke(null) as? String ?: "[]" }.getOrDefault("[]")
    }

    override fun setTunnelDns(dnsServer: String, dohUrl: String): Boolean {
        val m = setTunnelDnsMethod ?: return false
        return runCatching { m.invoke(null, dnsServer, dohUrl) }.isSuccess
    }

    override fun setTrafficLimits(upBytesPerSec: Long, downBytesPerSec: Long, quotaBytes: Long): Boolean {
        val m = setTrafficLimitsMethod ?: return false
        return runCatching { m.invoke(null, upBytesPerSec, downBytesPerSec, quotaBytes) }.isSuccess
    }

    override fun quotaUsedBytes(): Long = quotaUsedBytesMethod?.let { number(it) } ?: 0L

    override fun quotaExceeded(): Boolean {
        val m = quotaExceededMethod ?: return false
        return runCatching { m.invoke(null) as? Boolean ?: false }.getOrDefault(false)
    }

    override fun setTetherTtl(ttl: Int): Boolean {
        val m = setTetherTtlMethod ?: return false
        val arg: Any = if (m.parameterTypes[0] == Long::class.javaPrimitiveType) ttl.toLong() else ttl
        return runCatching { m.invoke(null, arg) }.isSuccess
    }

    override fun enableIcmpRelay(enabled: Boolean): Boolean {
        val m = enableIcmpRelayMethod ?: return false
        return runCatching { (m.invoke(null, enabled) as? Boolean) ?: false }.getOrDefault(false)
    }

    override fun addInboundForward(proto: String, listenPort: Int, targetHost: String, targetPort: Int): Boolean {
        val m = addInboundForwardMethod ?: return false
        return runCatching {
            if (m.parameterTypes[0] == Long::class.javaPrimitiveType) {
                m.invoke(null, listenPort.toLong(), targetHost, targetPort.toLong(), proto)
            } else {
                m.invoke(null, listenPort, targetHost, targetPort, proto)
            }
        }.isSuccess
    }

    override fun removeInboundForward(proto: String, listenPort: Int): Boolean {
        val m = removeInboundForwardMethod ?: return false
        return runCatching {
            if (m.parameterTypes[0] == Long::class.javaPrimitiveType) {
                m.invoke(null, listenPort.toLong(), proto)
            } else {
                m.invoke(null, listenPort, proto)
            }
        }.isSuccess
    }

    override fun inboundForwardsJson(): String {
        val m = inboundForwardsJsonMethod ?: return "[]"
        return runCatching { m.invoke(null) as? String ?: "[]" }.getOrDefault("[]")
    }

    override fun startWgTcpBridge(tcpPort: Int, wgUdpPort: Int): Int {
        val m = startWgTcpBridgeMethod ?: return -1
        return runCatching {
            val res = if (m.parameterTypes[0] == Long::class.javaPrimitiveType) {
                m.invoke(null, tcpPort.toLong(), wgUdpPort.toLong())
            } else {
                m.invoke(null, tcpPort, wgUdpPort)
            }
            (res as? Number)?.toInt() ?: -1
        }.getOrDefault(-1)
    }

    override fun stopWgTcpBridge() {
        val m = stopWgTcpBridgeMethod ?: return
        runCatching { m.invoke(null) }
    }

    private fun number(method: Method): Long =
        runCatching { (method.invoke(null) as Number).toLong() }.getOrDefault(0L)

    private fun toHexKey(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.length == 64 && trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return trimmed.lowercase()
        }
        return runCatching { WgConfig.toHex(trimmed) }.getOrNull()
    }

    private fun opt(cls: Class<*>, name: String, vararg params: Class<*>): Method? =
        runCatching { cls.getMethod(name, *params) }.getOrNull()

    private fun optAny(cls: Class<*>, names: List<String>, vararg params: Class<*>): Method? {
        for (name in names) {
            opt(cls, name, *params)?.let { return it }
        }
        return null
    }

    private companion object {
        const val RELAYWG_CLASS = "relaywg.Relaywg"
    }
}
