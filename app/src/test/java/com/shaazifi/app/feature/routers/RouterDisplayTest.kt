package com.shaazifi.app.feature.routers

import com.shaazifi.app.TestStatus
import com.shaazifi.app.core.ubus.UbusError
import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.ui.theme.PillTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RouterDisplayTest {

    private fun profile(address: String, fp: String) =
        RouterProfile(name = "R", address = address, certificateFingerprint = fp)

    @Test
    fun plainHttpIsDetectedFromAddressOrMissingPin() {
        assertTrue(RouterDisplay.isPlainHttp(profile("http://192.168.1.1", "HTTP_NO_PIN")))
        assertTrue(RouterDisplay.isPlainHttp(profile("http://192.168.1.1", "AA:BB")))
        assertTrue(RouterDisplay.isPlainHttp(profile("https://192.168.1.1", "HTTP_NO_PIN")))
        assertFalse(RouterDisplay.isPlainHttp(profile("https://192.168.1.1", "AA:BB")))
    }

    @Test
    fun hostLabelDropsSchemeAndPathButKeepsUnusualPorts() {
        assertEquals("192.168.1.1", RouterDisplay.hostLabel("https://192.168.1.1/untangled/"))
        assertEquals("192.168.1.1:8443", RouterDisplay.hostLabel("https://192.168.1.1:8443/"))
        assertEquals("10.0.0.1", RouterDisplay.hostLabel("http://10.0.0.1"))
        assertEquals("not a url", RouterDisplay.hostLabel("not a url"))
    }

    @Test
    fun fingerprintIsSplitIntoReadableLinesOfEightPairs() {
        val fp = (0 until 32).joinToString(":") { "%02X".format(it) }
        val lines = RouterDisplay.fingerprintLines(fp)
        assertEquals(4, lines.size)
        assertEquals("00:01:02:03:04:05:06:07", lines[0])
        assertEquals("18:19:1A:1B:1C:1D:1E:1F", lines[3])
        assertEquals(fp, lines.joinToString(":"))
    }

    @Test
    fun signInErrorsAreInPlainEnglish() {
        assertEquals(
            "Wrong user name or password. Please try again.",
            RouterDisplay.signInError(UbusError.AccessDenied())
        )
        assertTrue(RouterDisplay.signInError(UbusError.NetworkError(IOException("x"))).startsWith("Can't reach the router"))
        assertTrue(RouterDisplay.signInError(IllegalStateException("boom")).contains("boom"))
        val changed = UbusError.NetworkError(
            com.shaazifi.app.core.net.CertificateChangedException("192.168.1.1", "AA", "BB")
        )
        assertTrue(RouterDisplay.signInError(changed).startsWith("This router's security certificate has changed"))
    }

    @Test
    fun selfTestStatusesMapToMockPillTones() {
        assertEquals(PillTone.Ok, RouterDisplay.testStatusPill(TestStatus.SUCCESS).second)
        assertEquals(PillTone.Ok, RouterDisplay.testStatusPill(TestStatus.REFUSED_EXPECTED).second)
        assertEquals(PillTone.Ok, RouterDisplay.testStatusPill(TestStatus.HARD_STOP_EXPECTED).second)
        assertEquals(PillTone.Warn, RouterDisplay.testStatusPill(TestStatus.REDIRECT).second)
        assertEquals(PillTone.Bad, RouterDisplay.testStatusPill(TestStatus.FAILED).second)
        assertEquals(PillTone.Off, RouterDisplay.testStatusPill(TestStatus.PENDING).second)
        assertEquals(PillTone.Off, RouterDisplay.testStatusPill(TestStatus.RUNNING).second)
        TestStatus.entries.forEach { assertTrue(RouterDisplay.testStatusPill(it).first.isNotBlank()) }
    }
}
