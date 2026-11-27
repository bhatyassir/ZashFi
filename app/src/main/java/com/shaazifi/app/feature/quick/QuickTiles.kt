package com.shaazifi.app.feature.quick

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.shaazifi.app.MainActivity
import com.shaazifi.app.ShaaziFiApp
import com.shaazifi.app.feature.widget.WidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class TileFeedback(val active: Boolean, val subtitle: String)

internal fun tileFeedback(action: QuickAction, defaultHint: String, snapshot: WidgetSnapshot?): TileFeedback =
    when (action) {
        QuickAction.ADGUARD_PAUSE -> when (snapshot?.adguardOn) {
            true -> TileFeedback(active = true, subtitle = "Protection on")
            false -> TileFeedback(active = false, subtitle = "Protection off")
            null -> TileFeedback(active = false, subtitle = defaultHint)
        }
        QuickAction.GUEST_WIFI -> {
            val router = snapshot?.routerName?.takeIf { it.isNotBlank() }
            TileFeedback(
                active = false,
                subtitle = if (router != null) "$router · $defaultHint" else defaultHint
            )
        }
        QuickAction.LED_NIGHT -> TileFeedback(active = false, subtitle = "Turn LEDs off")
    }

/**
 * A Quick Settings tile that opens ShaaziFi at one screen (developer.android.com/reference/android/
 * service/quicksettings/TileService, read 2026-11-17). It cannot sign in by itself, and saved
 * passwords only open after fingerprint or PIN; status feedback comes solely from the cached
 * secret-free [WidgetSnapshot]. When the phone is locked it asks to unlock first (unlockAndRun).
 * On Android 14+ it must use startActivityAndCollapse (PendingIntent): the Intent version throws
 * UnsupportedOperationException there.
 */
abstract class ShortcutTileService(
    private val action: QuickAction,
    private val hint: String
) : TileService() {

    private var listenScope: CoroutineScope? = null

    override fun onStartListening() {
        applyFeedback(tileFeedback(action, hint, null))
        listenScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        listenScope = scope
        scope.launch {
            val store = (applicationContext as? ShaaziFiApp)?.container?.widgetStore ?: return@launch
            val snapshot = withContext(Dispatchers.IO) { runCatching { store.read() }.getOrNull() }
            applyFeedback(tileFeedback(action, hint, snapshot))
        }
    }

    override fun onStopListening() {
        listenScope?.cancel()
        listenScope = null
    }

    override fun onDestroy() {
        listenScope?.cancel()
        listenScope = null
        super.onDestroy()
    }

    private fun applyFeedback(feedback: TileFeedback) {
        val tile = qsTile ?: return
        tile.state = if (feedback.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = feedback.subtitle
        tile.updateTile()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { open() } else open()
    }

    private fun open() {
        val intent = launchIntent(this, action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, action.requestCode, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun launchIntent(context: Context, action: QuickAction): Intent =
            Intent(context, MainActivity::class.java).apply {
                this.action = action.intentAction()
                QuickAction.extrasFor(action).forEach { (k, v) -> putExtra(k, v) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }
}

/** "Pause AdGuard": opens AdGuard Home and asks to pause blocking for 10 minutes. */
class AdGuardPauseTileService : ShortcutTileService(QuickAction.ADGUARD_PAUSE, "10 minutes")

/** "Guest Wi-Fi": opens the guest network screen, where its switch is. */
class GuestWifiTileService : ShortcutTileService(QuickAction.GUEST_WIFI, "Open to switch")

/** "Night LEDs": opens the LED schedule, where the one-tap action lives. */
class NightLedTileService : ShortcutTileService(QuickAction.LED_NIGHT, "Turn LEDs off")
