package com.obsession.schedule.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.obsession.schedule.data.SlotIssue
import com.obsession.schedule.data.TimeSlotEntity

/** 一节课的编辑草稿。节次不存字段，始终等于它在列表里的下标 + 1，避免删行后编号错乱 */
private data class SlotDraft(val start: String, val end: String)

private const val MAX_NODE = 20

/**
 * 「每节课时间」编辑器。
 *
 * 保存的是整份作息（清空重写），所以这里不需要处理增量更新的麻烦。
 */
@Composable
fun TimeSlotEditorDialog(
    initial: List<TimeSlotEntity>,
    maxCourseNode: Int,
    onDismiss: () -> Unit,
    onSave: (List<TimeSlotEntity>) -> Unit
) {
    var drafts by remember {
        mutableStateOf(
            initial.sortedBy { it.node }
                .map { SlotDraft(it.startTime, it.endTime) }
                .ifEmpty { TimeSlotEntity.defaults().map { SlotDraft(it.startTime, it.endTime) } }
        )
    }

    val entities = drafts.mapIndexed { index, draft ->
        TimeSlotEntity(index + 1, draft.start, draft.end)
    }
    val issues = TimeSlotEntity.inspect(entities)
    val blockingNodes = issues.filter { it.blocking }.map { it.node }.toSet()
    val canSave = blockingNodes.isEmpty()

    val dialogProperties = remember { DialogProperties(usePlatformDefaultWidth = false) }

    Dialog(onDismissRequest = onDismiss, properties = dialogProperties) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                DialogHeader(
                    count = drafts.size,
                    onRestoreDefaults = {
                        drafts = TimeSlotEntity.defaults().map { SlotDraft(it.startTime, it.endTime) }
                    }
                )

                Spacer(Modifier.height(10.dp))

                if (issues.isNotEmpty()) {
                    IssuePanel(issues = issues)
                    Spacer(Modifier.height(10.dp))
                }

                if (drafts.size < maxCourseNode) {
                    Text(
                        "提示：已有课程排到第 $maxCourseNode 节。节次减少到 ${drafts.size} 后，" +
                            "多出的行仍会显示，但没有上下课时间。",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                }

                ColumnHeader()

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    drafts.forEachIndexed { index, draft ->
                        SlotRow(
                            index = index,
                            draft = draft,
                            hasError = (index + 1) in blockingNodes,
                            canDelete = drafts.size > 1,
                            onChange = { updated ->
                                drafts = drafts.toMutableList().also { it[index] = updated }
                            },
                            onDelete = {
                                drafts = drafts.toMutableList().also { it.removeAt(index) }
                            }
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    OutlinedButton(
                        enabled = drafts.size < MAX_NODE,
                        onClick = {
                            val next = TimeSlotEntity.suggestNext(entities)
                            drafts = drafts + SlotDraft(next.startTime, next.endTime)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (drafts.size < MAX_NODE) "加一节课" else "最多 $MAX_NODE 节",
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("取消", fontSize = 14.sp)
                    }
                    Button(
                        enabled = canSave,
                        onClick = { onSave(entities) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("保存", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogHeader(count: Int, onRestoreDefaults: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("每节课时间", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(
                "共 $count 节 · 只改数字即可，冒号会自动补上",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onRestoreDefaults) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("恢复默认", fontSize = 12.sp)
        }
    }
}

/** 问题清单。阻断性问题用错误色，提醒类用次级文字色 */
@Composable
private fun IssuePanel(issues: List<SlotIssue>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        issues.take(4).forEach { issue ->
            Text(
                (if (issue.blocking) "需修改：" else "提醒：") + issue.message,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = if (issue.blocking) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (issues.size > 4) {
            Text(
                "还有 ${issues.size - 4} 条…",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ColumnHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "节次",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(NODE_WIDTH)
        )
        Text(
            "上课",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "下课",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(DELETE_WIDTH))
    }
}

private val NODE_WIDTH = 26.dp
private val DELETE_WIDTH = 30.dp

@Composable
private fun SlotRow(
    index: Int,
    draft: SlotDraft,
    hasError: Boolean,
    canDelete: Boolean,
    onChange: (SlotDraft) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "${index + 1}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(NODE_WIDTH)
        )

        TimeField(
            value = draft.start,
            hasError = hasError,
            modifier = Modifier.weight(1f),
            onValueChange = { onChange(draft.copy(start = it)) }
        )

        Text("—", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        TimeField(
            value = draft.end,
            hasError = hasError,
            modifier = Modifier.weight(1f),
            onValueChange = { onChange(draft.copy(end = it)) }
        )

        IconButton(
            onClick = onDelete,
            enabled = canDelete,
            modifier = Modifier.size(DELETE_WIDTH)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "删掉第 ${index + 1} 节",
                modifier = Modifier.size(15.dp),
                tint = if (canDelete) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline
                }
            )
        }
    }
}

@Composable
private fun TimeField(
    value: String,
    hasError: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(formatAsTyping(it)) },
        placeholder = { Text("08:00", fontSize = 12.sp) },
        singleLine = true,
        isError = hasError,
        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

/**
 * 边输边格式化：只保留数字，并按 2 / 3 / 4 位自动补冒号。
 *
 * 三位数时先看「前两位能不能当小时」：能就用 08:0 这种形式，
 * 不能（比如 800、905）就把首位当小时补零成 08:00、09:05。
 * 这样既照顾了不敲前导零的输入习惯，退格也不会把已填好的时间搅乱。
 * 数字键盘就够用，用户不必去找冒号键。
 */
private fun formatAsTyping(raw: String): String {
    val digits = raw.filter { it.isDigit() }.take(4)
    return when {
        digits.length <= 2 -> digits
        digits.length == 3 -> {
            val twoDigitHour = digits.substring(0, 2).toIntOrNull() ?: 0
            if (twoDigitHour in 0..23) {
                "${digits.substring(0, 2)}:${digits[2]}"
            } else {
                "0${digits[0]}:${digits.substring(1)}"
            }
        }
        else -> "${digits.substring(0, 2)}:${digits.substring(2)}"
    }
}
