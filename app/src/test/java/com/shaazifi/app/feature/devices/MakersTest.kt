package com.shaazifi.app.feature.devices

import com.shaazifi.app.feature.about.Credits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Device makers from Wireshark's manuf list (owner's choice, 2026-11-17). */
class MakersTest {

    private val table = Makers.parse("# header\n3C2EFF\tApple\n001BC5\tIEEE Registration Authority\n001BC5000\tConverging Systems\n")

    @Test
    fun longestPrefixWins() {
        assertEquals("Apple", table.lookup("3c:2e:ff:11:22:33"))
        assertEquals("Converging Systems", table.lookup("00:1B:C5:00:0A:BC"))
        assertEquals("IEEE Registration Authority", table.lookup("00:1B:C5:FF:00:01"))
        assertNull(table.lookup("12:34:56:78:9A:BC".replace("12", "10")))
        assertNull(table.lookup("not a mac"))
    }

    @Test
    fun randomAddressesHaveNoMaker() {
        // The "locally administered" bit (second-lowest bit of the first byte): phones' private addresses.
        assertTrue(Makers.isRandomized("DA:A1:19:00:00:01"))
        assertTrue(Makers.isRandomized("3E:2E:FF:00:00:01"))
        assertFalse(Makers.isRandomized("3C:2E:FF:00:00:01"))
        assertNull(table.lookup("3E:2E:FF:00:00:01"))
        assertEquals("Private address", Makers.label(table, "DA:A1:19:00:00:01"))
        assertEquals("Apple", Makers.label(table, "3C:2E:FF:00:00:01"))
    }

    @Test
    fun theBundledListIsCompleteAndCredited() {
        val f = listOf(File("src/main/assets/oui/makers.tsv"), File("app/src/main/assets/oui/makers.tsv")).first { it.isFile }
        val real = Makers.parse(f.readText())
        assertTrue(real.size > 40_000)
        assertEquals("Apple", real.lookup("3C:2E:FF:00:00:01"))
        assertTrue(Credits.entries.any { it.name == "Wireshark (device maker list)" })
    }
}
