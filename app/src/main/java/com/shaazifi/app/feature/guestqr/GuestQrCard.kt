package com.shaazifi.app.feature.guestqr

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import com.shaazifi.app.feature.router.ChangeController
import com.shaazifi.app.feature.router.ChoiceChips
import com.shaazifi.app.feature.router.FeatureLoadException

import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Note
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
fun GuestQrCard(
    load: suspend () -> List<GuestCredential>,
    onSessionExpired: (() -> Unit)? = null
) {
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
                    } catch (e: FeatureLoadException.SessionExpired) {
                        message = "Your router session has expired. Sign in again to read the guest network."
                        onSessionExpired?.invoke()
                    } catch (e: FeatureLoadException.NotPermitted) {
                        message = "This router login does not have permission to read the guest Wi-Fi password."
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(g.ssid, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
            Pill(if (g.enabled) "Enabled" else "Disabled", tone = if (g.enabled) PillTone.Ok else PillTone.Off)
        }
        if (!g.enabled) {
            Note("This guest network is currently turned off. Turn it on before visitors scan this code.")
        }
        if (g.qrType == null) {
            Text(
                "This guest network uses a security type that a QR code cannot share (enterprise or enhanced open). Visitors join it by name.",
                style = SfText.sub,
                color = c.muted
            )
        } else if (g.qrType != "nopass" && g.key.isNullOrEmpty()) {
            Note("The guest Wi-Fi password was not returned by the router, so a QR code cannot be generated.")
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

/**
 * Time-limited guest access. A pass is kept only in memory while this card is on screen;
 * when it expires, the owner can turn the guest network off with one tap through the normal
 * confirmation. The router has no built-in guest timer, so a pass cannot survive app restarts.
 */
@Composable
fun GuestExpiryCard(load: suspend () -> List<GuestCredential>, changes: ChangeController?) {
    val c = SfTheme.colors
    val scope = rememberCoroutineScope()
    var duration by remember { mutableStateOf("2h") }
    var pass by remember { mutableStateOf<com.shaazifi.app.feature.guestqr.GuestPass?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 10.dp) {
        Text("Time-limited guest access", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text("Set a pass; when it ends, turn the guest network off in one tap.", style = SfText.sub, color = c.muted)
        ChoiceChips(listOf("30m", "1h", "2h", "6h", "12h", "1d", "7d"), false, duration) { duration = it }
        PrimaryButton("Start ${duration} guest pass", onClick = {
            val ms = com.shaazifi.app.feature.guestqr.parseDuration(duration)
            if (ms == null) {
                message = "Choose a valid duration."
            } else {
                scope.launch {
                    val guests = runCatching { load() }.getOrNull().orEmpty()
                    val guest = guests.firstOrNull()
                    if (guest == null) {
                        message = "No guest network was found on this router."
                    } else {
                        val section = "guest_radio0"
                        val start = System.currentTimeMillis()
                        pass = com.shaazifi.app.feature.guestqr.GuestPass(section, guest.ssid, start + ms, start)
                        message = com.shaazifi.app.feature.guestqr.summarise(pass, start)
                        now = start
                    }
                }
            }
        })
        pass?.let { p ->
            now = System.currentTimeMillis()
            Text(com.shaazifi.app.feature.guestqr.summarise(p, now), style = SfText.sub, color = if (p.isExpired(now)) c.bad else c.muted)
            if (p.isExpired(now) && changes != null) {
                PrimaryButton("Turn the guest network off", onClick = {
                    val result = com.shaazifi.app.feature.guestqr.buildDisablePlan(p.sectionId, p.ssid)
                    if (result is com.shaazifi.app.feature.router.PlanResult.Ready) changes.request(result.plan)
                })
            }
            GhostButton("Forget this pass", onClick = { pass = null; message = null })
        }
        message?.let { Text(it, style = SfText.sub, color = c.bad) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Blocks screenshots and the recent-apps preview while composed. */
@Composable
private fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val hadSecure = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!hadSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
