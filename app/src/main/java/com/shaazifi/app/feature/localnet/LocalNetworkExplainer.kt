package com.shaazifi.app.feature.localnet

import androidx.compose.ui.res.stringResource
import com.shaazifi.app.R

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.shaazifi.app.feature.localnet.LocalNetworkAccess.Decision
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.launch

/**
 * Wraps the app: on Android 17 with targetSdk 37+, explains why ShaaziFi needs "local network"
 * access before Android asks, and shows [content] once allowed (or skipped). At today's targetSdk
 * (36) the decision is always NotNeeded and [content] is shown directly; nothing is requested.
 */
@Composable
fun LocalNetworkGate(prefs: LocalNetworkPrefs, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val applies = remember { LocalNetworkAccess.applies(context) }
    if (!applies) {
        content()
        return
    }
    val scope = rememberCoroutineScope()
    var decision by remember { mutableStateOf<Decision?>(null) }
    var skipped by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val activity = context as? Activity
        decision = LocalNetworkAccess.decide(
            applies = true,
            granted = LocalNetworkAccess.granted(context),
            askedBefore = runCatching { prefs.askedBefore() }.getOrDefault(false),
            showRationale = activity?.shouldShowRequestPermissionRationale(LocalNetworkAccess.PERMISSION) == true
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { refresh() }
    }
    LaunchedEffect(Unit) { refresh() }
    // Coming back from the phone's settings: check again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { refresh() } }

    when (val d = decision) {
        null -> Box(Modifier.fillMaxSize().background(SfTheme.colors.bg))
        Decision.NotNeeded, Decision.Granted -> content()
        Decision.Explain, Decision.OpenSettings -> if (skipped) content() else Explainer(
            openSettings = d == Decision.OpenSettings,
            onAllow = {
                if (d == Decision.OpenSettings) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    )
                } else {
                    scope.launch {
                        runCatching { prefs.markAsked() }
                        launcher.launch(LocalNetworkAccess.PERMISSION)
                    }
                }
            },
            onSkip = { skipped = true }
        )
    }
}

@Composable
private fun Explainer(openSettings: Boolean, onAllow: () -> Unit, onSkip: () -> Unit) {
    val c = SfTheme.colors
    Box(Modifier.fillMaxSize().background(c.bg).padding(16.dp), contentAlignment = Alignment.Center) {
        SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 12.dp) {
            Text("Allow access to your home network", style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(
                "${stringResource(R.string.app_name)} talks to your router over your home Wi-Fi. Android now asks before an app may reach " +
                    "devices on your home network. Without it, the app can only reach your router over your VPN.",
                style = SfText.sub,
                color = c.muted
            )
            Text(
                "The app only connects to your own router and AdGuard Home. It does not scan or collect your other devices.",
                style = SfText.sub,
                color = c.muted
            )
            if (openSettings) {
                Note("You said no earlier. Open the app's settings, then Permissions > Nearby devices > Allow.")
            }
            PrimaryButton(if (openSettings) "Open settings" else "Continue", onClick = onAllow)
            GhostButton("Not now", onClick = onSkip)
        }
    }
}
