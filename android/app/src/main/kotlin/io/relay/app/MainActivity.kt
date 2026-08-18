package io.relay.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import io.relay.app.service.ConnectionRepository
import io.relay.app.service.ConnectionState
import io.relay.app.service.DiagnosticReport
import io.relay.app.ui.HomeScreen
import io.relay.app.ui.MainViewModel
import io.relay.app.ui.theme.RelayBackground
import io.relay.app.ui.theme.RelayTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /**
     * Set when the launcher shortcut brought us here, cleared once acted on.
     *
     * A flag rather than a direct call into the view model, because starting
     * has to go through the same path the button does — the notification
     * permission prompt lives in a Compose launcher, and a second way in would
     * be a second place for that to be forgotten.
     */
    private val startRequested = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readStartRequest(intent)
        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            RelayTheme(themeMode = themeMode) {
                RelayBackground {
                    val state by viewModel.state.collectAsState()
                    val batteryExempt by viewModel.batteryExempt.collectAsState()
                    val warnings by viewModel.warnings.collectAsState()
                    val logs by viewModel.logs.collectAsState()

                    val notificationPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { viewModel.startSharing() } // start regardless; the notification just may not show

                    // Re-check the exemption whenever the user returns from Settings.
                    val lifecycleOwner = LocalLifecycleOwner.current
                    LaunchedEffect(lifecycleOwner) {
                        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            viewModel.refreshBatteryExempt()
                        }
                    }

                    val pendingClient by ConnectionRepository.clientGate.pending.collectAsState()

                    // The launcher shortcut. Honoured only from Idle: arriving
                    // here while already sharing means the person tapped it out
                    // of habit, and restarting would rotate the keys and drop
                    // the PC that is currently connected.
                    val wantsStart by startRequested.collectAsState()
                    LaunchedEffect(wantsStart, state) {
                        if (!wantsStart) return@LaunchedEffect
                        startRequested.value = false
                        if (state !is ConnectionState.Idle) return@LaunchedEffect
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.startSharing()
                        }
                    }

                    HomeScreen(
                        state = state,
                        batteryExempt = batteryExempt,
                        warnings = warnings,
                        themeMode = themeMode,
                        fullModeAvailable = viewModel.fullModeAvailable,
                        logs = logs,
                        onStart = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.startSharing()
                            }
                        },
                        onStop = viewModel::stopSharing,
                        onRetry = viewModel::retry,
                        onDismissError = viewModel::dismissError,
                        onAllowBattery = ::requestBatteryExemption,
                        onDismissWarning = viewModel::dismissWarning,
                        onSetTheme = viewModel::setThemeMode,
                        onClearLogs = viewModel::clearLogs,
                        onShareLogs = {
                            // Built here rather than in the view model because the
                            // report needs the installed version, and the share
                            // sheet needs an Activity to launch from.
                            val version = runCatching {
                                packageManager.getPackageInfo(packageName, 0).versionName
                            }.getOrNull() ?: "unknown"
                            val report = DiagnosticReport.build(state, logs, version)
                            startActivity(DiagnosticReport.shareIntent(this@MainActivity, report))
                        },
                        pendingClient = pendingClient?.address,
                        onApproveClient = { allowed ->
                            pendingClient?.let {
                                ConnectionRepository.clientGate.resolve(it.address, allowed)
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * singleTop means a shortcut tap on an already-open Relay arrives here
     * rather than through onCreate, so both have to read it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readStartRequest(intent)
    }

    private fun readStartRequest(intent: Intent?) {
        if (intent?.action == ACTION_START_SHARING) startRequested.value = true
    }

    companion object {
        /** Declared in the manifest and in res/xml/shortcuts.xml; keep the three in step. */
        const val ACTION_START_SHARING = "io.relay.app.action.START_SHARING"
    }

    /** Deep link to the exemption dialog for this app (ADR-0003). */
    private fun requestBatteryExemption() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(direct)
        } catch (_: Exception) {
            // Some OEM builds block the direct dialog — fall back to the list screen.
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
