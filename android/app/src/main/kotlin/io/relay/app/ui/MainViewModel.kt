package io.relay.app.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import io.relay.app.core.ConnectionState
import io.relay.app.service.ConnectionRepository
import io.relay.app.service.SharingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val state: StateFlow<ConnectionState> = ConnectionRepository.state

    private val _batteryExempt = MutableStateFlow(readBatteryExempt())
    val batteryExempt: StateFlow<Boolean> = _batteryExempt

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

    private fun readBatteryExempt(): Boolean {
        val app = getApplication<Application>()
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(app.packageName)
    }
}
