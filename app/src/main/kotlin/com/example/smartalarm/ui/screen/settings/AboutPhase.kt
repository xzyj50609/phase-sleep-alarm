package com.example.smartalarm.ui.screen.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.smartalarm.ui.screen.home.appVersionText

@Composable
internal fun AboutPhase() {
    val context = LocalContext.current
    val version = remember { appVersionText(context) }
    var licenses by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("关于相位", style = MaterialTheme.typography.titleSmall)
            Text("相位 Phase", style = MaterialTheme.typography.bodyMedium)
            Text("当前版本 $version", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xzyj50609/phase-sleep-alarm"))) }
                catch (_: Exception) { Toast.makeText(context, "没有可用的浏览器，请安装或启用浏览器后再试。", Toast.LENGTH_LONG).show() }
            }) { Text("项目主页") }
            TextButton(onClick = {
                try {
                    licenses = listOf("LICENSE", "THIRD-PARTY-NOTICES.md", "Apache-2.0.txt", "libsu-LICENSE.txt")
                        .joinToString("\n\n") { name -> "$name\n\n" + context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }
                } catch (_: Exception) { Toast.makeText(context, "许可文字暂时无法读取，请重试。", Toast.LENGTH_LONG).show() }
            }) { Text("开源许可") }
        }
    }
    licenses?.let { content ->
        AlertDialog(onDismissRequest = { licenses = null }, title = { Text("开源许可") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(content, style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { licenses = null }) { Text("关闭") } })
    }
}
