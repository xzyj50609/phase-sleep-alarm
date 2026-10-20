package com.example.smartalarm.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.smartalarm.core.model.AlarmMode
import kotlin.math.abs

/** Horizontal drags cancel button clicks; vertical drags remain owned by the page scroll. */
@Composable
fun SwipeModeCard(
    selected: AlarmMode,
    onSelect: (AlarmMode) -> Unit,
    onPreview: (Float?) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val distance = with(density) { 72.dp.toPx() }
    val flingSpeed = with(density) { 700.dp.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var displacement by remember { mutableFloatStateOf(0f) }
    val select by rememberUpdatedState(onSelect)
    val preview by rememberUpdatedState(onPreview)
    val visibleOffset by animateFloatAsState(if (dragging) displacement else 0f,
        if (dragging) snap() else spring(dampingRatio = 1f), label = "mode-card")
    Box(Modifier.fillMaxWidth().pointerInput(selected, distance, flingSpeed) {
        val velocityTracker = VelocityTracker()
        detectHorizontalDragGestures(
            onHorizontalDrag = { change, delta ->
                change.consume()
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                val atEdge = (selected.ordinal == 0 && displacement + delta > 0) ||
                    (selected.ordinal == AlarmMode.entries.lastIndex && displacement + delta < 0)
                displacement = (displacement + if (atEdge) delta * 0.2f else delta).coerceIn(-distance * 2, distance * 2)
                preview((selected.ordinal - (displacement / distance).coerceIn(-1f, 1f))
                    .coerceIn(0f, AlarmMode.entries.lastIndex.toFloat()))
            },
            onDragStart = { velocityTracker.resetTracking(); displacement = 0f; dragging = true },
            onDragCancel = { dragging = false; preview(null) },
            onDragEnd = {
                val velocity = velocityTracker.calculateVelocity().x
                val direction = when {
                    abs(velocity) >= flingSpeed -> if (velocity < 0) 1 else -1
                    abs(displacement) >= distance -> if (displacement < 0) 1 else -1
                    else -> 0
                }
                select(AlarmMode.entries[(selected.ordinal + direction).coerceIn(AlarmMode.entries.indices)])
                dragging = false
                preview(null)
            },
        )
    }) {
        Box(Modifier.graphicsLayer { translationX = visibleOffset }) { content() }
    }
}
