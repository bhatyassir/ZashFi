package com.shaazifi.app.feature.alerts

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.shaazifi.app.AppContainer
import com.shaazifi.app.core.session.SavedPassword
import com.shaazifi.app.core.session.SavedPasswordResult
import com.shaazifi.app.core.session.SecretKind
import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SwitchRow
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.launch

/**
 * Settings > Alerts (M10c). Off by default. Turning it on: notification permission (Android 13+),
 * then the router password for background checks (from the saved password with fingerprint, or
 * typed), kept as a second encrypted copy that opens without fingerprint (owner's choice).
 */
@Composable
fun AlertsSection(container: AppContainer, profile: RouterProfile, savedPassword: SavedPassword) {
    val c = SfTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabledHere by remember { mutableStateOf(false) }
    var otherRouter by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var hasSaved by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profile.id) {
        val id = runCatching { container.alertSettings.profileId() }.getOrNull()
        enabledHere = id == profile.id
        otherRouter = id != null && id != profile.id
        hasSaved = runCatching { savedPassword.has() }.getOrDefault(false) && savedPassword.usable
    }

    suspend fun finishEnable(password: String) {
        try {
            container.backgroundSecrets.save(profile.id, SecretKind.ROUTER_BACKGROUND, password)
            container.alertSettings.enable(profile.id)
            AlertNotifier.ensureChannel(context)
            AlertScheduler.start(context)
            enabledHere = true
            otherRouter = false
            askPassword = false
            message = "Alerts are on. The first check only learns what is on your network."
        } catch (e: Exception) {
            message = "Could not turn alerts on (${e::class.simpleName})."
        }
    }

    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askPassword = true else message = "Alerts need permission to show notifications."
    }

    fun disable() {
        scope.launch {
            AlertScheduler.stop(context)
            runCatching { container.alertSettings.disable() }
            runCatching { container.backgroundSecrets.delete(profile.id, SecretKind.ROUTER_BACKGROUND) }
            enabledHere = false
            message = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SfList {
            SwitchRow(
                title = "Alerts",
                subtitle = when {
                    enabledHere -> "On: new devices and internet outages"
                    otherRouter -> "On for another router; turning on here moves them"
                    else -> "Off"
                },
                checked = enabledHere,
                onToggle = { on ->
                    message = null
                    if (!on) {
                        disable()
                    } else if (!AlertNotifier.canNotify(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        askPassword = true
                    }
                }
            )
        }
        if (askPassword && !enabledHere) {
            SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 10.dp) {
                Text("Router password for alerts", style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(
                    "Checks run without you, so they need the password without your fingerprint. The app keeps a second copy, " +
                        "encrypted with a key in the phone's secure hardware. Turning alerts off deletes it.",
                    style = SfText.sub,
                    color = c.muted
                )
                if (hasSaved) {
                    PrimaryButton("Use the saved password", onClick = {
                        scope.launch {
                            when (val r = savedPassword.load()) {
                                is SavedPasswordResult.Value -> finishEnable(r.secret)
                                is SavedPasswordResult.Message -> message = r.text
                                SavedPasswordResult.Cancelled -> Unit
                            }
                        }
                    })
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Router password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                GhostButton("Turn alerts on", enabled = typed.isNotEmpty(), onClick = {
                    val pw = typed
                    typed = ""
                    scope.launch { finishEnable(pw) }
                })
                GhostButton("Cancel", onClick = { askPassword = false; typed = "" })
            }
        }
        message?.let { Text(it, style = SfText.sub, color = c.muted) }
        Note(
            "Checked about every 15 minutes, only while this phone is on the router's Wi-Fi, using the router's home address. " +
                "Android may delay checks to save battery. A wrong password is not reported; the check just skips."
        )
    }
}
