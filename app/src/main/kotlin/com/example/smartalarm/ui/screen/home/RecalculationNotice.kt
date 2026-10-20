package com.example.smartalarm.ui.screen.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smartalarm.bridge.AlarmSetController
import com.example.smartalarm.core.interaction.*
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
internal fun RecalculationNotice(controller: AlarmSetController, refreshAtMillis: Long, onRecalculate: () -> Unit) {
    val operation = controller.lastOperation()
    val visible = shouldOfferRecalculation(operation, refreshAtMillis)
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var reminder by remember { mutableStateOf<Pair<LocalTime, PreviousAlarmStatus>?>(null) }

    if (visible && operation != null) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text("躺了一个小时还没睡着，从现在重算。", style = MaterialTheme.typography.bodyMedium)
                TextButton(enabled = !checking, onClick = recalculate@{
                    if (checking) return@recalculate
                    val previous = controller.lastOperation() ?: operation
                    onRecalculate()
                    checking = true
                    scope.launch {
                        try {
                            val result = controller.checkPreviousAlarm(previous)
                            if (result != PreviousAlarmStatus.INACTIVE) reminder = LocalTime.of(previous.hour, previous.minute) to result
                        } finally { checking = false }
                    }
                }) { Text(if (checking) "正在检查…" else "从现在重算") }
            }
        }
    }

    reminder?.let { (time, status) ->
        val formatted = time.format(DateTimeFormatter.ofPattern("HH:mm"))
        AlertDialog(
            onDismissRequest = { reminder = null },
            title = { Text("留意之前的闹钟") },
            text = { Text(if (status == PreviousAlarmStatus.ACTIVE)
                "之前设的 $formatted 闹钟还开着。设好新闹钟后，不需要的话去时钟里把它关掉。"
                else "没能确认之前设的 $formatted 是否还开着，建议去时钟看一眼。") },
            confirmButton = { TextButton(onClick = { reminder = null; controller.showClock() }) { Text("查看闹钟") } },
            dismissButton = { TextButton(onClick = { reminder = null }) { Text("知道了") } },
        )
    }
}
