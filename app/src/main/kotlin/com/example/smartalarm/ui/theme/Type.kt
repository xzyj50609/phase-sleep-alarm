package com.example.smartalarm.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.smartalarm.R

/**
 * 相位的字体分三个“声音”，各管一类文字，不再全篇一个字体。
 * 随包字体均为 SIL OFL 许可（见 licenses/）；字库之外的生僻字由系统字体兜底。
 *
 * - [clock] 宋体：除标题外的几乎一切——时间、数字、模式名（正常、爽睡……）、状态行、按钮、候选标题、提示语、小节题签、各行标题。笔画细，显得轻。
 * - [title] 得意黑：招牌与标题——“相位”字样、页面标题、卡片标题、对话框标题。斜体、略带个性，只在这里出现。
 * - [body]  黑体：只留给成段的说明文字（提示、设置里的解释）。短标签、标题、模式名、按钮都不用黑体：黑体太重，会显得笨重。
 */
object PhaseFonts {
    @OptIn(ExperimentalTextApi::class)
    private fun variable(resId: Int) = FontFamily(
        listOf(
            FontWeight.Light to 300,
            FontWeight.Normal to 400,
            FontWeight.Medium to 500,
            FontWeight.SemiBold to 600,
            FontWeight.Bold to 700,
        ).map { (weight, value) ->
            Font(resId = resId, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(value)))
        },
    )

    val clock: FontFamily = variable(R.font.phase_serif_sc)
    val body: FontFamily = variable(R.font.phase_sans_sc)
    // 标题体只有一个字重，字形本身已经偏粗，所以样式里不再要粗体（否则会被系统加粗涂糊）。
    val title: FontFamily = FontFamily(Font(R.font.phase_display_sc, FontWeight.Normal))
}

private const val TabularNumerals = "tnum"

internal val PhaseTypography: Typography = run {
    fun TextStyle.clock() = copy(fontFamily = PhaseFonts.clock)
    fun TextStyle.title() = copy(fontFamily = PhaseFonts.title, fontWeight = FontWeight.Normal)
    val base = Typography()
    base.copy(
        // 主推荐时间——用户睡眼惺忪来看的就是它。
        displayLarge = TextStyle(
            fontWeight = FontWeight.Light,
            fontSize = 72.sp,
            lineHeight = 80.sp,
            letterSpacing = (-1.5).sp,
        ).clock(),
        displayMedium = base.displayMedium.clock(),
        displaySmall = base.displaySmall.clock(),
        headlineLarge = base.headlineLarge.clock(),
        // 次要时间（计划几点睡、候选时间、统计数字）。
        headlineMedium = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.3).sp,
        ).clock(),
        // 对话框标题。
        headlineSmall = TextStyle(fontSize = 21.sp, lineHeight = 28.sp).title(),
        titleLarge = base.titleLarge.title(),
        // 页面标题。
        titleMedium = TextStyle(fontSize = 19.sp, lineHeight = 26.sp).title(),
        // 卡片标题。
        titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.2.sp).title(),
        // 正文行高按中文放宽到约 1.55 倍。
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp, letterSpacing = 0.2.sp).clock(),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.2.sp).clock(),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp).clock(),
        // 按钮文字。
        labelLarge = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.4.sp,
        ).clock(),
        // 小节题签（“其他睡法”“今晚的条件”“建议这时候醒”）：宋体小字，字距略开。
        labelMedium = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 1.6.sp,
        ).clock(),
        // 仪表式的小数据：状态行、时间轴刻度、图表读数；数字等宽。
        labelSmall = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.6.sp,
            fontFeatureSettings = TabularNumerals,
        ).clock(),
    )
}
