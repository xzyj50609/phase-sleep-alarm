package com.example.smartalarm.ui.screen.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.smartalarm.ui.theme.NightColors
import java.time.DayOfWeek
import java.time.LocalDate

/** Weeks of history the heatmap shows, newest column rightmost. */
const val HEATMAP_WEEKS = 16

/** Sleep duration that saturates the heatmap color (10 h). */
private const val FULL_BRIGHT_MINUTES = 10 * 60f

/**
 * 星图 — the last 16 weeks as a sky chart: one cell per night, brightening
 * with sleep. Columns are weeks (Monday-first), like a contribution graph
 * but drawn in moonlight instead of terminal green.
 */
@Composable
fun SleepHeatmap(
    stats: Map<LocalDate, DayStat>,
    selectedDate: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = NightColors.InkMuted.copy(alpha = 0.8f),
    )
    val today = LocalDate.now()
    val firstMonday = today.with(DayOfWeek.MONDAY).minusWeeks((HEATMAP_WEEKS - 1).toLong())

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(158.dp)
                .pointerInput(stats) {
                    detectTapGestures { offset ->
                        val leftPad = 22.dp.toPx()
                        val topPad = 18.dp.toPx()
                        val cell = cellSize(size.width.toFloat(), size.height.toFloat(), leftPad, topPad)
                        val col = ((offset.x - leftPad) / cell).toInt()
                        val row = ((offset.y - topPad) / cell).toInt()
                        if (col in 0 until HEATMAP_WEEKS && row in 0..6) {
                            val date = firstMonday.plusWeeks(col.toLong()).plusDays(row.toLong())
                            if (!date.isAfter(today)) onSelect(date)
                        }
                    }
                },
        ) {
            val leftPad = 22.dp.toPx()
            val topPad = 18.dp.toPx()
            val cell = cellSize(size.width, size.height, leftPad, topPad)
            val gap = cell * 0.16f
            val corner = CornerRadius(3.dp.toPx())

            // Month labels above the first column of each month.
            var lastMonth = -1
            for (col in 0 until HEATMAP_WEEKS) {
                val monday = firstMonday.plusWeeks(col.toLong())
                if (monday.monthValue != lastMonth) {
                    lastMonth = monday.monthValue
                    val layout = textMeasurer.measure("${monday.monthValue}月", labelStyle)
                    drawText(layout, topLeft = Offset(leftPad + col * cell, 0f))
                }
            }

            // Weekday labels for rows 一 / 四 / 日.
            listOf(0 to "一", 3 to "四", 6 to "日").forEach { (row, name) ->
                val layout = textMeasurer.measure(name, labelStyle)
                drawText(
                    layout,
                    topLeft = Offset(0f, topPad + row * cell + (cell - gap - layout.size.height) / 2f),
                )
            }

            for (col in 0 until HEATMAP_WEEKS) {
                for (row in 0..6) {
                    val date = firstMonday.plusWeeks(col.toLong()).plusDays(row.toLong())
                    if (date.isAfter(today)) continue
                    val topLeft = Offset(leftPad + col * cell, topPad + row * cell)
                    val cellDrawSize = Size(cell - gap, cell - gap)
                    drawRoundRect(
                        color = heatColor(stats[date]?.totalMinutes ?: 0),
                        topLeft = topLeft,
                        size = cellDrawSize,
                        cornerRadius = corner,
                    )
                    if (date == selectedDate) {
                        drawRoundRect(
                            color = NightColors.Ink,
                            topLeft = topLeft,
                            size = cellDrawSize,
                            cornerRadius = corner,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("睡得少", style = labelStyle)
            listOf(0, 300, 420, 540, 600).forEach { minutes ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(heatColor(minutes)),
                )
            }
            Text("睡得多", style = labelStyle)
        }
    }
}

private fun cellSize(width: Float, height: Float, leftPad: Float, topPad: Float): Float =
    minOf((width - leftPad) / HEATMAP_WEEKS, (height - topPad) / 7f)

/** No record stays a dim sky cell; recorded nights brighten toward the moon. */
private fun heatColor(totalMinutes: Int): Color {
    if (totalMinutes <= 0) return NightColors.SurfaceHigh
    val t = 0.3f + 0.7f * (totalMinutes / FULL_BRIGHT_MINUTES).coerceIn(0f, 1f)
    return lerp(NightColors.SurfaceHigh, NightColors.Moon, t)
}
