package io.relay.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberInfiniteTransition
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.relay.app.R
import io.relay.app.core.ConnectionState
import io.relay.app.core.ErrorCode
import io.relay.app.core.QrPayloadCodec
import io.relay.app.core.WarningCode
import io.relay.app.service.LocalLog
import io.relay.app.ui.theme.LocalGlass
import io.relay.app.ui.theme.glassPanel
import java.util.Locale

@Composable
fun HomeScreen(
    state: ConnectionState,
    batteryExempt: Boolean,
    warnings: Set<WarningCode>,
    themeMode: String,
    preferredPort: Int,
    logs: List<LocalLog.Entry>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onAllowBattery: () -> Unit,
    onDismissWarning: (WarningCode) -> Unit,
    onSetTheme: (String) -> Unit,
    onSetPort: (Int) -> Unit,
    onClearLogs: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(state)
            Spacer(Modifier.height(24.dp))

            WarningBanners(warnings, onDismissWarning)

            Crossfade(
                targetState = state,
                animationSpec = tween(320),
                label = "state",
            ) { current ->
                when (current) {
                    is ConnectionState.Idle -> IdlePanel(onStart)
                    is ConnectionState.Preparing -> PreparingPanel()
                    is ConnectionState.Advertising ->
                        PairingPanel(
                            QrPayloadCodec.encodeForQr(current.payload), current.typedCode,
                            subtitle = stringResource(
                                if (current.reconnecting) R.string.status_reconnecting
                                else R.string.status_waiting
                            ),
                            reconnecting = current.reconnecting,
                            traffic = null, onStop = onStop,
                        )
                    is ConnectionState.Connected ->
                        PairingPanel(
                            QrPayloadCodec.encodeForQr(current.payload), current.typedCode,
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
            AdvancedSection(state, themeMode, preferredPort, logs, onSetTheme, onSetPort, onClearLogs)
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
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = InfiniteRepeatableSpec(tween(1200), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    val reconnecting = (state as? ConnectionState.Connected)?.reconnecting == true ||
        (state as? ConnectionState.Advertising)?.reconnecting == true
    val (targetColor, alpha) = when {
        reconnecting -> glass.warning to pulse
        state is ConnectionState.Idle -> glass.textTertiary to 1f
        state is ConnectionState.Preparing -> glass.warning to pulse
        state is ConnectionState.Advertising -> glass.accent to pulse
        state is ConnectionState.Connected -> glass.accent to 1f
        else -> glass.error to 1f
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
                modifier = Modifier.clickable { onDismiss(code) }.padding(4.dp),
            )
        }
    }
}

// --- panels ------------------------------------------------------------------

@Composable
private fun IdlePanel(onStart: () -> Unit) {
    val glass = LocalGlass.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.status_idle),
            style = MaterialTheme.typography.bodyMedium,
            color = glass.textSecondary,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(text = stringResource(R.string.action_start), onClick = onStart)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.tagline),
            style = MaterialTheme.typography.labelSmall,
            color = glass.textTertiary,
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

@Composable
private fun PairingPanel(
    qrContent: String,
    typedCode: String?,
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

        val qr = rememberQrBitmap(qrContent)
        Image(
            bitmap = qr,
            contentDescription = stringResource(R.string.scan_hint),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .alpha(if (reconnecting) 0.4f else 1f)
                .clip(RoundedCornerShape(glass.radiusMd))
                .background(Color.White)
                .padding(12.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.scan_hint),
            style = MaterialTheme.typography.labelSmall,
            color = glass.textTertiary,
        )

        if (typedCode != null) {
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
        }

        Spacer(Modifier.height(24.dp))
        SubtleButton(text = stringResource(R.string.action_stop), onClick = onStop)
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
) {
    val glass = LocalGlass.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = (if (expanded) "▾  " else "▸  ") + stringResource(R.string.advanced),
            style = MaterialTheme.typography.labelSmall,
            color = glass.textTertiary,
            modifier = Modifier.clickable { expanded = !expanded }.padding(8.dp),
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
                            stringResource(R.string.advanced_logs_clear),
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.accent,
                            modifier = Modifier.clickable { onClearLogs() }.padding(4.dp),
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
            .clickable { onSet(value) }
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
    var text by remember(preferredPort) { mutableStateOf(if (preferredPort == 0) "" else preferredPort.toString()) }
    Column {
        Text(stringResource(R.string.advanced_port), style = MaterialTheme.typography.labelSmall, color = glass.textSecondary)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = text,
                onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) text = it },
                placeholder = { Text(stringResource(R.string.advanced_port_hint), color = glass.textTertiary) },
                singleLine = true,
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
                onClick = { onSetPort(text.toIntOrNull() ?: 0) },
            )
        }
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
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = glass.onAccent)
    }
}

@Composable
private fun SubtleButton(text: String, onClick: () -> Unit) {
    val glass = LocalGlass.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .glassPanel(radius = 999.dp)
            .clickable(onClick = onClick)
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
