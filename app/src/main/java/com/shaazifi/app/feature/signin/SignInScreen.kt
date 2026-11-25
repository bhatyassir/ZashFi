package com.shaazifi.app.feature.signin

import androidx.compose.foundation.layout.Spacer
import com.shaazifi.app.feature.quick.QuickAction
import androidx.activity.compose.BackHandler
import com.shaazifi.app.feature.routers.RouterDisplay
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shaazifi.app.AppContainer
import com.shaazifi.app.debug.DebugRouterConfig
import com.shaazifi.app.core.session.ResumeResult
import com.shaazifi.app.core.session.SessionState
import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.feature.web.AuthenticatedRouterShell
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.LaunchedEffect
import androidx.fragment.app.FragmentActivity
import com.shaazifi.app.core.session.BiometricGate
import com.shaazifi.app.core.session.SavedPassword
import com.shaazifi.app.core.session.SavedPasswordResult
import com.shaazifi.app.core.session.SecretKind

@Composable
fun SignInScreen(
    container: AppContainer,
    profile: RouterProfile,
    /** M9: opened from a Quick Settings tile. */
    quickAction: QuickAction? = null,
    onQuickActionUsed: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        if (!DebugRouterConfig.IS_DEBUG) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (!DebugRouterConfig.IS_DEBUG) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val sessionState by container.sessionManager.sessionState.collectAsState()

    var username by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // M9: saved password behind fingerprint / PIN. Hidden when the phone has no screen lock.
    val saved = remember(profile.id) {
        SavedPassword(
            store = container.secretStore,
            lock = container.appLock,
            activity = context as? FragmentActivity,
            profileId = profile.id,
            kind = SecretKind.ROUTER,
            usable = BiometricGate.canUnlock(context)
        )
    }
    var hasSaved by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(false) }
    LaunchedEffect(profile.id) { hasSaved = runCatching { saved.has() }.getOrDefault(false) }

    val isLoggedIn = sessionState is SessionState.LoggedIn && (sessionState as SessionState.LoggedIn).profile.id == profile.id
    val isLoggingIn = sessionState is SessionState.LoggingIn

    var restoreSaved by remember(profile.id) { mutableStateOf(true) }
    var resumeStatus by remember(profile.id) { mutableStateOf<ResumeResult?>(null) }
    var resumeAttempt by remember(profile.id) { mutableStateOf(0) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(profile.id, restoreSaved, resumeAttempt, started) {
        if (!started || !restoreSaved || isLoggedIn) return@LaunchedEffect
        val kept = runCatching { container.savedSessions.last() }.getOrNull()?.takeIf { it.profileId == profile.id }
        if (kept != null) {
            while (kept.isFresh(System.currentTimeMillis())) {
                when (val result = container.sessionManager.resume(profile, kept)) {
                    ResumeResult.Restored -> return@LaunchedEffect
                    ResumeResult.Rejected -> break
                    ResumeResult.Unavailable -> {
                        resumeStatus = result
                        return@LaunchedEffect
                    }
                    ResumeResult.Unreachable -> {
                        resumeStatus = result
                        delay(5_000)
                    }
                }
            }
            runCatching { container.savedSessions.clear(kept) }
        }
        currentCoroutineContext().ensureActive()
        restoreSaved = false
    }

    if (isLoggedIn) {
        val session = sessionState as SessionState.LoggedIn
        AuthenticatedRouterShell(
            container = container,
            profile = profile,
            session = session,
            quickAction = quickAction,
            onQuickActionUsed = onQuickActionUsed,
            onSignOut = onBack,
            modifier = modifier
        )
        return
    }

    fun fingerprintSignIn() {
        errorMessage = null
        coroutineScope.launch {
            when (val r = saved.load()) {
                is SavedPasswordResult.Value -> try {
                    container.sessionManager.login(profile, r.secret)
                } catch (e: Exception) {
                    errorMessage = RouterDisplay.signInError(e)
                }
                SavedPasswordResult.Cancelled -> Unit
                is SavedPasswordResult.Message -> {
                    errorMessage = r.text
                    hasSaved = runCatching { saved.has() }.getOrDefault(false)
                }
            }
        }
    }

    // Opened from a tile with a saved password: ask for the fingerprint straight away.
    // Owner feedback: fingerprint is the default once a password is saved (asked once per visit).
    var autoPrompted by remember(profile.id) { mutableStateOf(false) }
    var showTyped by remember(profile.id) { mutableStateOf(false) }
    LaunchedEffect(quickAction, hasSaved, restoreSaved) {
        if (!restoreSaved && hasSaved && saved.usable && !isLoggingIn && !autoPrompted) {
            autoPrompted = true
            fingerprintSignIn()
        }
    }

    val c = SfTheme.colors
    val isHttp = RouterDisplay.isPlainHttp(profile)

    BackHandler(enabled = !isLoggingIn) { onBack() }

    val fingerprintFirst = hasSaved && saved.usable && !showTyped
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Owner feedback 2026-11-18: same coloured header as every other screen.
        com.shaazifi.app.ui.components.PageHero(
            com.shaazifi.app.ui.icons.SfIcons.RouterSaved, com.shaazifi.app.ui.theme.SfHue.GREEN,
            "Sign in", profile.name, RouterDisplay.hostLabel(profile.address)
        )
        if (isHttp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("HTTP", tone = PillTone.Warn)
                Text("Not encrypted: the password crosses your home network as plain text.", style = SfText.sub, color = c.muted, modifier = Modifier.weight(1f))
            }
        }

        // Everything you tap sits in the lower part of the screen, within thumb reach.
        Spacer(Modifier.weight(1f))

        if (restoreSaved) {
            Text(
                when (resumeStatus) {
                    ResumeResult.Unreachable -> "The router is unreachable. Retrying your saved sign-in…"
                    ResumeResult.Unavailable -> "The router couldn’t confirm your saved sign-in."
                    else -> "Checking your saved sign-in…"
                },
                style = SfText.sub,
                color = c.ink
            )
            if (resumeStatus != null) {
                PrimaryButton(text = "Try again", onClick = {
                    resumeStatus = null
                    resumeAttempt++
                })
                TextButton(onClick = { restoreSaved = false }) { Text("Other sign-in options") }
            }
            GhostButton(text = "Back to routers", onClick = onBack)
            return@Column
        }
        Column(Modifier.verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (fingerprintFirst) {
            if (errorMessage != null) Text(text = errorMessage ?: "", color = c.bad, style = SfText.sub, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { showTyped = true }) { Text("Type the password instead") }
        } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("User name") },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isLoggingIn
            )
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorMessage = null
                },
                label = { Text("Router password") },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        password()
                        if (!passwordVisible) {
                            this[SemanticsProperties.EditableText] = AnnotatedString("•".repeat(password.length))
                        }
                    },
                singleLine = true,
                enabled = !isLoggingIn,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(if (passwordVisible) "Hide" else "Show")
                    }
                }
            )
            if (saved.usable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = rememberPassword,
                        onCheckedChange = { rememberPassword = it },
                        enabled = !isLoggingIn
                    )
                    Text(
                        "Remember the password on this phone (opens with fingerprint or PIN)",
                        style = SfText.sub,
                        color = c.ink,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (errorMessage != null) {
                Text(text = errorMessage ?: "", color = c.bad, style = SfText.sub, fontWeight = FontWeight.SemiBold)
            }
        }

        PrimaryButton(
            text = if (isLoggingIn) "Signing in..." else "Sign in",
            enabled = !isLoggingIn && password.isNotBlank(),
            onClick = {
                if (password.isBlank()) {
                    errorMessage = "Enter the router password."
                    return@PrimaryButton
                }
                val submittedPassword = password
                password = ""
                errorMessage = null
                coroutineScope.launch {
                    try {
                        container.sessionManager.login(profile, submittedPassword)
                        if (rememberPassword && saved.usable) {
                            val problem = saved.save(submittedPassword)
                            Toast.makeText(
                                context,
                                problem ?: "Password saved. Next time, sign in with your fingerprint.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        errorMessage = RouterDisplay.signInError(e)
                    }
                }
            }
        )
        }
        if (hasSaved && saved.usable) {
            if (!fingerprintFirst) TextButton(onClick = { showTyped = false }) { Text("Use the fingerprint instead") }
            PrimaryButton(
                text = if (isLoggingIn) "Signing in..." else "Sign in with fingerprint",
                enabled = !isLoggingIn,
                onClick = { fingerprintSignIn() }
            )
        }
        GhostButton(text = "Back to routers", onClick = onBack, enabled = !isLoggingIn)
        if (hasSaved) {
            TextButton(onClick = {
                coroutineScope.launch {
                    runCatching { saved.forget() }
                    hasSaved = false
                    showTyped = false
                }
            }) { Text("Forget the saved password") }
        }
        }
    }
}
