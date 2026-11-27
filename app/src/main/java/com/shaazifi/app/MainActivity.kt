package com.shaazifi.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.shaazifi.app.debug.DebugRouterConfig
import com.shaazifi.app.feature.demo.DemoMode
import com.shaazifi.app.feature.localnet.LocalNetworkGate
import com.shaazifi.app.feature.localnet.LocalNetworkPrefs
import com.shaazifi.app.feature.lock.LockScreen
import com.shaazifi.app.feature.quick.QuickAction
import com.shaazifi.app.feature.routers.RoutersScreen
import com.shaazifi.app.feature.signin.SignInScreen
import com.shaazifi.app.feature.web.AuthenticatedRouterShell
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import com.shaazifi.app.ui.theme.ShaaziFiTheme
import com.shaazifi.app.ui.theme.ThemeChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class TestStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    REDIRECT,
    REFUSED_EXPECTED,
    HARD_STOP_EXPECTED,
    FAILED
}

// FragmentActivity: androidx.biometric 1.1.0's BiometricPrompt needs one (M9).
class MainActivity : FragmentActivity() {

    private companion object {
        const val KEY_QUICK_ACTION = "quick_action"
    }

    private val container: AppContainer by lazy {
        (application as ShaaziFiApp).container
    }

    private var lockReady by mutableStateOf(false)
    private var lockLoadError by mutableStateOf<String?>(null)

    /** M9: a Quick Settings tile's shortcut, waiting to be used. Carries no secrets. */
    private var quickAction by mutableStateOf<QuickAction?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        QuickAction.parse(intent.getStringExtra(QuickAction.EXTRA))?.let { quickAction = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        quickAction?.let { outState.putString(KEY_QUICK_ACTION, it.key) }
    }

    /** Locks the app when the screen turns off (only when the owner has turned the lock on). */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) container.appLock.onScreenOff()
        }
    }

    private fun loadLockSettings() {
        lockLoadError = null
        lifecycleScope.launch {
            try {
                val (enabled, timeout) = container.lockSettings.load()
                // Locks at a cold start when the lock is on; a no-op when already set up (screen rotation).
                container.appLock.configure(enabled, timeout)
                lockReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fail closed: never disable App Lock when settings fail to load.
                if (container.appLock.enabled) {
                    container.appLock.configure(true, container.appLock.timeout)
                    lockReady = true
                } else {
                    lockLoadError = e.message ?: "Could not read app lock settings."
                    lockReady = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        quickAction = if (savedInstanceState == null) {
            QuickAction.parse(intent?.getStringExtra(QuickAction.EXTRA))
        } else {
            QuickAction.parse(savedInstanceState.getString(KEY_QUICK_ACTION))
        }
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        loadLockSettings()
        setContent {
            // Phase 5: the owner's theme choice (same as the phone, light or dark).
            val themeChoice by container.themeStore.choice.collectAsState(initial = ThemeChoice.SYSTEM)
            ShaaziFiTheme(darkTheme = themeChoice.isDark(isSystemInDarkTheme())) {
                val locked by container.appLock.locked.collectAsState()
                var showSelfTest by rememberSaveable { mutableStateOf(false) }
                var demoMode by rememberSaveable { mutableStateOf(false) }
                var selectedProfileId by rememberSaveable { mutableStateOf<String?>(null) }
                var autoRestored by rememberSaveable { mutableStateOf(false) }

                if (lockLoadError != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SfTheme.colors.bg)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 12.dp) {
                            Text(
                                "Could not verify app lock",
                                style = MaterialTheme.typography.titleMedium,
                                color = SfTheme.colors.ink
                            )
                            Text(
                                lockLoadError.orEmpty(),
                                style = SfText.sub,
                                color = SfTheme.colors.muted
                            )
                            PrimaryButton(text = "Retry", onClick = { loadLockSettings() })
                        }
                    }
                    return@ShaaziFiTheme
                }

                if (!lockReady) {
                    Box(Modifier.fillMaxSize().background(SfTheme.colors.bg))
                    return@ShaaziFiTheme
                }

                if (locked) {
                    LockScreen(lock = container.appLock)
                    return@ShaaziFiTheme
                }

                // M9: Android 17 local network explainer. Off while targetSdk is 36 (shows the app directly).
                LocalNetworkGate(remember { LocalNetworkPrefs(container.dataStore) }) {
                    val profilesState by container.profileRepository.getProfiles().collectAsState(initial = null)
                    val profiles = profilesState

                    // Reopen the last router once per cold session. Its sign-in screen checks and retries the kept session.
                    LaunchedEffect(Unit) {
                        if (autoRestored) return@LaunchedEffect
                        autoRestored = true
                        val kept = runCatching { container.savedSessions.last() }.getOrNull() ?: return@LaunchedEffect
                        val list = runCatching { container.profileRepository.getProfiles().first() }.getOrNull()
                            ?: return@LaunchedEffect
                        val profile = list.firstOrNull { it.id == kept.profileId }
                        if (profile == null || !kept.isFresh(System.currentTimeMillis())) {
                            runCatching { container.savedSessions.clear(kept) }
                            return@LaunchedEffect
                        }
                        if (selectedProfileId == null && !demoMode && !showSelfTest) selectedProfileId = profile.id
                    }

                    // A tile shortcut: open the only saved router (with several, the owner picks from the list).
                    val pendingQuick = quickAction
                    LaunchedEffect(pendingQuick, profiles, demoMode, showSelfTest) {
                        if (pendingQuick == null) return@LaunchedEffect
                        if (demoMode || showSelfTest) {
                            quickAction = null
                            return@LaunchedEffect
                        }
                        val list = profiles ?: return@LaunchedEffect
                        if (selectedProfileId == null) {
                            if (list.none { !DemoMode.isDemo(it) }) {
                                quickAction = null
                            } else {
                                QuickAction.routerFor(list)?.let { selectedProfileId = it.id }
                            }
                        }
                    }

                    val selectedProfile = profiles?.let { list ->
                        selectedProfileId?.let { id -> list.firstOrNull { it.id == id } }
                    }
                    LaunchedEffect(profiles, selectedProfileId) {
                        if (profiles != null && selectedProfileId != null && selectedProfile == null) {
                            selectedProfileId = null
                        }
                    }

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = SfTheme.colors.bg
                    ) { innerPadding ->
                        if (selectedProfileId != null && profiles == null) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .background(SfTheme.colors.bg)
                            )
                        } else if (demoMode) {
                            AuthenticatedRouterShell(
                                container = container,
                                profile = DemoMode.profile,
                                session = DemoMode.session,
                                onSignOut = {
                                    demoMode = false
                                    quickAction = null
                                },
                                modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)
                            )
                        } else if (selectedProfile != null) {
                            SignInScreen(
                                container = container,
                                profile = selectedProfile,
                                quickAction = quickAction,
                                onQuickActionUsed = { quickAction = null },
                                onBack = {
                                    selectedProfileId = null
                                    quickAction = null
                                },
                                modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)
                            )
                        } else if (DebugRouterConfig.IS_DEBUG && showSelfTest) {
                            Box(Modifier.padding(innerPadding)) {
                                DebugRouterConfig.SelfTestOverlay(container = container) {
                                    showSelfTest = false
                                }
                            }
                        } else {
                            RoutersScreen(
                                container = container,
                                onSelectProfile = { selectedProfileId = it.id },
                                onOpenSelfTest = if (DebugRouterConfig.IS_DEBUG) ({
                                    quickAction = null
                                    showSelfTest = true
                                }) else null,
                                onTryDemo = {
                                    quickAction = null
                                    demoMode = true
                                },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isChangingConfigurations) {
            container.appLock.onAppStarted()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            container.appLock.onAppStopped()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        super.onDestroy()
    }
}
