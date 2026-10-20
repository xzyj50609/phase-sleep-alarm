package com.example.smartalarm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

    /** 比 SurfaceHigh 再亮一档：悬浮层（对话框、菜单）和开关关闭态的轨道。 */
    val SurfaceHighest = Color(0xFF30364A)

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
    // 以下容器色以前没设，Material 组件（卡片、对话框、开关）会退回默认的灰紫色，和夜空底色不搭。
    surfaceDim = NightColors.Background,
    surfaceBright = NightColors.SurfaceHighest,
    surfaceContainerLowest = NightColors.Background,
    surfaceContainerLow = NightColors.Surface,
    surfaceContainer = NightColors.Surface,
    surfaceContainerHigh = NightColors.SurfaceHigh,
    surfaceContainerHighest = NightColors.SurfaceHighest,
    secondaryContainer = NightColors.SurfaceHigh,
    onSecondaryContainer = NightColors.Ink,
    tertiaryContainer = NightColors.SurfaceHigh,
    onTertiaryContainer = NightColors.Moderate,
    errorContainer = Color(0xFF3A2A31),
    onErrorContainer = NightColors.Groggy,
    inverseSurface = NightColors.Ink,
    inverseOnSurface = NightColors.Background,
    inversePrimary = Color(0xFF4A5C8F),
    scrim = Color.Black,
)

@Composable
fun SmartAlarmTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MoonlightDark,
        typography = PhaseTypography,
        content = content,
    )
}
