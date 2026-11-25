package com.shaazifi.app.feature.quick

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.shaazifi.app.MainActivity

/**
 * A Quick Settings tile that opens ShaaziFi at one screen (developer.android.com/reference/android/
 * service/quicksettings/TileService, read 2026-11-17). It shows no router state: it cannot sign in
 * by itself, and saved passwords only open after fingerprint or PIN. When the phone is locked it
 * asks to unlock first (unlockAndRun). On Android 14+ it must use startActivityAndCollapse
 * (PendingIntent): the Intent version throws UnsupportedOperationException there.
 */
abstract class ShortcutTileService(
    private val action: QuickAction,
    private val hint: String
) : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = hint
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
                    this, action.ordinal, intent,
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
                QuickAction.extrasFor(action).forEach { (k, v) -> putExtra(k, v) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }
}

/** "Pause AdGuard": opens AdGuard Home and asks to pause blocking for 10 minutes. */
class AdGuardPauseTileService : ShortcutTileService(QuickAction.ADGUARD_PAUSE, "10 minutes")

/** "Guest Wi-Fi": opens the guest network screen, where its switch is. */
class GuestWifiTileService : ShortcutTileService(QuickAction.GUEST_WIFI, "Open to switch")
