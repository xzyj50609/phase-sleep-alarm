package com.example.smartalarm.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalTime

private val hourOptions = (0..23).toList()
private val minuteOptions = (0..55 step 5).toList()

/**
 * iOS-style inline hour:minute wheel pair (5-minute minute steps), built on
 * the same snapping [WheelPicker] as the fall-asleep delay. Meant to sit
 * inside a card, not in a dialog — nothing overlays the page.
 */
@Composable
fun TimeWheelPicker(
    value: LocalTime,
    onValueChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Snap stored minutes onto the 5-minute grid the wheel can express.
    val minuteIndex = ((value.minute + 2) / 5).coerceAtMost(minuteOptions.lastIndex)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WheelPicker(
            items = hourOptions,
            selectedIndex = value.hour,
            onSelectedChange = { onValueChange(LocalTime.of(hourOptions[it], minuteOptions[minuteIndex])) },
            label = { "%02d".format(it) },
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = ":",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WheelPicker(
            items = minuteOptions,
            selectedIndex = minuteIndex,
            onSelectedChange = { onValueChange(LocalTime.of(value.hour, minuteOptions[it])) },
            label = { "%02d".format(it) },
            modifier = Modifier.width(64.dp),
        )
    }
}
