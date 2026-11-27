package com.shaazifi.app.feature.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shaazifi.app.core.session.AppLock
import com.shaazifi.app.core.session.BiometricGate
import com.shaazifi.app.core.session.LockSettingsStore
import com.shaazifi.app.core.session.LockTimeout
import com.shaazifi.app.core.session.SecretKind
import com.shaazifi.app.core.session.SecretStore
import com.shaazifi.app.core.session.UnlockResult
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.components.SwitchRow
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.launch

/** Settings: app lock on/off, lock timing, and this router's saved passwords (M9). */
@Composable
fun LockSettingsSection(
    lock: AppLock,
    settings: LockSettingsStore,
    secrets: SecretStore,
    profileId: String
) {
    val c = SfTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val canUnlock = remember { BiometricGate.canUnlock(context) }
    var enabled by remember { mutableStateOf(lock.enabled) }
    var timeout by remember { mutableStateOf(lock.timeout) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var savedRouter by remember { mutableStateOf(false) }
    var savedAdGuard by remember { mutableStateOf(false) }

    LaunchedEffect(profileId) {
        savedRouter = runCatching { secrets.has(profileId, SecretKind.ROUTER) }.getOrDefault(false)
        savedAdGuard = runCatching { secrets.has(profileId, SecretKind.ADGUARD) }.getOrDefault(false)
    }

    fun apply(newEnabled: Boolean, newTimeout: LockTimeout) {
        if (busy || (newEnabled == lock.enabled && newTimeout == lock.timeout)) return
        busy = true
        scope.launch {
            try {
                if (needsLockVerification(lock.enabled, lock.timeout, newEnabled, newTimeout)) {
                    // Prove the owner can unlock before enabling, disabling, or lengthening the lock timeout.
                    val activity = BiometricGate.findActivity(context)
                    if (activity == null) {
                        message = "Fingerprint or PIN verification is unavailable here."
                        return@launch
                    }
                    val title = when {
                        newEnabled && !lock.enabled -> "Turn on the app lock"
                        !newEnabled && lock.enabled -> "Turn off the app lock"
                        else -> "Change lock timing"
                    }
                    lock.authenticationStarted()
                    val r = try {
                        BiometricGate.unlock(activity, title, "Confirm with your fingerprint, face or PIN")
                    } finally {
                        lock.authenticationEnded()
                    }
                    if (r != UnlockResult.Success) {
                        message = if (r is UnlockResult.Failed) r.message else null
                        return@launch
                    }
                }
                if (newEnabled && !lock.enabled) {
                    lock.configure(true, newTimeout)
                    lock.unlocked()
                } else {
                    lock.configure(newEnabled, newTimeout)
                }
                runCatching { settings.save(newEnabled, newTimeout) }
                    .onSuccess { message = null }
                    .onFailure { message = "Could not save the lock setting (${it::class.simpleName})." }
                enabled = newEnabled
                timeout = newTimeout
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SfList {
            SwitchRow(
                title = "App lock",
                subtitle = if (canUnlock) "Ask for fingerprint, face or PIN to open the app" else "Set a screen lock (PIN, pattern or password) on the phone first",
                checked = enabled,
                enabled = canUnlock || enabled,
                onToggle = { apply(it, timeout) }
            )
            if (enabled) {
                LockTimeout.entries.forEach { t ->
                    SfDivider()
                    SfListRow(
                        title = t.label,
                        subtitle = if (t == LockTimeout.DEFAULT) "Default" else null,
                        onClick = { apply(true, t) },
                        trailing = { if (t == timeout) Text("Selected", style = SfText.sub, color = c.accent) }
                    )
                }
            }
        }
        if (message != null) Text(message ?: "", style = SfText.sub, color = c.bad)

        SfCard(verticalSpacing = 10.dp) {
            Text("Saved passwords", style = SfText.sub, color = c.ink)
            Text(
                when {
                    savedRouter && savedAdGuard -> "Router and AdGuard passwords are saved on this phone, encrypted."
                    savedRouter -> "The router password is saved on this phone, encrypted."
                    savedAdGuard -> "The AdGuard password is saved on this phone, encrypted."
                    else -> "No passwords saved for this router. Tick \"Remember\" when signing in to save one."
                },
                style = SfText.sub,
                color = c.muted
            )
            if (savedRouter || savedAdGuard) {
                GhostButton(text = "Delete saved passwords", onClick = {
                    scope.launch {
                        runCatching { secrets.forget(profileId) }
                        savedRouter = false
                        savedAdGuard = false
                    }
                })
            }
        }
        Note(
            "Saved passwords are encrypted with a key kept in the phone's secure hardware. It only opens after your fingerprint, face or PIN, " +
                "and it is never copied to backups or a new phone. If you remove the phone's screen lock, saved passwords stop working and must be typed again."
        )
    }
}

/** True when changing lock settings weakens protection or turns the lock on and therefore requires re-authentication. */
internal fun needsLockVerification(
    currentEnabled: Boolean,
    currentTimeout: LockTimeout,
    newEnabled: Boolean,
    newTimeout: LockTimeout
): Boolean =
    (newEnabled && !currentEnabled) ||
        (!newEnabled && currentEnabled) ||
        (newEnabled && currentEnabled && newTimeout.millis > currentTimeout.millis)
