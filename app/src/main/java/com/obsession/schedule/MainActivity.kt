package com.obsession.schedule

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.obsession.schedule.browser.BrowserImportActivity
import com.obsession.schedule.ui.manage.CourseManageScreen
import com.obsession.schedule.ui.manage.ManageScreen
import com.obsession.schedule.ui.schedule.ScheduleScreen
import com.obsession.schedule.ui.schedule.ScheduleViewModel
import com.obsession.schedule.ui.settings.SettingsScreen
import com.obsession.schedule.ui.theme.ObsessionTheme
import com.obsession.schedule.ui.theme.appDarkTheme
import com.obsession.schedule.widget.ScheduleWidgetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 主界面的屏。序号即左右切换动画的方向（课表 → 管理 → 已添加课程 → 设置）。
 */
enum class Screen { SCHEDULE, MANAGE, COURSES, SETTINGS }

class MainActivity : ComponentActivity() {

    private val viewModel: ScheduleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleImportIntent(intent)
        setContent {
            val dark = appDarkTheme()
            // 系统栏图标颜色跟随应用内主题（而非系统外观），
            // 否则「系统浅色 + 强制深色」时状态栏会是黑图标看不清
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { dark }
                )
            }
            ObsessionTheme {
                MainContent(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImportIntent(intent)
    }

    /**
     * 两类导入入口都汇到这里：
     * 1. 内置浏览器解析成功 → PendingImport 中转（HTML 太大不走 Intent）；
     * 2. 系统分享（微信/QQ「发送文件」）与文件关联（MT 管理器「打开方式」）→
     *    ACTION_SEND / ACTION_VIEW，读出字节后走与「选文件」完全相同的 openFile 通道，
     *    由 ViewModel 按内容分派 HTML / MHTML / JSON。
     */
    private fun handleImportIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(BrowserImportActivity.EXTRA_SHOW_IMPORT, false) == true) {
            PendingImport.consume()?.let { viewModel.importHtmlFromBrowser(it) }
            return
        }
        when (intent?.action) {
            Intent.ACTION_SEND -> handleSend(intent)
            Intent.ACTION_VIEW -> handleView(intent)
        }
    }

    /** 微信等客户端「发送文件」：HTML 以 EXTRA_STREAM（content://）形式给 */
    private fun handleSend(intent: Intent) {
        val uri: Uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) ?: run {
            // 没有 stream 时看 text：有些客户端直接给 HTML 文本
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank() && text.contains('<')) {
                viewModel.importHtmlFromBrowser(text)
            }
            return
        }
        readUriAsync(uri, label = displayNameOf(uri))
    }

    /** 文件管理器「打开方式」：URI 在 data 里 */
    private fun handleView(intent: Intent) {
        val uri = intent.data ?: return
        readUriAsync(uri, label = displayNameOf(uri))
    }

    private fun readUriAsync(uri: Uri, label: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            launch(Dispatchers.Main) {
                if (bytes == null) {
                    viewModel.notify("读取文件失败")
                } else {
                    viewModel.openFile(label, bytes)
                }
            }
        }
    }

    private fun displayNameOf(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    override fun onResume() {
        super.onResume()
        // 打开应用时顺手刷一次小组件：覆盖「改完数据没刷、跨天没触发」等一切遗漏时机。
        // 这也是 v0.3 小组件不更新修复链条的最后一环。
        lifecycleScope.launch(Dispatchers.IO) {
            ScheduleWidgetRenderer.refreshAll(applicationContext)
        }
    }
}

@Composable
private fun MainContent(viewModel: ScheduleViewModel) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.SCHEDULE) }

    fun openBrowser() {
        context.startActivity(Intent(context, BrowserImportActivity::class.java))
    }

    // v0.4 导入交互自动化：任何入口产生导入预览时，自动切回课表主页弹窗，
    // 用户不用再手动从设置页/管理页退回来
    LaunchedEffect(viewModel) {
        viewModel.importPreview.collect { preview ->
            if (preview != null) screen = Screen.SCHEDULE
        }
    }

    // 二级屏按系统返回键 = 返回一级课表（此前缺 BackHandler 会直接退到桌面）
    BackHandler(enabled = screen != Screen.SCHEDULE) {
        screen = Screen.SCHEDULE
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            // 屏间按序号从左往右，返回时反向；v0.5.0 统一缓动曲线与时长体系
            val forward = targetState.ordinal > initialState.ordinal
            val ease = androidx.compose.animation.core.FastOutSlowInEasing
            (
                slideInHorizontally(tween(280, easing = ease)) { full: Int ->
                    if (forward) full / 4 else -full / 4
                } + fadeIn(tween(240, easing = ease))
                ) togetherWith (
                slideOutHorizontally(tween(240, easing = ease)) { full: Int ->
                    if (forward) -full / 4 else full / 4
                } + fadeOut(tween(200, easing = ease))
                )
        },
        label = "screen"
    ) { target ->
        when (target) {
            Screen.SCHEDULE -> ScheduleScreen(
                viewModel = viewModel,
                onOpenManage = { screen = Screen.MANAGE },
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenBrowser = { openBrowser() }
            )

            Screen.MANAGE -> ManageScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.SCHEDULE },
                onOpenCourses = { screen = Screen.COURSES }
            )

            Screen.COURSES -> CourseManageScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.MANAGE }
            )

            Screen.SETTINGS -> SettingsScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.SCHEDULE },
                onOpenBrowser = { openBrowser() }
            )
        }
    }
}
