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
import io.relay.app.ui.HomeScreen
import io.relay.app.ui.MainViewModel
import io.relay.app.ui.theme.RelayBackground
import io.relay.app.ui.theme.RelayTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            RelayTheme(themeMode = themeMode) {
                RelayBackground {
                    val state by viewModel.state.collectAsState()
                    val batteryExempt by viewModel.batteryExempt.collectAsState()
                    val warnings by viewModel.warnings.collectAsState()
                    val logs by viewModel.logs.collectAsState()
                    val preferredPort by viewModel.preferredPort.collectAsState()

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

                    HomeScreen(
                        state = state,
                        batteryExempt = batteryExempt,
                        warnings = warnings,
                        themeMode = themeMode,
                        preferredPort = preferredPort,
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
                        onSetPort = viewModel::setPreferredPort,
                        onClearLogs = viewModel::clearLogs,
                    )
                }
            }
        }
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
