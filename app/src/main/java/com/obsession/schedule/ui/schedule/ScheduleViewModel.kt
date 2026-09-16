package com.obsession.schedule.ui.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obsession.schedule.data.BgConfig
import com.obsession.schedule.data.ConfigStore
import com.obsession.schedule.data.CourseEntity
import com.obsession.schedule.data.ScheduleCodec
import com.obsession.schedule.data.ScheduleRepository
import com.obsession.schedule.data.ScheduleSnapshot
import com.obsession.schedule.data.SemesterConfig
import com.obsession.schedule.data.TimeSlotEntity
import com.obsession.schedule.data.TimetableEntity
import com.obsession.schedule.data.mondayOfDay
import com.obsession.schedule.importer.HtmlScheduleImporter
import com.obsession.schedule.importer.HtmlTextDecoder
import com.obsession.schedule.importer.ParseOutcome
import com.obsession.schedule.importer.ParsedCourse
import com.obsession.schedule.widget.ScheduleWidgetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 总周数的硬上限。学期设置里允许 1..40，实际取值存在 [SemesterConfig] 中 */
const val MAX_WEEK = 40

/**
 * 待用户确认的导入预览。
 *
 * 刻意做成「先看后导」而不是直接入库：教务页面的解析不可能 100% 准确，
 * 与其让用户过几天发现课表里混进了错课再回头找原因，
 * 不如在导入前花十秒核对一遍。
 */
data class ImportPreview(
    val fileName: String,
    val sourceLabel: String,
    val charsetName: String,
    /** 页面里写的学期名，例如「2026-2027学年第1学期」 */
    val termName: String?,
    val courses: List<ParsedCourse>,
    /** 课程名 → 颜色，让同一门课在预览和课表里颜色一致 */
    val colorsByName: Map<String, Int>,
    val warnings: List<String>,
    val skipped: List<String>
) {
    val maxWeek: Int get() = courses.maxOfOrNull { it.endWeek } ?: 1
    val distinctCourseCount: Int get() = courses.map { it.name }.distinct().size
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext
    private val repository = ScheduleRepository(app)
    private val configStore = ConfigStore(app)

    // ------------------------------------------------------------------
    // 多课表
    // ------------------------------------------------------------------

    val timetables: StateFlow<List<TimetableEntity>> = repository.observeTimetables()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _activeId = MutableStateFlow(0L)
    val activeId: StateFlow<Long> = _activeId.asStateFlow()

    /**
     * 当前课表。_activeId 还没初始化（启动首帧）或库里为空时是 null，
     * 界面层拿它做空态判断。
     */
    val activeTimetable: StateFlow<TimetableEntity?> =
        combine(timetables, _activeId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val config: StateFlow<SemesterConfig> = activeTimetable
        .map { it?.toConfig() ?: SemesterConfig.default() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SemesterConfig.default())

    val courses: StateFlow<List<CourseEntity>> = _activeId
        .flatMapLatest { id -> if (id <= 0) MutableStateFlow(emptyList()) else repository.observeCourses(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val timeSlots: StateFlow<List<TimeSlotEntity>> = _activeId
        .flatMapLatest { id -> if (id <= 0) MutableStateFlow(emptyList()) else repository.observeTimeSlots(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 初始周次定位到「今天在第几周」，而不是写死第 1 周。
     *
     * 学期配置里既然已经有了第一周起始日，就没有理由让用户每次打开应用
     * 都手动翻十几下翻到本周。放假期间算出来会越界，夹到合法范围内。
     */
    private val _week = MutableStateFlow(1)
    val week: StateFlow<Int> = _week.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importPreview = MutableStateFlow<ImportPreview?>(null)
    val importPreview: StateFlow<ImportPreview?> = _importPreview.asStateFlow()

    /** 当前课表的背景设置（图片 URI + 蒙层浓度） */
    private val _bgConfig = MutableStateFlow(BgConfig())
    val bgConfig: StateFlow<BgConfig> = _bgConfig.asStateFlow()

    init {
        viewModelScope.launch {
            // v0.3 全新安装：写入默认课表 + 作息。v0.2 升级用户在迁移里已经搬好数据
            repository.seedIfNeeded()
            val list = repository.allTimetables()
            if (list.isNotEmpty()) {
                val wanted = configStore.activeId(list.first().id)
                _activeId.value = list.firstOrNull { it.id == wanted }?.id ?: list.first().id
            }
        }
        // 课表切换后背景配置跟着换；这是个长期协程，ViewModel 存活期间一直监听
        viewModelScope.launch {
            _activeId.collect { id ->
                if (id > 0) _bgConfig.value = configStore.loadBackground(id)
            }
        }
    }

    private val totalWeeks: Int get() = config.value.totalWeeks

    fun previousWeek() = _week.update { (it - 1).coerceAtLeast(1) }

    fun nextWeek() = _week.update { (it + 1).coerceAtMost(config.value.totalWeeks) }

    fun jumpToWeek(target: Int) = _week.update { target.coerceIn(1, config.value.totalWeeks) }

    /** 跳回「今天」所在的周；放假期间落到最近的合法周 */
    fun jumpToToday() = jumpToWeek(config.value.weekOfDay(System.currentTimeMillis()))

    // ------------------------------------------------------------------
    // 课表管理
    // ------------------------------------------------------------------

    /** 切换当前课表。切换后周次自动定位到该学期的「今天」 */
    fun switchTimetable(id: Long) {
        if (id == _activeId.value || id <= 0) return
        configStore.setActiveId(id)
        _activeId.value = id
        viewModelScope.launch {
            val timetable = repository.timetable(id) ?: return@launch
            val c = timetable.toConfig()
            _week.value = c.weekOfDay(System.currentTimeMillis()).coerceIn(1, c.totalWeeks)
            refreshWidgets()
        }
    }

    /** 新建课表。学期起始日归一到周一，与旧存储口径一致 */
    fun createTimetable(name: String, semester: SemesterConfig) = viewModelScope.launch {
        val safe = semester.copy(
            termName = semester.termName.trim(),
            firstWeekStart = mondayOfDay(semester.firstWeekStart),
            totalWeeks = semester.totalWeeks.coerceIn(1, 40)
        )
        val id = repository.createTimetable(
            name = name.trim().ifBlank { safe.termName },
            config = safe
        )
        switchTimetable(id)
        _message.value = "已创建「${name.trim().ifBlank { safe.termName }}」"
    }

    fun renameTimetable(id: Long, name: String) = viewModelScope.launch {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return@launch
        val timetable = repository.timetable(id) ?: return@launch
        repository.updateTimetable(timetable.copy(name = trimmed))
        if (id == _activeId.value) refreshWidgets()
    }

    /** 删除课表。正在使用的课表拒绝删除 —— 界面层会先置灰，这里兜底 */
    fun deleteTimetable(id: Long) = viewModelScope.launch {
        if (id == _activeId.value) {
            _message.value = "正在使用的课表不能删除，请先切换到别的课表"
            return@launch
        }
        val timetable = repository.timetable(id) ?: return@launch
        repository.deleteTimetable(id)
        _message.value = "已删除「${timetable.name}」"
    }

    // ------------------------------------------------------------------
    // 背景
    // ------------------------------------------------------------------

    fun saveBackground(next: BgConfig) {
        val id = _activeId.value
        if (id <= 0) return
        configStore.saveBackground(id, next)
        _bgConfig.value = next
    }

    // ------------------------------------------------------------------
    // 周次 / 学期 / 课程 / 作息
    // ------------------------------------------------------------------

    fun saveConfig(next: SemesterConfig) = viewModelScope.launch {
        val current = activeTimetable.value ?: return@launch
        val safe = next.copy(
            termName = next.termName.trim(),
            firstWeekStart = mondayOfDay(next.firstWeekStart),
            totalWeeks = next.totalWeeks.coerceIn(1, 40)
        )
        repository.updateTimetable(
            current.copy(
                termName = safe.termName,
                firstWeekStart = safe.firstWeekStart,
                totalWeeks = safe.totalWeeks
            )
        )
        _week.update { it.coerceIn(1, safe.totalWeeks) }
        refreshWidgets()
        _message.value = "学期设置已保存"
    }

    fun save(course: CourseEntity) = viewModelScope.launch {
        // 课程必须挂在当前课表下 —— 编辑器只管字段，归属由这里统一注入
        val owned = if (course.timetableId == _activeId.value) {
            course
        } else {
            course.copy(timetableId = _activeId.value)
        }
        repository.upsert(owned)
        refreshWidgets()
        _message.value = if (course.id == 0L) {
            "已添加「${course.name}」"
        } else {
            "已更新「${course.name}」"
        }
    }

    fun delete(course: CourseEntity) = viewModelScope.launch {
        repository.delete(course)
        refreshWidgets()
        _message.value = "已删除「${course.name}」"
    }

    // ------------------------------------------------------------------
    // 已添加课程：批量管理
    // ------------------------------------------------------------------

    /** 批量删除所选课程分节 */
    fun deleteCoursesByIds(ids: List<Long>) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        repository.deleteCoursesByIds(ids)
        refreshWidgets()
        _message.value = "已删除 ${ids.size} 节课程"
    }

    /**
     * 对所选课程分节统一应用一个字段改动。
     * [label] 用于提示语；[transform] 只改目标字段。
     */
    fun applyCourseChange(
        targets: List<CourseEntity>,
        label: String,
        transform: (CourseEntity) -> CourseEntity
    ) = viewModelScope.launch {
        if (targets.isEmpty()) return@launch
        repository.updateCourses(targets, transform)
        refreshWidgets()
        _message.value = "已${label} ${targets.size} 节课程"
    }

    /** 保存作息设置。界面已做过校验，这里再兜一层，避免脏数据落库 */
    fun saveTimeSlots(slots: List<TimeSlotEntity>) = viewModelScope.launch {
        val blocking = TimeSlotEntity.inspect(slots).filter { it.blocking }
        if (blocking.isNotEmpty()) {
            _message.value = blocking.first().message
            return@launch
        }
        val id = _activeId.value
        if (id <= 0) return@launch
        repository.saveTimeSlots(id, slots)
        // 上下课时间变了，小组件的「下一节 / 已上过」判断也跟着变
        refreshWidgets()
        _message.value = "已保存 ${slots.size} 节课的时间"
    }

    /** 导出当前课表为文本，由界面负责写到用户选择的位置 */
    suspend fun exportText(): String {
        val id = _activeId.value
        val snapshot = if (id > 0) repository.snapshot(id) else ScheduleSnapshot(emptyList(), emptyList())
        return ScheduleCodec.toJson(snapshot)
    }

    // ------------------------------------------------------------------
    // 文件导入
    // ------------------------------------------------------------------

    /**
     * 处理用户选中的文件。
     *
     * 同一个入口要吃两种文件，所以先按内容分派：
     * - HTML（教务课表页另存）→ 走解析器，出预览
     * - 其它（`.wakeup_schedule` / 本应用导出的 JSON）→ 直接走原有通道
     */
    fun openFile(fileName: String, bytes: ByteArray) = viewModelScope.launch {
        if (bytes.isEmpty()) {
            _message.value = "文件是空的"
            return@launch
        }

        if (!HtmlScheduleImporter.isHtml(bytes)) {
            importText(HtmlTextDecoder.decode(bytes).text)
            return@launch
        }

        // decodeHtml 内部会先拆 MHTML 信封（.mht/.mhtml），再做字符集探测
        val decoded = HtmlScheduleImporter.decodeHtml(bytes)
        val warnings = buildList {
            if (decoded.text.contains('\uFFFD')) {
                add("文件里存在无法解码的字符，课程名可能有乱码——请确认原页面编码")
            }
        }
        showHtmlPreview(fileName, decoded.charsetName, decoded.text, warnings)
    }

    /**
     * 内置浏览器导入：WebView 抓到的页面 HTML 直接进解析。
     * 浏览器已经把 GBK 等老编码解成了 Unicode，这里不再做编码探测。
     */
    fun importHtmlFromBrowser(html: String) {
        if (html.isBlank()) {
            _message.value = "页面内容是空的"
            return
        }
        viewModelScope.launch { showHtmlPreview("教务页面", "浏览器", html, emptyList()) }
    }

    private suspend fun showHtmlPreview(
        fileName: String,
        charsetName: String,
        html: String,
        warnings: List<String>
    ) {
        when (val outcome = HtmlScheduleImporter.parse(html)) {
            is ParseOutcome.Success -> {
                _importPreview.value = ImportPreview(
                    fileName = fileName,
                    sourceLabel = outcome.source.label,
                    charsetName = charsetName,
                    termName = outcome.termName,
                    courses = outcome.courses,
                    colorsByName = assignColors(outcome.courses),
                    warnings = warnings,
                    skipped = outcome.skipped
                )
            }

            is ParseOutcome.Unsupported -> _message.value = outcome.hint

            is ParseOutcome.Failure -> _message.value = outcome.reason
        }
    }

    /** 用户核对过预览后确认导入 */
    fun confirmImport(keepExisting: Boolean) = viewModelScope.launch {
        val preview = _importPreview.value ?: return@launch
        _importPreview.value = null

        val id = _activeId.value
        if (id <= 0) return@launch

        val entities = preview.courses.map { parsed ->
            CourseEntity(
                name = parsed.name,
                teacher = parsed.teacher,
                room = parsed.room,
                dayOfWeek = parsed.dayOfWeek,
                startNode = parsed.startNode,
                step = parsed.step,
                startWeek = parsed.startWeek,
                endWeek = parsed.endWeek,
                weekType = parsed.weekType,
                colorArgb = preview.colorsByName[parsed.name] ?: COURSE_PALETTE.first(),
                timetableId = id
            )
        }

        repository.importCourses(id, entities, keepExisting)

        // 导入的周次可能超出当前总周数设置，那样第 15 周以后的课会翻不到。
        // 这里把总周数自动放宽到能覆盖导入内容，只放宽、不缩小。
        val current = activeTimetable.value ?: return@launch
        val nextTotal = maxOf(current.totalWeeks, preview.maxWeek).coerceIn(1, MAX_WEEK)
        if (nextTotal != current.totalWeeks) {
            repository.updateTimetable(current.copy(totalWeeks = nextTotal))
            _week.update { it.coerceIn(1, nextTotal) }
        }
        // 教务页面里的学期名比本地起的更准，同步过去（课表显示名不动）
        val newTermName = preview.termName
        if (!newTermName.isNullOrBlank() && newTermName != current.termName) {
            repository.updateTimetable(
                (repository.timetable(id) ?: current).copy(termName = newTermName)
            )
        }

        refreshWidgets()

        val action = if (keepExisting) "追加" else "导入"
        val extra = buildString {
            if (!newTermName.isNullOrBlank() && newTermName != current.termName) {
                append("，学期名已同步为「$newTermName」")
            }
            if (nextTotal != current.totalWeeks) append("，总周数已放宽到 $nextTotal 周")
        }
        _message.value = "已$action ${entities.size} 条课程记录（${preview.distinctCourseCount} 门课）$extra"
    }

    fun dismissImportPreview() {
        _importPreview.value = null
    }

    fun importText(text: String) = viewModelScope.launch {
        val id = _activeId.value
        if (id <= 0) return@launch
        when (val result = ScheduleCodec.parse(text)) {
            is ScheduleCodec.ImportResult.Success -> {
                repository.replaceAll(id, result.snapshot)
                refreshWidgets()
                _message.value = "已导入 ${result.snapshot.courses.size} 门课程（${result.source}）"
            }

            is ScheduleCodec.ImportResult.Failure -> {
                _message.value = result.reason
            }
        }
    }

    fun notify(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }

    /**
     * 给导入的课程分配颜色。
     *
     * 按「课程名第一次出现的顺序」依次取调色板，而不是按记录顺序 ——
     * 同一门课分散在周一到周五，必须拿到同一个颜色，否则课表会花成一片。
     * 课程数超过调色板长度时循环取用，这是可接受的：色相本来就有限。
     */
    private fun assignColors(courses: List<ParsedCourse>): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        for (course in courses) {
            if (!result.containsKey(course.name)) {
                result[course.name] = COURSE_PALETTE[result.size % COURSE_PALETTE.size]
            }
        }
        return result
    }

    /**
     * 刷新桌面小组件。
     *
     * 任何会影响「今天上什么课」的改动之后都要调一次 —— 包括改作息、切换课表，
     * 因为小组件靠上下课时间判断一门课是「正在上」还是「已上过」。
     * 切到 IO 是因为 refreshAll 内部用 runBlocking 查库，不能占着主线程。
     */
    private suspend fun refreshWidgets() {
        withContext(Dispatchers.IO) { ScheduleWidgetRenderer.refreshAll(appContext) }
    }
}
