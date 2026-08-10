package io.relay.app.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import io.relay.app.core.ConnectionState
import io.relay.app.core.TransportMode
import io.relay.app.core.WarningCode
import io.relay.app.net.wg.WgForwarderProvider
import io.relay.app.service.ConnectionRepository
import io.relay.app.service.LocalLog
import io.relay.app.service.Settings
import io.relay.app.service.SharingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)

    val state: StateFlow<ConnectionState> = ConnectionRepository.state
    val warnings: StateFlow<Set<WarningCode>> = ConnectionRepository.warnings
    val logs: StateFlow<List<LocalLog.Entry>> = LocalLog.entries

    private val _batteryExempt = MutableStateFlow(readBatteryExempt())
    val batteryExempt: StateFlow<Boolean> = _batteryExempt

    private val _themeMode = MutableStateFlow(settings.themeMode)
    val themeMode: StateFlow<String> = _themeMode

    private val _preferredPort = MutableStateFlow(settings.preferredPort)
    val preferredPort: StateFlow<Int> = _preferredPort

    /**
     * Full Mode needs the WireGuard forwarder, which this build may not ship
     * (Phase 3, docs/roadmap.md). When it is absent the mode is not offered, and
     * a FULL value persisted by an earlier build is coerced back to FAST so the
     * user cannot be stranded on a mode that always fails to start.
     */
    val fullModeAvailable: Boolean = WgForwarderProvider.isAvailable

    private val _transportMode = MutableStateFlow(
        TransportMode.fromSetting(settings.transportMode).coerceAvailable()
    )
    val transportMode: StateFlow<TransportMode> = _transportMode

    fun refreshBatteryExempt() {
        _batteryExempt.value = readBatteryExempt()
    }

    fun startSharing() = SharingService.start(getApplication())

    fun stopSharing() = SharingService.stop(getApplication())

    /** Error → Idle; the service is already stopped when an error is showing. */
    fun dismissError() {
        ConnectionRepository.dispatch("dismiss") { ConnectionState.Idle }
    }

    fun retry() {
        ConnectionRepository.dispatch("dismiss") { ConnectionState.Idle }
        startSharing()
    }

    fun dismissWarning(code: WarningCode) = ConnectionRepository.setWarning(code, active = false)

    fun setThemeMode(mode: String) {
        settings.themeMode = mode
        _themeMode.value = mode
    }

    /** Persisted preferred port; applied the next time sharing starts. */
    fun setPreferredPort(port: Int) {
        val clamped = port.takeIf { it in 1..65535 } ?: 0
        settings.preferredPort = clamped
        _preferredPort.value = clamped
    }

    /** Selects the transport mode; applied on the next start (choose while idle — AC3.3, no restart). */
    fun setTransportMode(mode: TransportMode) {
        val selected = mode.coerceAvailable()
        settings.transportMode = selected.name
        _transportMode.value = selected
    }

    private fun TransportMode.coerceAvailable(): TransportMode =
        if (this == TransportMode.FULL && !fullModeAvailable) TransportMode.FAST else this

    fun clearLogs() = LocalLog.clear()

    private fun readBatteryExempt(): Boolean {
        val app = getApplication<Application>()
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(app.packageName)
    }
}
