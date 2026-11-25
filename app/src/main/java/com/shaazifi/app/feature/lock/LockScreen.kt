package com.shaazifi.app.feature.lock

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.shaazifi.app.R
import com.shaazifi.app.core.session.AppLock
import com.shaazifi.app.core.session.BiometricGate
import com.shaazifi.app.core.session.UnlockResult
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.launch

/** Covers the whole app while it is locked. Opens with fingerprint, face or the phone's PIN. */
@Composable
fun LockScreen(lock: AppLock, modifier: Modifier = Modifier) {
    val c = SfTheme.colors
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val unlockTitle = stringResource(R.string.app_unlock, appName)
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun unlock() {
        val activity = context as? FragmentActivity ?: return
        if (busy) return
        busy = true
        scope.launch {
            lock.authenticationStarted()
            val r = try {
                BiometricGate.unlock(activity, unlockTitle, "Use your fingerprint, face or PIN")
            } finally {
                lock.authenticationEnded()
                busy = false
            }
            when (r) {
                UnlockResult.Success -> lock.unlocked()
                UnlockResult.Cancelled -> message = null
                is UnlockResult.Failed -> message = r.message
            }
        }
    }

    // Back leaves the app instead of reaching the screens underneath.
    BackHandler { (context as? Activity)?.moveTaskToBack(true) }
    LaunchedEffect(Unit) { unlock() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            // Swallow every tap so nothing underneath can be pressed.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 12.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.app_locked, appName), style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text("Unlock with your fingerprint, face or the phone's PIN.", style = SfText.sub, color = c.muted)
                if (message != null) Text(message ?: "", style = SfText.sub, color = c.bad)
            }
            PrimaryButton(text = if (busy) "Waiting..." else "Unlock", enabled = !busy, onClick = { unlock() })
        }
    }
}

/** True when the phone can show the unlock prompt (needs a screen lock). */
fun canUseAppLock(context: android.content.Context) = BiometricGate.canUnlock(context)
