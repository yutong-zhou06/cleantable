package com.obsession.schedule.ui.settings

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.window.Dialog
import com.obsession.schedule.data.SemesterConfig
import com.obsession.schedule.data.formatFullDate
import com.obsession.schedule.data.formatWeekRange
import com.obsession.schedule.data.mondayOfDay
import java.util.Calendar

/**
 * 学期设置。
 *
 * 只收三样东西：学期名、第一周起始日、总周数。
 * 用户选的第一周日期会被归一到该日期所在周的周一 —— 界面上明确写出这件事，
 * 否则用户选了「9月1日（周二）」却发现第 1 周从 8月31日 开始，会以为算错了。
 */
@Composable
fun SemesterSettingsDialog(
    initial: SemesterConfig,
    onDismiss: () -> Unit,
    onSave: (SemesterConfig) -> Unit
) {
    val context = LocalContext.current

    var termName by remember { mutableStateOf(initial.termName) }
    var firstWeekStart by remember { mutableStateOf(initial.firstWeekStart) }
    var totalWeeks by remember { mutableStateOf(initial.totalWeeks) }

    val draft = remember(termName, firstWeekStart, totalWeeks) {
        SemesterConfig(termName, firstWeekStart, totalWeeks)
    }
    val alignedMonday = remember(firstWeekStart) { mondayOfDay(firstWeekStart) }
    val todayWeek = remember(firstWeekStart) {
        draft.weekOfDay(System.currentTimeMillis())
    }

    fun pickDate() {
        val cal = Calendar.getInstance().apply { timeInMillis = firstWeekStart }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                firstWeekStart = picked.timeInMillis
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("学期设置", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "决定课表里「第几周」对应哪些真实日期，也决定周次上限。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(20.dp))
                SectionLabel("学期名")
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = termName,
                    onValueChange = { termName = it },
                    singleLine = true,
                    placeholder = { Text("例如：2026–2027 学年 · 秋季学期", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(20.dp))
                SectionLabel("第一周起始日")
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { pickDate() }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${formatFullDate(firstWeekStart)} · ${weekdayLabel(firstWeekStart)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "选择 ›",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "第 1 周从 ${formatFullDate(alignedMonday)}（周一）起算",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(20.dp))
                SectionLabel("总周数")
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepperButton("−") { totalWeeks = (totalWeeks - 1).coerceAtLeast(1) }
                    Text(
                        "$totalWeeks",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(64.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    StepperButton("＋") { totalWeeks = (totalWeeks + 1).coerceAtMost(40) }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "周",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(20.dp))
                PreviewCard(draft, todayWeek)

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) { Text("取消") }
                    Button(
                        onClick = {
                            onSave(
                                SemesterConfig(
                                    termName = termName.ifBlank { initial.termName },
                                    firstWeekStart = firstWeekStart,
                                    totalWeeks = totalWeeks
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(config: SemesterConfig, todayWeek: Int) {
    val firstRange = remember(config.firstWeekStart) { config.weekRange(1) }
    val lastRange = remember(config.firstWeekStart, config.totalWeeks) {
        config.weekRange(config.totalWeeks)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp)
    ) {
        Text(
            "预览",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        PreviewRow("第 1 周", formatWeekRange(firstRange.first, firstRange.second))
        Spacer(Modifier.height(6.dp))
        PreviewRow(
            "第 ${config.totalWeeks} 周",
            formatWeekRange(lastRange.first, lastRange.second)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                todayWeek < 1 -> "今天在开学前，课表会从第 1 周开始显示"
                todayWeek > config.totalWeeks ->
                    "今天已超出本学期 $todayWeek - ${config.totalWeeks} 周，课表会停在最后一周"
                else -> "今天是第 $todayWeek 周"
            },
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .width(40.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun weekdayLabel(millis: Long): String {
    val c = Calendar.getInstance()
    c.timeInMillis = millis
    return when (c.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "周一"
        Calendar.TUESDAY -> "周二"
        Calendar.WEDNESDAY -> "周三"
        Calendar.THURSDAY -> "周四"
        Calendar.FRIDAY -> "周五"
        Calendar.SATURDAY -> "周六"
        else -> "周日"
    }
}
