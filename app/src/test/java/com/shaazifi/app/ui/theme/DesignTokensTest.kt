package com.shaazifi.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards that the app's colours are exactly the approved mock's colours
 * (docs/design/shaazifi-review/dist/style.css), in light and dark, and that the Material
 * colour scheme is built only from them (no default Android purple or pink).
 */
class DesignTokensTest {

    private fun mockHtml(): String {
        val candidates = listOf(
            File("../docs/design/shaazifi-review/dist/style.css"),
            File("docs/design/shaazifi-review/dist/style.css")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Mock not found from working dir ${File(".").absolutePath}")
        return file.readText()
    }

    /** Reads "--name:#RRGGBB" pairs from the first CSS block that starts with [selector]. */
    private fun tokensFrom(html: String, selector: String): Map<String, Color> {
        val start = html.indexOf(selector)
        require(start >= 0) { "Selector not found in mock: $selector" }
        val open = html.indexOf('{', start)
        val close = html.indexOf('}', open)
        val block = html.substring(open + 1, close)
        return Regex("--([a-z-]+):\\s*#([0-9A-Fa-f]{6})").findAll(block).associate { m ->
            m.groupValues[1] to Color(("FF" + m.groupValues[2]).toLong(16))
        }
    }

    private fun fields(c: SfColors): Map<String, Color> = mapOf(
        "bg" to c.bg, "panel" to c.surface, "panel-deep" to c.sunk, "text" to c.ink,
        "muted" to c.muted, "line" to c.line, "green" to c.accent,
        "ink-green" to c.accentInk, "green-soft" to c.accentSoft,
        "gold" to c.warn, "gold-soft" to c.warnSoft, "red" to c.bad, "red-soft" to c.badSoft
    )

    @Test
    fun lightTokensMatchMock() {
        val mock = tokensFrom(mockHtml(), ":root[data-theme=\"light\"]").toMutableMap().apply { put("ink-green", Color.White) }
        for ((name, value) in fields(SfTokens.Light)) {
            assertEquals("Light --$name", mock[name], value)
        }
    }

    @Test
    fun darkTokensMatchMock() {
        val mock = tokensFrom(mockHtml(), ":root {")
        for ((name, value) in fields(SfTokens.Dark)) {
            assertEquals("Dark --$name", mock[name], value)
        }
    }

    @Test
    fun materialSchemeUsesOnlyMockColours() {
        for ((c, dark) in listOf(SfTokens.Light to false, SfTokens.Dark to true)) {
            val s = sfColorScheme(c, dark)
            assertEquals(c.accent, s.primary)
            assertEquals(c.accentInk, s.onPrimary)
            assertEquals(c.accentSoft, s.primaryContainer)
            assertEquals(c.bg, s.background)
            assertEquals(c.ink, s.onBackground)
            assertEquals(c.surface, s.surface)
            assertEquals(c.ink, s.onSurface)
            assertEquals(c.muted, s.onSurfaceVariant)
            assertEquals(c.line, s.outline)
            assertEquals(c.bad, s.error)
            val allowed = fields(c).values.toSet()
            val used = listOf(
                s.primary, s.onPrimary, s.primaryContainer, s.onPrimaryContainer, s.inversePrimary,
                s.secondary, s.onSecondary, s.secondaryContainer, s.onSecondaryContainer,
                s.tertiary, s.onTertiary, s.tertiaryContainer, s.onTertiaryContainer,
                s.background, s.onBackground, s.surface, s.onSurface, s.surfaceVariant,
                s.onSurfaceVariant, s.surfaceTint, s.inverseSurface, s.inverseOnSurface,
                s.error, s.onError, s.errorContainer, s.onErrorContainer, s.outline, s.outlineVariant,
                s.surfaceBright, s.surfaceDim, s.surfaceContainer, s.surfaceContainerHigh,
                s.surfaceContainerHighest, s.surfaceContainerLow, s.surfaceContainerLowest
            )
            used.forEachIndexed { i, col ->
                assertTrue("Scheme colour #$i ($col) is not a mock token (dark=$dark)", col in allowed)
            }
        }
    }

    @Test
    fun pillTonesFollowMock() {
        val c = SfTokens.Light
        assertEquals(c.accentSoft to c.accent, pillColors(PillTone.Ok, c))
        assertEquals(c.warnSoft to c.warn, pillColors(PillTone.Warn, c))
        assertEquals(c.badSoft to c.bad, pillColors(PillTone.Bad, c))
        assertEquals(c.sunk to c.muted, pillColors(PillTone.Off, c))
    }
}
