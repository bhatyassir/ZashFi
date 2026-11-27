package com.shaazifi.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Owner feedback 2026-11-17: "the whole app looks monotonous". A few extra colours used only for
 * icon tiles and avatars (device kinds, feature groups, ports), so screens are easier to scan.
 * The mock's own tokens ([SfColors]) are unchanged. Each hue has a strong colour (icon, text) and a
 * soft one (tile background), for light and dark themes.
 */
enum class SfHue(val light: Pair<Long, Long>, val dark: Pair<Long, Long>) {
    GREEN(0xFF14623C to 0xFFDCEEE1, 0xFF83E3AD to 0xFF20392B),
    BLUE(0xFF245C8C to 0xFFE3EDF9, 0xFF99C8F1 to 0xFF223343),
    VIOLET(0xFF634A9C to 0xFFEEE7F9, 0xFFC2B1EE to 0xFF302842),
    ORANGE(0xFF825018 to 0xFFF8EBD7, 0xFFF3C47A to 0xFF392D20),
    PINK(0xFFB8336A to 0xFFF8DDE9, 0xFFF08AB4 to 0xFF3A1A29),
    TEAL(0xFF0E7C86 to 0xFFD5EEF0, 0xFF5CCBD4 to 0xFF12313A),
    RED(0xFF99382F to 0xFFF9E6E2, 0xFFFF9E96 to 0xFF3D2826),
    SLATE(0xFF4B5B66 to 0xFFE2E8EC, 0xFFA9B7C0 to 0xFF222C31);

    /** (strong, soft) for the current theme. */
    @Composable
    @ReadOnlyComposable
    fun colors(): Pair<Color, Color> {
        val dark = SfTheme.colors.bg.luminance() < 0.3f
        val (fg, bg) = if (dark) this.dark else light
        return Color(fg) to Color(bg)
    }
}
