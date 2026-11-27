package com.shaazifi.app.feature.guestqr

import com.google.zxing.qrcode.decoder.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M9 item 4. Wi-Fi QR text format from ZXing's "Barcode Contents" wiki (github.com/zxing/zxing/wiki/
 * Barcode-Contents, read 2026-11-17): WIFI:T:<WEP|WPA|nopass>;S:<ssid>;P:<password>;H:<true>;;
 * with \ ; , " : escaped by a backslash; an ASCII SSID that looks like hex is put in double quotes.
 */
class GuestQrTest {

    private fun cred(ssid: String, type: String?, key: String?, hidden: Boolean = false) =
        GuestCredential(ssid = ssid, band = "2.4 GHz", qrType = type, key = key, hidden = hidden)

    @Test
    fun specialCharactersAreEscaped() {
        assertEquals("""foo\;bar\\baz""", GuestQr.escape("""foo;bar\baz"""))
        assertEquals("""a\:b\,c\"d""", GuestQr.escape("""a:b,c"d"""))
        assertEquals("plain", GuestQr.escape("plain"))
    }

    @Test
    fun theQrTextFollowsTheWifiFormat() {
        assertEquals("WIFI:T:WPA;S:Guest;P:pa;ss;;".replace("pa;ss", """pa\;ss"""), GuestQr.payload(cred("Guest", "WPA", "pa;ss")))
        assertEquals("WIFI:T:WPA;S:Guest;P:secret12;H:true;;", GuestQr.payload(cred("Guest", "WPA", "secret12", hidden = true)))
        assertEquals("WIFI:T:nopass;S:Guest;;", GuestQr.payload(cred("Guest", "nopass", null)))
        assertEquals("hex-looking names are quoted", "WIFI:T:WPA;S:\"ABCD\";P:secret12;;", GuestQr.payload(cred("ABCD", "WPA", "secret12")))
        assertEquals("hex-looking passwords are quoted", "WIFI:T:WPA;S:Guest;P:\"1234ABCD\";;", GuestQr.payload(cred("Guest", "WPA", "1234ABCD")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedSecurityNeverDowngradesToNopass() {
        GuestQr.payload(cred("CorpGuest", null, "secret"))
    }

    @Test
    fun securityTypesMapToTheQrTypes() {
        assertEquals("WPA", GuestQr.qrType("psk2"))
        assertEquals("WPA", GuestQr.qrType("psk-mixed+ccmp"))
        assertEquals("WPA", GuestQr.qrType("sae"))
        assertEquals("WPA", GuestQr.qrType("sae-mixed"))
        assertEquals("WEP", GuestQr.qrType("wep-open"))
        assertEquals("nopass", GuestQr.qrType("none"))
        assertEquals("nopass", GuestQr.qrType(null))
        assertNull("enterprise needs a user name: no QR", GuestQr.qrType("wpa2"))
        assertNull(GuestQr.qrType("wpa3-mixed"))
        assertNull(GuestQr.qrType("owe"))
    }

    private val reply = Json.parseToJsonElement(
        """
        {"radio0":{"up":true,"config":{"band":"2g"},"interfaces":[
          {"section":"default_radio0","ifname":"phy0-ap0","config":{"mode":"ap","ssid":"Home","encryption":"psk2","key":"MAIN-SECRET","network":["lan"]}},
          {"section":"guest_radio0","ifname":"phy0-ap1","config":{"mode":"ap","ssid":"Home Guest","encryption":"psk2","key":"guest-pass-1","network":["guest"]}}
        ]},
         "radio1":{"up":true,"config":{"band":"5g"},"interfaces":[
          {"section":"default_radio1","config":{"mode":"ap","ssid":"Home 5","encryption":"sae","key":"MAIN-SECRET-5","network":["lan"]}}
        ]}}
        """
    ).jsonObject

    @Test
    fun onlyTheGuestPasswordIsTakenFromTheReply() {
        val guests = GuestQr.guestCredentials(reply)
        assertEquals(1, guests.size)
        assertEquals("Home Guest", guests[0].ssid)
        assertEquals("guest-pass-1", guests[0].key)
        assertEquals("WPA", guests[0].qrType)
        val all = guests.joinToString { it.toString() }
        assertFalse("main network passwords are never kept", all.contains("MAIN-SECRET"))
        assertFalse("the password never appears in logs or toString", guests[0].toString().contains("guest-pass-1"))
    }

    @Test
    fun theQrCodeReadsBackToTheSameText() {
        val text = GuestQr.payload(cred("Home Guest", "WPA", "guest;pass:1"))
        val matrix = GuestQr.matrix(text, margin = 0)
        assertEquals(text, Decoder().decode(matrix).text)
        assertTrue(matrix.width >= 21)
    }

    @Test
    fun disabledGuestInterfaceOrRadioIsReportedInCredential() {
        val disabledReply = Json.parseToJsonElement(
            """
            {"radio0":{"up":false,"disabled":true,"config":{"band":"2g"},"interfaces":[
              {"section":"guest_radio0","config":{"mode":"ap","ssid":"Guest24","encryption":"psk2","key":"pass1234","network":["guest"]}}
            ]},
             "radio1":{"up":true,"config":{"band":"5g"},"interfaces":[
              {"section":"guest_radio1","config":{"mode":"ap","ssid":"Guest5","encryption":"psk2","key":"pass5678","network":["guest"],"disabled":true}}
            ]}}
            """
        ).jsonObject
        val guests = GuestQr.guestCredentials(disabledReply)
        assertEquals(2, guests.size)
        assertFalse(guests[0].enabled)
        assertFalse(guests[1].enabled)
    }
}
