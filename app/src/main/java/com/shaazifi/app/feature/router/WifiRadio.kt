package com.shaazifi.app.feature.router

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Wi-Fi radio settings, scan, join and leave (T-20261117-C0594015 phase 2).
 *
 * Adapted from LuCI's wireless page: openwrt/luci master,
 * modules/luci-mod-network/htdocs/luci-static/resources/view/network/wireless.js
 * (CBIWifiFrequencyValue, CBIWifiTxPowerValue, CBIWifiCountryValue, handleJoinConfirm) and
 * luci-base network.js (getScanList), read 2026-11-17.
 * Copyright the LuCI contributors, Apache License 2.0 (see CREDITS.md).
 * Differences from LuCI: the radio's width is left unchanged when joining (LuCI may switch an
 * ax radio to VHT/HT, which would also change the access point); WEP and enterprise networks
 * are not offered; an existing client link on the radio must be removed first.
 */

data class RadioInfo(
    val id: String,
    val band: String,
    val channel: String,
    val htmode: String,
    val country: String,
    val txpower: String?,
    val htmodes: List<String>,
    val channels: List<Int>,
    val powers: List<Int>
)

data class ScanEntry(
    val ssid: String,
    val bssid: String,
    val channel: Int?,
    val signal: Int?,
    val security: String,
    /** OpenWrt `encryption` to use when joining; null when the app cannot join it (WEP, enterprise). */
    val encryption: String?
)

object WifiRadio {

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = this as? JsonArray
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.content
    private fun JsonElement?.int() = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.bool() = (this as? JsonPrimitive)?.booleanOrNull

    const val CLIENT_NETWORK = "wwan"

    private val RADIO = Regex("^radio[0-9]{1,2}$")
    fun isRadioName(s: String) = RADIO.matches(s)

    fun radioInfos(wifi: JsonObject, freqlists: Map<String, JsonObject?>, powerlists: Map<String, JsonObject?>): List<RadioInfo> =
        wifi.mapNotNull { (id, el) ->
            val r = el.obj() ?: return@mapNotNull null
            val cfg = r["config"].obj()
            val iw = r["iwinfo"].obj()
            val band = cfg?.get("band").str() ?: ""
            val bandNumber = band.removeSuffix("g").toIntOrNull()
            RadioInfo(
                id = id,
                band = band,
                channel = cfg?.get("channel").str() ?: "auto",
                htmode = cfg?.get("htmode").str() ?: "",
                country = cfg?.get("country").str() ?: "",
                txpower = cfg?.get("txpower").str(),
                htmodes = iw?.get("htmodes").arr()?.mapNotNull { it.str() }.orEmpty(),
                // As LuCI: only this band, and not channels where transmitting is restricted (no_ir).
                channels = freqlists[id]?.get("results").arr()?.mapNotNull { it.obj() }?.filter { f ->
                    (bandNumber == null || f["band"].int() == bandNumber) &&
                        !(f["restricted"].bool() == true && f["flags"].arr()?.any { it.str() == "no_ir" } == true)
                }?.mapNotNull { it["channel"].int() }?.distinct().orEmpty(),
                powers = powerlists[id]?.get("results").arr()?.mapNotNull { it.obj()?.get("dbm").int() }?.distinct().orEmpty()
            )
        }

    private fun family(htmode: String) = Regex("^[A-Z]+").find(htmode)?.value ?: ""
    private fun mhz(htmode: String) = Regex("[0-9]+$").find(htmode)?.value?.toIntOrNull() ?: 0

    /** Widths of the same kind as the current one (HE, VHT, HT, EHT), narrowest first. */
    fun widthChoices(r: RadioInfo): List<String> {
        val fam = family(r.htmode)
        return r.htmodes.filter { fam.isEmpty() || family(it) == fam }.sortedBy { mhz(it) }
    }

    fun widthLabel(htmode: String) = if (htmode.isBlank()) "Driver default" else "${mhz(htmode)} MHz ($htmode)"

    fun radioSettings(r: RadioInfo, channel: String, width: String, country: String, power: String): PlanResult {
        val ch = channel.trim().lowercase()
        val w = width.trim().uppercase()
        val cc = country.trim().uppercase()
        val pw = power.trim().lowercase()
        if (ch != "auto" && (ch.toIntOrNull() == null || (r.channels.isNotEmpty() && ch.toInt() !in r.channels))) {
            return PlanResult.Invalid("Choose a channel this radio offers: auto${r.channels.joinToString("") { ", $it" }}.")
        }
        if (w != r.htmode && w !in widthChoices(r)) return PlanResult.Invalid("Choose a width this radio offers: ${widthChoices(r).joinToString(", ")}.")
        if (cc.isNotEmpty() && !Regex("^[A-Z0-9]{2}$").matches(cc)) return PlanResult.Invalid("Use a two-letter country code, for example IN.")
        if (pw != "default" && (pw.toIntOrNull() == null || (r.powers.isNotEmpty() && pw.toInt() !in r.powers))) {
            return PlanResult.Invalid("Choose a power this radio offers: default${r.powers.joinToString("") { ", $it" }} (dBm).")
        }
        val values = linkedMapOf<String, Any>()
        val lines = mutableListOf<ChangeLine>()
        val ops = mutableListOf<UciOp>()
        if (ch != r.channel.lowercase()) { values["channel"] = ch; lines += ChangeLine("Channel", r.channel, ch) }
        if (w != r.htmode) { values["htmode"] = w; lines += ChangeLine("Width", widthLabel(r.htmode), widthLabel(w)) }
        if (cc.isNotEmpty() && cc != r.country) { values["country"] = cc; lines += ChangeLine("Country", r.country.ifBlank { "Default" }, cc) }
        if (values.isNotEmpty()) ops += UciOp.Set("wireless", r.id, values)
        val currentPower = r.txpower ?: "default"
        if (pw != currentPower) {
            ops += if (pw == "default") UciOp.DeleteOption("wireless", r.id, "txpower") else UciOp.Set("wireless", r.id, mapOf("txpower" to pw))
            lines += ChangeLine("Transmit power", if (currentPower == "default") "Default" else "$currentPower dBm", if (pw == "default") "Default" else "$pw dBm")
        }
        if (ops.isEmpty()) return PlanResult.Invalid("Nothing changed.")
        return PlanResult.Ready(ChangePlan(
            title = "Change the ${bandLabel(r.band)} radio?",
            lines = lines,
            ops = ops,
            longWait = true,
            note = "Wi-Fi on this band restarts; phones and laptops reconnect by themselves. " +
                (if (values.containsKey("country")) "Use the country you are in: it sets which channels and power are legal. " else "") +
                "If the app cannot reach the router afterwards, the router undoes the change within 2 minutes."
        ))
    }

    fun bandLabel(band: String) = when (band) { "2g" -> "2.4 GHz"; "5g" -> "5 GHz"; "6g" -> "6 GHz"; "60g" -> "60 GHz"; else -> band.ifBlank { "Wi-Fi" } }

    /** `iwinfo scan` results, strongest first. */
    fun parseScan(reply: JsonObject): List<ScanEntry> =
        reply["results"].arr()?.mapNotNull { it.obj() }.orEmpty().map { b ->
            val enc = b["encryption"].obj()
            val auth = enc?.get("authentication").arr()?.mapNotNull { it.str() }.orEmpty()
            val wpa = enc?.get("wpa").arr()?.mapNotNull { it.int() }.orEmpty()
            val wep = enc?.get("wep") != null
            val (security, encryption) = when {
                enc == null || enc["enabled"].bool() == false -> "Open" to "none"
                auth.any { it == "802.1x" || it == "8021x" } -> "Enterprise" to null
                wep -> "WEP" to null
                "sae" in auth && "psk" in auth -> "WPA2/WPA3" to "sae"
                "sae" in auth -> "WPA3" to "sae"
                "psk" in auth && 2 in wpa -> "WPA2" to "psk2"
                "psk" in auth -> "WPA" to "psk"
                else -> "Unknown" to null
            }
            ScanEntry(b["ssid"].str().orEmpty(), b["bssid"].str().orEmpty(), b["channel"].int(), b["signal"].int(), security, encryption)
        }.distinctBy { it.bssid + it.ssid }.sortedByDescending { it.signal ?: -999 }

    /** Client (station) links already on the router, from `luci-rpc getWirelessDevices`: section to SSID. */
    fun clientLinks(wifi: JsonObject): List<Triple<String, String, String>> = wifi.flatMap { (radio, el) ->
        el.obj()?.get("interfaces").arr()?.mapNotNull { it.obj() }.orEmpty().mapNotNull { i ->
            val cfg = i["config"].obj() ?: return@mapNotNull null
            if (cfg["mode"].str() != "sta") return@mapNotNull null
            Triple(i["section"].str() ?: return@mapNotNull null, radio, cfg["ssid"].str().orEmpty())
        }
    }

    fun join(radio: String, bss: ScanEntry, password: String, wifi: JsonObject, firewall: UciConfig, network: UciConfig): PlanResult {
        if (!isRadioName(radio)) return PlanResult.Invalid("Unknown radio.")
        if (bss.ssid.isBlank()) return PlanResult.Invalid("Hidden networks cannot be joined from the app yet.")
        val enc = bss.encryption ?: return PlanResult.Invalid(
            if (bss.security == "Enterprise") "Enterprise networks need a user name; join them in LuCI." else "This network's security (${bss.security}) is not supported."
        )
        if (clientLinks(wifi).any { it.second == radio }) return PlanResult.Invalid("This radio is already connected to another network. Disconnect it first.")
        if (network.byId(CLIENT_NETWORK) != null && clientLinks(wifi).isNotEmpty()) return PlanResult.Invalid("The router is already connected to another network. Disconnect it first.")
        if (enc != "none" && (password.length !in 8..63 || password.any { it.code !in 32..126 })) {
            return PlanResult.Invalid("The password needs 8 to 63 letters, numbers or symbols.")
        }
        val wanZone = firewall.ofType("zone").firstOrNull { it.opt("name") == "wan" }
            ?: return PlanResult.Invalid("The router has no firewall zone named wan.")
        val ops = mutableListOf<UciOp>()
        bss.channel?.let { ops += UciOp.Set("wireless", radio, mapOf("channel" to it.toString())) }
        ops += UciOp.Add("wireless", "wifi-iface", linkedMapOf<String, Any>(
            "device" to radio, "mode" to "sta", "network" to CLIENT_NETWORK, "ssid" to bss.ssid, "encryption" to enc
        ).apply { if (enc != "none") put("key", password) })
        if (network.byId(CLIENT_NETWORK) == null) ops += UciOp.Add("network", "interface", mapOf("proto" to "dhcp"), name = CLIENT_NETWORK)
        val nets = wanZone.list("network")
        if (CLIENT_NETWORK !in nets) ops += UciOp.Set("firewall", wanZone.id, mapOf("network" to nets + CLIENT_NETWORK))
        return PlanResult.Ready(ChangePlan(
            title = "Join \"${bss.ssid}\"?",
            lines = listOf(
                ChangeLine("Network", "None", bss.ssid),
                ChangeLine("Security", "", bss.security),
                ChangeLine("Radio channel", "", bss.channel?.toString() ?: "unchanged")
            ),
            ops = ops,
            longWait = true,
            note = "The router connects to \"${bss.ssid}\" and uses it for internet. Its own Wi-Fi on this band moves to channel ${bss.channel ?: "the same"}, " +
                "so devices may drop off for a moment. If the app cannot reach the router afterwards, the router undoes this within 2 minutes."
        ))
    }

    /** [section] identifies the link on screen; the delete itself removes client links only (mode sta). */
    @Suppress("UNUSED_PARAMETER")
    fun disconnect(section: String, ssid: String, firewall: UciConfig, network: UciConfig): PlanResult {
        val ops = mutableListOf<UciOp>(UciOp.DeleteWhere("wireless", "wifi-iface", mapOf("mode" to "sta")))
        if (network.byId(CLIENT_NETWORK) != null) ops += UciOp.Delete("network", CLIENT_NETWORK)
        firewall.ofType("zone").firstOrNull { CLIENT_NETWORK in it.list("network") }?.let { z ->
            ops += UciOp.Set("firewall", z.id, mapOf("network" to z.list("network") - CLIENT_NETWORK))
        }
        return PlanResult.Ready(ChangePlan(
            title = "Disconnect from \"$ssid\"?",
            lines = listOf(ChangeLine("Network", ssid, "None")),
            ops = ops,
            longWait = true,
            note = "The router stops using \"$ssid\". If that was its only internet, it has none until it is connected again."
        ))
    }
}
