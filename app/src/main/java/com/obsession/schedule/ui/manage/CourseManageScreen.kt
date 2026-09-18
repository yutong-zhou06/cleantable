package com.obsession.schedule.ui.manage

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obsession.schedule.data.CourseEntity
import com.obsession.schedule.data.WEEK_TYPE_ALL
import com.obsession.schedule.data.WEEK_TYPE_EVEN
import com.obsession.schedule.data.WEEK_TYPE_ODD
import com.obsession.schedule.ui.common.NumberField
import com.obsession.schedule.ui.schedule.COURSE_PALETTE
import com.obsession.schedule.ui.schedule.MAX_WEEK
import com.obsession.schedule.ui.schedule.ScheduleViewModel

private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 「已添加课程」统一管理页。
 *
 * 同一门课（按课程名聚合）往往拆在好几个时间段里，单节课的编辑器改起来要重复 N 次。
 * 这里按课程聚合：点开一门课 → 勾选要改的分节（不勾 = 应用到全部）→
 * 统一改名 / 换色 / 改时间 / 批量删除，一次搞定。
 */
@Composable
fun CourseManageScreen(
    viewModel: ScheduleViewModel,
    onBack: () -> Unit
) {
    val courses by viewModel.courses.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // null = 课程列表；非 null = 进入该课程的分节详情
    var openCourseName by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val groups = remember(courses) {
        courses
            .groupBy { it.name }
            .map { (name, sections) ->
                name to sections.sortedWith(compareBy({ it.dayOfWeek }, { it.startNode }))
            }
            .sortedWith(compareBy({ it.second.firstOrNull()?.dayOfWeek ?: 9 }, { it.first }))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (openCourseName != null) {
                        openCourseName = null
                        selectedIds = emptySet()
                    } else {
                        onBack()
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    openCourseName ?: "已添加课程",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            val openName = openCourseName
            if (openName == null) {
                // ---------- 课程列表 ----------
                if (groups.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 120.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "还没有课程",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "回到课表主页添加，或从教务导入",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp)
                    ) {
                        Text(
                            "同一门课的分节在这里聚合，点开可以统一修改。",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp, bottom = 10.dp)
                        )
                        groups.forEach { (name, sections) ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        openCourseName = name
                                        selectedIds = emptySet()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(Color(sections.first().colorArgb))
                                    )
                                    Text(
                                        name,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 12.dp)
                                    )
                                    Text(
                                        "${sections.size} 节",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.padding(bottom = 40.dp))
                    }
                }
            } else {
                // ---------- 分节详情 ----------
                val sections = courses
                    .filter { it.name == openName }
                    .sortedWith(compareBy({ it.dayOfWeek }, { it.startNode }))
                CourseSectionList(
                    viewModel = viewModel,
                    name = openName,
                    sections = sections,
                    selectedIds = selectedIds,
                    onToggle = { id ->
                        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                    },
                    onSelectAll = { ids -> selectedIds = ids },
                    onRenamed = { newName ->
                        openCourseName = newName
                        selectedIds = emptySet()
                    },
                    onDeleted = {
                        openCourseName = null
                        selectedIds = emptySet()
                    }
                )
            }
        }
    }
}

/**
 * 分节详情 + 底部操作栏。
 * 勾选分节后操作只作用于所选；不勾选 = 应用到该课的全部分节（弹窗里写明范围）。
 */
@Composable
private fun CourseSectionList(
    viewModel: ScheduleViewModel,
    name: String,
    sections: List<CourseEntity>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onSelectAll: (Set<Long>) -> Unit,
    onRenamed: (String) -> Unit,
    onDeleted: () -> Unit
) {
    var deleteVisible by remember { mutableStateOf(false) }
    var renameVisible by remember { mutableStateOf(false) }
    var colorVisible by remember { mutableStateOf(false) }
    var timeEditVisible by remember { mutableStateOf(false) }
    var pendingColor by remember { mutableStateOf<Int?>(null) }

    val scopeNote = if (selectedIds.isEmpty()) {
        "全部 ${sections.size} 节"
    } else {
        "所选 ${selectedIds.size} 节"
    }
    val targets = if (selectedIds.isEmpty()) {
        sections
    } else {
        sections.filter { it.id in selectedIds }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (selectedIds.isEmpty()) {
                    "未勾选 = 操作应用于全部 ${sections.size} 节"
                } else {
                    "已勾选 ${selectedIds.size} / ${sections.size} 节"
                },
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                onSelectAll(
                    if (selectedIds.size == sections.size) {
                        emptySet()
                    } else {
                        sections.map { it.id }.toSet()
                    }
                )
            }) {
                Text(if (selectedIds.size == sections.size) "全不选" else "全选", fontSize = 12.sp)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
        ) {
            sections.forEach { section ->
                val checked = section.id in selectedIds
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onToggle(section.id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { onToggle(section.id) })
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(section.colorArgb))
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp)
                        ) {
                            Text(
                                "${WEEKDAY_LABELS[section.dayOfWeek - 1]} · ${section.startNode}-${section.endNode} 节",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                buildString {
                                    append(section.weekLabel())
                                    if (section.room.isNotBlank()) append(" · ${section.room}")
                                    if (section.teacher.isNotBlank()) append(" · ${section.teacher}")
                                },
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.padding(bottom = 12.dp))
        }

        // 底部操作栏
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BarAction(Icons.Default.Delete, "删除") { deleteVisible = true }
                BarAction(Icons.Default.Edit, "改名") { renameVisible = true }
                BarAction(Icons.Default.Create, "换色") { colorVisible = true }
                BarAction(Icons.Default.DateRange, "改时间") { timeEditVisible = true }
            }
        }
    }

    // ---------- 操作弹窗 ----------

    if (deleteVisible) {
        AlertDialog(
            onDismissRequest = { deleteVisible = false },
            title = { Text("删除课程分节") },
            text = { Text("将从课表中删除「$name」的$scopeNote，删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCoursesByIds(targets.map { it.id })
                    deleteVisible = false
                    onDeleted()
                }) {
                    Text("删除$scopeNote", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteVisible = false }) { Text("取消") }
            }
        )
    }

    if (renameVisible) {
        var newName by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renameVisible = false },
            title = { Text("统一改名") },
            text = {
                Column {
                    Text(
                        "「$name」的$scopeNote 将全部改为新名称。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.padding(top = 10.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && newName.trim() != name,
                    onClick = {
                        viewModel.applyCourseChange(targets, "重命名") {
                            it.copy(name = newName.trim())
                        }
                        renameVisible = false
                        onRenamed(newName.trim())
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameVisible = false }) { Text("取消") }
            }
        )
    }

    if (colorVisible) {
        AlertDialog(
            onDismissRequest = {
                colorVisible = false
                pendingColor = null
            },
            title = { Text("统一换色") },
            text = {
                Column {
                    Text(
                        "「$name」的$scopeNote 将使用同一颜色。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.padding(top = 14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        COURSE_PALETTE.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .border(
                                        width = if (pendingColor == c) 2.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape
                                    )
                                    .clickable { pendingColor = c }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pendingColor != null,
                    onClick = {
                        viewModel.applyCourseChange(targets, "换色") {
                            it.copy(colorArgb = pendingColor!!)
                        }
                        colorVisible = false
                        pendingColor = null
                    }
                ) { Text("应用") }
            },
            dismissButton = {
                TextButton(onClick = {
                    colorVisible = false
                    pendingColor = null
                }) { Text("取消") }
            }
        )
    }

    if (timeEditVisible) {
        BulkTimeEditDialog(
            reference = targets.firstOrNull() ?: sections.first(),
            scopeNote = scopeNote,
            onDismiss = { timeEditVisible = false },
            onApply = { transform ->
                viewModel.applyCourseChange(targets, "修改时间", transform)
                timeEditVisible = false
            }
        )
    }
}

/**
 * 统一改时间弹窗：以目标第一节为参照预填，改完应用到目标分节。
 */
@Composable
private fun BulkTimeEditDialog(
    reference: CourseEntity,
    scopeNote: String,
    onDismiss: () -> Unit,
    onApply: ((CourseEntity) -> CourseEntity) -> Unit
) {
    var day by remember { mutableStateOf(reference.dayOfWeek) }
    var startNode by remember { mutableStateOf(reference.startNode) }
    var step by remember { mutableStateOf(reference.step) }
    var startWeek by remember { mutableStateOf(reference.startWeek) }
    var endWeek by remember { mutableStateOf(reference.endWeek) }
    var weekType by remember { mutableStateOf(reference.weekType) }
    var room by remember { mutableStateOf(reference.room) }
    var teacher by remember { mutableStateOf(reference.teacher) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("统一改时间（$scopeNote）", fontSize = 16.sp) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "以目标第一节为预填，确认后应用到目标分节。",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("星期", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WEEKDAY_LABELS.forEachIndexed { index, label ->
                        ChipSmall(
                            label = label,
                            selected = day == index + 1,
                            onClick = { day = index + 1 }
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NumberField(startNode, "开始节", 1..MAX_NODE, Modifier.weight(1f), labelSize = 10.sp) {
                        startNode = it
                    }
                    NumberField(step, "连堂", 1..MAX_NODE, Modifier.weight(1f), labelSize = 10.sp) {
                        step = it
                    }
                    NumberField(startWeek, "起始周", 1..MAX_WEEK, Modifier.weight(1f), labelSize = 10.sp) {
                        startWeek = it
                    }
                    NumberField(endWeek, "结束周", 1..MAX_WEEK, Modifier.weight(1f), labelSize = 10.sp) {
                        endWeek = it
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChipSmall("每周", weekType == WEEK_TYPE_ALL) { weekType = WEEK_TYPE_ALL }
                    ChipSmall("单周", weekType == WEEK_TYPE_ODD) { weekType = WEEK_TYPE_ODD }
                    ChipSmall("双周", weekType == WEEK_TYPE_EVEN) { weekType = WEEK_TYPE_EVEN }
                }
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("教室") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("教师") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onApply { course ->
                    course.copy(
                        dayOfWeek = day,
                        // 输入框允许中途留空，落库前统一钳到合法范围
                        startNode = startNode.coerceIn(1, MAX_NODE),
                        step = step.coerceIn(1, MAX_NODE),
                        startWeek = minOf(startWeek, endWeek).coerceIn(1, MAX_WEEK),
                        endWeek = maxOf(startWeek, endWeek).coerceIn(1, MAX_WEEK),
                        weekType = weekType,
                        room = room.trim(),
                        teacher = teacher.trim()
                    )
                }
            }) { Text("应用到目标分节") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ChipSmall(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            fontSize = 11.5.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 批量改时间时允许的节次上限（与作息编辑器一致，这里只要够用即可） */
private const val MAX_NODE = 15

@Composable
private fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Text(
            label,
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
