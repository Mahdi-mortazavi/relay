package io.relay.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.relay.app.R
import io.relay.app.core.ConnectionState
import io.relay.app.core.ErrorCode
import io.relay.app.core.QrPayloadCodec
import io.relay.app.ui.theme.Glass
import io.relay.app.ui.theme.glassPanel
import java.util.Locale

@Composable
fun HomeScreen(
    state: ConnectionState,
    batteryExempt: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onAllowBattery: () -> Unit,
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
            Spacer(Modifier.height(32.dp))

            Crossfade(targetState = state, label = "state") { current ->
                when (current) {
                    is ConnectionState.Idle -> IdlePanel(onStart)
                    is ConnectionState.Preparing -> PreparingPanel()
                    is ConnectionState.Advertising ->
                        PairingPanel(current.payload.let(QrPayloadCodec::encodeForQr),
                            current.typedCode, subtitle = stringResource(R.string.status_waiting),
                            traffic = null, onStop = onStop)
                    is ConnectionState.Connected ->
                        PairingPanel(current.payload.let(QrPayloadCodec::encodeForQr),
                            current.typedCode,
                            subtitle = pluralStringResource(
                                R.plurals.status_connected, current.clientCount, current.clientCount,
                            ),
                            traffic = formatTraffic(current.bytesUp, current.bytesDown),
                            onStop = onStop, connected = true)
                    is ConnectionState.Error -> ErrorPanel(current.code, onRetry, onDismissError)
                }
            }

            Spacer(Modifier.height(24.dp))
            if (!batteryExempt) BatteryBanner(onAllowBattery)
        }
    }
}

// --- pieces ------------------------------------------------------------------

@Composable
private fun Header(state: ConnectionState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = Glass.textPrimary,
        )
        Spacer(Modifier.weight(1f))
        StatusDot(state)
    }
}

@Composable
private fun StatusDot(state: ConnectionState) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = InfiniteRepeatableSpec(tween(1200), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    val (color, alpha) = when (state) {
        is ConnectionState.Idle -> Glass.textTertiary to 1f
        is ConnectionState.Preparing -> Glass.warning to pulse
        is ConnectionState.Advertising -> Glass.accent to pulse
        is ConnectionState.Connected -> Glass.accent to 1f
        is ConnectionState.Error -> Glass.error to 1f
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

@Composable
private fun IdlePanel(onStart: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.status_idle),
            style = MaterialTheme.typography.bodyMedium,
            color = Glass.textSecondary,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(text = stringResource(R.string.action_start), onClick = onStart)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.tagline),
            style = MaterialTheme.typography.labelSmall,
            color = Glass.textTertiary,
        )
    }
}

@Composable
private fun PreparingPanel() {
    val pulse by rememberInfiniteTransition(label = "prep").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = InfiniteRepeatableSpec(tween(900), RepeatMode.Reverse),
        label = "prepAlpha",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.status_preparing),
            style = MaterialTheme.typography.bodyMedium,
            color = Glass.textSecondary,
            modifier = Modifier.alpha(pulse),
        )
    }
}

@Composable
private fun PairingPanel(
    qrContent: String,
    typedCode: String?,
    subtitle: String,
    traffic: String?,
    onStop: () -> Unit,
    connected: Boolean = false,
) {
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
            color = if (connected) Glass.accent else Glass.textSecondary,
        )
        if (traffic != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = traffic,
                style = MaterialTheme.typography.labelSmall,
                color = Glass.textTertiary,
            )
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
                .clip(RoundedCornerShape(Glass.radiusMd))
                .background(Color.White)
                .padding(12.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.scan_hint),
            style = MaterialTheme.typography.labelSmall,
            color = Glass.textTertiary,
        )

        if (typedCode != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.or_type_code),
                style = MaterialTheme.typography.labelSmall,
                color = Glass.textTertiary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = typedCode.chunked(4).joinToString("-"),
                style = MaterialTheme.typography.headlineMedium,
                color = Glass.textPrimary,
            )
        }

        Spacer(Modifier.height(24.dp))
        SubtleButton(text = stringResource(R.string.action_stop), onClick = onStop)
    }
}

@Composable
private fun ErrorPanel(code: ErrorCode, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val (title, body) = when (code) {
        ErrorCode.HOTSPOT_OFF ->
            R.string.error_hotspot_off_title to R.string.error_hotspot_off_body
        ErrorCode.PORT_IN_USE ->
            R.string.error_port_in_use_title to R.string.error_port_in_use_body
        ErrorCode.SERVICE_FAILED ->
            R.string.error_service_failed_title to R.string.error_service_failed_body
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            color = Glass.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = Glass.textSecondary,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(radius = Glass.radiusMd)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.battery_banner_title),
                style = MaterialTheme.typography.bodyMedium,
                color = Glass.textPrimary,
            )
            Text(
                text = stringResource(R.string.battery_banner_body),
                style = MaterialTheme.typography.labelSmall,
                color = Glass.textSecondary,
            )
        }
        SubtleButton(text = stringResource(R.string.battery_banner_allow), onClick = onAllow)
    }
}

// --- buttons -------------------------------------------------------------------

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Glass.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Glass.backgroundBase,
        )
    }
}

@Composable
private fun SubtleButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .glassPanel(radius = 999.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Glass.textPrimary,
        )
    }
}

// --- helpers ---------------------------------------------------------------------

@Composable
private fun formatTraffic(up: Long, down: Long): String =
    stringResource(R.string.traffic_format, formatBytes(up), formatBytes(down))

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
    else -> "$bytes B"
}
