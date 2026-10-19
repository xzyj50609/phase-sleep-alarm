package com.example.smartalarm.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smartalarm.core.model.*
import com.example.smartalarm.ui.component.QualityDot
import java.time.format.DateTimeFormatter

@Composable
internal fun RootAlternativesCard(
    output: SleepCalculatorOutput, chosen: WakeUpRecommendation, enabled: Boolean,
    onChoose: (WakeUpRecommendation) -> Unit, onRequest: (WakeUpRecommendation, WakeOption?) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column {
            output.alternatives.forEachIndexed { index, recommendation ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onChoose(recommendation) }.padding(vertical = 10.dp)) {
                            Text(recommendation.label, style = MaterialTheme.typography.bodyMedium,
                                color = if (chosen == recommendation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("全晚睡 ${formatDuration(recommendation.sleepMinutes)}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                                if (recommendation == output.recommended) RecommendedChip()
                            }
                        }
                        QualityDot(recommendation.quality)
                        if (recommendation.wakeOptions.isEmpty()) TextButton(enabled = enabled,
                            onClick = { onRequest(recommendation, null) }) {
                            Text(recommendation.wakeUpTime.format(DateTimeFormatter.ofPattern("HH:mm")))
                        }
                    }
                    if (recommendation.wakeOptions.isNotEmpty()) WakeOptionButtons(recommendation.wakeOptions, enabled) {
                        onRequest(recommendation, it)
                    }
                }
                if (index < output.alternatives.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
