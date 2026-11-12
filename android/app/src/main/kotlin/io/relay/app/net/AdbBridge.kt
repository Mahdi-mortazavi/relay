package io.relay.app.net

import io.relay.app.net.wg.WgForwarder
import io.relay.app.service.LocalLog

/**
 * Manages the phone-side WireGuard-over-TCP + Pairing bridge exposed on
 * loopback for `adb forward` USB cable sessions when hotspot / USB tethering
 * or LAN/AP isolation blocks normal UDP/TCP reachability.
 */
object AdbBridge {
    const val DEFAULT_PAIRING_PORT = 47655
    const val DEFAULT_TCP_PORT = 51821

    data class Status(
        val active: Boolean = false,
        val tcpPort: Int = DEFAULT_TCP_PORT,
        val pairingPort: Int = DEFAULT_PAIRING_PORT,
        val adbCommand: String =
            "adb forward tcp:$DEFAULT_PAIRING_PORT tcp:$DEFAULT_PAIRING_PORT && adb forward tcp:$DEFAULT_TCP_PORT tcp:$DEFAULT_TCP_PORT",
    )

    fun start(forwarder: WgForwarder, pairingPort: Int, wgUdpPort: Int): Status {
        val boundTcp = runCatching {
            forwarder.startWgTcpBridge(DEFAULT_TCP_PORT, wgUdpPort)
        }.getOrDefault(-1)

        if (boundTcp <= 0) {
            LocalLog.warn(
                LocalLog.Area.LINK,
                "Could not start ADB WireGuard-over-TCP loopback bridge",
                "tcp_port" to DEFAULT_TCP_PORT.toString(),
                "wg_udp_port" to wgUdpPort.toString(),
            )
            return Status(active = false, tcpPort = DEFAULT_TCP_PORT, pairingPort = pairingPort)
        }

        val cmd = "adb forward tcp:$pairingPort tcp:$pairingPort && adb forward tcp:$boundTcp tcp:$boundTcp"
        LocalLog.info(
            LocalLog.Area.LINK,
            "ADB loopback bridge listening",
            "pairing_tcp" to pairingPort.toString(),
            "wg_tcp" to boundTcp.toString(),
        )
        return Status(
            active = true,
            tcpPort = boundTcp,
            pairingPort = pairingPort,
            adbCommand = cmd,
        )
    }

    fun stop(forwarder: WgForwarder?) {
        runCatching { forwarder?.stopWgTcpBridge() }
    }
}
