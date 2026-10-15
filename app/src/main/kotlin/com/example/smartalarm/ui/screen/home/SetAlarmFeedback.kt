package com.example.smartalarm.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smartalarm.bridge.SetUiState
import com.example.smartalarm.bridge.ClockChoice
import com.example.smartalarm.core.interaction.FailureKind
import com.example.smartalarm.core.interaction.SetHow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 设闹钟的反馈。等待 / 成功 / 失败 / 兜底已请求 是四种不同的画面，文案和图标都不同。 */
@Composable
internal fun SetAlarmFeedbackCard(
    state: SetUiState,
    onFallback: (LocalTime, String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    onChooseClock: (ClockChoice) -> Unit,
    onReconcile: () -> Unit,
    onViewClock: () -> Unit,
    onResolveManually: () -> Unit,
    modifier: Modifier = Modifier,
    allowFallback: Boolean = true,
) {
    if (state is SetUiState.Idle) return
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (state) {
                is SetUiState.Working -> WorkingBody(state)
                is SetUiState.Success -> SuccessBody(state, onDismiss)
                is SetUiState.Failed -> FailedBody(state, onFallback, onDismiss, allowFallback)
                is SetUiState.AwaitingUser -> {
                    Text("已请求交给 ${state.clockName} · ${state.target.format(clockFormat)}", style = MaterialTheme.typography.labelLarge)
                    Text("在时钟里保存了吗？返回相位本身不代表保存成功。")
                    Row {
                        TextButton(onClick = { onConfirm(true) }) { Text("保存了") }
                        TextButton(onClick = { onConfirm(false) }) { Text("没保存 / 已取消") }
                    }
                    TextButton(onClick = onViewClock) { Text("查看闹钟") }
                }
                is SetUiState.UserConfirmed -> {
                    Text("你已确认保存 ${state.target.format(clockFormat)}")
                    Text("已按你的确认记录。本次为用户确认，相位没有读取系统时钟结果。")
                    TextButton(onClick = onDismiss) { Text("知道了") }
                }
                is SetUiState.Choosing -> {
                    Text(state.detail)
                    state.choices.forEach { choice ->
                        OutlinedButton(onClick = { onChooseClock(choice) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${choice.label} · ${choice.identity.clockPackage}")
                        }
                    }
                    TextButton(onClick = onDismiss) { Text("取消选择") }
                }
                is SetUiState.Uncertain -> UncertainBody(state, onReconcile, onViewClock, onResolveManually)
                SetUiState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun WorkingBody(state: SetUiState.Working) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "正在设置 ${state.target.format(clockFormat)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Text(
        text = "正在核对时钟里的闹钟，请稍等",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SuccessBody(state: SetUiState.Success, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (state.late) "原请求稍后已核对 ${state.target.format(clockFormat)}" else "已核对设好 ${state.target.format(clockFormat)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("知道了") }
    }
    Text(
        text = when (state.how) {
            SetHow.REUSED_ENABLED -> "时钟里本来就有这一条，已确认是开启的。"
            SetHow.ENABLED_EXISTING -> "时钟里有这一条但是关着，已为你打开，没有新增。"
            SetHow.CREATED -> "时钟里没有这一条，已新建一条并确认开启。"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.listNote?.let {
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FailedBody(state: SetUiState.Failed, onFallback: (LocalTime, String) -> Unit, onDismiss: () -> Unit, allowFallback: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = failureTitle(state.kind),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("关闭") }
    }
    Text(
        text = failureAdvice(state.kind, state.target),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (state.detail.isNotBlank()) {
        Text(
            text = state.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!allowFallback) return
    Spacer(Modifier.height(4.dp))
    OutlinedButton(
        onClick = { onFallback(state.target, state.label) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("用系统方式新建（可能多一条）")
    }
}

@Composable
private fun UncertainBody(state: SetUiState.Uncertain, onReconcile: () -> Unit, onView: () -> Unit, onResolve: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Text("尚未确认 ${state.target.format(clockFormat)}", color = MaterialTheme.colorScheme.error)
    Text(state.detail)
    TextButton(onClick = onView) { Text("查看原时钟") }
    TextButton(onClick = onReconcile, enabled = !state.running) { Text("重新核对（不会新建）") }
    TextButton(onClick = { confirm = true }, enabled = !state.running) { Text("已手动核对，结束本次操作") }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("结束未决操作？") },
        text = { Text("请先在原时钟查看是否已有此时间的闹钟。原请求仍可能迟到；结束后再次新建可能重复。此操作不会记为设置成功，也不会删除时钟里的闹钟。") },
        confirmButton = { TextButton(onClick = { confirm = false; onResolve() }) { Text("我已核对并理解，结束") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("继续核对") } },
    )
}

internal fun failureTitle(kind: FailureKind): String = when (kind) {
    FailureKind.ROOT_DENIED -> "相位没有拿到 root 授权"
    FailureKind.UNAVAILABLE -> "没能连上时钟桥接"
    FailureKind.REJECTED -> "时钟没有接受这次设置"
    FailureKind.LIMIT_REACHED -> "时钟里的闹钟已满"
    FailureKind.UNSUPPORTED -> "这台手机暂不支持这种方式"
    FailureKind.UNKNOWN, FailureKind.WRITE_UNKNOWN -> "没能确认闹钟是否设好"
}

internal fun failureAdvice(kind: FailureKind, target: LocalTime): String {
    val time = target.format(clockFormat)
    return when (kind) {
        FailureKind.ROOT_DENIED -> "$time 没有设置。请在 root 管理器里允许相位，然后再点一次。"
        FailureKind.UNAVAILABLE -> "$time 没有设置。root 服务没有连上，稍后再试。"
        FailureKind.REJECTED -> "$time 没有设好。时钟没有接受这次请求。"
        FailureKind.LIMIT_REACHED -> "时钟里的闹钟数量已到上限，$time 没有新建。"
        FailureKind.WRITE_UNKNOWN -> "$time 的创建请求已经发出但没有拿到结果，时钟里可能已经有了。过几秒再点一次会先核对，不会重复新建。"
        FailureKind.UNSUPPORTED -> "$time 没有设置，相位没有向时钟发出任何请求。"
        FailureKind.UNKNOWN -> "$time 可能没有设上。相位不会在查不清的时候自己新建。"
    }
}
