package com.obsession.schedule.ui.schedule

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obsession.schedule.data.BgConfig
import com.obsession.schedule.widget.WidgetRefreshResult
import com.obsession.schedule.widget.WidgetSnapshotRenderer
import com.obsession.schedule.data.CourseEntity
import com.obsession.schedule.data.TimeSlotEntity
import com.obsession.schedule.data.formatDayNumber
import com.obsession.schedule.data.formatWeekRange
import com.obsession.schedule.data.shiftDays
import com.obsession.schedule.ui.settings.BackgroundDialog
import com.obsession.schedule.ui.settings.SemesterSettingsDialog
import com.obsession.schedule.ui.settings.TimeSlotEditorDialog
import com.obsession.schedule.ui.theme.appDarkTheme
import com.obsession.schedule.ui.theme.courseColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Calendar
import kotlin.math.abs

private val TIME_COLUMN_WIDTH = 46.dp
private val ROW_HEIGHT = 56.dp

/**
 * v0.2 真机反馈「表头日期显示不全」的修复：星期 + 日期两行文字加上行距
 * 至少要 40dp，之前给的 36dp 太挤，这里放宽到 46dp 并去掉多余行距。
 */
private val DOW_HEADER_HEIGHT = 46.dp
private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel,
    onOpenManage: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = appDarkTheme()

    val courses by viewModel.courses.collectAsState()
    val timeSlots by viewModel.timeSlots.collectAsState()
    val currentWeek by viewModel.week.collectAsState()
    val config by viewModel.config.collectAsState()
    val message by viewModel.message.collectAsState()
    val importPreview by viewModel.importPreview.collectAsState()
    val bgConfig by viewModel.bgConfig.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }
    var editorVisible by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<CourseEntity?>(null) }
    var timeSlotEditorVisible by remember { mutableStateOf(false) }
    var semesterEditorVisible by remember { mutableStateOf(false) }
    var bgDialogVisible by remember { mutableStateOf(false) }
    var weekJumpVisible by remember { mutableStateOf(false) }

    // v0.4.3「一键更新桌面小组件」：结果逐组件展示，失败直接显示异常原文
    var widgetUpdating by remember { mutableStateOf(false) }
    var widgetReport by remember { mutableStateOf<List<WidgetRefreshResult>?>(null) }

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
        // 必须读原始字节而不是直接读文本：教务页普遍是 GBK，只有拿到字节
        // 才能先探测编码再解码，否则课程名会整片乱码。
        val bytes = readBytesFrom(context, uri)
        if (bytes == null) {
            viewModel.notify("读取文件失败")
        } else {
            viewModel.openFile(displayNameOf(context, uri), bytes)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = viewModel.exportText()
            val ok = writeTextTo(context, uri, text)
            viewModel.notify(if (ok) "课表已导出" else "导出失败")
        }
    }

    // 背景图选择：拿持久化读权限，重启后仍可用
    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModel.saveBackground(bgConfig.copy(uri = uri.toString()))
    }

    val maxNode = remember(timeSlots, courses) {
        maxOf(timeSlots.size, courses.maxOfOrNull { it.endNode } ?: 0, 1)
    }
    val slotByNode = remember(timeSlots) { timeSlots.associateBy { it.node } }

    val todayIndex = remember { todayWeekdayIndex() }
    val dayDates = remember(config, currentWeek) {
        val monday = config.mondayOf(currentWeek)
        List(7) { shiftDays(monday, it) }
    }
    val weekRangeText = remember(config, currentWeek) {
        val (start, end) = config.weekRange(currentWeek)
        formatWeekRange(start, end)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingCourse = null
                    editorVisible = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加课程")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ScheduleHeader(
                termName = config.termName,
                week = currentWeek,
                rangeText = weekRangeText,
                canPrevious = currentWeek > 1,
                canNext = currentWeek < config.totalWeeks,
                onPrevious = viewModel::previousWeek,
                onNext = viewModel::nextWeek,
                onOpenWeekPicker = { weekJumpVisible = true },
                onOpenManage = onOpenManage,
                onOpenSettings = onOpenSettings,
                menu = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("学期设置") },
                                onClick = {
                                    menuExpanded = false
                                    semesterEditorVisible = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("每节课时间") },
                                onClick = {
                                    menuExpanded = false
                                    timeSlotEditorVisible = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("回到本周") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.jumpToToday()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导入课表文件") },
                                onClick = {
                                    menuExpanded = false
                                    // 不限类型：Html 与 .wakeup_schedule 都从这个入口进，
                                    // 由 ViewModel 按文件内容分派
                                    importLauncher.launch(arrayOf("*/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("从教务网站导入") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenBrowser()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导出课表文件") },
                                onClick = {
                                    menuExpanded = false
                                    exportLauncher.launch("Obsession-backup.json")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (widgetUpdating) "正在更新小组件…" else "更新桌面小组件") },
                                onClick = {
                                    menuExpanded = false
                                    if (widgetUpdating) return@DropdownMenuItem
                                    widgetUpdating = true
                                    scope.launch {
                                        widgetReport = kotlinx.coroutines.withContext(
                                            kotlinx.coroutines.Dispatchers.IO
                                        ) {
                                            WidgetSnapshotRenderer.refreshAllWithReport(context)
                                        }
                                        widgetUpdating = false
                                    }
                                }
                            )
                        }
                    }
                }
            )

            // 周次切换：网格按翻页方向滑动过渡（v0.5.0 与屏间过渡统一缓动/时长）
            AnimatedContent(
                targetState = currentWeek,
                transitionSpec = {
                    val forward = targetState >= initialState
                    val ease = androidx.compose.animation.core.FastOutSlowInEasing
                    (
                        slideInHorizontally(tween(260, easing = ease)) { full: Int -> if (forward) full / 3 else -full / 3 } +
                            fadeIn(tween(220, easing = ease))
                        ) togetherWith (
                        slideOutHorizontally(tween(220, easing = ease)) { full: Int -> if (forward) -full / 3 else full / 3 } +
                            fadeOut(tween(180, easing = ease))
                        )
                },
                label = "week"
            ) { week ->
                ScheduleGrid(
                    courses = courses,
                    slotByNode = slotByNode,
                    maxNode = maxNode,
                    week = week,
                    dayDates = dayDates,
                    todayIndex = todayIndex,
                    dark = dark,
                    bgConfig = bgConfig,
                    onCourseClick = {
                        editingCourse = it
                        editorVisible = true
                    },
                    onLongPressGrid = { bgDialogVisible = true },
                    // v0.4.2 修正方向：手指左滑（totalDrag<0 → direction=1）= 翻到下一周，
                    // 与 AnimatedContent 的翻页动画（新周从右侧滑入）一致
                    onSwipeWeek = { direction ->
                        if (direction > 0) viewModel.nextWeek() else viewModel.previousWeek()
                    }
                )
            }
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

    if (widgetReport != null) {
        val report = widgetReport.orEmpty()
        AlertDialog(
            onDismissRequest = { widgetReport = null },
            title = { Text("桌面小组件更新结果") },
            text = {
                Column {
                    report.forEach { r ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(r.widget, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    !r.placed -> "未放置"
                                    r.ok -> "✓ 已更新"
                                    else -> "✗ 失败"
                                },
                                color = when {
                                    !r.placed -> MaterialTheme.colorScheme.onSurfaceVariant
                                    r.ok -> Color(0xFF1D9E75)
                                    else -> MaterialTheme.colorScheme.error
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        r.error?.let { err ->
                            Text(
                                "原因：$err",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                    if (report.all { it.ok }) {
                        Text(
                            "若桌面画面暂时没变，等 1～2 秒即可；个别桌面会缓存旧画面，删掉组件重新添加一次就好。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { widgetReport = null }) { Text("好") }
            }
        )
    }

    if (timeSlotEditorVisible) {
        TimeSlotEditorDialog(
            initial = timeSlots,
            maxCourseNode = maxNode,
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
            dark = dark,
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

    if (weekJumpVisible) {
        WeekJumpPanel(
            totalWeeks = config.totalWeeks,
            current = currentWeek,
            onDismiss = { weekJumpVisible = false },
            onJump = {
                viewModel.jumpToWeek(it)
                weekJumpVisible = false
            }
        )
    }

    if (editorVisible) {
        CourseEditorDialog(
            initial = editingCourse,
            maxNode = maxNode,
            onDismiss = { editorVisible = false },
            onSave = {
                viewModel.save(it)
                editorVisible = false
            },
            onDelete = {
                viewModel.delete(it)
                editorVisible = false
            }
        )
    }

    importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            onDismiss = viewModel::dismissImportPreview,
            onConfirm = viewModel::confirmImport
        )
    }
}

@Composable
private fun ScheduleHeader(
    termName: String,
    week: Int,
    rangeText: String,
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenWeekPicker: () -> Unit,
    onOpenManage: () -> Unit,
    onOpenSettings: () -> Unit,
    menu: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenWeekPicker)
        ) {
            Text(
                termName,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "第 $week 周",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    "▾",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp)
                )
            }
            Text(
                rangeText,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        IconButton(onClick = onPrevious, enabled = canPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上一周"
            )
        }
        IconButton(onClick = onNext, enabled = canNext) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下一周"
            )
        }
        IconButton(onClick = onOpenManage) {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = "课表管理"
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "设置"
            )
        }
        menu()
    }
}

/**
 * 课表网格。背景图只铺这片区域（星期表头 + 网格），顶部学期/周次区域保持干净；
 * 长按网格随时呼出背景设置。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ScheduleGrid(
    courses: List<CourseEntity>,
    slotByNode: Map<Int, TimeSlotEntity>,
    maxNode: Int,
    week: Int,
    dayDates: List<Long>,
    todayIndex: Int,
    dark: Boolean,
    bgConfig: BgConfig,
    onCourseClick: (CourseEntity) -> Unit,
    onLongPressGrid: () -> Unit,
    onSwipeWeek: (direction: Int) -> Unit
) {
    val backdrop = rememberBackdropBitmap(bgConfig.uri)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // v0.4 手势切周：水平累计拖动超过阈值判定为翻周；
                // 竖向滚动（verticalScroll）与课程块点击不受影响——
                // 拖拽手势只在横向越过 touch slop 后才开始消费
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragEnd = {
                        if (abs(totalDrag) > 60f) {
                            onSwipeWeek(if (totalDrag < 0) 1 else -1)
                        }
                    },
                    onHorizontalDrag = { _, amount -> totalDrag += amount }
                )
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongPressGrid
            )
    ) {
        if (backdrop != null) {
            Image(
                bitmap = backdrop.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            // 蒙层：浅色盖白、深色盖黑；低蒙层时额外垫一层基底色，
            // 保证滑到最淡时课程文字依然可读（「对比度不足自动加深」）
            val baseMask = if (bgConfig.mask < 0.4f) 0.22f else 0f
            val maskColor = (if (dark) Color.Black else Color.White)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(maskColor.copy(alpha = bgConfig.mask))
            )
            if (baseMask > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(maskColor.copy(alpha = baseMask))
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .width(TIME_COLUMN_WIDTH)
                        .height(DOW_HEADER_HEIGHT),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "节",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                WEEKDAY_LABELS.forEachIndexed { index, label ->
                    val isToday = index + 1 == todayIndex
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(DOW_HEADER_HEIGHT),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            label,
                            fontSize = 10.5.sp,
                            color = if (isToday) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Text(
                            formatDayNumber(dayDates[index]),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isToday) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .width(TIME_COLUMN_WIDTH)
                ) {
                    for (node in 1..maxNode) {
                        val slot = slotByNode[node]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ROW_HEIGHT),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "$node",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (slot != null) {
                                Text(
                                    slot.startTime,
                                    fontSize = 8.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    slot.endTime,
                                    fontSize = 8.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }

                for (day in 1..7) {
                    DayColumn(
                        day = day,
                        week = week,
                        courses = courses,
                        maxNode = maxNode,
                        isToday = day == todayIndex,
                        dark = dark,
                        onCourseClick = onCourseClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(88.dp))
        }
    }
}

@Composable
private fun DayColumn(
    day: Int,
    week: Int,
    courses: List<CourseEntity>,
    maxNode: Int,
    isToday: Boolean,
    dark: Boolean,
    onCourseClick: (CourseEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val groups = remember(courses, day, week, maxNode) {
        courses
            .filter { it.dayOfWeek == day && it.activeIn(week) }
            .sortedBy { it.startNode }
            .groupByOverlap(maxNode)
    }

    Column(modifier = modifier) {
        var node = 1
        for (group in groups) {
            val groupStart = group.first().startNode
            while (node < groupStart) {
                EmptyCell(ROW_HEIGHT, isToday)
                node++
            }

            val groupEnd = group.maxOf { it.endNode.coerceAtMost(maxNode) }
            val span = groupEnd - groupStart + 1
            val blockHeight = ROW_HEIGHT * span

            if (group.size == 1) {
                CourseBlock(
                    course = group.first(),
                    height = blockHeight,
                    dark = dark,
                    onClick = { onCourseClick(group.first()) }
                )
            } else {
                SplitCourseBlock(
                    group = group,
                    height = blockHeight,
                    dark = dark,
                    onCourseClick = onCourseClick
                )
            }

            node = groupStart + span
        }
        while (node <= maxNode) {
            EmptyCell(ROW_HEIGHT, isToday)
            node++
        }
    }
}

@Composable
private fun CourseBlock(
    course: CourseEntity,
    height: Dp,
    dark: Boolean,
    onClick: () -> Unit
) {
    val colors = remember(course.colorArgb, dark) { courseColors(course.colorArgb, dark) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(120),
        label = "coursePress"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(1.5.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(9.dp))
            .background(colors.container)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(colors.accent)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 5.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Text(
                course.name,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.content,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            if (course.room.isNotBlank()) {
                Text(
                    course.room,
                    fontSize = 8.5.sp,
                    lineHeight = 10.sp,
                    color = colors.content.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
    }
}

/**
 * 同一时段有 N 门课时，等宽并排。
 *
 * 块高取整组的跨度，组内各课按数量均分宽度 —— 这是刻意的简化：
 * 严格按各自的起止节次定位会出现「一门课从第 2 节开始、另一门从第 1 节开始」
 * 这种错位，窄格子里反而更难看清。均分至少保证每门课都露脸。
 */
@Composable
private fun SplitCourseBlock(
    group: List<CourseEntity>,
    height: Dp,
    dark: Boolean,
    onCourseClick: (CourseEntity) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(1.5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (course in group) {
                val colors = remember(course.colorArgb, dark) {
                    courseColors(course.colorArgb, dark)
                }
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (pressed) 0.92f else 1f,
                    animationSpec = tween(120),
                    label = "splitPress"
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(RoundedCornerShape(9.dp))
                        .background(colors.container)
                        .clickable(
                            interactionSource = interaction,
                            indication = null
                        ) { onCourseClick(course) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(colors.accent)
                    )
                    Text(
                        course.name,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.content,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 1.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.onSurface)
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text(
                "${group.size}",
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.surface
            )
        }
    }
}

@Composable
private fun EmptyCell(height: Dp, isToday: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(
                if (isToday) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.045f)
                } else {
                    Color.Transparent
                }
            )
            .border(
                width = 0.4.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
            )
    )
}

/**
 * 把一天内的课程按时间重叠分组成若干块。
 *
 * 采用「链式合并」：只要新课的开始节次不晚于当前组已覆盖到的最后一节，
 * 就并进同一组。这样 A 与 B 重叠、B 与 C 重叠时三节课会合并成一组统一分栏，
 * 避免出现宽度各不相同的碎片。
 */
private fun List<CourseEntity>.groupByOverlap(maxNode: Int): List<List<CourseEntity>> {
    val groups = mutableListOf<MutableList<CourseEntity>>()
    var covered = -1
    for (course in this) {
        val end = course.endNode.coerceAtMost(maxNode)
        if (groups.isEmpty() || course.startNode > covered) {
            groups.add(mutableListOf(course))
            covered = end
        } else {
            groups.last().add(course)
            if (end > covered) covered = end
        }
    }
    return groups
}

/** 1 = 周一 … 7 = 周日 */
private fun todayWeekdayIndex(): Int {
    val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    return if (dow == Calendar.SUNDAY) 7 else dow - 1
}

private fun readBytesFrom(context: Context, uri: Uri): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
}.getOrNull()

/** 取用户选中文件的显示名，用于在导入预览里回显「从哪个文件来的」 */
private fun displayNameOf(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
}.getOrNull() ?: uri.lastPathSegment.orEmpty()

private fun writeTextTo(context: Context, uri: Uri, text: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    true
}.getOrDefault(false)

/**
 * 加载背景图。有意识做的取舍：
 * - 采样到最长边 1440px —— 一周网格撑满一块 1080p 屏绰绰有余，原图动辄十几 MB；
 * - LaunchedEffect 异步解码，不卡首帧；
 * - URI 失效（图源被删）静默回退纯色网格，不弹错误。
 */
@Composable
private fun rememberBackdropBitmap(uriString: String?): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(uriString) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uriString) {
        if (uriString.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                decodeSampled(context, Uri.parse(uriString))
            }.getOrNull()
        }
    }
    return bitmap
}

private fun decodeSampled(context: Context, uri: Uri, maxDim: Int = 1440): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

/**
 * v0.4 跳周面板：网格点选 + 滑动条 + 数字输入三种方式放在同一个面板里，
 * 状态实时互通 —— 网格里点「9」，滑动条和输入框同步变 9；反之亦然。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WeekJumpPanel(
    totalWeeks: Int,
    current: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    var selected by remember { mutableStateOf(current) }
    var input by remember { mutableStateOf(current.toString()) }

    // 输入框 → 选中值：合法数字才同步，非法输入不打断打字
    fun syncFromInput(text: String) {
        input = text
        text.toIntOrNull()?.let { if (it in 1..totalWeeks) selected = it }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "跳转到指定周",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "当前第 $current 周",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // —— 周数网格：每行 6 个 ——
            val rows = (totalWeeks + 5) / 6
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in 0 until rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (col in 0 until 6) {
                            val week = row * 6 + col + 1
                            if (week > totalWeeks) {
                                Spacer(modifier = Modifier.weight(1f))
                                continue
                            }
                            val isSelected = week == selected
                            val isCurrent = week == current
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    isCurrent -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable {
                                        selected = week
                                        input = week.toString()
                                    }
                            ) {
                                Text(
                                    "$week",
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected || isCurrent) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else if (isCurrent) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 9.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // —— 滑动条 ——
            Slider(
                value = selected.toFloat(),
                onValueChange = { value ->
                    val v = value.toInt().coerceIn(1, totalWeeks)
                    selected = v
                    input = v.toString()
                },
                valueRange = 1f..totalWeeks.toFloat(),
                steps = (totalWeeks - 2).coerceAtLeast(0)
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("第 1 周", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.weight(1f))
                Text("第 $totalWeeks 周", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // —— 数字输入 ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = ::syncFromInput,
                    singleLine = true,
                    label = { Text("周数") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.width(120.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    selected = current
                    input = current.toString()
                }) {
                    Text("回到本周")
                }
                TextButton(
                    onClick = { onJump(selected) },
                    enabled = selected in 1..totalWeeks
                ) {
                    Text("跳转", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
