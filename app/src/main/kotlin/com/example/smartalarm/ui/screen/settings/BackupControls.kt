package com.example.smartalarm.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.example.smartalarm.bridge.AlarmSetController
import com.example.smartalarm.data.LocalBackup
import com.example.smartalarm.ui.component.LinkRow

@Composable
internal fun BackupControls(onImported: () -> Unit) {
    val context = LocalContext.current
    val backup = remember { LocalBackup(context) }
    val controller = remember { AlarmSetController.get(context) }
    var message by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) message = runCatching {
            val data = backup.export().toByteArray()
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(data) }
            "已导出设置和记录。文件包含个人记录，请自行妥善保存。"
        }.getOrElse { "导出失败：${it.message}" }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() <= 2_000_000) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            require(bytes.size <= 2_000_000) { "备份文件过大" }
            val raw = bytes.toString(Charsets.UTF_8)
            backup.validate(raw)
            pending = raw
        }.onFailure { message = "未导入，原数据保留：${it.message}" }
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("数据备份", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 2.dp))
            LinkRow("导出设置与历史", onClick = { export.launch("相位-设置与历史.json") })
            LinkRow("从文件恢复设置与历史", enabled = !controller.isLocked,
                onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
            if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (pending != null) AlertDialog(
        onDismissRequest = { pending = null }, title = { Text("恢复这份备份？") },
        text = { Text("将替换当前设置和相位历史。导入前自动保留当前数据副本；不会改动系统时钟。备份中的未决操作不会自动重发。") },
        confirmButton = {
            TextButton(onClick = {
                if (controller.isLocked) message = "请先处理尚未确认的闹钟操作"
                else runCatching { backup.import(pending!!); controller.reloadPreferences(); onImported() }
                    .onFailure { message = "恢复失败，原数据已保留：${it.message}" }
                pending = null
            }) { Text("恢复") }
        }, dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } },
    )
}
