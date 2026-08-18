package io.relay.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.relay.app.R
import io.relay.app.ui.theme.LocalGlass
import io.relay.app.ui.theme.glassPanel

/**
 * What a step can be in. [Offer] means the platform will do the asking for us;
 * [Instruct] means it will not, and the honest thing is to say where the button
 * is rather than show one that does nothing.
 */
enum class StepAction { Offer, Instruct, Done }

data class OnboardingStep(
    val title: String,
    val body: String,
    val action: StepAction,
    val actionLabel: String,
    val onAction: () -> Unit,
)

/**
 * The first launch.
 *
 * Relay's whole value is being *there* when a laptop has no internet — which
 * means the setup that matters is not "how do I share" but "where will this be
 * when I need it". So this screen is a checklist of exactly that, and every
 * item that Android will let an app request is a button rather than a
 * paragraph telling someone to go and find a setting.
 *
 * Shown once. Skippable from the first frame: someone who knows what they are
 * doing should never have to read it, and everything here is reachable from
 * Advanced afterwards.
 */
@Composable
fun OnboardingScreen(
    steps: List<OnboardingStep>,
    onDone: () -> Unit,
) {
    val glass = LocalGlass.current

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.displaySmall,
                color = glass.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_body),
                style = MaterialTheme.typography.bodyMedium,
                color = glass.textSecondary,
            )

            Spacer(Modifier.height(28.dp))

            steps.forEachIndexed { index, step ->
                StepRow(index + 1, step)
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = glass.accent,
                    contentColor = glass.onAccent,
                ),
            ) {
                Text(
                    stringResource(R.string.onboarding_done),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepRow(number: Int, step: OnboardingStep) {
    val glass = LocalGlass.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(radius = 20.dp)
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The number, not a tick: these are things to do, and a row that is
        // already done says so on its action instead.
        Surface(
            shape = CircleShape,
            color = if (step.action == StepAction.Done) glass.accent else glass.accentSubtle,
            modifier = Modifier.size(26.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (step.action == StepAction.Done) "✓" else number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (step.action == StepAction.Done) glass.onAccent else glass.accent,
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = glass.textPrimary,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = step.body,
                style = MaterialTheme.typography.labelSmall,
                color = glass.textTertiary,
            )

            if (step.action != StepAction.Done) {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = step.onAction,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(
                        step.actionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = glass.accent,
                    )
                }
            }
        }
    }
}
