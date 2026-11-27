package com.shaazifi.app.feature.alerts

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SwitchRow
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.launch

private val TEMP_THRESHOLDS_C = listOf(70.0, 75.0, 80.0, 85.0, 90.0)

/**
 * Settings > Alerts (M10c). Off by default. Turning it on: notification permission (Android 13+),
 * then the router password for background checks (from the saved password with fingerprint, or
 * typed), kept as a second encrypted copy that opens without fingerprint (owner's choice).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertsSection(container: AppContainer, profile: RouterProfile, savedPassword: SavedPassword) {
    val c = SfTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allowBackground = remember(profile.address, profile.certificateFingerprint) {
        AlertSecurity.canPollInBackground(profile)
    }
    var enabledHere by remember { mutableStateOf(false) }
    var otherRouter by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var hasSaved by remember { mutableStateOf(false) }
    var highTempEnabled by remember { mutableStateOf(false) }
    var tempThresholdC by remember { mutableDoubleStateOf(AlertRules.DEFAULT_TEMP_THRESHOLD_C) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profile.id) {
        val id = runCatching { container.alertSettings.profileId() }.getOrNull()
        enabledHere = id == profile.id
        otherRouter = id != null && id != profile.id
        hasSaved = runCatching { savedPassword.has() }.getOrDefault(false) && savedPassword.usable
        highTempEnabled = runCatching { container.alertSettings.highTempEnabled() }.getOrDefault(false)
        tempThresholdC = runCatching { container.alertSettings.tempThresholdC() }.getOrDefault(AlertRules.DEFAULT_TEMP_THRESHOLD_C)
    }

    suspend fun finishEnable(password: String) {
        if (!allowBackground) {
            message = "Alerts cannot be enabled for plain http:// routers without a pinned HTTPS certificate."
            return
        }
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
        if (granted && allowBackground) askPassword = true else if (!granted) message = "Alerts need permission to show notifications."
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
        if (!allowBackground) {
            Note(
                "Background alerts are disabled for plain http:// routers without a pinned HTTPS certificate, " +
                    "because background checks could send your password unencrypted on any Wi-Fi network. " +
                    "Switch this router to HTTPS with a pinned certificate to use alerts."
            )
        }
        SfList {
            SwitchRow(
                title = "Alerts",
                subtitle = when {
                    !allowBackground && enabledHere -> "Blocked over plain HTTP — turn off or switch to HTTPS"
                    !allowBackground -> "Unavailable over plain http:// without a pinned certificate"
                    enabledHere -> "On: new devices, internet outages" + if (highTempEnabled) ", high temp" else ""
                    otherRouter -> "On for another router; turning on here moves them"
                    else -> "Off"
                },
                checked = enabledHere,
                enabled = enabledHere || allowBackground,
                onToggle = { on ->
                    message = null
                    if (!on) {
                        disable()
                    } else if (!allowBackground) {
                        message = "Switch this router to HTTPS with a pinned certificate first."
                    } else if (!AlertNotifier.canNotify(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        askPassword = true
                    }
                }
            )
            if (enabledHere && allowBackground) {
                SfDivider()
                SwitchRow(
                    title = "High temperature alert",
                    subtitle = "Warn when a router sensor reaches ${tempThresholdC.toInt()}°C",
                    checked = highTempEnabled,
                    onToggle = { on ->
                        highTempEnabled = on
                        scope.launch { runCatching { container.alertSettings.setHighTempEnabled(on) } }
                    }
                )
            }
        }
        if (enabledHere && allowBackground && highTempEnabled) {
            SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 6.dp) {
                Text("Temperature threshold", style = MaterialTheme.typography.titleSmall, color = c.ink)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TEMP_THRESHOLDS_C.forEach { limit ->
                        FilterChip(
                            selected = tempThresholdC.toInt() == limit.toInt(),
                            onClick = {
                                tempThresholdC = limit
                                scope.launch { runCatching { container.alertSettings.setTempThresholdC(limit) } }
                            },
                            label = { Text("${limit.toInt()}°C") }
                        )
                    }
                }
            }
        }
        if (askPassword && !enabledHere && allowBackground) {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            password()
                            this[SemanticsProperties.EditableText] = AnnotatedString("•".repeat(typed.length))
                        }
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
