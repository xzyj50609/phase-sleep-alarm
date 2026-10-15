package com.example.smartalarm.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.smartalarm.ui.theme.NightColors
import java.time.LocalTime

/**
 * 夜航图 — the night rendered as a horizontal voyage: falling asleep, the
 * cycle chain ticking along, the punch alarm cutting the line, the re-sleep
 * segment, and the chosen wake-up point. The structure of the drawing IS the
 * algorithm's model, so the user can see why a time was recommended.
 */
@Composable
fun NightTimeline(
    bedTime: LocalTime,
    sleepStart: LocalTime,
    wakeTime: LocalTime,
    cycleMinutes: Int,
    punchTime: LocalTime?,
    resleepStart: LocalTime?,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = NightColors.InkMuted)
    val punchLabelStyle = MaterialTheme.typography.labelSmall.copy(color = NightColors.Groggy)
    val wakeLabelStyle = MaterialTheme.typography.labelSmall.copy(color = NightColors.Moon)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp),
    ) {
        val pad = 10.dp.toPx()
        val trackY = size.height * 0.48f
        val spanMinutes = minutesForward(bedTime, wakeTime).coerceAtLeast(30)
        val usableWidth = size.width - 2 * pad

        fun xOf(time: LocalTime): Float =
            pad + usableWidth * minutesForward(bedTime, time).toFloat() / spanMinutes

        val xBed = xOf(bedTime)
        val xSleep = xOf(sleepStart)
        val xWake = xOf(wakeTime)

        // Falling asleep: dashed approach.
        drawLine(
            color = NightColors.Outline,
            start = Offset(xBed, trackY),
            end = Offset(xSleep, trackY),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)),
        )

        if (punchTime != null && resleepStart != null &&
            minutesForward(sleepStart, punchTime) < minutesForward(sleepStart, wakeTime)
        ) {
            val xPunch = xOf(punchTime)
            val xResleep = xOf(resleepStart).coerceAtMost(xWake)

            drawSleepSegment(xSleep, xPunch, trackY)
            drawCycleTicks(sleepStart, punchTime, cycleMinutes, trackY, ::xOf)

            // Awake gap between punch and re-sleep.
            drawLine(
                color = NightColors.Outline,
                start = Offset(xPunch, trackY),
                end = Offset(xResleep, trackY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawSleepSegment(xResleep, xWake, trackY)

            // Punch alarm: the cut through the night.
            drawLine(
                color = NightColors.Groggy,
                start = Offset(xPunch, trackY - 14.dp.toPx()),
                end = Offset(xPunch, trackY + 10.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawTimeLabel(
                textMeasurer, "必醒 ${format(punchTime)}", punchLabelStyle,
                centerX = xPunch, y = trackY - 32.dp.toPx(),
            )
        } else {
            drawSleepSegment(xSleep, xWake, trackY)
            drawCycleTicks(sleepStart, wakeTime, cycleMinutes, trackY, ::xOf)
        }

        // Wake-up marker.
        drawCircle(
            color = NightColors.Moon,
            radius = 6.dp.toPx(),
            center = Offset(xWake, trackY),
        )
        drawCircle(
            color = NightColors.Background,
            radius = 2.5.dp.toPx(),
            center = Offset(xWake, trackY),
        )

        // Labels below the track.
        val labelY = trackY + 16.dp.toPx()
        drawTimeLabel(textMeasurer, format(bedTime), labelStyle, centerX = xBed, y = labelY)
        drawTimeLabel(textMeasurer, "${format(wakeTime)} 起", wakeLabelStyle, centerX = xWake, y = labelY)
    }
}

private fun DrawScope.drawSleepSegment(startX: Float, endX: Float, trackY: Float) {
    if (endX <= startX) return
    drawLine(
        color = NightColors.Moon.copy(alpha = 0.55f),
        start = Offset(startX, trackY),
        end = Offset(endX, trackY),
        strokeWidth = 8.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawCycleTicks(
    sleepStart: LocalTime,
    segmentEnd: LocalTime,
    cycleMinutes: Int,
    trackY: Float,
    xOf: (LocalTime) -> Float,
) {
    val segmentMinutes = minutesForward(sleepStart, segmentEnd)
    var elapsed = cycleMinutes
    while (elapsed < segmentMinutes) {
        val x = xOf(sleepStart.plusMinutes(elapsed.toLong()))
        drawLine(
            color = NightColors.Background,
            start = Offset(x, trackY - 5.dp.toPx()),
            end = Offset(x, trackY + 5.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
        elapsed += cycleMinutes
    }
}

private fun DrawScope.drawTimeLabel(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    style: TextStyle,
    centerX: Float,
    y: Float,
) {
    val layout = textMeasurer.measure(text, style)
    val x = (centerX - layout.size.width / 2f)
        .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
    drawText(layout, topLeft = Offset(x, y))
}

private fun minutesForward(from: LocalTime, to: LocalTime): Int {
    val diff = to.toSecondOfDay() - from.toSecondOfDay()
    return (((diff % 86400) + 86400) % 86400) / 60
}

private fun format(time: LocalTime): String =
    "%02d:%02d".format(time.hour, time.minute)
