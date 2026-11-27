package com.shaazifi.app.feature.about

/** Someone whose work ShaaziFi uses or learned from. Shown in the app (About) and in CREDITS.md. */
data class Credit(
    val name: String,
    val author: String,
    val licence: String,
    val url: String,
    val how: String
)

/**
 * Credits. Licences checked 2026-11-17 on each project's repository (GitHub licence field or
 * LICENSE file). Every licence here is compatible with ShaaziFi's GPL-3.0.
 */
object Credits {
    fun entries(appName: String): List<Credit> = listOf(
        Credit("OpenWrt", "The OpenWrt Project", "GPL-2.0-or-later", "https://openwrt.org",
            "The router system $appName talks to (ubus, rpcd, uci, fw4)."),
        Credit("LuCI", "The OpenWrt Project and LuCI contributors", "Apache-2.0", "https://github.com/openwrt/luci",
            "Reference for router calls, page addresses and diagnostics (luci-rpc, access lists, menus)."),
        Credit("UnTangled", "Krish Paul", "GPL-2.0-or-later", "https://github.com/bidhata/untangled",
            "The web interface $appName was first built around; reference for features and router calls."),
        Credit("LuCI Mobile", "cogwheel0 and contributors", "GPL-3.0", "https://github.com/cogwheel0/luci-mobile",
            "Reference for features and router calls (Wi-Fi scan, services, interfaces, capability checks)."),
        Credit("OpenWrtManager", "hagaygo", "GPL-3.0", "https://github.com/hagaygo/OpenWrtManager",
            "Early inspiration for LuCI Mobile, credited by it; acknowledged here too."),
        Credit("AdGuard Home", "AdGuard Software Ltd", "GPL-3.0", "https://github.com/AdguardTeam/AdGuardHome",
            "The ad blocker $appName manages; API definition and server code used as the reference."),
        Credit("AdGuard Home Manager", "JGeek00", "Apache-2.0", "https://github.com/JGeek00/adguard-home-manager",
            "Reference list of AdGuard Home features and API calls."),
        Credit("Wireshark (device maker list)", "The Wireshark developers; data from the IEEE Registration Authority", "GPL-2.0-or-later",
            "https://www.wireshark.org", "Device makers by hardware address (assets/oui/makers.tsv, made by tools/make_makers.py)."),
        Credit("ZXing", "ZXing authors", "Apache-2.0", "https://github.com/zxing/zxing", "Guest Wi-Fi QR codes."),
        Credit("OkHttp", "Square, Inc.", "Apache-2.0", "https://github.com/square/okhttp", "Network connections."),
        Credit("Android Jetpack (AndroidX, Compose, WorkManager, Biometric, DataStore)", "Google and the Android Open Source Project", "Apache-2.0",
            "https://developer.android.com/jetpack", "App framework, screens, background checks, fingerprint prompt, storage."),
        Credit("Kotlin and kotlinx (coroutines, serialization)", "JetBrains and contributors", "Apache-2.0", "https://kotlinlang.org", "Programming language and libraries."),
        Credit("Bricolage Grotesque", "The Bricolage Grotesque Project Authors", "OFL-1.1", "https://github.com/ateliertriay/bricolage", "Heading font."),
        Credit("IBM Plex Sans and Mono", "IBM Corp.", "OFL-1.1", "https://github.com/IBM/plex", "Text and number fonts.")
    )

    val entries: List<Credit> = entries("ZashFi")
}
