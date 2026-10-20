package com.example.smartalarm.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.smartalarm.ui.theme.PhaseFonts
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Real scroll-wheel picker built on LazyColumn snapping (the standard
 * pattern behind iOS-style pickers in Compose):
 *
 * - fling with inertia, snapping to the nearest item;
 * - tap any visible item to scroll straight to it;
 * - haptic tick whenever the centered value changes;
 * - a soft "lens" strip marks the selected row.
 *
 * Replaces the old drag-threshold stepper that required ~56dp of drag per
 * step with no inertia — the root cause of "滑不动、停不准".
 */
@Composable
fun <T> WheelPicker(
    items: List<T>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    visibleCount: Int = 3,
    itemHeight: Dp = 40.dp,
) {
    require(items.isNotEmpty())
    require(visibleCount > 0 && visibleCount % 2 == 1)

    val safeSelected = selectedIndex.coerceIn(items.indices)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeSelected)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }

    val centeredIndex by remember {
        derivedStateOf { listState.centeredItemIndex() }
    }

    // Commit the centered value while scrolling; tick on every change.
    LaunchedEffect(items) {
        snapshotFlow { listState.centeredItemIndex() }
            .distinctUntilChanged()
            .collect { index ->
                if (index in items.indices && index != selectedIndex) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    onSelectedChange(index)
                }
            }
    }

    // Follow external selection changes (e.g. restored preferences).
    LaunchedEffect(safeSelected) {
        if (!listState.isScrollInProgress && centeredIndex != safeSelected) {
            listState.scrollToItem(safeSelected)
        }
    }

    Box(
        modifier = modifier.height(itemHeight * visibleCount),
        contentAlignment = Alignment.Center,
    ) {
        // Selection lens behind the centered row.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )

        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = itemHeight * (visibleCount / 2)),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items.size) { index ->
                val isCentered = index == centeredIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clickable {
                            scope.launch { listState.animateScrollToItem(index) }
                        }
                        .graphicsLayer {
                            val distance = listState.distanceFromCenter(index, itemHeightPx)
                            alpha = (1f - 0.55f * distance).coerceIn(0.25f, 1f)
                            val scale = (1f - 0.10f * distance).coerceIn(0.8f, 1f)
                            scaleX = scale
                            scaleY = scale
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(items[index]),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = PhaseFonts.clock,
                        fontWeight = if (isCentered) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isCentered) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** Index of the item whose center is closest to the viewport center. */
private fun LazyListState.centeredItemIndex(): Int {
    val info = layoutInfo
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo
        .minByOrNull { abs(it.offset + it.size / 2 - viewportCenter) }
        ?.index ?: firstVisibleItemIndex
}

/** Distance of an item from the viewport center, in item heights. */
private fun LazyListState.distanceFromCenter(index: Int, itemHeightPx: Float): Float {
    val info = layoutInfo
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return 1f
    return abs(item.offset + item.size / 2f - viewportCenter) / itemHeightPx
}
