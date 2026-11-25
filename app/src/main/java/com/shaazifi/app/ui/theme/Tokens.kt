package com.shaazifi.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens of the approved mock, docs/design/router-app-mock.html (`:root` and
 * `:root[data-theme="dark"]`). Names follow the mock's CSS variables.
 * DesignTokensTest reads the mock file and fails if these drift from it.
 */
@Immutable
data class SfColors(
    val bg: Color,
    val surface: Color,
    val sunk: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val bad: Color,
    val badSoft: Color
)

object SfTokens {
    val Light = SfColors(
        bg = Color(0xFFEDF2EE), surface = Color(0xFFFBFDFA), sunk = Color(0xFFEDF4EE),
        ink = Color(0xFF17271E), muted = Color(0xFF455E50), line = Color(0xFFCCD9CF),
        accent = Color(0xFF14623C), accentInk = Color(0xFFFFFFFF), accentSoft = Color(0xFFDCEEE1),
        warn = Color(0xFF825018), warnSoft = Color(0xFFF8EBD7),
        bad = Color(0xFF99382F), badSoft = Color(0xFFF9E6E2)
    )

    val Dark = SfColors(
        bg = Color(0xFF101513), surface = Color(0xFF171F1B), sunk = Color(0xFF121A16),
        ink = Color(0xFFEDF4EC), muted = Color(0xFFA1B0A6), line = Color(0xFF2A3830),
        accent = Color(0xFF83E3AD), accentInk = Color(0xFF0B2214), accentSoft = Color(0xFF20392B),
        warn = Color(0xFFF3C47A), warnSoft = Color(0xFF392D20),
        bad = Color(0xFFFF9E96), badSoft = Color(0xFF3D2826)
    )
}

val LocalSfColors = staticCompositionLocalOf { SfTokens.Light }

/**
 * Maps the mock tokens onto every slot of the Material 3 colour scheme, so that
 * stock Material components (buttons, dialogs, text fields, switches) never fall
 * back to the default purple palette.
 */
fun sfColorScheme(c: SfColors, dark: Boolean): ColorScheme {
    val onError = if (dark) c.badSoft else c.surface
    val onWarn = if (dark) c.warnSoft else c.surface
    return if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.accentInk,
            primaryContainer = c.accentSoft, onPrimaryContainer = c.accent,
            inversePrimary = c.accentSoft,
            secondary = c.accent, onSecondary = c.accentInk,
            secondaryContainer = c.accentSoft, onSecondaryContainer = c.accent,
            tertiary = c.warn, onTertiary = onWarn,
            tertiaryContainer = c.warnSoft, onTertiaryContainer = c.warn,
            background = c.bg, onBackground = c.ink,
            surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.sunk, onSurfaceVariant = c.muted,
            surfaceTint = c.surface,
            inverseSurface = c.ink, inverseOnSurface = c.surface,
            error = c.bad, onError = onError,
            errorContainer = c.badSoft, onErrorContainer = c.bad,
            outline = c.line, outlineVariant = c.line,
            surfaceBright = c.surface, surfaceDim = c.bg,
            surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
            surfaceContainerHighest = c.surface, surfaceContainerLow = c.surface,
            surfaceContainerLowest = c.sunk
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.accentInk,
            primaryContainer = c.accentSoft, onPrimaryContainer = c.accent,
            inversePrimary = c.accentSoft,
            secondary = c.accent, onSecondary = c.accentInk,
            secondaryContainer = c.accentSoft, onSecondaryContainer = c.accent,
            tertiary = c.warn, onTertiary = onWarn,
            tertiaryContainer = c.warnSoft, onTertiaryContainer = c.warn,
            background = c.bg, onBackground = c.ink,
            surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.sunk, onSurfaceVariant = c.muted,
            surfaceTint = c.surface,
            inverseSurface = c.ink, inverseOnSurface = c.surface,
            error = c.bad, onError = onError,
            errorContainer = c.badSoft, onErrorContainer = c.bad,
            outline = c.line, outlineVariant = c.line,
            surfaceBright = c.surface, surfaceDim = c.bg,
            surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
            surfaceContainerHighest = c.surface, surfaceContainerLow = c.surface,
            surfaceContainerLowest = c.surface
        )
    }
}

/** The mock's four pill styles: `.pill`, `.pill.warn`, `.pill.bad`, `.pill.off`. */
enum class PillTone { Ok, Warn, Bad, Off }

/** Returns (background, text) colours for a pill, as in the mock's CSS. */
fun pillColors(tone: PillTone, c: SfColors): Pair<Color, Color> = when (tone) {
    PillTone.Ok -> c.accentSoft to c.accent
    PillTone.Warn -> c.warnSoft to c.warn
    PillTone.Bad -> c.badSoft to c.bad
    PillTone.Off -> c.sunk to c.muted
}
