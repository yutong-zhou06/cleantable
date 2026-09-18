package com.obsession.schedule.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obsession.schedule.data.CourseEntity
import com.obsession.schedule.data.WEEK_TYPE_ALL
import com.obsession.schedule.data.WEEK_TYPE_EVEN
import com.obsession.schedule.data.WEEK_TYPE_ODD
import com.obsession.schedule.ui.common.NumberField

/**
 * 8 色课程色板。
 *
 * 这里存的仍是 ARGB 整数（兼容 WakeUp 的 `.wakeup_schedule` 文件），
 * 但渲染时只取色相，饱和度和明度由当前主题决定 —— 见 `CourseColors.kt`。
 * 所以下面这些值的明度差异不影响最终观感，选色相清晰、饱和度均匀的即可。
 */
val COURSE_PALETTE = listOf(
    0xFF457DED.toInt(), // 蓝
    0xFF2EB877.toInt(), // 绿
    0xFFF48D34.toInt(), // 橙
    0xFFEA5362.toInt(), // 红
    0xFF9A62DA.toInt(), // 紫
    0xFF25B9D0.toInt(), // 青
    0xFFE4589E.toInt(), // 粉
    0xFFE9AD20.toInt()  // 黄
)

private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
fun CourseEditorDialog(
    initial: CourseEntity?,
    maxNode: Int,
    onDismiss: () -> Unit,
    onSave: (CourseEntity) -> Unit,
    onDelete: ((CourseEntity) -> Unit)? = null
) {
    val isEdit = initial != null
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var teacher by remember { mutableStateOf(initial?.teacher ?: "") }
    var room by remember { mutableStateOf(initial?.room ?: "") }
    var day by remember { mutableStateOf(initial?.dayOfWeek ?: 1) }
    var startNode by remember { mutableStateOf(initial?.startNode ?: 1) }
    var step by remember { mutableStateOf(initial?.step ?: 1) }
    var startWeek by remember { mutableStateOf(initial?.startWeek ?: 1) }
    var endWeek by remember { mutableStateOf(initial?.endWeek ?: 16) }
    var weekType by remember { mutableStateOf(initial?.weekType ?: WEEK_TYPE_ALL) }
    var color by remember { mutableStateOf(initial?.colorArgb ?: COURSE_PALETTE.first()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val nameError = name.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑课程" else "添加课程", fontSize = 16.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课程名") },
                    singleLine = true,
                    isError = nameError,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("教师") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("教室") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                FieldLabel("星期")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WEEKDAY_LABELS.forEachIndexed { index, label ->
                        ChoiceChip(
                            label = label,
                            selected = day == index + 1,
                            onClick = { day = index + 1 }
                        )
                    }
                }

                FieldLabel("节次")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NumberField(
                        value = startNode,
                        label = "开始",
                        range = 1..maxNode.coerceAtLeast(1),
                        modifier = Modifier.weight(1f),
                        onChange = { startNode = it }
                    )
                    NumberField(
                        value = step,
                        label = "连堂",
                        range = 1..maxNode.coerceAtLeast(1),
                        modifier = Modifier.weight(1f),
                        onChange = { step = it }
                    )
                }
                Text(
                    "第 $startNode - ${(startNode + step - 1).coerceAtMost(maxNode)} 节",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FieldLabel("周次")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NumberField(
                        value = startWeek,
                        label = "起始周",
                        range = 1..MAX_WEEK,
                        modifier = Modifier.weight(1f),
                        onChange = { startWeek = it }
                    )
                    NumberField(
                        value = endWeek,
                        label = "结束周",
                        range = 1..MAX_WEEK,
                        modifier = Modifier.weight(1f),
                        onChange = { endWeek = it }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceChip("每周", weekType == WEEK_TYPE_ALL) { weekType = WEEK_TYPE_ALL }
                    ChoiceChip("单周", weekType == WEEK_TYPE_ODD) { weekType = WEEK_TYPE_ODD }
                    ChoiceChip("双周", weekType == WEEK_TYPE_EVEN) { weekType = WEEK_TYPE_EVEN }
                }

                FieldLabel("颜色")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    COURSE_PALETTE.forEach { c ->
                        val selected = c == color
                        Box(
                            modifier = Modifier
                                .width(26.dp)
                                .height(26.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                )
                                .clickable { color = c }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !nameError,
                onClick = {
                    // 兜底再钳一次：输入框留空时用的是「进来时的值」，
                    // 这里保证写进库的节次/周次永远是合法范围
                    val maxN = maxNode.coerceAtLeast(1)
                    onSave(
                        CourseEntity(
                            id = initial?.id ?: 0L,
                            name = name.trim(),
                            teacher = teacher.trim(),
                            room = room.trim(),
                            dayOfWeek = day,
                            startNode = startNode.coerceIn(1, maxN),
                            step = step.coerceIn(1, maxN),
                            startWeek = minOf(startWeek, endWeek).coerceIn(1, MAX_WEEK),
                            endWeek = maxOf(startWeek, endWeek).coerceIn(1, MAX_WEEK),
                            weekType = weekType,
                            colorArgb = color
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isEdit && onDelete != null) {
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )

    if (showDeleteConfirm && initial != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除课程", fontSize = 16.sp) },
            text = { Text("确定删除「${initial.name}」吗？") },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirm = false
                    onDelete(initial)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 12.sp, color = foreground)
    }
}
