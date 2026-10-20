package com.example.smartalarm.ui.screen.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartalarm.core.model.AlarmMode
import com.example.smartalarm.core.model.WakeQuality
import com.example.smartalarm.data.SleepLogEntry
import com.example.smartalarm.data.SleepLogStore
import com.example.smartalarm.ui.component.QualityDot
import com.example.smartalarm.ui.screen.home.chineseLabel
import com.example.smartalarm.ui.screen.home.formatDuration
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

private enum class HistoryView(val label: String) {
    WEEK("周"),
    MONTH("月"),
    HEAT("热力"),
}

/**
 * Sleep diary, v9: the log drawn as charts first — a week or month of
 * moonlight columns, or a 16-week heatmap — with the raw records listed
 * below the chart for the visible range.
 */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries = remember { SleepLogStore(context).loadAll() }
    val stats = remember(entries) { dailyStats(entries) }
    val today = LocalDate.now()

    var view by remember { mutableStateOf(HistoryView.WEEK) }
    var weekStart by remember { mutableStateOf(today.with(DayOfWeek.MONDAY)) }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(today.takeIf { stats.containsKey(it) }) }

    val rangeDays = when (view) {
        HistoryView.WEEK -> (0..6L).map { weekStart.plusDays(it) }
        HistoryView.MONTH -> (1..month.lengthOfMonth()).map { month.atDay(it) }
        HistoryView.HEAT -> {
            val firstMonday = today.with(DayOfWeek.MONDAY).minusWeeks((HEATMAP_WEEKS - 1).toLong())
            generateSequence(firstMonday) { it.plusDays(1) }
                .takeWhile { !it.isAfter(today) }
                .toList()
        }
    }
    val rangeEntries = entries.filter { it.date in rangeDays.first()..rangeDays.last() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "睡眠记录",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            ViewToggle(view) { view = it }
        }

        if (entries.isEmpty()) {
            EmptyState()
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            item(key = "record-meaning") {
                Text("这里记录计划睡眠与闹钟确认，不是手环测得的实际睡眠。", style = MaterialTheme.typography.bodySmall,
                    fontFamily = com.example.smartalarm.ui.theme.PhaseFonts.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item(key = "nav") {
                RangeNav(
                    view = view,
                    weekStart = weekStart,
                    month = month,
                    today = today,
                    onWeekChange = { weekStart = it },
                    onMonthChange = { month = it },
                )
            }

            item(key = "chart") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
                        RangeStats(rangeDays, stats)

                        when (view) {
                            HistoryView.WEEK -> SleepBarChart(
                                days = rangeDays,
                                stats = stats,
                                selectedDate = selectedDate,
                                onSelect = { selectedDate = it },
                                xLabel = { weekdayName(it).removePrefix("周") },
                            )
                            HistoryView.MONTH -> SleepBarChart(
                                days = rangeDays,
                                stats = stats,
                                selectedDate = selectedDate,
                                onSelect = { selectedDate = it },
                                xLabel = { date ->
                                    date.dayOfMonth
                                        .takeIf { it == 1 || it % 5 == 0 }
                                        ?.toString()
                                },
                            )
                            HistoryView.HEAT -> SleepHeatmap(
                                stats = stats,
                                selectedDate = selectedDate,
                                onSelect = { selectedDate = it },
                            )
                        }

                        SelectedDayCaption(selectedDate, stats)
                    }
                }
            }

            if (rangeEntries.isNotEmpty()) {
                item(key = "list-header") {
                    Text(
                        text = "这段时间的记录",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                item(key = "list") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column {
                            rangeEntries.forEachIndexed { index, entry ->
                                EntryRow(entry)
                                if (index < rangeEntries.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "bottom-space") { Spacer(Modifier.padding(4.dp)) }
        }
    }
}

// ── Chart furniture ──

@Composable
private fun ViewToggle(current: HistoryView, onSelect: (HistoryView) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
    ) {
        HistoryView.entries.forEach { value ->
            val isSelected = value == current
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    )
                    .selectable(selected = isSelected, role = androidx.compose.ui.semantics.Role.Tab) { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = value.label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun RangeNav(
    view: HistoryView,
    weekStart: LocalDate,
    month: YearMonth,
    today: LocalDate,
    onWeekChange: (LocalDate) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
) {
    val title: String
    val onPrev: (() -> Unit)?
    val onNext: (() -> Unit)?
    when (view) {
        HistoryView.WEEK -> {
            val weekEnd = weekStart.plusDays(6)
            title = "${weekStart.monthValue}月${weekStart.dayOfMonth}日 – " +
                "${weekEnd.monthValue}月${weekEnd.dayOfMonth}日"
            onPrev = { onWeekChange(weekStart.minusWeeks(1)) }
            onNext = { onWeekChange(weekStart.plusWeeks(1)) }
                .takeIf { weekStart.plusWeeks(1) <= today }
        }
        HistoryView.MONTH -> {
            title = "${month.year}年${month.monthValue}月"
            onPrev = { onMonthChange(month.minusMonths(1)) }
            onNext = { onMonthChange(month.plusMonths(1)) }
                .takeIf { month < YearMonth.from(today) }
        }
        HistoryView.HEAT -> {
            title = "近 $HEATMAP_WEEKS 周"
            onPrev = null
            onNext = null
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavArrow(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上一段", onPrev)
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        NavArrow(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下一段", onNext)
    }
}

@Composable
private fun NavArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: (() -> Unit)?,
) {
    IconButton(onClick = onClick ?: {}, enabled = onClick != null) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (onClick != null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
    }
}

@Composable
private fun RangeStats(days: List<LocalDate>, stats: Map<LocalDate, DayStat>) {
    val dayStats = days.mapNotNull { stats[it] }.filter { it.totalMinutes > 0 }
    val avgSleep = dayStats.takeIf { it.isNotEmpty() }
        ?.let { list -> list.sumOf { it.totalMinutes } / list.size }
    val avgBed = averageBedTime(dayStats.mapNotNull { it.bedTime })

    Row(modifier = Modifier.fillMaxWidth()) {
        StatBlock(
            label = "平均每晚",
            value = avgSleep?.let { formatDuration(it) } ?: "—",
            modifier = Modifier.weight(1f),
        )
        StatBlock(
            label = "平均准备睡",
            value = avgBed?.let { format(it) } ?: "—",
            modifier = Modifier.weight(1f),
        )
        StatBlock(
            label = "有记录",
            value = "${dayStats.size} 天",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = com.example.smartalarm.ui.theme.PhaseFonts.clock,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SelectedDayCaption(selectedDate: LocalDate?, stats: Map<LocalDate, DayStat>) {
    val text = selectedDate?.let { date ->
        val prefix = "${date.monthValue}月${date.dayOfMonth}日 ${weekdayName(date)}"
        val stat = stats[date]
        if (stat == null || stat.totalMinutes == 0) {
            "$prefix · 无记录"
        } else {
            buildString {
                append("$prefix · 睡 ${formatDuration(stat.totalMinutes)}")
                if (stat.napMinutes > 0) {
                    append("（夜间 ${formatDuration(stat.nightMinutes)} + 小睡 ${formatDuration(stat.napMinutes)}）")
                }
                stat.bedTime?.let { append(" · ${format(it)} 上床") }
            }
        }
    } ?: "点一根柱子或一个格子看当天详情"

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

// ── Record list (unchanged from v8) ──

@Composable
private fun EntryRow(entry: SleepLogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(96.dp)) {
            Text(
                text = "${entry.date.monthValue}月${entry.date.dayOfMonth}日",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${weekdayName(entry.date)} · ${modeLabel(entry.modeName)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${format(entry.bedTime)} → ${format(entry.wakeTime)}",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "计划 ${formatDuration(entry.sleepMinutes)} · " + when (entry.confirmationSource) {
                    "verified" -> "时钟已核对"
                    "user-confirmed" -> "用户确认"
                    else -> "旧版记录"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        qualityOrNull(entry.qualityName)?.let { QualityDot(it) }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "还没有记录",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.padding(4.dp))
        Text(
            text = "确认一次闹钟设置后，这里会记住\n计划几点准备睡、几点起。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Helpers ──

private fun weekdayName(date: LocalDate): String = when (date.dayOfWeek) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

private fun modeLabel(name: String): String =
    try { AlarmMode.valueOf(name).chineseLabel() } catch (_: IllegalArgumentException) { name }

private fun qualityOrNull(name: String): WakeQuality? =
    try { WakeQuality.valueOf(name) } catch (_: IllegalArgumentException) { null }

private fun format(time: LocalTime): String =
    "%02d:%02d".format(time.hour, time.minute)
