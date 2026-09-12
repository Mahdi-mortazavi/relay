package io.relay.app.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import io.relay.app.core.ConnectionState
import io.relay.app.core.WarningCode
import io.relay.app.net.UsbLink
import io.relay.app.net.wg.WgForwarderProvider
import io.relay.app.service.ConnectionRepository
import io.relay.app.service.LocalLog
import io.relay.app.service.Settings
import io.relay.app.service.SharingService
import io.relay.app.core.UpdateCheck
import io.relay.app.service.UpdateFetcher
import io.relay.app.service.UpdateNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {


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
     *
     * Nothing called this until now, so [updateAvailable] was permanently null
     * and the banner HomeScreen draws for it had never been drawn for anybody.
     * The whole update path -- check, compare, download, verify, install -- was
     * written and tested and connected to no caller, on both platforms.
     */
    fun checkForUpdate(currentVersion: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val latest = UpdateFetcher.latestVersion() ?: return@launch
            if (!UpdateCheck.isNewer(latest, currentVersion)) return@launch

            val version = latest.trimStart('v')
            LocalLog.info(
                LocalLog.Area.UPDATE, "A newer release is available",
                "latest" to latest, "current" to currentVersion,
            )
            _updateAvailable.value = version
            // The banner only reaches someone already opening the app, which is
            // the person least likely to be on an old build.
            UpdateNotice.show(getApplication(), version)
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
                UpdateFetcher.Result.Installing -> {
                    _updateAvailable.value = null
                    UpdateNotice.clear(getApplication())
                }
                UpdateFetcher.Result.ChecksumMismatch ->
                    LocalLog.error(
                        LocalLog.Area.UPDATE,
                        "Update refused: the download did not match the published checksum",
                    )
                UpdateFetcher.Result.Unverifiable ->
                    LocalLog.error(
                        LocalLog.Area.UPDATE, "Update refused: that release published no checksums",
                    )
                UpdateFetcher.Result.Unavailable ->
                    LocalLog.warn(
                        LocalLog.Area.UPDATE, "Update could not be downloaded; try again later",
                    )
            }
        }
    }

    // --- the cable ----------------------------------------------------------

    private val _cable = MutableStateFlow(UsbLink.Cable.Absent)

    /**
     * What the USB cable is doing, for the offer and the "over USB" line.
     * Absent while the offer stands dismissed, so the screen needs no second
     * flag to consult.
     */
    val cable: StateFlow<UsbLink.Cable> = _cable.asStateFlow()

    /** Applies the dismissal rule; see [UsbLink.Offer]. */
    private val offer = UsbLink.Offer()

    /**
     * Polls the cable for as long as the screen is in front of someone.
     *
     * Polling rather than listening, because neither half of this has a
     * broadcast worth trusting: a USB interface appearing is not a
     * ConnectivityManager network this app is ever told about, and the one
     * broadcast that would say a host is on the other end of the cable is
     * @hide. Twice a second would be a waste; every two seconds is faster than
     * anyone can plug a cable in and look up.
     *
     * Suspends forever by design — the caller runs it inside repeatOnLifecycle,
     * so it starts when the screen appears and is cancelled when it leaves.
     */
    suspend fun watchCable() {
        while (true) {
            // Interface enumeration is a syscall; the battery read crosses a
            // binder. Neither belongs on the frame-drawing thread.
            val now = withContext(Dispatchers.IO) { UsbLink.read(getApplication()) }
            _cable.value = offer.observe(now)
            delay(CABLE_POLL_MS)
        }
    }

    /**
     * Hides the offer until the cable situation changes.
     *
     * Deliberately not remembered across launches. The signal behind the offer
     * is a guess (see [UsbLink.cableToComputer]), so a person dismissing it once
     * on a wall charger should not lose the feature on the day they actually
     * plug into their PC.
     */
    fun dismissUsbOffer() {
        offer.dismiss()
        _cable.value = UsbLink.Cable.Absent
    }

    /** Opens the one screen with the USB tethering switch on it. */
    fun openTetheringSettings() = UsbLink.openTetheringSettings(getApplication())

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

    private companion object {
        /** How often [watchCable] looks, while the screen is in front of someone. */
        const val CABLE_POLL_MS = 2000L
    }

    private fun readBatteryExempt(): Boolean {
        val app = getApplication<Application>()
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(app.packageName)
    }
}
