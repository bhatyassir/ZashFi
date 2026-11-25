package com.shaazifi.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/** Mock radii: inputs and buttons 13, cards 16, icon tiles 11. */
val SfShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(13.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * App theme built from the approved mock (docs/design/router-app-mock.html).
 * Follows the phone's light or dark setting, as the mock does.
 */
@Composable
fun ShaaziFiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) SfTokens.Dark else SfTokens.Light
    CompositionLocalProvider(LocalSfColors provides colors) {
        MaterialTheme(
            colorScheme = sfColorScheme(colors, darkTheme),
            typography = SfTypography,
            shapes = SfShapes,
            content = content
        )
    }
}

/** Access to the mock tokens from any screen: `SfTheme.colors.accent`. */
object SfTheme {
    val colors: SfColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSfColors.current
}

/** App theme choice (phase 5): follow the phone, or always light or dark. */
enum class ThemeChoice(val label: String) {
    SYSTEM("Same as the phone"), LIGHT("Light"), DARK("Dark");

    fun isDark(systemDark: Boolean) = when (this) { SYSTEM -> systemDark; LIGHT -> false; DARK -> true }

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: SYSTEM
    }
}
