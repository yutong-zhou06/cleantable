package com.obsession.schedule.ui.manage

import android.app.DatePickerDialog
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obsession.schedule.data.SemesterConfig
import com.obsession.schedule.data.TimetableEntity
import com.obsession.schedule.data.formatFullDate
import com.obsession.schedule.ui.schedule.ScheduleViewModel
import java.util.Calendar

/**
 * 课表管理。
 *
 * 每张卡片是一张独立课表（自带课程 + 作息 + 学期配置）。
 * 点卡片切换到该课表；重命名 / 删除走各自的二级操作。
 * 删除有二次确认，且正在使用的课表不给删 —— 防手滑是第一原则。
 */
@Composable
fun ManageScreen(
    viewModel: ScheduleViewModel,
    onBack: () -> Unit,
    onOpenCourses: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val timetables by viewModel.timetables.collectAsState()
    val activeId by viewModel.activeId.collectAsState()
    val message by viewModel.message.collectAsState()

    var createVisible by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<TimetableEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<TimetableEntity?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
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
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("课表管理", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.weight(1f))
                // v0.4：已添加课程（按课程名聚合做统一修改）
                IconButton(onClick = onOpenCourses) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = "已添加课程"
                    )
                }
                IconButton(onClick = { createVisible = true }) {
                    Icon(Icons.Default.Add, contentDescription = "新建课表")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
            ) {
                Text(
                    "每张课表都有独立的课程、作息和学期设置，互不影响。",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, bottom = 10.dp)
                )

                timetables.forEach { timetable ->
                    val isActive = timetable.id == activeId
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = if (isActive) {
                            androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                MaterialTheme.colorScheme.primary
                            )
                        } else {
                            null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clickable {
                                viewModel.switchTimetable(timetable.id)
                                onBack()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        timetable.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (isActive) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(
                                                    MaterialTheme.colorScheme.primaryContainer
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                "使用中",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    "${timetable.termName} · ${timetable.totalWeeks} 周",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { renameTarget = timetable }) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "重命名",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (isActive) {
                                        viewModel.notify("正在使用的课表不能删除，请先切换到别的课表")
                                    } else {
                                        deleteTarget = timetable
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
                                    tint = if (isActive) {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }

    if (createVisible) {
        CreateTimetableDialog(
            onDismiss = { createVisible = false },
            onCreate = { name, config ->
                viewModel.createTimetable(name, config)
                createVisible = false
            }
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.name,
            onDismiss = { renameTarget = null },
            onRename = {
                viewModel.renameTimetable(target.id, it)
                renameTarget = null
            }
        )
    }

    deleteTarget?.let { target ->
        // 二次确认，按钮上直接写出目标课表名，防手滑
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除课表？") },
            text = {
                Text(
                    "「${target.name}」里的全部课程和作息设置都会被删除，且无法恢复。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTimetable(target.id)
                        deleteTarget = null
                    }
                ) {
                    Text(
                        "删除「${target.name}」",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名课表") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onRename(name) },
                enabled = name.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 新建课表向导。
 *
 * 一屏完成：课表名 → 学期名 → 起始日 / 总周数；
 * 底部确认按钮明确写出「将切换到『xxx』」，避免建完发现建到了别处。
 */
@Composable
private fun CreateTimetableDialog(
    onDismiss: () -> Unit,
    onCreate: (String, SemesterConfig) -> Unit
) {
    val context = LocalContext.current
    val fallback = remember { SemesterConfig.default() }

    var name by remember { mutableStateOf("") }
    var termName by remember { mutableStateOf(fallback.termName) }
    var firstWeekStart by remember { mutableStateOf(fallback.firstWeekStart) }
    var totalWeeks by remember { mutableStateOf(fallback.totalWeeks) }

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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建课表") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("课表名，例如：大二上", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = termName,
                    onValueChange = { termName = it },
                    singleLine = true,
                    placeholder = { Text("学期名", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { pickDate() }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatFullDate(firstWeekStart), fontSize = 13.sp)
                    Text(
                        "第一周 ›",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("总周数", fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { totalWeeks = (totalWeeks - 1).coerceAtLeast(1) }) {
                        Text("−")
                    }
                    Text(
                        "$totalWeeks",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(32.dp)
                    )
                    TextButton(onClick = { totalWeeks = (totalWeeks + 1).coerceAtMost(40) }) {
                        Text("＋")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        name,
                        SemesterConfig(
                            termName = termName.ifBlank { fallback.termName },
                            firstWeekStart = firstWeekStart,
                            totalWeeks = totalWeeks
                        )
                    )
                }
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.width(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("创建并切换到「${name.ifBlank { "新课表" }}」")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
