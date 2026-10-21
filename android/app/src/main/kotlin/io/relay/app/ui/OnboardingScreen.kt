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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.relay.app.R
import io.relay.app.ui.theme.LocalGlass
import io.relay.app.ui.theme.glassPanel
import java.util.Locale

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
    /**
     * Whether sharing depends on it (notifications, battery) rather than it
     * being a shortcut (tile, widget, long-press). Shown in two groups, because
     * five steps of equal weight hid which two mattered (docs/ux-audit.md P-36).
     */
    val essential: Boolean = false,
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

    // The steps scroll; the button does not.
    //
    // It was inside the scroller, and on a short screen it sat below the fold —
    // caught on an emulator, where the first-run check could not find it. A
    // primary action that has to be scrolled to is a primary action some people
    // never reach, and "some people" on a first-run screen means they never get
    // past it.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))

            // Skippable from the first frame, as this screen always promised:
            // everything on it is reachable later, and the person who already
            // knows what they are doing should not have to read it. The string
            // existed in both languages and nothing showed it.
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDone) {
                    Text(
                        stringResource(R.string.onboarding_skip),
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textSecondary,
                    )
                }
            }

            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.displaySmall,
                color = glass.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_body),
                style = MaterialTheme.typography.bodyMedium,
                color = glass.textSecondary,
            )

            Spacer(Modifier.height(28.dp))

            val (needed, optional) = steps.partition { it.essential }
            var number = 0
            for ((label, group) in listOf(
                R.string.onboarding_section_needed to needed,
                R.string.onboarding_section_optional to optional,
            )) {
                if (group.isEmpty()) continue
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textSecondary,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                group.forEach { step ->
                    number += 1
                    StepRow(number, step)
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(16.dp))
        }

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
        Spacer(Modifier.height(28.dp))
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
                    // A count in prose, so it follows the reader's digits;
                    // codes and addresses elsewhere stay Latin on purpose.
                    text = if (step.action == StepAction.Done) "✓" else String.format(Locale.getDefault(), "%d", number),
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
                style = MaterialTheme.typography.bodySmall,
                color = glass.textSecondary,
            )

            when (step.action) {
                // Something to press: the platform will do the asking.
                StepAction.Offer -> {
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
                // Where the control is, when the platform will not ask for us.
                //
                // Plain text, not a button. It was a TextButton with zero
                // content padding, and the wrapped instruction came out with
                // the first character of each line shaved off — a button lays
                // its label out as one line and clips what does not fit. It
                // also was not a button in any useful sense: pressing it only
                // repeated the same sentence in a toast.
                StepAction.Instruct -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        step.actionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = glass.accent,
                    )
                }
                StepAction.Done -> Unit
            }
        }
    }
}
