package com.example.smartalarm.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.smartalarm.core.model.AlarmMode
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 卡片横向拖动这么远，上方滑块块刚好走完一格。 */
private const val STEP_DP = 144f
/** 拖过一格的这个比例后松手才切换，也就是滑块块过了两格之间的中点。 */
private const val COMMIT_FRACTION = 0.5f
/** 快甩要同时满足：速度够快，并且至少拖过一格的这个比例，轻轻一碰不算。 */
private const val FLING_SPEED_DP = 1000f
private const val FLING_MIN_FRACTION = 0.28f
/** 已经在第一个/最后一个模式时继续往外拖，手指走多少卡片只走这个比例。 */
private const val EDGE_RESISTANCE = 0.25f
private const val EXIT_MS = 110
private const val ENTER_MS = 220

/**
 * 推荐卡片：左右拖动时跟手，并通过 [onPreview] 让上方滑块按同一进度走；松手后
 * 拖得不够就两边一起回原位，够了就旧卡片滑出淡出、新模式的卡片从另一侧滑入淡入。
 * 竖向滑动仍归页面滚动。[content] 按传入的模式画卡片：滑出的旧卡片必须还显示它自己的模式。
 */
@Composable
fun SwipeModeCard(
    selected: AlarmMode,
    onSelect: (AlarmMode) -> Unit,
    onPreview: (Float?) -> Unit,
    content: @Composable (AlarmMode) -> Unit,
) {
    val density = LocalDensity.current
    val step = with(density) { STEP_DP.dp.toPx() }
    val flingSpeed = with(density) { FLING_SPEED_DP.dp.toPx() }
    val scope = rememberCoroutineScope()
    // 卡片离开原位的程度：-1..1，负数是向左（下一个模式）。手指按着时用 drag，其余时候用 progress。
    val progress = remember { Animatable(0f) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var shown by remember { mutableStateOf(selected) }
    val select by rememberUpdatedState(onSelect)
    val preview by rememberUpdatedState(onPreview)
    val last = AlarmMode.entries.lastIndex

    // 模式一变（不论是卡片拖的还是点/拖上方滑块），都走同一套：旧卡片出、换内容、新卡片入。
    LaunchedEffect(selected) {
        if (shown == selected) return@LaunchedEffect
        val outgoing = if (selected.ordinal > shown.ordinal) -1f else 1f
        try {
            progress.animateTo(outgoing, tween(EXIT_MS, easing = FastOutLinearInEasing))
        } finally {
            shown = selected
        }
        progress.snapTo(-outgoing)
        progress.animateTo(0f, tween(ENTER_MS, easing = LinearOutSlowInEasing))
    }

    Box(Modifier.fillMaxWidth().pointerInput(selected, step, flingSpeed) {
        val velocityTracker = VelocityTracker()
        fun finish(velocityX: Float, cancelled: Boolean) {
            val p = drag ?: return
            val travelled = abs(p)
            val direction = when {
                cancelled -> 0
                abs(velocityX) >= flingSpeed && travelled >= FLING_MIN_FRACTION && (velocityX < 0) == (p < 0) ->
                    if (p < 0) 1 else -1
                travelled >= COMMIT_FRACTION -> if (p < 0) 1 else -1
                else -> 0
            }
            val target = selected.ordinal + direction
            scope.launch {
                // 先把手指位置交给动画值，再放开，卡片不会在交接那一帧跳动。
                progress.snapTo(p)
                drag = null
                preview(null)
                if (direction != 0 && target in 0..last) select(AlarmMode.entries[target])
                else progress.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            }
        }
        detectHorizontalDragGestures(
            onDragStart = {
                velocityTracker.resetTracking()
                drag = progress.value
                scope.launch { progress.stop() }
            },
            onHorizontalDrag = { change, delta ->
                change.consume()
                velocityTracker.addPointerInputChange(change) // 带上同一帧里合并的历史点，速度才准
                val current = drag ?: 0f
                val move = delta / step
                val atEdge = (selected.ordinal == 0 && current + move > 0f) || (selected.ordinal == last && current + move < 0f)
                val updated = (current + if (atEdge) move * EDGE_RESISTANCE else move).coerceIn(-1f, 1f)
                drag = updated
                preview((selected.ordinal - updated).coerceIn(0f, last.toFloat()))
            },
            onDragCancel = { finish(0f, cancelled = true) },
            onDragEnd = { finish(velocityTracker.calculateVelocity().x, cancelled = false) },
        )
    }) {
        // 平移放在 animateContentSize 外面：它自带裁剪，放里面会把滑出的卡片切掉一截。
        Box(Modifier.graphicsLayer {
            val p = drag ?: progress.value
            translationX = p * step
            alpha = (1f - abs(p)).coerceIn(0f, 1f)
        }.animateContentSize(tween(ENTER_MS))) { content(shown) }
    }
}
