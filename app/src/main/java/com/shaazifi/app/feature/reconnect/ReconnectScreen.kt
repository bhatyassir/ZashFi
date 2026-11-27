package com.shaazifi.app.feature.reconnect

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.feature.routers.RouterDisplay
import com.shaazifi.app.ui.components.Eyebrow
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme

/**
 * Native reconnect cover (mock: "reconnect cover"): a countdown ring while the app waits
 * for the router, then the outcome. Probing logic is unchanged (ReconnectProber).
 */
@Composable
fun ReconnectScreen(
    profile: RouterProfile,
    candidateAddress: String? = null,
    onReconnected: (verifiedAddress: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prober = remember { ReconnectProber() }
    var attempt by remember(profile.id, candidateAddress) { mutableStateOf(0) }
    var reconnectState by remember(profile.id, candidateAddress) {
        mutableStateOf<ReconnectState>(ReconnectState.Probing(ReconnectProber.DEFAULT_DURATION_SECONDS, 0))
    }

    LaunchedEffect(profile.id, candidateAddress, attempt) {
        prober.probe(
            currentAddress = profile.address,
            candidateAddress = candidateAddress,
            pinnedFingerprint = profile.certificateFingerprint
        ).collect { state ->
            reconnectState = state
        }
    }

    val c = SfTheme.colors
    val state = reconnectState
    val copy = ReconnectDisplay.copy(state, profile.name)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Pill(copy.pill, tone = copy.tone)

        if (state is ReconnectState.Probing) {
            CountdownRing(
                remainingSeconds = state.remainingSeconds,
                fraction = ReconnectDisplay.ringFraction(state.remainingSeconds, ReconnectProber.DEFAULT_DURATION_SECONDS)
            )
        }

        Text(copy.title, style = MaterialTheme.typography.headlineMedium, color = c.ink, textAlign = TextAlign.Center)
        Text(copy.body, style = SfText.sub, color = c.muted, textAlign = TextAlign.Center)

        when (state) {
            is ReconnectState.Probing -> {
                SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 4.dp) {
                    Eyebrow("Trying")
                    Text(RouterDisplay.hostLabel(profile.address), style = SfText.mono, color = c.ink)
                    if (candidateAddress != null) {
                        Text("then ${RouterDisplay.hostLabel(candidateAddress)}", style = SfText.mono, color = c.ink)
                    }
                }
                GhostButton(text = "Stop waiting", onClick = onCancel)
            }
            is ReconnectState.ReconnectedKept -> {
                Text(RouterDisplay.hostLabel(state.newAddress), style = SfText.mono, color = c.ink)
                PrimaryButton(text = "Continue", onClick = { onReconnected(state.newAddress) })
            }
            is ReconnectState.ReconnectedSameAddress -> {
                Text(RouterDisplay.hostLabel(state.address), style = SfText.mono, color = c.ink)
                PrimaryButton(text = "Continue", onClick = { onReconnected(state.address) })
            }
            is ReconnectState.ReconnectedRolledBack -> {
                Text(RouterDisplay.hostLabel(state.oldAddress), style = SfText.mono, color = c.ink)
                PrimaryButton(text = "Continue with the old settings", onClick = { onReconnected(state.oldAddress) })
            }
            is ReconnectState.CertificateChanged -> {
                SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 4.dp) {
                    Eyebrow("Approved certificate")
                    RouterDisplay.fingerprintLines(profile.certificateFingerprint).forEach {
                        Text(it, style = SfText.mono, color = c.muted)
                    }
                    Eyebrow("Certificate shown now")
                    RouterDisplay.fingerprintLines(state.presentedFingerprint).forEach {
                        Text(it, style = SfText.mono, color = c.bad)
                    }
                }
                PrimaryButton(text = "Back to safety", onClick = onCancel)
            }
            ReconnectState.TimeoutUnreachable -> {
                PrimaryButton(
                    text = "Try again",
                    onClick = {
                        reconnectState = ReconnectState.Probing(ReconnectProber.DEFAULT_DURATION_SECONDS, 0)
                        attempt++
                    }
                )
                GhostButton(text = "Back to routers", onClick = onCancel)
            }
        }
    }
}

/** The mock's `.ring`: seconds left in the middle, an accent arc that shrinks as time runs out. */
@Composable
private fun CountdownRing(remainingSeconds: Int, fraction: Float) {
    val c = SfTheme.colors
    val animated by animateFloatAsState(fraction, label = "ring")
    Box(modifier = Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            drawArc(
                color = c.line,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke)
            )
            drawArc(
                color = c.accent,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$remainingSeconds", style = SfText.big, color = c.ink)
            Text("seconds", style = SfText.sub, color = c.muted)
        }
    }
}
