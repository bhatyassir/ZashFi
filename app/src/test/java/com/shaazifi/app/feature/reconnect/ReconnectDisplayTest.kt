package com.shaazifi.app.feature.reconnect

import com.shaazifi.app.ui.theme.PillTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectDisplayTest {

    @Test
    fun ringShowsTimeLeftAsAFractionAndNeverBreaksOnOddInput() {
        assertEquals(1f, ReconnectDisplay.ringFraction(remainingSeconds = 130, totalSeconds = 130), 0.0001f)
        assertEquals(0.5f, ReconnectDisplay.ringFraction(65, 130), 0.0001f)
        assertEquals(0f, ReconnectDisplay.ringFraction(0, 130), 0.0001f)
        assertEquals(0f, ReconnectDisplay.ringFraction(-5, 130), 0.0001f)
        assertEquals(1f, ReconnectDisplay.ringFraction(500, 130), 0.0001f)
        assertEquals(0f, ReconnectDisplay.ringFraction(10, 0), 0.0001f)
    }

    @Test
    fun everyStateHasPlainWordsAndTheRightTone() {
        val probing = ReconnectDisplay.copy(ReconnectState.Probing(remainingSeconds = 100, elapsedSeconds = 30), "Home router")
        assertEquals("Reconnecting to Home router", probing.title)
        assertEquals(PillTone.Off, probing.tone)

        assertEquals(PillTone.Ok, ReconnectDisplay.copy(ReconnectState.ReconnectedKept("https://192.168.1.1"), "R").tone)
        assertEquals(PillTone.Ok, ReconnectDisplay.copy(ReconnectState.ReconnectedSameAddress("https://192.168.1.1"), "R").tone)
        assertEquals(PillTone.Warn, ReconnectDisplay.copy(ReconnectState.ReconnectedRolledBack("https://192.168.1.1"), "R").tone)
        assertEquals(PillTone.Bad, ReconnectDisplay.copy(ReconnectState.CertificateChanged("AA"), "R").tone)
        assertEquals(PillTone.Bad, ReconnectDisplay.copy(ReconnectState.TimeoutUnreachable, "R").tone)

        listOf(
            ReconnectState.Probing(1, 1),
            ReconnectState.ReconnectedKept("a"),
            ReconnectState.ReconnectedSameAddress("a"),
            ReconnectState.ReconnectedRolledBack("a"),
            ReconnectState.CertificateChanged("AA"),
            ReconnectState.TimeoutUnreachable
        ).forEach { state ->
            val copy = ReconnectDisplay.copy(state, "R")
            assertTrue(copy.title.isNotBlank() && copy.body.isNotBlank() && copy.pill.isNotBlank())
        }
    }
}
