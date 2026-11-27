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
        "mesh" to "admin/network/wireless",
        "interfaces" to "admin/network/network",
        "wan" to "admin/network/network",
        "lan" to "admin/network/network",
        "ipv6" to "admin/network/network",
        "vpn" to "admin/network/network",
        "tailscale" to "admin/network/network",
        "dualwan" to "admin/network/network",
        // DDNS and UPnP are separate LuCI apps that are not installed on every router;
        // the Services menu is always present and is where those apps appear when installed.
        "ddns" to "admin/services",
        "upnp" to "admin/services",
        "qos" to "admin/network/sqm",
        "routes" to "admin/network/routes",
        "firewall" to "admin/network/firewall/zones",
        "portfwd" to "admin/network/firewall/forwards",
        "dmz" to "admin/network/firewall/forwards",
        "parental" to "admin/network/firewall/rules",
        "aiprotect" to "admin/network/firewall/rules",
        "traffic" to "admin/status/realtime/bandwidth",
        "live" to "admin/status/realtime/connections",
        "bandwidth" to "admin/status/realtime/bandwidth",
        "usage" to "admin/services/nlbw/display",
        "admin" to "admin/system/system",
        "log" to "admin/status/logs",
        // Point at the exact sub-page instead of the System Log alias.
        "kernellog" to "admin/status/logs/dmesg",
        "doctor" to "admin/network/diagnostics",
        "usb" to "admin/system/mounts",
        "software" to "admin/system/package-manager",
        "services" to "admin/system/startup",
        "health" to "admin/status/overview",
        "inspector" to "admin/status/overview",
        "configtasks" to "admin/system/crontab",
        "firewallx" to "admin/network/firewall/snats",
        "realtime" to "admin/status/realtime",
        "tailscale-peers" to "admin/network/network",
        "adguardpkg" to "admin/services/adguardhome",
        "wifiextras" to "admin/network/wireless",
        "downtimeline" to "admin/status/overview",
        "thermaltrend" to "admin/status/overview"
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
