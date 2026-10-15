package com.example.smartalarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.smartalarm.ui.screen.history.HistoryScreen
import com.example.smartalarm.ui.screen.home.HomeScreen
import com.example.smartalarm.ui.screen.settings.SettingsScreen
import com.example.smartalarm.ui.theme.SmartAlarmTheme

private const val SCREEN_HOME = "home"
private const val SCREEN_HISTORY = "history"
private const val SCREEN_SETTINGS = "settings"

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        com.example.smartalarm.bridge.AlarmSetController.get(this).restore()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val restoreFailure = runCatching {
            com.example.smartalarm.data.LocalBackup(this).recoverInterruptedImport()
        }.exceptionOrNull()
        if (restoreFailure != null) {
            setContent { androidx.compose.material3.Text("上次导入的恢复尚未完成，已停止闹钟操作。请保留应用数据并反馈：${restoreFailure.javaClass.simpleName}") }
            return
        }
        enableEdgeToEdge()
        setContent {
            SmartAlarmTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var screen by rememberSaveable { mutableStateOf(SCREEN_HOME) }

    when (screen) {
        SCREEN_HISTORY -> {
            BackHandler { screen = SCREEN_HOME }
            HistoryScreen(onBack = { screen = SCREEN_HOME })
        }
        SCREEN_SETTINGS -> {
            BackHandler { screen = SCREEN_HOME }
            SettingsScreen(onBack = { screen = SCREEN_HOME })
        }
        else -> HomeScreen(
            onOpenHistory = { screen = SCREEN_HISTORY },
            onOpenSettings = { screen = SCREEN_SETTINGS },
        )
    }
}
