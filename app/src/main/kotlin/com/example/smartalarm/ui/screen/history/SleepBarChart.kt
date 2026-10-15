package com.example.smartalarm.ui.screen.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.smartalarm.ui.theme.NightColors
import java.time.LocalDate
import kotlin.math.min

/**
 * Daily sleep as quiet moonlight columns — night sleep in moon blue, naps
 * stacked on top in sand, an 8-hour reference line as the only guidance.
 * Tap a column to select its day; the screen shows details below, keeping
 * the drawing itself free of clutter.
 */
@Composable
fun SleepBarChart(
    days: List<LocalDate>,
    stats: Map<LocalDate, DayStat>,
    selectedDate: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    xLabel: (LocalDate) -> String?,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(
        color = NightColors.InkMuted.copy(alpha = 0.8f),
    )
    val todayStyle = MaterialTheme.typography.labelSmall.copy(color = NightColors.Moon)
    val today = LocalDate.now()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp)
            .pointerInput(days) {
                detectTapGestures { offset ->
                    if (days.isEmpty()) return@detectTapGestures
                    val slot = size.width.toFloat() / days.size
                    val index = (offset.x / slot).toInt().coerceIn(0, days.lastIndex)
                    onSelect(days[index])
                }
            },
    ) {
        if (days.isEmpty()) return@Canvas

        val labelBand = 22.dp.toPx()
        val topPad = 14.dp.toPx()
        val chartBottom = size.height - labelBand
        val chartHeight = chartBottom - topPad

        val maxTotal = days.maxOf { stats[it]?.totalMinutes ?: 0 }
        val yMaxMinutes = maxOf(10 * 60, maxTotal + 30).toFloat()
        fun yOf(minutes: Int): Float = chartBottom - chartHeight * (minutes / yMaxMinutes)

        // Reference lines: 4h hairline, 8h the healthy-night marker.
        listOf(4, 8).forEach { hours ->
            val y = yOf(hours * 60)
            drawLine(
                color = NightColors.Outline.copy(alpha = if (hours == 8) 0.9f else 0.5f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f)),
            )
            val layout = textMeasurer.measure("${hours}h", axisStyle)
            drawText(layout, topLeft = Offset(size.width - layout.size.width, y - layout.size.height - 2))
        }

        // Baseline.
        drawLine(
            color = NightColors.Outline,
            start = Offset(0f, chartBottom),
            end = Offset(size.width, chartBottom),
            strokeWidth = 1.dp.toPx(),
        )

        val slot = size.width / days.size
        val barWidth = min(slot * 0.55f, 26.dp.toPx())

        days.forEachIndexed { index, date ->
            val stat = stats[date] ?: return@forEachIndexed
            if (stat.totalMinutes > 0) {
                val isSelected = date == selectedDate
                val centerX = slot * index + slot / 2
                val left = centerX - barWidth / 2
                val topTotal = yOf(stat.totalMinutes)
                val radius = min(barWidth / 2, 5.dp.toPx())

                if (stat.napMinutes > 0) {
                    // Nap segment carries the rounded cap; night sits flat below.
                    drawTopRoundedBar(
                        left, topTotal, barWidth, yOf(stat.nightMinutes) - topTotal, radius,
                        NightColors.Moderate.copy(alpha = if (isSelected) 0.95f else 0.6f),
                    )
                    drawRect(
                        color = NightColors.Moon.copy(alpha = if (isSelected) 1f else 0.55f),
                        topLeft = Offset(left, yOf(stat.nightMinutes)),
                        size = Size(barWidth, chartBottom - yOf(stat.nightMinutes)),
                    )
                } else {
                    drawTopRoundedBar(
                        left, topTotal, barWidth, chartBottom - topTotal, radius,
                        NightColors.Moon.copy(alpha = if (isSelected) 1f else 0.55f),
                    )
                }
            }

            xLabel(date)?.let { label ->
                val style = if (date == today) todayStyle else axisStyle
                val layout = textMeasurer.measure(label, style)
                drawText(
                    layout,
                    topLeft = Offset(
                        slot * index + slot / 2 - layout.size.width / 2f,
                        chartBottom + (labelBand - layout.size.height) / 2f,
                    ),
                )
            }
        }
    }
}

private fun DrawScope.drawTopRoundedBar(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    radius: Float,
    color: androidx.compose.ui.graphics.Color,
) {
    if (height <= 0f) return
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(left, top, left + width, top + height),
                topLeft = CornerRadius(radius),
                topRight = CornerRadius(radius),
            ),
        )
    }
    drawPath(path, color)
}
