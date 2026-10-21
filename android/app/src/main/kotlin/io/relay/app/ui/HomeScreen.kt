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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.text.BidiFormatter
import android.os.SystemClock
import io.relay.app.R
import io.relay.app.core.ConnectionState
import io.relay.app.core.ErrorCode
import io.relay.app.core.QrPayload
import io.relay.app.core.QrPayloadCodec
import io.relay.app.core.WarningCode
import io.relay.app.net.ProxyScan
import io.relay.app.core.RouteChoice
import io.relay.app.net.UsbLink
import io.relay.app.service.LocalLog
import io.relay.app.service.Settings
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
    fullModeAvailable: Boolean,
    logs: List<LocalLog.Entry>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onAllowBattery: () -> Unit,
    onDismissWarning: (WarningCode) -> Unit,
    onSetTheme: (String) -> Unit,
    onClearLogs: () -> Unit,
    onShareLogs: () -> Unit = {},
    /** Version string of a newer release, or null when this build is current. */
    updateAvailable: String? = null,
    onGetUpdate: () -> Unit = {},
    /** The computer waiting on an answer, or null. /shared/pairing-beacon.md. */
    pendingClient: String? = null,
    /** The name that computer gave, already sanitised, or null. */
    pendingClientName: String? = null,
    /** Opens the system's network settings, for the errors only a setting can fix. */
    onOpenNetworkSettings: () -> Unit = {},
    onApproveClient: (Boolean) -> Unit = {},
    /** What the USB cable is doing. See [UsbLink.Cable]. */
    cable: UsbLink.Cable = UsbLink.Cable.Absent,
    onTurnOnUsb: () -> Unit = {},
    onDismissUsbOffer: () -> Unit = {},
    /** Opens Android's VPN screen, for the one warning that can name a setting. */
    onOpenVpnSettings: () -> Unit = {},
    /** SOCKS5 `host:port` the PC's traffic is forwarded through, or empty. */
    upstreamProxy: String = "",
    onSetUpstreamProxy: (String) -> Unit = {},
    /** Where the search for a local proxy got to. See [ProxyScan]. */
    proxyScan: ProxyScan.State = ProxyScan.State.Idle,
    onScanForProxy: () -> Unit = {},
    onUseFoundProxy: () -> Unit = {},
    /** Whether Relay picks the PC's route itself. See [RouteChoice]. */
    automaticRoute: Boolean = true,
    onSetAutomaticRoute: (Boolean) -> Unit = {},
    /** The route in force while sharing, or null. */
    currentRoute: RouteChoice.Route? = null,
) {
    // Survives the crossfade between Connected and Reconnecting, and resets
    // whenever sharing stops: a new session starts with the code hidden.
    var showCode by rememberSaveable { mutableStateOf(false) }
    val connected = state is ConnectionState.Connected
    LaunchedEffect(connected) { if (!connected) showCode = false }
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

            // The battery request has its own banner with a button below, so
            // the warning of the same name would only say it twice.
            WarningBanners(warnings - WarningCode.BATTERY_UNRESTRICTED_DENIED, onDismissWarning, onOpenVpnSettings)

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
                        IdlePanel(fullModeAvailable, onStart)
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
                            overUsb = cable == UsbLink.Cable.Carrying,
                        )
                    // Connected is its own screen now (docs/ux-audit.md P-03). It
                    // used to be the pairing screen with one extra line, so the
                    // answer to "is it working?" was the smallest text on it and
                    // a code nobody needed any more was the biggest. The code is
                    // one tap away for the rare second pairing; it is no longer
                    // the subject.
                    is ConnectionState.Connected -> Column {
                        ConnectedPanel(
                            state = current,
                            overUsb = cable == UsbLink.Cable.Carrying,
                            showCode = showCode,
                            onToggleCode = { showCode = !showCode },
                            onStop = onStop,
                        )
                        if (showCode) {
                            Spacer(Modifier.height(16.dp))
                            PairingPanel(
                                rememberQrText(current.payload), current.typedCode, current.shortCode,
                                subtitle = null,
                                reconnecting = current.reconnecting,
                                traffic = null, onStop = onStop,
                                showStop = false,
                            )
                        }
                    }
                    is ConnectionState.Error ->
                        ErrorPanel(current.code, logs, onRetry, onDismissError, onOpenNetworkSettings)
                }
            }

            Spacer(Modifier.height(20.dp))
            // Only while sharing. On the idle screen the cable is a fact about
            // the phone; the moment Start is pressed it becomes the answer to
            // "how does the PC reach me", which is the question on screen.
            val sharing = state is ConnectionState.Advertising || state is ConnectionState.Connected
            if (sharing && cable == UsbLink.Cable.Offered) {
                UsbOffer(onTurnOn = onTurnOnUsb, onDismiss = onDismissUsbOffer)
                Spacer(Modifier.height(12.dp))
            }
            if (updateAvailable != null) {
                UpdateBanner(updateAvailable, onGetUpdate)
                Spacer(Modifier.height(12.dp))
            }
            if (!batteryExempt) {
                BatteryBanner(onAllowBattery)
                Spacer(Modifier.height(12.dp))
            }
            AdvancedSection(
                state, themeMode, logs, onSetTheme, onClearLogs, onShareLogs,
                upstreamProxy, onSetUpstreamProxy,
                proxyScan, onScanForProxy, onUseFoundProxy,
                automaticRoute, onSetAutomaticRoute, currentRoute,
            )
        }

        // Inside the Box and after the scrolling Column, so it sits above the
        // whole screen rather than scrolling away with the content.
        if (pendingClient != null) {
            ApprovalDialog(address = pendingClient, name = pendingClientName, onAnswer = onApproveClient)
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
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.weight(1f))
        // A dot alone made colour the only carrier of state on this platform,
        // while the Windows chip has said the word since 2.6. The row is a
        // polite live region, so TalkBack announces each change of state
        // without anyone having to go looking for it (docs/ux-audit.md P-24/P-25).
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(state)
            Text(
                text = stringResource(statusWord(state)),
                style = MaterialTheme.typography.labelSmall,
                color = glass.textSecondary,
            )
        }
    }
}

/** The one word that names the state, in the same order of precedence as the dot. */
private fun statusWord(state: ConnectionState): Int {
    val reconnecting = (state as? ConnectionState.Connected)?.reconnecting == true ||
        (state as? ConnectionState.Advertising)?.reconnecting == true
    return when {
        reconnecting -> R.string.status_word_reconnecting
        state is ConnectionState.Idle -> R.string.status_word_idle
        state is ConnectionState.Preparing -> R.string.status_word_starting
        state is ConnectionState.Advertising -> R.string.status_word_waiting
        state is ConnectionState.Connected -> R.string.status_word_connected
        else -> R.string.status_word_error
    }
}

/**
 * Whether the person has turned animations off ("Remove animations").
 *
 * Read from the animator scale Android itself uses for that switch. Infinite
 * pulses are the motion most likely to bother someone who asked for none, so
 * they get an explicit branch rather than relying on a scale of zero to land
 * them on a sensible frame.
 */
@Composable
private fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/** A disclosure arrow that points the way the reading direction goes. */
@Composable
private fun disclosure(expanded: Boolean): String = when {
    expanded -> "▾"
    LocalLayoutDirection.current == LayoutDirection.Rtl -> "◂"
    else -> "▸"
}

/** Keeps an embedded run (a name, an address, a number) from reordering its sentence. */
private fun isolate(text: String): String = BidiFormatter.getInstance().unicodeWrap(text)

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
    val pulsing = !reducedMotion() && (
        reconnecting ||
            state is ConnectionState.Preparing ||
            state is ConnectionState.Advertising
        )
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
private fun WarningBanners(
    warnings: Set<WarningCode>,
    onDismiss: (WarningCode) -> Unit,
    onOpenVpnSettings: () -> Unit = {},
) {
    val glass = LocalGlass.current
    warnings.forEach { code ->
        val (title, body) = when (code) {
            WarningCode.NO_VPN_ACTIVE ->
                R.string.warning_no_vpn_title to R.string.warning_no_vpn_body
            WarningCode.BATTERY_UNRESTRICTED_DENIED ->
                R.string.battery_banner_title to R.string.battery_banner_body
            WarningCode.PC_GOT_NO_REPLY ->
                R.string.warning_no_reply_title to R.string.warning_no_reply_body
            WarningCode.VPN_LOCKDOWN_ON ->
                R.string.warning_lockdown_title to R.string.warning_lockdown_body
        }
        // Informational, not a fault: sharing without a VPN is the ordinary
        // case for most people, and the amber dot real faults use made it look
        // like one (docs/ux-audit.md P-13).
        val informational = code == WarningCode.NO_VPN_ACTIVE
        // Actions sit under the words, as the USB card's already do. Beside
        // them, a dismiss and a button left a long body about three words a
        // line at large font sizes (P-29).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .glassPanel(radius = 16.dp)
                .padding(16.dp)
                .semantics(mergeDescendants = false) {
                    if (!informational) liveRegion = LiveRegionMode.Polite
                },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(8.dp).background(
                        if (informational) glass.textTertiary else glass.warning, CircleShape,
                    ),
                )
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = glass.textPrimary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodySmall,
                color = glass.textSecondary,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                QuietAction(stringResource(R.string.action_dismiss)) { onDismiss(code) }
                Spacer(Modifier.weight(1f))
                // Only this one gets a button, because it is the only warning that
                // knows a screen worth opening. Android exports no deep link to the
                // page the switch is actually on, so this lands on the VPN list and
                // the body text names the last tap -- the ⚙ beside the app's name.
                if (code == WarningCode.VPN_LOCKDOWN_ON) {
                    SubtleButton(
                        text = stringResource(R.string.warning_lockdown_action),
                        onClick = onOpenVpnSettings,
                    )
                }
            }
        }
    }
}

/**
 * A text-weight action: dismiss, show details, look again. Secondary label
 * colour rather than the accent, so the screen's one accented thing stays the
 * one thing to do (DESIGN.md → One Accent Rule).
 */
@Composable
private fun QuietAction(text: String, onClick: () -> Unit) {
    val glass = LocalGlass.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = glass.textSecondary,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(horizontal = 4.dp),
    )
}

// --- panels ------------------------------------------------------------------

/** The exact QR string, recomputed only when the payload itself changes. */
@Composable
private fun rememberQrText(payload: QrPayload): String =
    remember(payload) { QrPayloadCodec.encodeForQr(payload) }

@Composable
private fun IdlePanel(
    fullModeAvailable: Boolean,
    onStart: () -> Unit,
) {
    val glass = LocalGlass.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.status_idle),
            style = MaterialTheme.typography.bodyMedium,
            color = glass.textSecondary,
        )
        Spacer(Modifier.height(12.dp))
        // One transport since ADR-0009, so there is nothing to choose here --
        // just a sentence saying what pressing the button will do.
        Text(
            text = stringResource(
                if (fullModeAvailable) R.string.mode_full_desc else R.string.mode_full_unavailable
            ),
            style = MaterialTheme.typography.labelSmall,
            color = glass.textTertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        // A build without the forwarder cannot share at all now that there is no
        // second transport, so the button is not offered rather than offered and
        // guaranteed to fail.
        PrimaryButton(
            text = stringResource(R.string.action_start),
            onClick = onStart,
            enabled = fullModeAvailable,
        )
    }
}

@Composable
private fun PreparingPanel() {
    val glass = LocalGlass.current
    val alpha = if (reducedMotion()) {
        1f
    } else {
        val pulse by rememberInfiniteTransition(label = "prep").animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = InfiniteRepeatableSpec(tween(900), RepeatMode.Reverse),
            label = "prepAlpha",
        )
        pulse
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.status_preparing),
            style = MaterialTheme.typography.bodyMedium,
            color = glass.textSecondary,
            modifier = Modifier.alpha(alpha),
        )
        // This can take several seconds while a link is still getting an
        // address (LinkWait), and a word that only pulses gives no reason.
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.preparing_hint),
            style = MaterialTheme.typography.bodySmall,
            color = glass.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
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
private fun ApprovalDialog(address: String, name: String?, onAnswer: (Boolean) -> Unit) {
    val glass = LocalGlass.current
    // The name the PC gave, beside the address it came from. A name alone is
    // whatever the sender typed; an address alone is something nobody can
    // check. Isolated so neither can reorder the sentence (docs/ux-audit.md P-04).
    val body = if (name != null) {
        stringResource(R.string.approve_body_named, isolate(name), isolate(address))
    } else {
        stringResource(R.string.approve_body, isolate(address))
    }
    AlertDialog(
        onDismissRequest = { },
        containerColor = glass.backgroundGradientTop,
        titleContentColor = glass.textPrimary,
        textContentColor = glass.textSecondary,
        title = { Text(stringResource(R.string.approve_title)) },
        text = { Text(body) },
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
    subtitle: String?,
    reconnecting: Boolean,
    traffic: String?,
    onStop: () -> Unit,
    connected: Boolean = false,
    /** Whether the phone is currently reachable over the cable. */
    overUsb: Boolean = false,
    /** False when shown under the connected screen, which has its own Stop. */
    showStop: Boolean = true,
) {
    val glass = LocalGlass.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(raised = connected)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    reconnecting -> glass.warning
                    connected -> glass.accent
                    else -> glass.textSecondary
                },
            )
        }
        // The payoff for the whole feature: the person who plugged the cable in
        // gets told it is being used, rather than having to infer it from the
        // speed. Accent-coloured because it is good news, and it sits above the
        // byte counters because it explains them.
        if (overUsb) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.usb_carrying),
                style = MaterialTheme.typography.labelSmall,
                color = glass.accent,
            )
        }
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
            if (showStop) {
                SubtleButton(text = stringResource(R.string.action_stop), onClick = onStop)
                Spacer(Modifier.height(24.dp))
            }
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

        // The eight-character code, shown only when this phone could not
        // announce itself at all — then the QR and this are the only ways in.
        // The PC keeps a way to type it, behind a link that names what the user
        // would be looking at (/shared/pairing-beacon.md → Compatibility).
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

        if (shortCode == null && showStop) {
            Spacer(Modifier.height(24.dp))
            SubtleButton(text = stringResource(R.string.action_stop), onClick = onStop)
        }
    }
}

/**
 * What the PC's traffic is doing, from the endpoint's own counters.
 *
 * Speed first, because it answers "is it working right now"; totals second,
 * because they answer "has it been working". Rates are the difference between
 * two readings of the counters the service already pushes once a second, so
 * nothing new is asked of the tunnel to show them. Figures are tabular so a
 * changing number does not make its neighbours jitter.
 */
@Composable
private fun ConnectedPanel(
    state: ConnectionState.Connected,
    overUsb: Boolean,
    showCode: Boolean,
    onToggleCode: () -> Unit,
    onStop: () -> Unit,
) {
    val glass = LocalGlass.current
    var previous by remember { mutableStateOf<Triple<Long, Long, Long>?>(null) }
    var upRate by remember { mutableStateOf<Long?>(null) }
    var downRate by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.bytesUp, state.bytesDown) {
        val now = SystemClock.elapsedRealtime()
        previous?.let { (up, down, at) ->
            val elapsed = now - at
            if (elapsed > 0) {
                upRate = (state.bytesUp - up).coerceAtLeast(0) * 1000 / elapsed
                downRate = (state.bytesDown - down).coerceAtLeast(0) * 1000 / elapsed
            }
        }
        previous = Triple(state.bytesUp, state.bytesDown, now)
    }

    Column(
        modifier = Modifier.fillMaxWidth().glassPanel(raised = true).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                if (state.reconnecting) R.string.status_reconnecting else R.string.connected_title,
            ),
            style = MaterialTheme.typography.titleLarge,
            color = if (state.reconnecting) glass.warning else glass.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (overUsb) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.usb_carrying),
                style = MaterialTheme.typography.bodySmall,
                color = glass.accent,
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth().alpha(if (state.reconnecting) 0.4f else 1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RateColumn(R.string.connected_down, downRate, Modifier.weight(1f))
            RateColumn(R.string.connected_up, upRate, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(
                R.string.connected_totals,
                isolate(formatBytes(state.bytesDown)),
                isolate(formatBytes(state.bytesUp)),
            ),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = glass.textTertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        SubtleButton(text = stringResource(R.string.action_stop), onClick = onStop)
        Spacer(Modifier.height(8.dp))
        QuietAction(
            "${disclosure(showCode)}  " + stringResource(
                if (showCode) R.string.connected_hide_code else R.string.connected_show_code,
            ),
            onClick = onToggleCode,
        )
    }
}

@Composable
private fun RateColumn(label: Int, bytesPerSecond: Long?, modifier: Modifier) {
    val glass = LocalGlass.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = glass.textTertiary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = bytesPerSecond?.let { stringResource(R.string.traffic_rate, formatBytes(it)) } ?: "—",
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            color = glass.textPrimary,
            maxLines = 1,
        )
    }
}

/**
 * An error, what Relay already did about it, and the one thing to do next.
 *
 * Three of these are fixed in Settings and nowhere else, so for those the
 * accented action opens Settings: offering "Try Again" first sent people
 * through the same eight-second wait to the same failure (docs/ux-audit.md
 * P-08). Retry stays, one step quieter, for after the setting is changed.
 */
@Composable
private fun ErrorPanel(
    code: ErrorCode,
    logs: List<LocalLog.Entry>,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
) {
    val glass = LocalGlass.current
    val (title, body) = when (code) {
        ErrorCode.HOTSPOT_OFF -> R.string.error_hotspot_off_title to R.string.error_hotspot_off_body
        ErrorCode.LINK_NEGOTIATING ->
            R.string.error_link_negotiating_title to R.string.error_link_negotiating_body
        ErrorCode.ONLY_MOBILE_DATA ->
            R.string.error_only_mobile_data_title to R.string.error_only_mobile_data_body
        ErrorCode.ONLY_VPN -> R.string.error_only_vpn_title to R.string.error_only_vpn_body
        ErrorCode.HOTSPOT_LOST -> R.string.error_hotspot_lost_title to R.string.error_hotspot_lost_body
        ErrorCode.PORT_IN_USE -> R.string.error_port_in_use_title to R.string.error_port_in_use_body
        ErrorCode.SERVICE_FAILED -> R.string.error_service_failed_title to R.string.error_service_failed_body
        ErrorCode.WG_START_FAILED -> R.string.error_wg_start_title to R.string.error_wg_start_body
    }
    val tried = when (code) {
        ErrorCode.HOTSPOT_OFF, ErrorCode.LINK_NEGOTIATING,
        ErrorCode.ONLY_MOBILE_DATA, ErrorCode.ONLY_VPN -> R.string.error_tried_link
        ErrorCode.HOTSPOT_LOST -> R.string.error_tried_lost
        ErrorCode.WG_START_FAILED -> R.string.error_tried_tunnel
        else -> null
    }
    val needsSettings = code == ErrorCode.HOTSPOT_OFF ||
        code == ErrorCode.ONLY_MOBILE_DATA || code == ErrorCode.ONLY_VPN
    var details by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel()
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
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
        if (tried != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(tried),
                style = MaterialTheme.typography.bodySmall,
                color = glass.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
        if (needsSettings) {
            PrimaryButton(text = stringResource(R.string.error_action_network), onClick = onOpenNetworkSettings)
            Spacer(Modifier.height(8.dp))
            SubtleButton(text = stringResource(R.string.action_retry), onClick = onRetry)
        } else {
            PrimaryButton(text = stringResource(R.string.action_retry), onClick = onRetry)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietAction(stringResource(R.string.action_dismiss), onClick = onDismiss)
            QuietAction(
                "${disclosure(details)}  " + stringResource(R.string.error_details),
                onClick = { details = !details },
            )
        }
        // The code and the last few log lines, for the person who will paste
        // them into an issue. Collapsed, because they are not the next step.
        AnimatedVisibility(visible = details) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    stringResource(R.string.error_details_code, code.name),
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textSecondary,
                    fontFamily = FontFamily.Monospace,
                )
                logs.takeLast(4).forEach { entry ->
                    Text(
                        entry.renderCompact(),
                        style = MaterialTheme.typography.labelSmall,
                        color = glass.textTertiary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/**
 * Offers the cable that is already plugged in.
 *
 * Not a warning, and it does not borrow the warning banner's orange dot:
 * nothing is wrong, there is simply a better link available. It asks rather
 * than asserts because the signal behind it cannot tell a computer from a
 * charger — see [UsbLink.cableToComputer] — so the cost of being wrong is one
 * card a person can wave away.
 *
 * The button leaves the app, which is the honest shape of this feature: USB
 * tethering is a system switch, and no permission Relay can hold will flip it.
 */
@Composable
private fun UsbOffer(onTurnOn: () -> Unit, onDismiss: () -> Unit) {
    val glass = LocalGlass.current
    // The actions sit under the words rather than beside them, which the other
    // banners get away with because they have one action each. A dot, two lines
    // of copy, a button *and* a dismiss on one row leaves the copy about 96dp on
    // a 360dp phone — three words a line, and the sentence that explains the
    // whole feature turns into a column of fragments.
    Column(
        modifier = Modifier.fillMaxWidth().glassPanel(radius = 16.dp).padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(8.dp).background(glass.accent, CircleShape))
            Text(
                stringResource(R.string.usb_offer_title),
                style = MaterialTheme.typography.bodyMedium,
                color = glass.textPrimary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.usb_offer_body),
            style = MaterialTheme.typography.bodySmall,
            color = glass.textSecondary,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // "Not now", not "Dismiss": nothing is wrong, and the offer is
            // meant to come back the next time the cable turns up.
            Text(
                text = stringResource(R.string.usb_offer_dismiss),
                style = MaterialTheme.typography.labelSmall,
                color = glass.textTertiary,
                modifier = Modifier
                    .clickable(role = Role.Button) { onDismiss() }
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            SubtleButton(text = stringResource(R.string.usb_offer_action), onClick = onTurnOn)
        }
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
            Text(stringResource(R.string.battery_banner_body), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
        }
        SubtleButton(text = stringResource(R.string.battery_banner_allow), onClick = onAllow)
    }
}

/**
 * Says a newer release exists and offers to fetch it.
 *
 * The note about Android asking first is not filler. This app cannot install
 * an update by itself -- the platform reserves that for system apps and store
 * installs -- and a banner that implied otherwise would leave someone thinking
 * the update failed when the installer prompt is exactly what is supposed to
 * happen.
 */
@Composable
private fun UpdateBanner(version: String, onGet: () -> Unit) {
    val glass = LocalGlass.current
    Row(
        modifier = Modifier.fillMaxWidth().glassPanel(radius = 16.dp).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.update_available, version),
                style = MaterialTheme.typography.bodyMedium,
                color = glass.textPrimary,
            )
            Text(
                stringResource(R.string.update_manual_note),
                style = MaterialTheme.typography.labelSmall,
                color = glass.textSecondary,
            )
        }
        SubtleButton(text = stringResource(R.string.update_get), onClick = onGet)
    }
}

// --- advanced ----------------------------------------------------------------

@Composable
private fun AdvancedSection(
    state: ConnectionState,
    themeMode: String,
    logs: List<LocalLog.Entry>,
    onSetTheme: (String) -> Unit,
    onClearLogs: () -> Unit,
    onShareLogs: () -> Unit,
    upstreamProxy: String,
    onSetUpstreamProxy: (String) -> Unit,
    proxyScan: ProxyScan.State,
    onScanForProxy: () -> Unit,
    onUseFoundProxy: () -> Unit,
    automaticRoute: Boolean,
    onSetAutomaticRoute: (Boolean) -> Unit,
    currentRoute: RouteChoice.Route?,
) {
    val glass = LocalGlass.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    // Look for the VPN's proxy port the moment this section is opened with the
    // field empty, because that is the only moment the answer is useful and
    // nobody would think to ask for it. Keyed so it runs once per opening and
    // not on every recomposition, and skipped entirely once something is set —
    // a scan is cheap, but overwriting what someone typed would not be.
    LaunchedEffect(expanded, upstreamProxy.isEmpty()) {
        if (expanded && upstreamProxy.isEmpty()) onScanForProxy()
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "${disclosure(expanded)}  " + stringResource(R.string.advanced),
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
                // Three groups, each with a heading, instead of five unrelated
                // rows in one list (docs/ux-audit.md P-40).
                AdvancedHeading(R.string.advanced_section_connection)

                // Phone address (read-only, from current state)
                val host = when (state) {
                    is ConnectionState.Advertising -> "${state.payload.host}:${state.payload.port}"
                    is ConnectionState.Connected -> "${state.payload.host}:${state.payload.port}"
                    else -> "—"
                }
                LabeledValue(stringResource(R.string.advanced_ip), host)

                // Where the PC's traffic leaves this phone.
                //
                // The only setting here that changes routing, and it is last
                // because most people never need it: it earns its place on the
                // phones where a VPN captures Relay, where without it you can
                // have the connection or the VPN and not both.
                Column {
                    Text(
                        stringResource(R.string.advanced_upstream),
                        style = MaterialTheme.typography.labelSmall,
                        color = glass.textSecondary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.advanced_route_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textTertiary,
                    )
                    Spacer(Modifier.height(6.dp))
                    // Automatic is the default since 2.10.0 (ADR-0011): the
                    // phone's route while Relay is inside the VPN, the VPN's
                    // own proxy while it is not. A typed address below beats
                    // both and is never dropped.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val chosen = if (automaticRoute) "auto" else "phone"
                        ThemeChip("auto", R.string.advanced_route_auto, chosen) { onSetAutomaticRoute(true) }
                        ThemeChip("phone", R.string.advanced_route_phone, chosen) { onSetAutomaticRoute(false) }
                    }
                    // What is actually happening, not what was asked for: the
                    // one setting that changes where packets go has to say so.
                    if (currentRoute != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when (currentRoute.why) {
                                RouteChoice.Why.MANUAL, RouteChoice.Why.VPN_PROXY ->
                                    stringResource(R.string.advanced_route_now_proxy, currentRoute.proxy)
                                RouteChoice.Why.RELAY_IN_VPN -> stringResource(R.string.advanced_route_now_vpn)
                                RouteChoice.Why.NO_PROXY -> stringResource(R.string.advanced_route_now_bare)
                                RouteChoice.Why.AUTOMATIC_OFF -> stringResource(R.string.advanced_route_now_phone)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (currentRoute.why == RouteChoice.Why.NO_PROXY) glass.textSecondary else glass.accent,
                        )
                    }
                    // A typed proxy is for the few who need to pin one, so the
                    // field stays folded away unless one is already set.
                    var manual by rememberSaveable { mutableStateOf(upstreamProxy.isNotEmpty()) }
                    Spacer(Modifier.height(2.dp))
                    QuietAction("${disclosure(manual)}  " + stringResource(R.string.advanced_upstream_manual)) {
                        manual = !manual
                    }
                    val usable = Settings.isUsableProxy(upstreamProxy)
                    if (manual) {
                    Text(
                        stringResource(R.string.advanced_upstream_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textTertiary,
                    )
                    Spacer(Modifier.height(6.dp))
                    BasicTextField(
                        value = upstreamProxy,
                        onValueChange = onSetUpstreamProxy,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = glass.textPrimary,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(glass.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassPanel(radius = 12.dp)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        decorationBox = { field ->
                            if (upstreamProxy.isEmpty()) {
                                Text(
                                    stringResource(R.string.advanced_upstream_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = glass.textTertiary,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            field()
                        },
                    )
                    // Said while it is still being typed, not after sharing
                    // fails: a bad address here produces a tunnel that comes up
                    // and carries nothing, which looks like a broken network.
                    if (!usable) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.advanced_upstream_invalid),
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.error,
                        )
                    }
                    }

                    // What the search found. Only while the field is empty:
                    // once there is an address, the person has decided, and a
                    // line suggesting a different port is second-guessing them.
                    //
                    // A found port is *offered*, not filled in. This is the one
                    // setting that changes where packets go, and opening a
                    // section is not consent to reroute a connection.
                    if (upstreamProxy.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        when (proxyScan) {
                            is ProxyScan.State.Idle -> Unit

                            is ProxyScan.State.Looking -> Text(
                                stringResource(R.string.advanced_upstream_looking),
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.textTertiary,
                            )

                            // Automatic already uses what was found whenever
                            // the PC needs it, so there is nothing to tap.
                            is ProxyScan.State.Found -> if (automaticRoute) Text(
                                stringResource(
                                    R.string.advanced_upstream_found_auto,
                                    proxyScan.address,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.textTertiary,
                            ) else Text(
                                stringResource(
                                    R.string.advanced_upstream_found,
                                    proxyScan.address,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.accent,
                                modifier = Modifier
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = stringResource(
                                            R.string.advanced_upstream_found_action,
                                        ),
                                        onClick = onUseFoundProxy,
                                    )
                                    .minimumInteractiveComponentSize(),
                            )

                            is ProxyScan.State.NotFound -> Text(
                                stringResource(R.string.advanced_upstream_none),
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.textTertiary,
                                modifier = Modifier
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = stringResource(
                                            R.string.advanced_upstream_again,
                                        ),
                                        onClick = onScanForProxy,
                                    )
                                    .minimumInteractiveComponentSize(),
                            )
                        }
                    }
                }

                // Appearance
                AdvancedHeading(R.string.advanced_theme)
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeChip("system", R.string.theme_system, themeMode, onSetTheme)
                        ThemeChip("dark", R.string.theme_dark, themeMode, onSetTheme)
                        ThemeChip("light", R.string.theme_light, themeMode, onSetTheme)
                    }
                }


                AdvancedHeading(R.string.advanced_section_diagnostics)
                // Which build this is. Two people holding two APKs with the same
                // name and different vintages had no way to discover that was
                // what they were doing, and the report they sent named a version
                // that never existed. Read from the installed package, so it is
                // the truth about this APK rather than about the source tree.
                LabeledValue(stringResource(R.string.advanced_version), installedVersion())

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
                            color = glass.textSecondary,
                            modifier = Modifier
                                .clickable(role = Role.Button) { onShareLogs() }
                                .minimumInteractiveComponentSize()
                                .padding(4.dp),
                        )
                        Text(
                            stringResource(R.string.advanced_logs_clear),
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.textSecondary,
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
                                    text = entry.renderCompact(),
                                    style = MaterialTheme.typography.labelSmall,
                                    // The level as colour rather than as a
                                    // column: this box is 160dp on a phone, and
                                    // the whole reason for having levels is
                                    // being able to find the failure without
                                    // reading every line.
                                    color = when (entry.level) {
                                        LocalLog.Level.ERROR -> glass.error
                                        LocalLog.Level.WARN -> glass.warning
                                        LocalLog.Level.INFO -> glass.textTertiary
                                    },
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

/** The versionName of the APK actually installed, or "unknown". */
@Composable
private fun installedVersion(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }
}

/** A group heading inside Advanced, announced as a heading by TalkBack. */
@Composable
private fun AdvancedHeading(label: Int) {
    val glass = LocalGlass.current
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelSmall,
        color = glass.textSecondary,
        modifier = Modifier.semantics { heading() },
    )
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
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val glass = LocalGlass.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) glass.accent else glass.accent.copy(alpha = 0.35f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(horizontal = 32.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) glass.onAccent else glass.onAccent.copy(alpha = 0.6f),
        )
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

/**
 * A byte count in the reader's own language.
 *
 * The units used to be Kotlin string literals — `"%.1f GB"` — so "GB", "MB",
 * "KB" and "B" were the four user-facing words in the app that had no Persian
 * and no way to get one: they never reached `strings.xml`, so the parity test
 * could not see them and a translator had nothing to translate. They are
 * parameterised resources now.
 *
 * The *number* is still formatted with [Locale.US] on purpose. It is set beside
 * an address and a port in a line that is already pinned LTR, and a decimal
 * separator that changes with the locale would put a Persian comma inside a run
 * of Latin digits.
 */
@Composable
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 ->
        stringResource(R.string.traffic_gb, String.format(Locale.US, "%.1f", bytes / 1e9))
    bytes >= 1_000_000 ->
        stringResource(R.string.traffic_mb, String.format(Locale.US, "%.1f", bytes / 1e6))
    bytes >= 1_000 ->
        stringResource(R.string.traffic_kb, String.format(Locale.US, "%.0f", bytes / 1e3))
    else -> stringResource(R.string.traffic_b, bytes.toString())
}
