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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import io.relay.app.ui.OnboardingScreen
import io.relay.app.ui.OnboardingStep
import io.relay.app.ui.StepAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import io.relay.app.service.ConnectionRepository
import io.relay.app.core.ConnectionState
import io.relay.app.service.HomeScreenExtras
import io.relay.app.service.Settings as RelaySettings
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
                    val updateAvailable by viewModel.updateAvailable.collectAsState()

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

                    // First launch only. Everything it offers is reachable
                    // from Advanced afterwards, so it never has to be shown twice.
                    val settings = remember { RelaySettings(this@MainActivity) }
                    var onboarding by remember { mutableStateOf(!settings.onboarded) }
                    if (onboarding) {
                        OnboardingScreen(
                            steps = onboardingSteps(batteryExempt),
                            onDone = {
                                settings.onboarded = true
                                onboarding = false
                            },
                        )
                        return@RelayBackground
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
                        // The banner used to offer an update it had no way to
                        // deliver: HomeScreen took this lambda, nothing passed
                        // one, and the default was empty.
                        onGetUpdate = viewModel::getUpdate,
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
                        updateAvailable = updateAvailable,
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


    /**
     * The first-run checklist.
     *
     * Each step is a button when Android will let an app ask for the thing, and
     * a line of instructions when it will not — which is version-dependent, so
     * [HomeScreenExtras] decides and this only renders the answer. The
     * alternative is a button that does nothing on the phones where the
     * platform declines, which is worse than no button.
     */
    @androidx.compose.runtime.Composable
    private fun onboardingSteps(batteryExempt: Boolean): List<OnboardingStep> {
        val ctx = this
        val notifGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        val canTile = HomeScreenExtras.canOfferTile()
        val canWidget = HomeScreenExtras.canOfferWidget(ctx)
        val widgetOn = HomeScreenExtras.widgetPlaced(ctx)
        val tileManual = stringResource(R.string.onboarding_tile_manual)
        val widgetManual = stringResource(R.string.onboarding_widget_manual)

        return listOf(
            OnboardingStep(
                title = stringResource(R.string.onboarding_notif_title),
                body = stringResource(R.string.onboarding_notif_body),
                action = if (notifGranted) StepAction.Done else StepAction.Offer,
                actionLabel = stringResource(R.string.onboarding_notif_action),
                onAction = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    }
                },
            ),
            OnboardingStep(
                title = stringResource(R.string.onboarding_battery_title),
                body = stringResource(R.string.onboarding_battery_body),
                action = if (batteryExempt) StepAction.Done else StepAction.Offer,
                actionLabel = stringResource(R.string.onboarding_battery_action),
                onAction = ::requestBatteryExemption,
            ),
            OnboardingStep(
                title = stringResource(R.string.onboarding_tile_title),
                body = stringResource(R.string.onboarding_tile_body),
                action = if (canTile) StepAction.Offer else StepAction.Instruct,
                actionLabel = if (canTile) {
                    stringResource(R.string.onboarding_tile_action)
                } else {
                    tileManual
                },
                onAction = {
                    if (canTile) {
                        HomeScreenExtras.offerTile(ctx)
                    } else {
                        Toast.makeText(ctx, tileManual, Toast.LENGTH_LONG).show()
                    }
                },
            ),
            OnboardingStep(
                title = stringResource(R.string.onboarding_widget_title),
                body = stringResource(R.string.onboarding_widget_body),
                action = when {
                    widgetOn -> StepAction.Done
                    canWidget -> StepAction.Offer
                    else -> StepAction.Instruct
                },
                actionLabel = if (canWidget) {
                    stringResource(R.string.onboarding_widget_action)
                } else {
                    widgetManual
                },
                onAction = {
                    if (canWidget) {
                        HomeScreenExtras.offerWidget(ctx)
                    } else {
                        Toast.makeText(ctx, widgetManual, Toast.LENGTH_LONG).show()
                    }
                },
            ),
            OnboardingStep(
                title = stringResource(R.string.onboarding_shortcut_title),
                body = stringResource(R.string.onboarding_shortcut_body),
                action = StepAction.Done,
                actionLabel = "",
                onAction = {},
            ),
        )
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
