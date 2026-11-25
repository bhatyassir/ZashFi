package com.shaazifi.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.shaazifi.app.R

/*
 * Fonts of the approved mock, bundled in res/font (SIL Open Font License 1.1,
 * licence texts in assets/licenses). Not downloaded at run time: the app only
 * talks to the user's own router.
 *
 * Bricolage Grotesque and IBM Plex Sans are variable fonts; one file serves every
 * weight. Variable fonts from res/font work on Android 8 (API 26) and later, which
 * is this app's minSdk. Source: developer.android.com/develop/ui/compose/text/fonts,
 * "Use variable fonts", read 2026-11-16.
 */

@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

/** Headings and big numbers (mock `--display`). */
val SfDisplayFont = FontFamily(
    variable(R.font.bricolage_grotesque, FontWeight.Normal),
    variable(R.font.bricolage_grotesque, FontWeight.Medium),
    variable(R.font.bricolage_grotesque, FontWeight.SemiBold),
    variable(R.font.bricolage_grotesque, FontWeight.Bold)
)

/** Body text (mock `--body`). */
val SfBodyFont = FontFamily(
    variable(R.font.ibm_plex_sans, FontWeight.Normal),
    variable(R.font.ibm_plex_sans, FontWeight.Medium),
    variable(R.font.ibm_plex_sans, FontWeight.SemiBold),
    variable(R.font.ibm_plex_sans, FontWeight.Bold)
)

/** Addresses, numbers, versions (mock `--mono`). */
val SfMonoFont = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium)
)

/** Mock sizes: h1 26, h2 17, body 15, sub 13.5, eyebrow 11.5, mono 12.5, big 24. */
val SfTypography = Typography(
    displayLarge = TextStyle(fontFamily = SfDisplayFont, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    displayMedium = TextStyle(fontFamily = SfDisplayFont, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    displaySmall = TextStyle(fontFamily = SfDisplayFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineLarge = TextStyle(fontFamily = SfDisplayFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.01).em),
    headlineMedium = TextStyle(fontFamily = SfDisplayFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.01).em),
    headlineSmall = TextStyle(fontFamily = SfDisplayFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = SfDisplayFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = SfDisplayFont, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = SfBodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = SfBodyFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = SfBodyFont, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = SfBodyFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = SfBodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = SfBodyFont, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = SfBodyFont, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = 0.08.em)
)

/** Styles the mock uses that Material has no slot for. */
object SfText {
    val mono = TextStyle(fontFamily = SfMonoFont, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp)
    val big = TextStyle(fontFamily = SfDisplayFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp)
    val eyebrow = TextStyle(fontFamily = SfBodyFont, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = 0.08.em)
    val sub = TextStyle(fontFamily = SfBodyFont, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 19.sp)
}
