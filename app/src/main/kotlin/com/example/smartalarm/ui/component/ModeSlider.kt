package com.example.smartalarm.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartalarm.core.model.AlarmMode
import com.example.smartalarm.ui.screen.home.chineseLabel
import kotlin.math.roundToInt

@Composable
fun ModeSlider(selected: AlarmMode, onSelect: (AlarmMode) -> Unit, previewPosition: Float? = null) {
    val modes = AlarmMode.entries
    val animation = remember { Animatable(selected.ordinal.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(selected.ordinal.toFloat()) }
    LaunchedEffect(selected, dragging, previewPosition) {
        if (!dragging) {
            if (previewPosition != null) animation.snapTo(previewPosition)
            else animation.animateTo(selected.ordinal.toFloat(), tween(240))
        }
    }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth().height(52.dp).systemGestureExclusion()
        .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp)) {
        val segmentWidth = maxWidth / modes.size
        val segmentPx = with(density) { segmentWidth.toPx() }
        // Fit the longest label using the real font metrics, including the system font scale.
        val fontSize = remember(segmentPx, density.fontScale, textMeasurer) {
            var size = 13f
            while (size > 6f && modes.any { textMeasurer.measure(it.chineseLabel(),
                    TextStyle(fontSize = size.sp, fontWeight = FontWeight.SemiBold), softWrap = false).size.width >
                    segmentPx - with(density) { 8.dp.toPx() } }) size -= 0.5f
            size.sp
        }
        val position = if (dragging) dragPosition else previewPosition ?: animation.value
        Box(Modifier.fillMaxSize().draggable(
            state = rememberDraggableState { delta ->
                dragPosition = (dragPosition + delta / segmentPx).coerceIn(0f, modes.lastIndex.toFloat())
            },
            orientation = Orientation.Horizontal,
            onDragStarted = { animation.stop(); dragPosition = animation.value; dragging = true },
            onDragStopped = {
                animation.snapTo(dragPosition)
                val destination = modes[dragPosition.roundToInt().coerceIn(modes.indices)]
                dragging = false
                onSelect(destination)
            },
        ).selectableGroup()) {
            Box(Modifier.offset { IntOffset((position * segmentPx).roundToInt(), 0) }
                .width(segmentWidth).fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primary))
            Row(Modifier.fillMaxSize()) {
                modes.forEach { mode ->
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected = mode == selected, role = Role.Tab, onClick = { onSelect(mode) }),
                        contentAlignment = Alignment.Center) {
                        val highlighted = if (dragging || previewPosition != null) position.roundToInt() == mode.ordinal else mode == selected
                        Text(mode.chineseLabel(), maxLines = 1, softWrap = false, fontSize = fontSize,
                            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
