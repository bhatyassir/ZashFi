package com.shaazifi.app.feature.guestqr

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.shaazifi.app.feature.router.FeatureLoadException
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.launch

/**
 * "Share with a QR code" on the guest network screen. The guest password is read only after the
 * owner taps, kept only in this card's memory, and dropped when the card is hidden, the screen is
 * left or the app goes to the background. While shown, screenshots and the recent-apps preview are
 * blocked (FLAG_SECURE). A Kotlin String cannot be wiped from memory; it is simply not kept.
 */
@Composable
fun GuestQrCard(load: suspend () -> List<GuestCredential>) {
    val c = SfTheme.colors
    val scope = rememberCoroutineScope()
    var creds by remember { mutableStateOf<List<GuestCredential>?>(null) }
    var index by remember { mutableStateOf(0) }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun hide() { creds = null; showPassword = false; index = 0 }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { hide() }

    val shown = creds
    if (shown != null) SecureWindow()

    SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Share with a QR code", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
            if (shown != null) Pill("Showing", tone = PillTone.Warn)
        }
        if (shown == null) {
            Text(
                "Visitors point their phone camera at the code to join. The guest password is read from the router only when you tap.",
                style = SfText.sub,
                color = c.muted
            )
            message?.let { Text(it, style = SfText.sub, color = c.bad) }
            PrimaryButton(if (busy) "Reading..." else "Show QR code", enabled = !busy, onClick = {
                busy = true
                message = null
                scope.launch {
                    try {
                        val list = load()
                        if (list.isEmpty()) message = "No guest network on this router yet." else creds = list
                    } catch (e: FeatureLoadException) {
                        message = "Could not read the guest network. Check the connection and try again."
                    } catch (e: Exception) {
                        message = "Could not read the guest network (${e::class.simpleName})."
                    } finally {
                        busy = false
                    }
                }
            })
            return@SfCard
        }

        if (shown.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEachIndexed { i, g ->
                    Pill(g.band, tone = if (i == index) PillTone.Ok else PillTone.Off, onClick = { index = i; showPassword = false })
                }
            }
        }
        val g = shown[index.coerceIn(0, shown.lastIndex)]
        Text(g.ssid, style = MaterialTheme.typography.titleMedium, color = c.ink)
        if (g.qrType == null) {
            Text(
                "This guest network uses a security type that a QR code cannot share (enterprise or enhanced open). Visitors join it by name.",
                style = SfText.sub,
                color = c.muted
            )
        } else {
            val matrix = remember(g) { GuestQr.matrix(GuestQr.payload(g)) }
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Always black on white so cameras read it in dark mode too.
                Canvas(
                    Modifier
                        .widthIn(max = 280.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                ) {
                    val cell = size.width / matrix.width
                    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                        if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
            if (g.qrType == "nopass") {
                Text("Open network: no password.", style = SfText.sub, color = c.muted)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Password", style = SfText.sub, color = c.muted)
                        Text(if (showPassword) g.key.orEmpty() else "••••••••", style = SfText.mono, color = c.ink)
                    }
                    TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                }
            }
        }
        Text("Screenshots are blocked while the code is shown.", style = SfText.sub, color = c.muted)
        GhostButton("Hide the code", onClick = { hide() })
    }
}

/** Blocks screenshots and the recent-apps preview while composed. */
@Composable
private fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
