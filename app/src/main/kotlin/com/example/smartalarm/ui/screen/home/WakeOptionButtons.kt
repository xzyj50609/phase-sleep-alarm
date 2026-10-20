package com.example.smartalarm.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smartalarm.core.model.*
import com.example.smartalarm.ui.theme.PhaseFonts
import java.time.format.DateTimeFormatter

private val optionTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
internal fun WakeOptionButtons(options: List<WakeOption>, enabled: Boolean, onChoose: (WakeOption) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val title = when (option.scenario) {
                WakeScenario.FAST -> "睡得快"
                WakeScenario.EXPECTED -> "按预计"
                WakeScenario.SLOW -> "躺得久"
            }
            val time = option.time.format(optionTimeFormatter)
            val expected = option.scenario == WakeScenario.EXPECTED
            TextButton(
                onClick = { onChoose(option) }, enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics { contentDescription = "$title $time" },
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (expected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (expected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.bodySmall)
                    Text(time, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
internal fun TimingUncertaintyHint(recommendation: WakeUpRecommendation) {
    val offset = recommendation.wakeOptions.firstOrNull { it.scenario == WakeScenario.SLOW }?.offsetMinutes ?: return
    // 说明文字放进一块淡色底板：和上面的推荐结果分开，又不抢眼。
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("时间可以微调", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Text("这个时间是按平均睡眠周期推算的。你今晚多久睡着、每个周期多长，都可能和计算有出入，这一档前后大约 $offset 分钟内都算正常。",
            style = MaterialTheme.typography.bodySmall, fontFamily = PhaseFonts.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("跳到时钟后，可以按今晚的状态自己调几分钟：觉得要躺很久，就往后挪一点；已经很困、很快能睡着，就往前挪一点。",
            style = MaterialTheme.typography.bodySmall, fontFamily = PhaseFonts.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
