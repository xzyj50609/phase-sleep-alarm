package com.example.smartalarm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * "月光夜空" palette — the app is used at 00:30-01:30 with dark-adapted eyes,
 * so the theme stays dark-only and dim, but on a slate blue-gray base (not
 * near-black) with a soft moonlight accent. Saturation and contrast are kept
 * low so nothing glares at a sleepy user; this also matches the launcher
 * icon's night-sky styling.
 */
object NightColors {
    val Background = Color(0xFF151823)
    val Surface = Color(0xFF1D212E)
    val SurfaceHigh = Color(0xFF272C3C)
    val Moon = Color(0xFFA8BCE8)
    val OnMoon = Color(0xFF10141F)
    val Ink = Color(0xFFE2E6F0)
    val InkMuted = Color(0xFF8D95A9)
    val Outline = Color(0xFF343B4E)

    /** Wake-quality encoding: muted tones so the dots inform, not glare. */
    val Easy = Color(0xFF95C3A4)
    val Moderate = Color(0xFFD3BE93)
    val Groggy = Color(0xFFDD8B7E)
}

private val MoonlightDark = darkColorScheme(
    primary = NightColors.Moon,
    onPrimary = NightColors.OnMoon,
    primaryContainer = NightColors.SurfaceHigh,
    onPrimaryContainer = NightColors.Moon,
    secondary = NightColors.Easy,
    onSecondary = NightColors.OnMoon,
    tertiary = NightColors.Moderate,
    onTertiary = NightColors.OnMoon,
    background = NightColors.Background,
    onBackground = NightColors.Ink,
    surface = NightColors.Surface,
    onSurface = NightColors.Ink,
    surfaceVariant = NightColors.SurfaceHigh,
    onSurfaceVariant = NightColors.InkMuted,
    outline = NightColors.Outline,
    outlineVariant = NightColors.Outline,
    error = NightColors.Groggy,
    onError = NightColors.OnMoon,
)

/**
 * Type pairing: light-weight tabular numerals give the hero time the feel of
 * a bedside clock; monospace carries small data labels (status line,
 * timeline ticks, history table) like instrument markings. Both come from
 * system families — no bundled fonts.
 */
private val ClockTypography = Typography(
    // Hero wake-up time — the one thing the sleepy user came for.
    displayLarge = TextStyle(
        fontWeight = FontWeight.Light,
        fontSize = 72.sp,
        letterSpacing = (-2).sp,
        fontFeatureSettings = "tnum",
    ),
    // Secondary times (bedtime, candidate rows).
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum",
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    // Section eyebrows.
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 2.sp,
    ),
    // Instrument labels: status line, timeline ticks, history table.
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun SmartAlarmTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MoonlightDark,
        typography = ClockTypography,
        content = content,
    )
}
