package io.relay.app.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import io.relay.app.core.ConnectionState
import io.relay.app.core.WarningCode
import io.relay.app.net.wg.WgForwarderProvider
import io.relay.app.service.ConnectionRepository
import io.relay.app.service.LocalLog
import io.relay.app.service.Settings
import io.relay.app.service.SharingService
import io.relay.app.core.UpdateCheck
import io.relay.app.service.UpdateFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val LATEST_RELEASE_API =
            "https://api.github.com/repos/Mahdi-mortazavi/relay/releases/latest"
    }


    private val settings = Settings(application)

    val state: StateFlow<ConnectionState> = ConnectionRepository.state
    val warnings: StateFlow<Set<WarningCode>> = ConnectionRepository.warnings
    val logs: StateFlow<List<LocalLog.Entry>> = LocalLog.entries

    /**
     * The newer release, once a check has found one. Null covers both "this is
     * the current build" and "the check could not be made" -- someone offline
     * should not be told anything is wrong with their app.
     */
    private val _updateAvailable = MutableStateFlow<String?>(null)
    val updateAvailable: StateFlow<String?> = _updateAvailable.asStateFlow()

    /**
     * Asks GitHub once per launch. Failure is silence by design: the whole
     * feature is a courtesy, and a courtesy that interrupts you when the
     * network is down is not one.
     */
    fun checkForUpdate(currentVersion: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val latest = runCatching {
                val text = java.net.URL(LATEST_RELEASE_API).openConnection().let { connection ->
                    connection.setRequestProperty("User-Agent", "Relay")
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 8_000
                    connection.getInputStream().bufferedReader().use { it.readText() }
                }
                val json = org.json.JSONObject(text)
                if (json.optBoolean("draft") || json.optBoolean("prerelease")) null
                else json.optString("tag_name").takeIf { it.isNotEmpty() }
            }.getOrNull()

            if (latest != null && UpdateCheck.isNewer(latest, currentVersion)) {
                LocalLog.add("Update available: $latest")
                _updateAvailable.value = latest.trimStart('v')
            }
        }
    }

    fun dismissUpdate() {
        _updateAvailable.value = null
    }

    /** True while an update is being fetched, so the button cannot be tapped twice. */
    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating

    /**
     * Downloads the release APK, verifies it against the release's published
     * checksums, and opens the system installer.
     *
     * The "Get it" button used to call nothing at all: HomeScreen took an
     * onGetUpdate lambda, MainActivity never passed one, and the default was
     * empty — so the banner offered an update it had no way to deliver.
     */
    fun getUpdate() {
        if (_updating.value) return
        _updating.value = true
        viewModelScope.launch {
            val result = UpdateFetcher.downloadAndInstall(getApplication())
            _updating.value = false
            when (result) {
                UpdateFetcher.Result.Installing -> _updateAvailable.value = null
                UpdateFetcher.Result.ChecksumMismatch ->
                    LocalLog.add("Update refused: the download did not match the published checksum")
                UpdateFetcher.Result.Unverifiable ->
                    LocalLog.add("Update refused: that release published no checksums")
                UpdateFetcher.Result.Unavailable ->
                    LocalLog.add("Update could not be downloaded; try again later")
            }
        }
    }

    private val _batteryExempt = MutableStateFlow(readBatteryExempt())
    val batteryExempt: StateFlow<Boolean> = _batteryExempt

    private val _themeMode = MutableStateFlow(settings.themeMode)
    val themeMode: StateFlow<String> = _themeMode

    /**
     * Whether this build shipped the WireGuard forwarder at all.
     *
     * Since ADR-0009 there is no second transport to fall back to, so a build
     * without it cannot share anything. That is a packaging failure rather than
     * a user choice, and the screen says so up front instead of letting someone
     * press Start and collect an error.
     */
    val fullModeAvailable: Boolean = WgForwarderProvider.isAvailable

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

    fun clearLogs() = LocalLog.clear()

    private fun readBatteryExempt(): Boolean {
        val app = getApplication<Application>()
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(app.packageName)
    }
}
