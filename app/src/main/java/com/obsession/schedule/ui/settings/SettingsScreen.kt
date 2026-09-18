package com.obsession.schedule.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obsession.schedule.data.BgConfig
import com.obsession.schedule.ui.schedule.ScheduleViewModel
import com.obsession.schedule.ui.theme.ThemeController
import com.obsession.schedule.ui.theme.ThemeMode
import com.obsession.schedule.ui.theme.appDarkTheme
import com.obsession.schedule.widget.WidgetRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 设置页。设置按「这张课表」分组：学期、作息、背景都跟着当前课表走，
 * 换学期就是换课表，配置互不干扰。
 */
@Composable
fun SettingsScreen(
    viewModel: ScheduleViewModel,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val config by viewModel.config.collectAsState()
    val timetable by viewModel.activeTimetable.collectAsState()
    val timeSlots by viewModel.timeSlots.collectAsState()
    val courses by viewModel.courses.collectAsState()
    val bgConfig by viewModel.bgConfig.collectAsState()
    val message by viewModel.message.collectAsState()

    var semesterEditorVisible by remember { mutableStateOf(false) }
    var timeSlotEditorVisible by remember { mutableStateOf(false) }
    var bgDialogVisible by remember { mutableStateOf(false) }
    // ThemeController.mode 是 Compose 状态：读它 → 保存后本页与全局同时重组
    val themeMode by ThemeController.mode
    val maxNode = maxOf(timeSlots.size, courses.maxOfOrNull { it.endNode } ?: 0, 1)

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = readBytesFromSafely(context, uri)
        if (bytes == null) {
            viewModel.notify("读取文件失败")
        } else {
            viewModel.openFile(displayNameSafely(context, uri), bytes)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = viewModel.exportText()
            val ok = writeTextToSafely(context, uri, text)
            viewModel.notify(if (ok) "课表已导出" else "导出失败")
        }
    }

    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModel.saveBackground(bgConfig.copy(uri = uri.toString()))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
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
                Text("设置", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(4.dp))

            // 当前课表
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "当前课表",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            timetable?.name ?: "—",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            "${config.termName} · ${config.totalWeeks} 周",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SectionTitle("这张课表")
            SectionCard {
                SettingsRow(Icons.Default.DateRange, "学期设置", "第一周起始日 · 总周数") {
                    semesterEditorVisible = true
                }
                SettingsRow(Icons.AutoMirrored.Filled.List, "每节课时间", "${timeSlots.size} 节已配置") {
                    timeSlotEditorVisible = true
                }
                SettingsRow(
                    Icons.Default.Create,
                    "课表背景",
                    if (bgConfig.hasImage) "蒙层 ${(bgConfig.mask * 100).toInt()}%" else "未设置"
                ) {
                    bgDialogVisible = true
                }
            }

            SectionTitle("课程数据")
            SectionCard {
                SettingsRow(Icons.Default.Search, "从教务网站导入", "内置浏览器 · 正方教务") {
                    onOpenBrowser()
                }
                SettingsRow(Icons.Default.Add, "导入课表文件", "HTML / 备份文件") {
                    importLauncher.launch(arrayOf("*/*"))
                }
                SettingsRow(Icons.AutoMirrored.Filled.Send, "导出课表文件", "备份为 JSON") {
                    exportLauncher.launch("Obsession-backup.json")
                }
            }

            SectionTitle("外观")
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        "主题模式",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "切换立即生效，全部页面统一变化",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ThemeMode.entries.forEach { candidate ->
                            val selected = themeMode == candidate
                            Text(
                                candidate.label,
                                fontSize = 12.5.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            androidx.compose.ui.graphics.Color.Transparent
                                        }
                                    )
                                    .clickable { ThemeController.save(context, candidate) }
                                    .padding(vertical = 7.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

            SectionTitle("关于")
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text("Obsession v0.7.0", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "零广告 · 无账号 · 数据只保存在本机。\n内置浏览器导入时需要访问教务网站（唯一联网场景），" +
                            "登录密码不经过本应用，关闭导入页面即清除访问记录。",
                        fontSize = 11.5.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (semesterEditorVisible) {
        SemesterSettingsDialog(
            initial = config,
            onDismiss = { semesterEditorVisible = false },
            onSave = {
                viewModel.saveConfig(it)
                semesterEditorVisible = false
            }
        )
    }

    if (timeSlotEditorVisible) {
        TimeSlotEditorDialog(
            initial = timeSlots,
            maxCourseNode = maxNode,
            timetableId = timetable?.id ?: 0L,
            onDismiss = { timeSlotEditorVisible = false },
            onSave = {
                viewModel.saveTimeSlots(it)
                timeSlotEditorVisible = false
            }
        )
    }

    if (bgDialogVisible) {
        BackgroundDialog(
            current = bgConfig,
            dark = appDarkTheme(),
            onDismiss = { bgDialogVisible = false },
            onPickImage = {
                bgDialogVisible = false
                bgLauncher.launch(arrayOf("image/*"))
            },
            onMaskChange = { viewModel.saveBackground(bgConfig.copy(mask = it)) },
            onClear = {
                viewModel.saveBackground(BgConfig(mask = bgConfig.mask))
                bgDialogVisible = false
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp)
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(7.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.height(18.dp)
            )
        }
        Spacer(Modifier.height(0.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun readBytesFromSafely(context: Context, uri: Uri): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
}.getOrNull()

internal fun writeTextToSafely(context: Context, uri: Uri, text: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    true
}.getOrDefault(false)

internal fun displayNameSafely(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
}.getOrNull() ?: uri.lastPathSegment.orEmpty()

