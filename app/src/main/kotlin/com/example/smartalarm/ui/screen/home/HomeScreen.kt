package com.example.smartalarm.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartalarm.ui.component.uiLabel
import com.example.smartalarm.ui.theme.PhaseFonts
import com.example.smartalarm.bridge.AlarmSetController
import com.example.smartalarm.bridge.SetUiState
import com.example.smartalarm.bridge.requestRecommendation
import com.example.smartalarm.core.model.AlarmMode
import com.example.smartalarm.core.interaction.DeliveryMode
import com.example.smartalarm.core.model.SleepCalculatorInput
import com.example.smartalarm.core.model.WakeUpRecommendation
import com.example.smartalarm.core.model.WakeOption
import com.example.smartalarm.core.schedule.SleepCalculator
import com.example.smartalarm.data.PrefsHelper
import com.example.smartalarm.ui.component.ModeSlider
import com.example.smartalarm.ui.component.SwipeModeCard
import com.example.smartalarm.ui.component.NightTimeline
import com.example.smartalarm.ui.component.QualityBadge
import com.example.smartalarm.ui.component.QualityDot
import com.example.smartalarm.ui.component.TimeEditDialog
import com.example.smartalarm.ui.component.TimeWheelPicker
import com.example.smartalarm.ui.component.WheelPicker
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val delayOptions = listOf(5, 10, 15, 20, 25, 30, 35, 40, 45, 60)

@Composable
fun HomeScreen(
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val setController = remember { AlarmSetController.get(context) }
    val setState = setController.state
    LaunchedEffect(Unit) { setController.prepare() }

    // 反馈卡片在按钮下方，失败时它的“用系统方式新建”按钮可能被挤出屏幕；状态一变就把整张卡片滚进可视区域
    val feedbackInView = remember { BringIntoViewRequester() }
    LaunchedEffect(setState::class) {
        if (setState !is SetUiState.Idle) {
            delay(120) // 等卡片按新状态完成布局
            feedbackInView.bringIntoView()
        }
    }
    val calculator = remember { SleepCalculator() }
    val prefs = remember { PrefsHelper(context) }

    // The app answers "if I lie down NOW" — keep "now" ticking by the minute.
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay((61 - LocalDateTime.now().second) * 1000L)
            now = LocalTime.now()
        }
    }

    var pinnedBedTime by remember { mutableStateOf<LocalTime?>(null) }
    val bedTime = pinnedBedTime ?: now

    var selectedDelay by remember { mutableIntStateOf(prefs.lastDelay) }
    var selectedMode by remember { mutableStateOf(prefs.lastMode) }
    var modePreview by remember { mutableStateOf<Float?>(null) }

    // Calibration parameters live on the settings screen; this screen leaves
    // composition while it is open, so a plain read stays current on return.
    val cycleMinutes = remember { prefs.cycleMinutes }
    val resettleMinutes = remember { prefs.resettleMinutes }
    val classTarget = remember { prefs.classTarget }
    val morningTarget = remember { prefs.morningTarget }

    // 必醒时刻: opt-in, edited inline with wheels, both halves remembered.
    var punchTime by remember { mutableStateOf(prefs.punchTime) }
    var punchEnabled by remember { mutableStateOf(prefs.punchEnabled) }

    var editingBedtime by remember { mutableStateOf(false) }

    fun calculateFor(mode: AlarmMode) = calculator.calculate(
        SleepCalculatorInput(
            bedTime = bedTime,
            fallAsleepMinutes = selectedDelay,
            mode = mode,
            cycleMinutes = cycleMinutes,
            punchTime = punchTime.takeIf { punchEnabled },
            resettleMinutes = resettleMinutes,
            classTarget = classTarget,
            morningTarget = morningTarget,
        ),
    )
    val output = remember(
        bedTime, selectedDelay, selectedMode, punchEnabled,
        cycleMinutes, resettleMinutes, punchTime, classTarget, morningTarget,
    ) { calculateFor(selectedMode) }

    // User's pick among candidates, tracked by stable label so it survives
    // the minute tick; reset when the mode changes.
    var chosenLabel by remember(selectedMode) { mutableStateOf<String?>(null) }
    val chosen = output.alternatives.firstOrNull { it.label == chosenLabel }
        ?: output.recommended

    var timeChanged by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                now = LocalTime.now()
                pinnedBedTime = null
                chosenLabel = null
                timeChanged = true
                setController.dismiss() // Only cancels an unsent selection; durable pending operations stay locked.
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) now = LocalTime.now()
        }
        lifecycle?.addObserver(observer)
        onDispose { context.unregisterReceiver(receiver); lifecycle?.removeObserver(observer) }
    }

    fun requestAlarm(recommendation: WakeUpRecommendation, option: WakeOption? = null) {
        if (timeChanged) { timeChanged = false; return }
        prefs.lastDelay = selectedDelay
        prefs.lastMode = selectedMode
        setController.requestRecommendation(recommendation, bedTime, selectedMode, option)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TopBar(
            onOpenHistory = onOpenHistory,
            onOpenSettings = onOpenSettings,
        )

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (setController.mode == DeliveryMode.STANDARD) "普通模式 · ${setController.clockName}" else "root 模式（实验性）· ColorOS 时钟",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { setController.showClock() }) { Text("查看闹钟") }
        }
        if (setController.checkingRoot) Text("正在等待 Root 授权并核验，暂不发送闹钟请求。")
        setController.rootModeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (timeChanged) TextButton(onClick = { timeChanged = false }) {
            Text("系统时间已变化，已重新计算；点此确认后再设置")
        }

        ModeSlider(
            selected = selectedMode,
            onSelect = { selectedMode = it },
            previewPosition = modePreview,
        )

        RecalculationNotice(setController, System.currentTimeMillis()) {
            pinnedBedTime = null
            now = LocalTime.now()
            chosenLabel = null
        }

        if (setController.requiresManualTime) Text(
            "这版 ColorOS 时钟需手动填时间。点下方按钮打开新建页后，请设为 ${chosen.wakeUpTime.format(timeFormatter)} 并保存。",
            style = MaterialTheme.typography.bodySmall,
        )
        SwipeModeCard(selectedMode, { selectedMode = it }, { modePreview = it }) { mode ->
            // 滑出中的旧卡片按它自己的模式显示，并且不能再设闹钟：设闹钟只认当前选中模式的那张。
            val current = mode == selectedMode
            val cardOutput = if (current) output else calculateFor(mode)
            val cardChosen = if (current) chosen else cardOutput.recommended
            HeroCard(
                recommendation = cardChosen,
                output = cardOutput,
                bedTime = bedTime,
                fallAsleepMinutes = selectedDelay,
                cycleMinutes = cycleMinutes,
                punchTime = punchTime.takeIf { punchEnabled },
                isSetting = setState is SetUiState.Working,
                submitBlocked = setController.isLocked,
                onCreateAlarm = { if (current) requestAlarm(cardChosen) },
                rootMode = setController.mode == DeliveryMode.ROOT,
                onChooseOption = { if (current) requestAlarm(cardChosen, it) },
            )
        }

        SetAlarmFeedbackCard(
            state = setState,
            onFallback = { time, label -> setController.requestSystemCreate(time, label) },
            onDismiss = { setController.dismiss() },
            onConfirm = setController::confirmStandard,
            onChooseClock = setController::selectClock,
            onReconcile = setController::reconcile,
            onViewClock = { setController.showClock() },
            onResolveManually = setController::resolveManually,
            modifier = Modifier.bringIntoViewRequester(feedbackInView),
            allowFallback = setController.mode == DeliveryMode.ROOT,
        )

        if (output.alternatives.size > 1) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("其他睡法")
            if (setController.mode == DeliveryMode.ROOT) RootAlternativesCard(
                output = output,
                chosen = chosen,
                enabled = !setController.isLocked,
                onChoose = { chosenLabel = it.label },
                onRequest = { recommendation, option -> requestAlarm(recommendation, option) },
            ) else AlternativesCard(
                output = output,
                chosen = chosen,
                onChoose = { chosenLabel = it.label },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("今晚的条件")
            InputsCard(
                bedTime = bedTime,
                isPinned = pinnedBedTime != null,
                onEditBedtime = { editingBedtime = true },
                onUseNow = { pinnedBedTime = null },
                selectedDelay = selectedDelay,
                onDelayChange = { selectedDelay = it },
                punchTime = punchTime,
                onPunchTimeChange = {
                    punchTime = it
                    prefs.punchTime = it
                },
                punchEnabled = punchEnabled,
                onPunchEnabledChange = {
                    punchEnabled = it
                    prefs.punchEnabled = it
                },
                punchIgnoredByMode = selectedMode == AlarmMode.NORMAL || selectedMode == AlarmMode.NAP,
            )
        }

        Spacer(Modifier.height(16.dp))
    }

    if (editingBedtime) {
        TimeEditDialog(
            title = "计划几点睡",
            initial = bedTime,
            onConfirm = {
                pinnedBedTime = it
                editingBedtime = false
            },
            onDismiss = { editingBedtime = false },
        )
    }

}

private val weekdayNames = mapOf(
    DayOfWeek.MONDAY to "周一",
    DayOfWeek.TUESDAY to "周二",
    DayOfWeek.WEDNESDAY to "周三",
    DayOfWeek.THURSDAY to "周四",
    DayOfWeek.FRIDAY to "周五",
    DayOfWeek.SATURDAY to "周六",
    DayOfWeek.SUNDAY to "周日",
)

// ── Sub-components ──

@Composable
private fun TopBar(
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 左右各一个图标、中间是“相位”：对称的页眉，时间不再挤在左上角（它已经在“计划几点睡”里了）。
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOpenHistory) {
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = "睡眠记录",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "相位",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = PhaseFonts.clock,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            letterSpacing = 8.sp,
            color = MaterialTheme.colorScheme.primary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = "设置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeroCard(
    recommendation: WakeUpRecommendation,
    output: com.example.smartalarm.core.model.SleepCalculatorOutput,
    bedTime: LocalTime,
    fallAsleepMinutes: Int,
    cycleMinutes: Int,
    punchTime: LocalTime?,
    isSetting: Boolean,
    submitBlocked: Boolean,
    onCreateAlarm: () -> Unit,
    rootMode: Boolean,
    onChooseOption: (WakeOption) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "建议这时候醒",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                QualityBadge(recommendation.quality)
            }

            Text(
                text = recommendation.wakeUpTime.format(timeFormatter),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = recommendation.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = PhaseFonts.clock,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // 周期类标题里已经写了总时长，不再重复；必醒、回笼这类标题没写，才补一行。
                if (!recommendation.label.contains(formatDuration(recommendation.sleepMinutes))) {
                    Text(
                        text = "全晚睡 ${formatDuration(recommendation.sleepMinutes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = recommendation.reason,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = PhaseFonts.clock,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            NightTimeline(
                bedTime = bedTime,
                sleepStart = bedTime.plusMinutes(fallAsleepMinutes.toLong()),
                wakeTime = recommendation.wakeUpTime,
                cycleMinutes = cycleMinutes,
                punchTime = punchTime,
                resleepStart = output.nightPlan?.resleepStart,
            )

            output.nightPlan?.let { plan ->
                Text(
                    text = plan.punchAdvice + (plan.bedtimeShiftMinutes?.let { shift ->
                        val direction = if (shift > 0) "推迟" else "提前"
                        "今晚$direction ${kotlin.math.abs(shift)} 分钟上床，必醒正好落在周期边缘。"
                    }?.let { " $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = onCreateAlarm,
                enabled = !submitBlocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                if (isSetting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Alarm,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(if (isSetting) "正在设置…" else if (submitBlocked) "请先确认上次操作" else "设为系统闹钟", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (rootMode && recommendation.wakeOptions.isNotEmpty()) {
                WakeOptionButtons(recommendation.wakeOptions, !submitBlocked, onChooseOption)
            }
            // 说明放在设闹钟按钮之后：按钮要在不滚动的情况下就能点到。
            if (!rootMode) TimingUncertaintyHint(recommendation)
        }
    }
}

@Composable
private fun AlternativesCard(
    output: com.example.smartalarm.core.model.SleepCalculatorOutput,
    chosen: WakeUpRecommendation,
    onChoose: (WakeUpRecommendation) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            output.alternatives.forEachIndexed { index, recommendation ->
                val isChosen = recommendation == chosen
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                        // 选中行是圆角色块，不是贴满卡片宽度的直角条
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isChosen) MaterialTheme.colorScheme.surfaceVariant
                            else androidx.compose.ui.graphics.Color.Transparent,
                        )
                        .clickable { onChoose(recommendation) }
                        .padding(horizontal = 10.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = recommendation.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = PhaseFonts.clock,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        RecommendationMeta(recommendation, recommendation == output.recommended)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = recommendation.wakeUpTime.format(timeFormatter),
                        style = MaterialTheme.typography.headlineMedium,
                        fontSize = 22.sp,
                        color = if (isChosen) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                if (index < output.alternatives.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 18.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/**
 * 候选行的第二行：醒来状态（圆点加文字，不只靠颜色）、必要时的“全晚睡”、推荐标记。
 * 标题里已经写了总时长的周期类候选不再重复一遍。
 */
@Composable
internal fun RecommendationMeta(recommendation: WakeUpRecommendation, recommended: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        QualityDot(recommendation.quality)
        Text(
            text = buildString {
                append(recommendation.quality.uiLabel())
                if (!recommendation.label.contains(formatDuration(recommendation.sleepMinutes))) {
                    append(" · 全晚睡 ").append(formatDuration(recommendation.sleepMinutes))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 空间不够时让这行字自己换行，标签始终保留完整宽度
            modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
        )
        if (recommended) RecommendedChip()
    }
}

@Composable
internal fun RecommendedChip() {
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = "推荐",
            maxLines = 1,
            softWrap = false,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun InputsCard(
    bedTime: LocalTime,
    isPinned: Boolean,
    onEditBedtime: () -> Unit,
    onUseNow: () -> Unit,
    selectedDelay: Int,
    onDelayChange: (Int) -> Unit,
    punchTime: LocalTime,
    onPunchTimeChange: (LocalTime) -> Unit,
    punchEnabled: Boolean,
    onPunchEnabledChange: (Boolean) -> Unit,
    punchIgnoredByMode: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEditBedtime)
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "计划几点睡",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = bedTime.format(timeFormatter),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!isPinned) {
                            // 时间是主角，“跟随现在”只是它的状态说明
                            Text(
                                text = "跟随现在 · " + (weekdayNames[LocalDateTime.now().dayOfWeek] ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 10.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
                if (isPinned) {
                    TextButton(onClick = onUseNow) {
                        Text("回到现在")
                    }
                } else {
                    Text(
                        text = "点击修改",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "预计多久睡着",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                WheelPicker(
                    items = delayOptions,
                    selectedIndex = delayOptions.indexOf(selectedDelay).coerceAtLeast(0),
                    onSelectedChange = { onDelayChange(delayOptions[it]) },
                    label = { "$it 分钟" },
                    modifier = Modifier.width(150.dp),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "必醒时刻",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when {
                            !punchEnabled -> "中途有事必须醒一次再回笼时打开"
                            punchIgnoredByMode -> "当前模式不看必醒；其他模式按两段式计算"
                            else -> "${punchTime.format(timeFormatter)} 必醒，醒后按两段式计算"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = punchEnabled,
                    onCheckedChange = onPunchEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    ),
                )
            }

            AnimatedVisibility(visible = punchEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "几点必须醒",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TimeWheelPicker(
                        value = punchTime,
                        onValueChange = onPunchTimeChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

// ── Helpers ──

/** 版本号从系统里“已安装的这份包”读，不用编译期常量：用户念出来的就是实际在跑的包。 */
internal fun appVersionText(context: android.content.Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    "v${info.versionName} ($code)"
} catch (_: Exception) {
    "版本未知"
}

internal fun AlarmMode.chineseLabel(): String = when (this) {
    AlarmMode.CLASS -> "上课"
    AlarmMode.BREAKFAST -> "保早餐"
    AlarmMode.CATCH_UP -> "爽睡"
    AlarmMode.NORMAL -> "正常"
    AlarmMode.NAP -> "小睡"
}

internal fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m 分钟"
        m == 0 -> "$h 小时"
        else -> "$h 小时 $m 分"
    }
}
