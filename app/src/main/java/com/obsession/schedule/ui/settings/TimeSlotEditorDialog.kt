package com.obsession.schedule.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.obsession.schedule.data.ConfigStore
import com.obsession.schedule.data.SlotIssue
import com.obsession.schedule.data.TimeSlotEntity
import com.obsession.schedule.data.TimeText

/** 一节课的编辑草稿。节次不存字段，始终等于它在列表里的下标 + 1，避免删行后编号错乱 */
private data class SlotDraft(val start: String, val end: String)

private const val MAX_NODE = 20
private val OPS_WIDTH = 58.dp

/**
 * 「每节课时间」编辑器。
 *
 * 保存的是整份作息（清空重写），所以这里不需要处理增量更新的麻烦。
 *
 * v0.6 三处升级：
 * 1. 时间拆成「时 / 分」两格输入（旧版单框边输边补冒号，光标会错乱跳位）；
 * 2. 每行可「在下方插入一节」，后续节次可选整体顺延；
 * 3. 「只填开始时间」模式（可开关）：下课 = 开始 + 时长，自动生成。
 *    开关与时长按课表存本地偏好（SharedPreferences），不改数据库结构；
 *    关掉开关后自动生成的下课时间保留成普通数据，可继续手改。
 */
@Composable
fun TimeSlotEditorDialog(
    initial: List<TimeSlotEntity>,
    maxCourseNode: Int,
    timetableId: Long,
    onDismiss: () -> Unit,
    onSave: (List<TimeSlotEntity>) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { ConfigStore(context) }
    var autoMode by remember { mutableStateOf(prefs.slotAutoMode(timetableId)) }
    var lessonMinutes by remember { mutableStateOf(prefs.slotLessonMinutes(timetableId)) }
    var shiftOnInsert by remember { mutableStateOf(false) }

    var drafts by remember {
        mutableStateOf(
            initial.sortedBy { it.node }
                .map { SlotDraft(it.startTime, it.endTime) }
                .ifEmpty { TimeSlotEntity.defaults().map { SlotDraft(it.startTime, it.endTime) } }
        )
    }

    /** 「只填开始时间」开着时，按开始 + 时长重算每行下课时间 */
    fun applyAutoEnds(list: List<SlotDraft>): List<SlotDraft> = list.map { d ->
        val start = TimeText.parse(d.start)
        if (start == null) d else d.copy(end = start.plusMinutes(lessonMinutes).toString())
    }

    fun toggleAuto(on: Boolean) {
        autoMode = on
        prefs.setSlotAutoMode(timetableId, on)
        // 开启即重算（立刻看到效果）；关闭不回收，自动结果保留成普通数据可手改
        if (on) drafts = applyAutoEnds(drafts)
    }

    fun setMinutes(v: Int) {
        lessonMinutes = v.coerceIn(20, 120)
        prefs.setSlotLessonMinutes(timetableId, lessonMinutes)
        if (autoMode) drafts = applyAutoEnds(drafts)
    }

    /** 在 [index] 行下方插入一节：默认时间 = 上一节下课 + 课间；可选顺延后续 */
    fun insertAfter(index: Int) {
        val (s, _) = TimeSlotEntity.suggestAfter(drafts.getOrNull(index)?.end)
        val duration = if (autoMode) lessonMinutes else TimeSlotEntity.DEFAULT_LESSON_MINUTES
        val end = TimeText.parse(s)?.plusMinutes(duration)?.toString() ?: "--:--"
        val inserted = drafts.toMutableList().apply { add(index + 1, SlotDraft(s, end)) }
        drafts = if (shiftOnInsert) {
            val delta = TimeSlotEntity.DEFAULT_LESSON_MINUTES + TimeSlotEntity.DEFAULT_BREAK_MINUTES
            inserted.mapIndexed { i, d ->
                if (i <= index + 1) d else d.copy(
                    start = TimeSlotEntity.shiftTime(d.start, delta),
                    end = TimeSlotEntity.shiftTime(d.end, delta)
                )
            }
        } else {
            inserted
        }
    }

    fun updateDraft(index: Int, updated: SlotDraft) {
        val fixed = if (autoMode) {
            val start = TimeText.parse(updated.start)
            if (start == null) updated else updated.copy(end = start.plusMinutes(lessonMinutes).toString())
        } else {
            updated
        }
        drafts = drafts.toMutableList().also { it[index] = fixed }
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

                ModePanel(
                    autoMode = autoMode,
                    lessonMinutes = lessonMinutes,
                    shiftOnInsert = shiftOnInsert,
                    onAutoChange = ::toggleAuto,
                    onMinutesChange = ::setMinutes,
                    onShiftChange = { shiftOnInsert = it }
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
                            autoMode = autoMode,
                            lessonMinutes = lessonMinutes,
                            onChange = { updated -> updateDraft(index, updated) },
                            onInsert = { insertAfter(index) },
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
                "共 $count 节 · 时 / 分分开输入，填满 2 位自动跳到下一格",
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

/** v0.6 编辑模式面板：只填开始时间（可开关）+ 插入顺延（一次性选择） */
@Composable
private fun ModePanel(
    autoMode: Boolean,
    lessonMinutes: Int,
    shiftOnInsert: Boolean,
    onAutoChange: (Boolean) -> Unit,
    onMinutesChange: (Int) -> Unit,
    onShiftChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("只填开始时间", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (autoMode) "开启中：下课时间 = 开始 + 时长，自动生成" else "开启后每节只需填上课时间",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = autoMode, onCheckedChange = onAutoChange)
        }

        if (autoMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Text("每节时长", fontSize = 12.sp)
                OutlinedTextField(
                    value = lessonMinutes.toString(),
                    onValueChange = { raw ->
                        raw.filter { it.isDigit() }.take(3).toIntOrNull()?.let(onMinutesChange)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.width(64.dp)
                )
                Text(
                    "分钟（下课 = 开始 + 时长）",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("插入后顺延后续节次", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                Text(
                    "插入一节时，后面所有节次的时间整体后移",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = shiftOnInsert, onCheckedChange = onShiftChange)
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
        Spacer(Modifier.width(OPS_WIDTH))
    }
}

private val NODE_WIDTH = 26.dp

@Composable
private fun SlotRow(
    index: Int,
    draft: SlotDraft,
    hasError: Boolean,
    canDelete: Boolean,
    autoMode: Boolean,
    lessonMinutes: Int,
    onChange: (SlotDraft) -> Unit,
    onInsert: () -> Unit,
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

        if (autoMode) {
            AutoEndCell(end = autoEndOf(draft.start, lessonMinutes), modifier = Modifier.weight(1f))
        } else {
            TimeField(
                value = draft.end,
                hasError = hasError,
                modifier = Modifier.weight(1f),
                onValueChange = { onChange(draft.copy(end = it)) }
            )
        }

        Row(modifier = Modifier.width(OPS_WIDTH)) {
            IconButton(onClick = onInsert, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "在下方插入一节",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete, enabled = canDelete, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "删掉第 ${index + 1} 节",
                    modifier = Modifier.size(14.dp),
                    tint = if (canDelete) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
            }
        }
    }
}

private fun autoEndOf(start: String, lessonMinutes: Int): String {
    val parsed = TimeText.parse(start) ?: return "--:--"
    return parsed.plusMinutes(lessonMinutes).toString()
}

/**
 * 「时 : 分」两格输入。
 *
 * 旧版是单框 + 每次按键重写整串文本（只留数字、自动补冒号），冒号一插入，
 * 系统对光标位置的映射就错乱，会跳到最前面，接着输入的数字插到小时前面。
 * 拆成两格后每格只管自己的两位数字，结构上不存在这个问题：
 * - 填满 2 位自动跳到下一格；格内为空时退格回到上一格；
 * - 输入冒号视为「跳到下一格」；
 * - 失焦时纠正非法值（时 ≤ 23、分 ≤ 59）。
 */
@Composable
private fun TimeField(
    value: String,
    hasError: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    var hour by remember(value) { mutableStateOf(value.substringBefore(":", "").take(2)) }
    var minute by remember(value) { mutableStateOf(value.substringAfter(":", "").take(2)) }
    val hourFocus = remember { FocusRequester() }
    val minuteFocus = remember { FocusRequester() }

    fun emit(h: String, m: String) = onValueChange("$h:$m")

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        PartField(
            value = hour,
            hint = "08",
            maxValue = 23,
            isError = hasError,
            focusRequester = hourFocus,
            modifier = Modifier.weight(1f),
            onChange = { newH ->
                hour = newH
                emit(newH, minute)
                if (newH.length == 2) minuteFocus.requestFocus()
            },
            onEmptyBackspace = { }
        )
        Text(":", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PartField(
            value = minute,
            hint = "00",
            maxValue = 59,
            isError = hasError,
            focusRequester = minuteFocus,
            modifier = Modifier.weight(1f),
            onChange = { newM ->
                minute = newM
                emit(hour, newM)
            },
            onEmptyBackspace = { hourFocus.requestFocus() }
        )
    }
}

@Composable
private fun PartField(
    value: String,
    hint: String,
    maxValue: Int,
    isError: Boolean,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
    onEmptyBackspace: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val bg = when {
        isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(modifier = modifier.height(34.dp)) {
        BasicTextField(
            value = value,
            onValueChange = { raw -> onChange(raw.filter { it.isDigit() }.take(2)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxSize()
                .background(bg, shape)
                .border(
                    width = 1.dp,
                    color = when {
                        isError -> MaterialTheme.colorScheme.error
                        focused -> MaterialTheme.colorScheme.primary
                        else -> Color.Transparent
                    },
                    shape = shape
                )
                .focusRequester(focusRequester)
                .onFocusChanged { st ->
                    // 失焦时纠正非法值（比如把 25 分钳到 59 之前的合法范围）
                    if (focused && !st.isFocused) {
                        val n = value.toIntOrNull()
                        if (n != null && n > maxValue) onChange(maxValue.toString())
                    }
                    focused = st.isFocused
                }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && value.isEmpty()) {
                        onEmptyBackspace()
                        true
                    } else {
                        false
                    }
                }
                .padding(vertical = 8.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight()) {
                    if (value.isEmpty() && !focused) {
                        Text(
                            hint,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    inner()
                }
            }
        )
    }
}

/** 「只填开始时间」模式下，下课时间的只读展示 */
@Composable
private fun AutoEndCell(end: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(34.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            end,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
