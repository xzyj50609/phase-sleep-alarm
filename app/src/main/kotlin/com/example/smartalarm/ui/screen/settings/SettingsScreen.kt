package com.example.smartalarm.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.smartalarm.data.PrefsHelper
import com.example.smartalarm.bridge.AlarmSetController
import com.example.smartalarm.core.interaction.DeliveryMode
import com.example.smartalarm.ui.component.SegmentedChoice
import com.example.smartalarm.ui.component.TimeEditDialog
import com.example.smartalarm.ui.theme.PhaseFonts
import com.example.smartalarm.ui.component.WheelPicker
import java.time.LocalTime

private val cycleOptions = (80..120).toList()
private val resettleOptions = listOf(5, 10, 15, 20)

/**
 * Calibration parameters as a full screen of their own — v9 dropped the
 * bottom sheet so nothing stacks on top of the home page. Cycle length is a
 * 1-minute-step wheel (80-120): people really do run 90 or 100, never a
 * hardcoded "90-minute rule".
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PrefsHelper(context) }
    val controller = remember { AlarmSetController.get(context) }
    val operationLocked = controller.state.let { controller.isLocked }

    var confirmRoot by remember { mutableStateOf(false) }
    var cycleMinutes by remember { mutableIntStateOf(prefs.cycleMinutes) }
    var resettleMinutes by remember { mutableIntStateOf(prefs.resettleMinutes) }
    var classTarget by remember { mutableStateOf(prefs.classTarget) }
    var morningTarget by remember { mutableStateOf(prefs.morningTarget) }

    if (confirmRoot) AlertDialog(
        onDismissRequest = { confirmRoot = false },
        title = { Text("开启实验性 root 模式？") },
        text = { Text("这个模式会用 Root 权限直接读写系统时钟里的闹钟，属于实验性功能。继续后会检查兼容性并申请授权，只有核验成功才会切换。如果管理器不弹窗，请到超级用户管理器中为本应用授权，再回来重试。") },
        confirmButton = { TextButton(onClick = { confirmRoot = false; controller.selectMode(DeliveryMode.ROOT) }) { Text("申请授权") } },
        dismissButton = { TextButton(onClick = { confirmRoot = false }) { Text("取消") } },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                text = "设置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("闹钟设置方式", style = MaterialTheme.typography.titleSmall)
                SegmentedChoice(
                    options = listOf(DeliveryMode.STANDARD, DeliveryMode.ROOT),
                    selected = controller.mode,
                    label = { if (it == DeliveryMode.STANDARD) "普通" else "root（实验性）" },
                    onSelect = {
                        if (it == DeliveryMode.STANDARD) controller.selectMode(DeliveryMode.STANDARD)
                        else if (controller.mode != DeliveryMode.ROOT) confirmRoot = true
                    },
                    isEnabled = {
                        if (it == DeliveryMode.STANDARD) !operationLocked || controller.checkingRoot else !operationLocked
                    },
                )
                if (controller.checkingRoot) NoteText("正在等待 Root 授权并核验，当前仍为普通模式。可点“普通”取消。")
                controller.rootModeError?.let { NoteText(it, MaterialTheme.colorScheme.error) }
                NoteText(if (controller.mode == DeliveryMode.STANDARD)
                    "无需特殊权限。交给所选时钟，保存后回相位确认。"
                    else "实验性功能，普通用户请勿开启。需要 root 授权，只适配 Android 16、ColorOS 时钟 16.19.14、简体中文（中国）主用户；会通过 root 直接读写系统时钟的闹钟，当前版本没有在真实 root 手机上回归。")
                if (operationLocked && !controller.checkingRoot) NoteText("有操作尚未确认，请先回首页核对。")
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !operationLocked) { controller.chooseClock(); onBack() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("选择普通模式时钟", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(controller.clockName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                WheelRow(
                    label = "睡眠周期长度",
                    hint = "因人而异，80–120 分钟逐分可调",
                    options = cycleOptions,
                    selected = cycleMinutes,
                    format = { "$it 分钟" },
                    onSelect = {
                        cycleMinutes = it
                        prefs.cycleMinutes = it
                    },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                WheelRow(
                    label = "必醒后多久重新睡着",
                    hint = "回笼觉的入睡延迟",
                    options = resettleOptions,
                    selected = resettleMinutes,
                    format = { "$it 分钟" },
                    onSelect = {
                        resettleMinutes = it
                        prefs.resettleMinutes = it
                    },
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                TimeParamRow("上课日最晚起床", classTarget) {
                    classTarget = it
                    prefs.classTarget = it
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                TimeParamRow("平日目标起床", morningTarget) {
                    morningTarget = it
                    prefs.morningTarget = it
                }
            }
        }

        BackupControls(onImported = onBack)

        Text(
            text = "周期长度可以用手环校准：连续记几晚，看深浅睡交替的间隔大约多少分钟。" +
                "必醒时刻的开关和时间在首页「今晚的条件」里调。",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = PhaseFonts.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AboutPhase()
    }
}

@Composable
private fun NoteText(text: String, color: Color = Color.Unspecified) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = PhaseFonts.body,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
    )
}

@Composable
private fun WheelRow(
    label: String,
    hint: String,
    options: List<Int>,
    selected: Int,
    format: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        WheelPicker(
            items = options,
            selectedIndex = options.indexOf(selected).coerceAtLeast(0),
            onSelectedChange = { onSelect(options[it]) },
            label = format,
            modifier = Modifier.width(120.dp),
        )
    }
}

@Composable
private fun TimeParamRow(
    label: String,
    value: LocalTime,
    onChange: (LocalTime) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { editing = true }) {
            Text(
                text = "%02d:%02d".format(value.hour, value.minute),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = PhaseFonts.clock,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (editing) {
        TimeEditDialog(
            title = label,
            initial = value,
            onConfirm = {
                onChange(it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}
