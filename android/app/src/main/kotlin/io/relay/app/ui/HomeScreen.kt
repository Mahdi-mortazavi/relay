package io.relay.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.relay.app.R
import io.relay.app.core.ConnectionState
import io.relay.app.core.ErrorCode
import io.relay.app.core.QrPayload
import io.relay.app.core.QrPayloadCodec
import io.relay.app.core.TransportMode
import io.relay.app.core.WarningCode
import io.relay.app.service.LocalLog
import io.relay.app.ui.theme.LocalGlass
import io.relay.app.ui.theme.glassPanel
import java.util.Locale

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun HomeScreen(
    state: ConnectionState,
    batteryExempt: Boolean,
    warnings: Set<WarningCode>,
    themeMode: String,
    transportMode: TransportMode,
    fullModeAvailable: Boolean,
    preferredPort: Int,
    logs: List<LocalLog.Entry>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onAllowBattery: () -> Unit,
    onDismissWarning: (WarningCode) -> Unit,
    onSetTheme: (String) -> Unit,
    onSetMode: (TransportMode) -> Unit,
    onSetPort: (Int) -> Unit,
    onClearLogs: () -> Unit,
    onShareLogs: () -> Unit = {},
    /** The computer waiting on an answer, or null. /shared/pairing-beacon.md. */
    pendingClient: String? = null,
    onApproveClient: (Boolean) -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // enableEdgeToEdge() draws behind the system bars, so the content
                // has to inset itself: without this the header sits under the
                // status bar and the Advanced panel under the navigation bar.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(state)
            Spacer(Modifier.height(24.dp))

            WarningBanners(warnings, onDismissWarning)

            // Key the crossfade on the state *name*, not the state value.
            // Connected is a data class carrying byte counters that change every
            // second, and the plain Crossfade overload keys on the value itself:
            // the whole panel became a new entry once a second, so the card
            // visibly re-faded and the QR was re-encoded from scratch (ZXing plus
            // a 1.6 MB bitmap, on the main thread) for the entire session.
            // Same-name updates now reuse the subtree and just re-render.
            updateTransition(targetState = state, label = "state").Crossfade(
                animationSpec = tween(320),
                contentKey = { it.stateName },
            ) { current ->
                when (current) {
                    is ConnectionState.Idle ->
                        IdlePanel(transportMode, fullModeAvailable, onSetMode, onStart)
                    is ConnectionState.Preparing -> PreparingPanel()
                    is ConnectionState.Advertising ->
                        PairingPanel(
                            rememberQrText(current.payload), current.typedCode, current.shortCode,
                            subtitle = stringResource(
                                if (current.reconnecting) R.string.status_reconnecting
                                else R.string.status_waiting
                            ),
                            reconnecting = current.reconnecting,
                            traffic = null, onStop = onStop,
                        )
                    is ConnectionState.Connected ->
                        PairingPanel(
                            rememberQrText(current.payload), current.typedCode, current.shortCode,
                            subtitle = if (current.reconnecting)
                                stringResource(R.string.status_reconnecting)
                            else pluralStringResource(
                                R.plurals.status_connected, current.clientCount, current.clientCount,
                            ),
                            reconnecting = current.reconnecting,
                            traffic = formatTraffic(current.bytesUp, current.bytesDown),
                            onStop = onStop, connected = true,
                        )
                    is ConnectionState.Error -> ErrorPanel(current.code, onRetry, onDismissError)
                }
            }

            Spacer(Modifier.height(20.dp))
            if (!batteryExempt) {
                BatteryBanner(onAllowBattery)
                Spacer(Modifier.height(12.dp))
            }
            AdvancedSection(state, themeMode, preferredPort, logs, onSetTheme, onSetPort, onClearLogs, onShareLogs)
        }

        // Inside the Box and after the scrolling Column, so it sits above the
        // whole screen rather than scrolling away with the content.
        if (pendingClient != null) {
            ApprovalDialog(address = pendingClient, onAnswer = onApproveClient)
        }
    }
}

// --- header ------------------------------------------------------------------

@Composable
private fun Header(state: ConnectionState) {
    val glass = LocalGlass.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = glass.textPrimary,
        )
        Spacer(Modifier.weight(1f))
        StatusDot(state)
    }
}

@Composable
private fun StatusDot(state: ConnectionState) {
    val glass = LocalGlass.current
    val reconnecting = (state as? ConnectionState.Connected)?.reconnecting == true ||
        (state as? ConnectionState.Advertising)?.reconnecting == true
    val targetColor = when {
        reconnecting -> glass.warning
        state is ConnectionState.Idle -> glass.textTertiary
        state is ConnectionState.Preparing -> glass.warning
        state is ConnectionState.Advertising -> glass.accent
        state is ConnectionState.Connected -> glass.accent
        else -> glass.error
    }
    // Only run the pulse in the states that actually pulse. It used to be
    // started unconditionally, including in Idle and steady Connected where the
    // alpha it produced was discarded for a constant — an animation nobody can
    // see, invalidating a frame forever, for the life of the screen.
    val pulsing = reconnecting ||
        state is ConnectionState.Preparing ||
        state is ConnectionState.Advertising
    val alpha = if (pulsing) {
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = InfiniteRepeatableSpec(tween(1200), RepeatMode.Reverse),
            label = "pulseAlpha",
        )
        pulse
    } else {
        1f
    }
    val color by animateColorAsState(targetColor, tween(300), label = "dotColor")
    Box(
        modifier = Modifier
            .size(10.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

// --- warnings ----------------------------------------------------------------

@Composable
private fun WarningBanners(warnings: Set<WarningCode>, onDismiss: (WarningCode) -> Unit) {
    val glass = LocalGlass.current
    warnings.forEach { code ->
        val (title, body) = when (code) {
            WarningCode.NO_VPN_ACTIVE ->
                R.string.warning_no_vpn_title to R.string.warning_no_vpn_body
            WarningCode.BATTERY_UNRESTRICTED_DENIED ->
                R.string.battery_banner_title to R.string.battery_banner_body
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .glassPanel(radius = 16.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(8.dp).background(glass.warning, CircleShape))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyMedium, color = glass.textPrimary)
                Text(stringResource(body), style = MaterialTheme.typography.labelSmall, color = glass.textSecondary)
            }
            Text(
                text = stringResource(R.string.action_dismiss),
                style = MaterialTheme.typography.labelSmall,
                color = glass.accent,
                modifier = Modifier
                    .clickable(role = Role.Button) { onDismiss(code) }
                    .minimumInteractiveComponentSize()
                    .padding(4.dp),
            )
        }
    }
}

// --- panels ------------------------------------------------------------------

/** The exact QR string, recomputed only when the payload itself changes. */
@Composable
private fun rememberQrText(payload: QrPayload): String =
    remember(payload) { QrPayloadCodec.encodeForQr(payload) }

@Composable
private fun IdlePanel(
    transportMode: TransportMode,
    fullModeAvailable: Boolean,
    onSetMode: (TransportMode) -> Unit,
    onStart: () -> Unit,
) {
    val glass = LocalGlass.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.status_idle),
            style = MaterialTheme.typography.bodyMedium,
            color = glass.textSecondary,
        )
        Spacer(Modifier.height(20.dp))
        ModeToggle(transportMode, fullModeAvailable, onSetMode)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                when {
                    transportMode == TransportMode.FULL -> R.string.mode_full_desc
                    fullModeAvailable -> R.string.mode_fast_desc
                    // Say plainly why the other segment can't be picked instead
                    // of letting the user tap it and hit an error.
                    else -> R.string.mode_full_unavailable
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = glass.textTertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(text = stringResource(R.string.action_start), onClick = onStart)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.tagline),
            style = MaterialTheme.typography.labelSmall,
            color = glass.textTertiary,
        )
    }
}

/** Segmented Fast/Full selector — one glass pill with the active segment in accent. */
@Composable
private fun ModeToggle(mode: TransportMode, fullModeAvailable: Boolean, onSet: (TransportMode) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .glassPanel(radius = 999.dp)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ModeSegment(
            label = stringResource(R.string.mode_fast),
            selected = mode == TransportMode.FAST,
            enabled = true,
        ) { onSet(TransportMode.FAST) }
        ModeSegment(
            label = stringResource(R.string.mode_full),
            selected = mode == TransportMode.FULL,
            enabled = fullModeAvailable,
        ) { onSet(TransportMode.FULL) }
    }
}

@Composable
private fun ModeSegment(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val glass = LocalGlass.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) glass.accent else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(horizontal = 22.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                selected -> glass.onAccent
                enabled -> glass.textSecondary
                else -> glass.textTertiary
            },
        )
    }
}

@Composable
private fun PreparingPanel() {
    val glass = LocalGlass.current
    val pulse by rememberInfiniteTransition(label = "prep").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = InfiniteRepeatableSpec(tween(900), RepeatMode.Reverse),
        label = "prepAlpha",
    )
    Text(
        text = stringResource(R.string.status_preparing),
        style = MaterialTheme.typography.bodyMedium,
        color = glass.textSecondary,
        modifier = Modifier.alpha(pulse),
    )
}

/**
 * The prompt that makes a two-digit code defensible: the code says which phone,
 * this says whether that computer may actually use it.
 *
 * Deliberately not dismissible by tapping outside. A stray tap that silently
 * means "no" is confusing; a stray tap that silently means "yes" is dangerous.
 * The person has to choose, or let it time out, which the gate counts as no.
 */
@Composable
private fun ApprovalDialog(address: String, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.approve_title)) },
        text = { Text(stringResource(R.string.approve_body, address)) },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) {
                Text(stringResource(R.string.approve_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) {
                Text(stringResource(R.string.approve_deny))
            }
        },
    )
}

@Composable
private fun PairingPanel(
    qrContent: String,
    typedCode: String?,
    shortCode: String?,
    subtitle: String,
    reconnecting: Boolean,
    traffic: String?,
    onStop: () -> Unit,
    connected: Boolean = false,
) {
    val glass = LocalGlass.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(raised = connected)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                reconnecting -> glass.warning
                connected -> glass.accent
                else -> glass.textSecondary
            },
        )
        if (traffic != null && !reconnecting) {
            Spacer(Modifier.height(4.dp))
            Text(traffic, style = MaterialTheme.typography.labelSmall, color = glass.textTertiary)
        }
        Spacer(Modifier.height(20.dp))

        // The two digits come first and come biggest. Reading eight characters
        // off a screen and typing them was the slowest part of setup; this is
        // the part a person should see without looking for it. The QR stays,
        // one scroll down, for anyone who would rather point a camera.
        if (shortCode != null) {
            Text(
                text = stringResource(R.string.short_code_label),
                style = MaterialTheme.typography.labelSmall,
                color = glass.textTertiary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = shortCode,
                style = MaterialTheme.typography.displayLarge,
                color = glass.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (reconnecting) 0.4f else 1f)
                    // Digits read left-to-right in every locale this ships in;
                    // without this they mirror under a right-to-left layout.
                    .semantics { contentDescription = shortCode.toList().joinToString(" ") },
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.short_code_hint),
                style = MaterialTheme.typography.labelSmall,
                color = glass.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            // Stop sits with the code rather than after the QR. Leading with a
            // tall QR pushed it off the bottom of a small screen: the way out
            // of the screen has to be visible without scrolling.
            SubtleButton(text = stringResource(R.string.action_stop), onClick = onStop)
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.or_scan_qr),
                style = MaterialTheme.typography.labelSmall,
                color = glass.textTertiary,
            )
            Spacer(Modifier.height(8.dp))
        }

        val qr = rememberQrBitmap(qrContent)
        val qrFrame = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .alpha(if (reconnecting) 0.4f else 1f)
            .clip(RoundedCornerShape(glass.radiusMd))
            .background(Color.White)
            .padding(12.dp)
        if (qr != null) {
            Image(
                bitmap = qr,
                contentDescription = stringResource(R.string.qr_content_description),
                contentScale = ContentScale.Fit,
                modifier = qrFrame,
            )
        } else {
            Box(modifier = qrFrame)
        }

        // The eight-character code stays available for one release, for a PC
        // that is too old to listen for the beacon (/shared/pairing-beacon.md).
        if (shortCode == null && typedCode != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.or_type_code),
                style = MaterialTheme.typography.labelSmall,
                color = glass.textTertiary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = typedCode.chunked(4).joinToString("-"),
                style = MaterialTheme.typography.headlineMedium,
                color = glass.textPrimary,
            )
        } else if (shortCode == null) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.code_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = glass.textTertiary,
            )
        }

        if (shortCode == null) {
            Spacer(Modifier.height(24.dp))
            SubtleButton(text = stringResource(R.string.action_stop), onClick = onStop)
        }
    }
}

@Composable
private fun ErrorPanel(code: ErrorCode, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val glass = LocalGlass.current
    val (title, body) = when (code) {
        ErrorCode.HOTSPOT_OFF -> R.string.error_hotspot_off_title to R.string.error_hotspot_off_body
        ErrorCode.HOTSPOT_LOST -> R.string.error_hotspot_lost_title to R.string.error_hotspot_lost_body
        ErrorCode.PORT_IN_USE -> R.string.error_port_in_use_title to R.string.error_port_in_use_body
        ErrorCode.SERVICE_FAILED -> R.string.error_service_failed_title to R.string.error_service_failed_body
        ErrorCode.WG_START_FAILED -> R.string.error_wg_start_title to R.string.error_wg_start_body
    }
    Column(
        modifier = Modifier.fillMaxWidth().glassPanel().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            color = glass.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = glass.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(text = stringResource(R.string.action_retry), onClick = onRetry)
        Spacer(Modifier.height(8.dp))
        SubtleButton(text = stringResource(R.string.action_dismiss), onClick = onDismiss)
    }
}

@Composable
private fun BatteryBanner(onAllow: () -> Unit) {
    val glass = LocalGlass.current
    Row(
        modifier = Modifier.fillMaxWidth().glassPanel(radius = 16.dp).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.battery_banner_title), style = MaterialTheme.typography.bodyMedium, color = glass.textPrimary)
            Text(stringResource(R.string.battery_banner_body), style = MaterialTheme.typography.labelSmall, color = glass.textSecondary)
        }
        SubtleButton(text = stringResource(R.string.battery_banner_allow), onClick = onAllow)
    }
}

// --- advanced ----------------------------------------------------------------

@Composable
private fun AdvancedSection(
    state: ConnectionState,
    themeMode: String,
    preferredPort: Int,
    logs: List<LocalLog.Entry>,
    onSetTheme: (String) -> Unit,
    onSetPort: (Int) -> Unit,
    onClearLogs: () -> Unit,
    onShareLogs: () -> Unit,
) {
    val glass = LocalGlass.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = (if (expanded) "▾  " else "▸  ") + stringResource(R.string.advanced),
            style = MaterialTheme.typography.labelSmall,
            color = glass.textTertiary,
            modifier = Modifier
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.advanced),
                ) { expanded = !expanded }
                .minimumInteractiveComponentSize()
                .padding(8.dp),
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(200)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(150)) + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().glassPanel(radius = 16.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Hotspot address (read-only, from current state)
                val host = when (state) {
                    is ConnectionState.Advertising -> "${state.payload.host}:${state.payload.port}"
                    is ConnectionState.Connected -> "${state.payload.host}:${state.payload.port}"
                    else -> "—"
                }
                LabeledValue(stringResource(R.string.advanced_ip), host)

                // Appearance
                Column {
                    Text(stringResource(R.string.advanced_theme), style = MaterialTheme.typography.labelSmall, color = glass.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeChip("system", R.string.theme_system, themeMode, onSetTheme)
                        ThemeChip("dark", R.string.theme_dark, themeMode, onSetTheme)
                        ThemeChip("light", R.string.theme_light, themeMode, onSetTheme)
                    }
                }

                // Preferred port
                PortField(preferredPort, onSetPort)

                // Local-only activity log
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.advanced_logs),
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.advanced_logs_share),
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.accent,
                            modifier = Modifier
                                .clickable(role = Role.Button) { onShareLogs() }
                                .minimumInteractiveComponentSize()
                                .padding(4.dp),
                        )
                        Text(
                            stringResource(R.string.advanced_logs_clear),
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.accent,
                            modifier = Modifier
                                .clickable(role = Role.Button) { onClearLogs() }
                                .minimumInteractiveComponentSize()
                                .padding(4.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (logs.isEmpty()) {
                            Text(
                                stringResource(R.string.advanced_logs_empty),
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.textTertiary,
                            )
                        } else {
                            logs.asReversed().forEach { entry ->
                                Text(
                                    text = "%6.1fs  %s".format(Locale.US, entry.elapsedMs / 1000.0, entry.message),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = glass.textTertiary,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    val glass = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = glass.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = glass.textPrimary, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ThemeChip(value: String, labelRes: Int, current: String, onSet: (String) -> Unit) {
    val glass = LocalGlass.current
    val selected = value == current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) glass.accentSubtle else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton) { onSet(value) }
            .minimumInteractiveComponentSize()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) glass.accent else glass.textSecondary,
        )
    }
}

@Composable
private fun PortField(preferredPort: Int, onSetPort: (Int) -> Unit) {
    val glass = LocalGlass.current
    // rememberSaveable: a half-typed port survived neither rotation nor the
    // activity being recreated behind the battery-settings screen.
    var text by rememberSaveable(preferredPort) {
        mutableStateOf(if (preferredPort == 0) "" else preferredPort.toString())
    }
    var saved by rememberSaveable { mutableStateOf(false) }
    // The field used to accept 65536-99999 and Save silently stored "automatic"
    // (0) while the field went on displaying the rejected number — no error, no
    // confirmation, and a value the user believed was applied. Out-of-range
    // input is now rejected as it is typed, so what is on screen is always what
    // will be saved.
    val outOfRange = text.isNotEmpty() && (text.toIntOrNull() ?: 0) !in 1..65535
    Column {
        Text(stringResource(R.string.advanced_port), style = MaterialTheme.typography.labelSmall, color = glass.textSecondary)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = text,
                onValueChange = {
                    if (it.length <= 5 && it.all(Char::isDigit)) {
                        text = it
                        saved = false
                    }
                },
                placeholder = { Text(stringResource(R.string.advanced_port_hint), color = glass.textTertiary) },
                singleLine = true,
                isError = outOfRange,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = glass.fill,
                    unfocusedContainerColor = glass.fill,
                    focusedTextColor = glass.textPrimary,
                    unfocusedTextColor = glass.textPrimary,
                ),
                modifier = Modifier.weight(1f),
            )
            SubtleButton(
                text = stringResource(R.string.advanced_port_save),
                enabled = !outOfRange,
                onClick = {
                    onSetPort(text.toIntOrNull() ?: 0)
                    saved = true
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        // Saving used to be entirely silent, so there was no way to tell whether
        // anything had happened.
        Text(
            text = when {
                outOfRange -> stringResource(R.string.advanced_port_invalid)
                saved -> stringResource(R.string.advanced_port_saved)
                else -> stringResource(R.string.advanced_port_hint)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (outOfRange) glass.error else glass.textTertiary,
        )
    }
}

// --- buttons -----------------------------------------------------------------

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    val glass = LocalGlass.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(glass.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(horizontal = 32.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = glass.onAccent)
    }
}

@Composable
private fun SubtleButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val glass = LocalGlass.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .glassPanel(radius = 999.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = glass.textPrimary)
    }
}

// --- helpers -----------------------------------------------------------------

@Composable
private fun formatTraffic(up: Long, down: Long): String =
    stringResource(R.string.traffic_format, formatBytes(up), formatBytes(down))

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
    else -> "$bytes B"
}
