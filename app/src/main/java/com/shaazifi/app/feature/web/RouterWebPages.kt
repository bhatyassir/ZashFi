package com.shaazifi.app.feature.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class RouterWebUi(val label: String) {
    Untangled("UnTangled"),
    Luci("LuCI")
}

/**
 * Links into the router's web interfaces. LuCI addresses from its menu definitions
 * (openwrt/luci master: luci-mod-network,
 * luci-mod-system, luci-mod-status, luci-app-ddns, -upnp, -sqm, -firewall menu.d, read 2026-11-17).
 */
object RouterWebPages {
    private const val LUCI = "/cgi-bin/luci/"

    private val LUCI_PATHS = mapOf(
        "wireless" to "admin/network/wireless",
        "guest" to "admin/network/wireless",
        "extend" to "admin/network/wireless",
        "wan" to "admin/network/network",
        "lan" to "admin/network/network",
        "ipv6" to "admin/network/network",
        "vpn" to "admin/network/network",
        "tailscale" to "admin/network/network",
        "dualwan" to "admin/network/network",
        "ddns" to "admin/services/ddns",
        "upnp" to "admin/services/upnp",
        "qos" to "admin/network/sqm",
        "firewall" to "admin/network/firewall",
        "portfwd" to "admin/network/firewall/forwards",
        "dmz" to "admin/network/firewall/forwards",
        "parental" to "admin/network/firewall/rules",
        "aiprotect" to "admin/network/firewall/rules",
        "traffic" to "admin/status/realtime/bandwidth",
        "usage" to "admin/status/realtime/bandwidth",
        "admin" to "admin/system/system",
        "log" to "admin/status/logs",
        "doctor" to "admin/network/diagnostics",
        "usb" to "admin/system/mounts",
        "software" to "admin/system/package-manager",
        "services" to "admin/system/startup",
        "health" to "admin/status/overview"
    )

    /** LuCI page for a feature, or LuCI's front page. */
    fun luciPath(featureId: String?): String = LUCI + (LUCI_PATHS[featureId] ?: "")

    fun target(origin: String, webUi: RouterWebUi, featureId: String?): String = when (webUi) {
        RouterWebUi.Untangled -> "$origin/untangled/" + (featureId?.let { "#$it" } ?: "")
        RouterWebUi.Luci -> origin + luciPath(featureId)
    }

    /** True when the router serves UnTangled's page. Uses the app's guarded client (pinned certificate). */
    suspend fun detectUntangled(client: OkHttpClient, origin: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$origin/untangled/").get().build()).execute().use { r ->
                r.code == 200 && r.body.string().take(4096).contains("untangled", ignoreCase = true)
            }
        }.getOrDefault(false)
    }
}
