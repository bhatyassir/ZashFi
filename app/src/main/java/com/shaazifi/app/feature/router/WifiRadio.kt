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
    val powers: List<Int>,
    val countries: List<String> = emptyList()
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

    fun radioInfos(
        wifi: JsonObject,
        freqlists: Map<String, JsonObject?>,
        powerlists: Map<String, JsonObject?>,
        countrylists: Map<String, JsonObject?> = emptyMap()
    ): List<RadioInfo> =
        wifi.mapNotNull { (id, el) ->
            val r = el.obj() ?: return@mapNotNull null
            val cfg = r["config"].obj()
            val iw = r["iwinfo"].obj() ?: r["interfaces"].arr()?.firstOrNull().obj()?.get("iwinfo").obj()
            val freq = iw?.get("frequency").int()
            val band = cfg?.get("band").str()?.takeIf { it.isNotBlank() } ?: bandFromFrequency(freq)
            val bandNumber = band.lowercase().removeSuffix("g").toIntOrNull()
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
                powers = powerlists[id]?.get("results").arr()?.mapNotNull { it.obj()?.get("dbm").int() }?.distinct().orEmpty(),
                countries = countrylists[id]?.get("results").arr()?.mapNotNull { entry ->
                    val o = entry.obj() ?: return@mapNotNull null
                    (o["code"].str() ?: o["iso3166"].str())?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                }?.distinct().orEmpty()
            )
        }

    private fun familyRank(htmode: String): Int = when {
        htmode.equals("NOHT", ignoreCase = true) -> 0
        htmode.startsWith("HT", ignoreCase = true) -> 1
        htmode.startsWith("VHT", ignoreCase = true) -> 2
        htmode.startsWith("HE", ignoreCase = true) -> 3
        htmode.startsWith("EHT", ignoreCase = true) -> 4
        else -> 5
    }

    internal fun mhz(htmode: String): Int {
        val upper = htmode.trim().uppercase()
        if (upper == "NOHT") return 20
        Regex("(\\d+)\\+(\\d+)$").find(upper)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: 0
            val b = m.groupValues[2].toIntOrNull() ?: 0
            return a + b
        }
        return Regex("[0-9]+$").find(upper)?.value?.toIntOrNull() ?: 0
    }

    /** Supported channel widths sorted narrowest-first across all advertised families (HT/VHT/HE/EHT). */
    fun widthChoices(r: RadioInfo): List<String> =
        r.htmodes.distinct().sortedWith(compareBy({ mhz(it) }, { familyRank(it) }, { it }))

    fun widthLabel(htmode: String): String {
        if (htmode.isBlank()) return "Driver default"
        val w = mhz(htmode)
        return if (w > 0) "$w MHz ($htmode)" else htmode
    }

    fun radioSettings(r: RadioInfo, channel: String, width: String, country: String, power: String): PlanResult {
        val ch = channel.trim().lowercase()
        val w = width.trim().uppercase()
        val cc = country.trim().uppercase()
        val pw = power.trim().lowercase()
        if (ch != "auto" && (ch.toIntOrNull() == null || (r.channels.isNotEmpty() && ch.toInt() !in r.channels))) {
            return PlanResult.Invalid("Choose a channel this radio offers: auto${r.channels.joinToString("") { ", $it" }}.")
        }
        if (w != r.htmode.uppercase() && w !in widthChoices(r).map { it.uppercase() }) {
            return PlanResult.Invalid("Choose a width this radio offers: ${widthChoices(r).joinToString(", ")}.")
        }
        if (cc.isNotEmpty() && !Regex("^[A-Z0-9]{2}$").matches(cc)) return PlanResult.Invalid("Use a two-letter country code, for example IN.")
        if (pw != "default" && (pw.toIntOrNull() == null || (r.powers.isNotEmpty() && pw.toInt() !in r.powers))) {
            return PlanResult.Invalid("Choose a power this radio offers: default${r.powers.joinToString("") { ", $it" }} (dBm).")
        }
        val values = linkedMapOf<String, Any>()
        val lines = mutableListOf<ChangeLine>()
        val ops = mutableListOf<UciOp>()
        if (ch != r.channel.lowercase()) { values["channel"] = ch; lines += ChangeLine("Channel", r.channel, ch) }
        if (w != r.htmode.uppercase()) {
            val canonicalWidth = widthChoices(r).firstOrNull { it.equals(w, ignoreCase = true) } ?: w
            values["htmode"] = canonicalWidth
            lines += ChangeLine("Width", widthLabel(r.htmode), widthLabel(canonicalWidth))
        }
        if (cc.isNotEmpty() && cc != r.country.uppercase()) { values["country"] = cc; lines += ChangeLine("Country", r.country.ifBlank { "Default" }, cc) }
        if (values.isNotEmpty()) ops += UciOp.Set("wireless", r.id, values)
        val currentPower = (r.txpower ?: "default").lowercase()
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

    fun bandFromFrequency(freqMhz: Int?): String = when (freqMhz) {
        null -> ""
        in 2400..2500 -> "2g"
        in 4900..5925 -> "5g"
        in 5926..7125 -> "6g"
        in 57000..66000 -> "60g"
        else -> ""
    }

    fun bandLabel(band: String) = when (band.trim().lowercase()) {
        "2g", "2.4g", "2.4 ghz" -> "2.4 GHz"
        "5g", "5 ghz" -> "5 GHz"
        "6g", "6 ghz" -> "6 GHz"
        "60g", "60 ghz" -> "60 GHz"
        else -> band.ifBlank { "Wi-Fi" }
    }

    /** WPA/WPA2/WPA3 PSK: either 64 hex characters or 8..63 UTF-8 bytes without control chars. */
    fun isValidWpaPsk(key: String): Boolean {
        if (key.length == 64 && key.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return true
        if (key.any { it.code < 32 || it.code == 127 }) return false
        return key.toByteArray(Charsets.UTF_8).size in 8..63
    }

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
                "sae" in auth && "psk" in auth -> "WPA2/WPA3" to "sae-mixed"
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
        if (enc != "none" && !isValidWpaPsk(password)) {
            return PlanResult.Invalid("The password needs 8 to 63 characters (or 64 hex digits).")
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
            reloadFirewall = true,
            longWait = true,
            note = "The router connects to \"${bss.ssid}\" and uses it for internet. Its own Wi-Fi on this band moves to channel ${bss.channel ?: "the same"}, " +
                "so devices may drop off for a moment. If the app cannot reach the router afterwards, the router undoes this within 2 minutes."
        ))
    }

    /** Removes the targeted client [section] and cleans up the app's `wwan` network/zone entry. */
    fun disconnect(section: String, ssid: String, firewall: UciConfig, network: UciConfig): PlanResult {
        val sec = section.trim()
        if (sec.isEmpty()) return PlanResult.Invalid("Unknown client link.")
        val ops = mutableListOf<UciOp>(UciOp.Delete("wireless", sec))
        if (network.byId(CLIENT_NETWORK) != null) ops += UciOp.Delete("network", CLIENT_NETWORK)
        firewall.ofType("zone").firstOrNull { CLIENT_NETWORK in it.list("network") }?.let { z ->
            ops += UciOp.Set("firewall", z.id, mapOf("network" to z.list("network") - CLIENT_NETWORK))
        }
        return PlanResult.Ready(ChangePlan(
            title = "Disconnect from \"$ssid\"?",
            lines = listOf(ChangeLine("Network", ssid, "None")),
            ops = ops,
            reloadFirewall = true,
            longWait = true,
            note = "The router stops using \"$ssid\". If that was its only internet, it has none until it is connected again."
        ))
    }
}
