package com.shaazifi.app.feature.quick

import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.feature.demo.DemoMode
import com.shaazifi.app.feature.web.MainTab
import com.shaazifi.app.feature.web.MorePage
import com.shaazifi.app.feature.web.ShellNavState

/**
 * M9 item 3, blueprint v2 row "Quick-settings tiles: Pause AdGuard 10 min; toggle guest Wi-Fi".
 * A tile is only a shortcut: it opens the app at the right screen. Nothing changes until the owner
 * signs in (fingerprint with a saved password) and confirms the change in the app's usual dialog.
 * The launch carries only the shortcut's name, never a password, token or router address.
 */
enum class QuickAction(val key: String) {
    ADGUARD_PAUSE("adguard_pause"),
    GUEST_WIFI("guest_wifi"),
    LED_NIGHT("led_night");

    /** Distinct action string so PendingIntents never collide with widget or alert intents. */
    fun intentAction(): String = "com.shaazifi.app.ACTION_QUICK_$key"

    /** Unique PendingIntent requestCode per shortcut. */
    val requestCode: Int get() = 100 + ordinal

    /** Where the signed-in app opens for this shortcut. */
    fun startNav(): ShellNavState = when (this) {
        ADGUARD_PAUSE -> ShellNavState().select(MainTab.AdGuard)
        GUEST_WIFI -> ShellNavState().openWifiFeature("guest")
        LED_NIGHT -> ShellNavState().openMore(com.shaazifi.app.feature.web.MorePage.Feature("configtasks"))
    }

    companion object {
        const val EXTRA = "com.shaazifi.app.QUICK_ACTION"
        const val PAUSE_MS = 10 * 60_000L

        fun parse(value: String?): QuickAction? = entries.firstOrNull { it.key == value }

        /** Everything the launch carries. */
        fun extrasFor(action: QuickAction): Map<String, String> = mapOf(EXTRA to action.key)

        /** The router a shortcut uses: the only saved real router. With several, the owner picks. */
        fun routerFor(profiles: List<RouterProfile>): RouterProfile? =
            profiles.filterNot { DemoMode.isDemo(it) }.singleOrNull()
    }
}
