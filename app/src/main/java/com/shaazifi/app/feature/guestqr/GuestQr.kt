package com.shaazifi.app.feature.guestqr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.shaazifi.app.feature.router.FeatureMappers
import com.shaazifi.app.feature.router.RouterReader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A guest network's join details, kept only in screen memory while the QR code is shown.
 * [toString] never prints the password.
 */
class GuestCredential(
    val ssid: String,
    val band: String,
    /** "WPA", "WEP" or "nopass"; null when the network type cannot be shared by QR code. */
    val qrType: String?,
    val key: String?,
    val hidden: Boolean
) {
    override fun toString() = "GuestCredential(ssid=$ssid, band=$band, type=$qrType, hidden=$hidden)"
}

/**
 * Guest Wi-Fi QR code (M9 item 4). Text format and escaping from ZXing's "Barcode Contents" wiki
 * (github.com/zxing/zxing/wiki/Barcode-Contents, read 2026-11-17). Encoded with ZXing core 3.5.3.
 */
object GuestQr {

    /** `\ ; , " :` get a backslash in front (as in MECARD). */
    fun escape(s: String): String = buildString {
        s.forEach { ch -> if (ch in "\\;,\":") append('\\'); append(ch) }
    }

    /** OpenWrt `encryption` value to the QR "T:" value. Enterprise (802.1X) and OWE: no QR. */
    fun qrType(raw: String?): String? {
        val e = raw?.trim()?.lowercase().orEmpty()
        return when {
            e.isEmpty() || e == "none" -> "nopass"
            e.startsWith("psk") || e.startsWith("sae") -> "WPA"
            e.startsWith("wep") -> "WEP"
            else -> null
        }
    }

    fun payload(c: GuestCredential): String = buildString {
        val type = c.qrType ?: "nopass"
        append("WIFI:T:").append(type).append(';')
        val hexLike = c.ssid.isNotEmpty() && c.ssid.length % 2 == 0 && c.ssid.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        append("S:").append(if (hexLike) "\"${escape(c.ssid)}\"" else escape(c.ssid)).append(';')
        if (type != "nopass" && !c.key.isNullOrEmpty()) append("P:").append(escape(c.key)).append(';')
        if (c.hidden) append("H:true;")
        append(';')
    }

    /** QR code modules (one pixel per module). Error correction M; [margin] modules of white border. */
    fun matrix(text: String, margin: Int = 4): BitMatrix =
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to margin, EncodeHintType.CHARACTER_SET to "UTF-8")
        )

    /**
     * Picks only the guest access points out of a `luci-rpc getWirelessDevices` reply and keeps
     * their passwords; every other network's password is left behind.
     */
    fun guestCredentials(reply: JsonObject): List<GuestCredential> {
        val guests = FeatureMappers.parseWireless(reply).second.filter { it.mode == "ap" && FeatureMappers.isGuest(it) }
        return guests.map { g ->
            val cfg = findConfig(reply, g.radio, g.section, g.ssid)
            val raw = (cfg?.get("encryption") as? JsonPrimitive)?.content
            GuestCredential(
                ssid = g.ssid,
                band = g.band,
                qrType = qrType(raw),
                key = (cfg?.get("key") as? JsonPrimitive)?.content,
                hidden = g.hidden
            )
        }
    }

    private fun findConfig(reply: JsonObject, radio: String, section: String?, ssid: String): JsonObject? {
        val ifaces = ((reply[radio] as? JsonObject)?.get("interfaces") as? JsonArray) ?: return null
        val objs = ifaces.mapNotNull { it as? JsonObject }
        val match = objs.firstOrNull { section != null && (it["section"] as? JsonPrimitive)?.content == section }
            ?: objs.firstOrNull { ((it["config"] as? JsonObject)?.get("ssid") as? JsonPrimitive)?.content == ssid }
        return match?.get("config") as? JsonObject
    }

    /** The single read for the QR screen: one allowlisted call, guest passwords only. */
    suspend fun load(reader: RouterReader): List<GuestCredential> =
        guestCredentials(reader.required("luci-rpc", "getWirelessDevices"))

    /** Offline demo (no router contacted). */
    val demo = listOf(GuestCredential("ZashFi Guest", "2.4 GHz", "WPA", "welcome-guest", hidden = false))
}
