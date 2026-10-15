package com.example.smartalarm.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smartalarm.core.model.WakeQuality
import com.example.smartalarm.ui.theme.NightColors

fun WakeQuality.uiColor(): Color = when (this) {
    WakeQuality.EASY -> NightColors.Easy
    WakeQuality.MODERATE -> NightColors.Moderate
    WakeQuality.GROGGY -> NightColors.Groggy
}

fun WakeQuality.uiLabel(): String = when (this) {
    WakeQuality.EASY -> "轻松醒"
    WakeQuality.MODERATE -> "可能有点昏"
    WakeQuality.GROGGY -> "深睡陷阱"
}

/** Small colored dot + text, used in candidate rows and history. */
@Composable
fun QualityDot(quality: WakeQuality, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(quality.uiColor()),
    )
}

/** Pill badge for the hero card. */
@Composable
fun QualityBadge(quality: WakeQuality, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(quality.uiColor().copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QualityDot(quality)
        Text(
            text = quality.uiLabel(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = quality.uiColor(),
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
