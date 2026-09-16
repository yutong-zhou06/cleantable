package com.obsession.schedule.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.obsession.schedule.MainActivity
import com.obsession.schedule.PendingImport
import com.obsession.schedule.importer.ParseOutcome
import com.obsession.schedule.ui.theme.ObsessionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener

/**
 * 内置浏览器导入。
 *
 * 流程：用户输入教务网址 → 自己登录（密码不经过本应用）→ 打开课表页面
 * → 点底部悬浮「解析此页」→ 取当前页面 DOM → 解析 → 跳回主页出预览。
 *
 * 两个刻意的设计：
 * 1. 桌面 UA —— 大量老教务会给手机浏览器返回一个功能残缺的简化页，取不到课表；
 * 2. Cookie 即用即清 —— 离开本页面立刻清掉全部 Cookie 与 LocalStorage，
 *    登录态不落盘。这是打破「零联网」后必须守住的安全底线。
 */
class BrowserImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 上次导入的临时文件残留与过期失败样本，打开时顺手清掉
        Thread { TempPageStore.cleanStaleFiles(this) }.start()
        setContent {
            ObsessionTheme {
                BrowserScreen(
                    onClose = { finish() },
                    onParsed = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        // 登录态即用即清：无论正常关闭还是被系统回收，离开即清空
        runCatching {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            WebStorage.getInstance().deleteAllData()
        }
        super.onDestroy()
    }

    companion object {
        /** MainActivity 收到后从 [PendingImport] 取 HTML 并弹导入预览 */
        const val EXTRA_SHOW_IMPORT = "show_import_preview"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserScreen(onClose: () -> Unit, onParsed: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var urlInput by remember { mutableStateOf("") }
    var browsing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    // v0.4 自动识别：页面加载完后探测课表表格特征，命中则悬浮按钮高亮
    var scheduleDetected by remember { mutableStateOf(false) }
    // v0.4.1 解析失败自动留样：临时文件已转存，这里一键导出给开发者适配
    var lastSample by remember { mutableStateOf<java.io.File?>(null) }

    val webView = remember { mutableStateOf<WebView?>(null) }

    // 失败样本导出：把留存的页面样本写到用户选择的位置
    val exportSampleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        val sample = lastSample ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    sample.inputStream().use { it.copyTo(out) }
                } != null
            }.getOrDefault(false)
            if (ok) sample.delete()
            launch(Dispatchers.Main) {
                if (ok) lastSample = null
            }
        }
    }

    // 页面另存（长按悬浮按钮呼出）：未知教务给不出解析样本时的取证通路
    val savePageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val view = webView.value ?: return@rememberLauncherForActivityResult
        // v0.4.2：与「点导入」走同一套探测 + 抓取（原先直接取主文档 outerHTML，
        // 课表在 iframe 里会存出空白页；且原先的 JSONArray 解析必抛异常 →
        // 保存下来的永远是 0 字节文件）
        view.evaluateJavascript(SCHEDULE_PROBE_JS) { probeRaw ->
            val frameIndex = probeRaw?.trim()
                ?.removeSurrounding("\"")?.toIntOrNull() ?: -1
            view.evaluateJavascript(grabHtmlJs(frameIndex)) { raw ->
                val html = jsonStringValue(raw)
                scope.launch(Dispatchers.IO) {
                    if (html == null) {
                        launch(Dispatchers.Main) { parseError = "没有读到页面内容，请等页面加载完成后再试" }
                        return@launch
                    }
                    val ok = runCatching {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(html.toByteArray(Charsets.UTF_8))
                        } != null
                    }.getOrDefault(false)
                    launch(Dispatchers.Main) {
                        parseError = if (ok) null else "保存失败"
                    }
                }
            }
        }
    }

    fun goHome() {
        parseError = null
        browsing = true
    }

    fun extractAndParse() {
        val view = webView.value ?: return
        loading = true
        parseError = null
        lastSample = null

        // 第一步：探测课表住在主文档还是某个 iframe 里
        view.evaluateJavascript(SCHEDULE_PROBE_JS) { probeRaw ->
            val frameIndex = probeRaw?.trim()
                ?.removeSurrounding("\"")?.toIntOrNull() ?: -1
            // 第二步：抓取课表所在层的完整 HTML
            view.evaluateJavascript(grabHtmlJs(frameIndex)) { rawHtml ->
                val html = jsonStringValue(rawHtml)
                scope.launch {
                    // 第三步：落临时文件 → 与文件导入完全相同的解析管线 → 成功即删/失败留样
                    val result = withContext(Dispatchers.IO) {
                        if (html.isNullOrBlank()) null
                        else TempPageStore.saveAndParse(context, html)
                    }
                    loading = false
                    if (result == null) {
                        parseError = "没有读到页面内容，请等页面加载完成后再试"
                        return@launch
                    }
                    when (val outcome = result.outcome) {
                        is ParseOutcome.Success -> {
                            // 整页 HTML 可能超过 Intent 事务上限，走单例中转
                            PendingImport.html = result.html
                            context.startActivity(
                                Intent(context, MainActivity::class.java).apply {
                                    putExtra(BrowserImportActivity.EXTRA_SHOW_IMPORT, true)
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    )
                                }
                            )
                            onParsed()
                        }

                        is ParseOutcome.Unsupported -> {
                            lastSample = result.sampleFile
                            parseError = outcome.hint
                        }

                        is ParseOutcome.Failure -> {
                            lastSample = result.sampleFile
                            parseError = outcome.reason
                        }
                    }
                }
            }
        }
    }

    // WebView 在原生返回键上先回退网页
    BackHandler(enabled = browsing && canGoBack) {
        webView.value?.goBack()
    }
    BackHandler(enabled = !browsing) { onClose() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // ---------- 顶栏 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (browsing && canGoBack) webView.value?.goBack() else onClose()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "导入教务课表",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                if (browsing) webView.value?.reload() else onClose()
            }) {
                Icon(
                    if (browsing) Icons.Default.Refresh else Icons.Default.Close,
                    contentDescription = if (browsing) "刷新" else "关闭"
                )
            }
        }

        if (!browsing) {
            // ---------- 首屏：输入网址 + 隐私说明 ----------
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text("教务", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "输入学校教务系统的网址",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "登录由你亲自完成，密码不会经过本应用；\n打开课表页面后，点下方「解析此页」自动读取。",
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如 jwgl.xxx.edu.cn") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(onGo = { goHome() }),
                    colors = OutlinedTextFieldDefaults.colors(),
                    shape = RoundedCornerShape(14.dp)
                )
                if (parseError != null) {
                    Text(
                        parseError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { goHome() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("进入教务网站", modifier = Modifier.padding(vertical = 6.dp))
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    "· 支持正方教务新版页面\n· 也保留「从文件导入」作为备用通道\n· 关闭本页面会立即清除登录记录",
                    fontSize = 11.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // ---------- WebView + 悬浮解析按钮 ----------
            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            @SuppressLint("SetJavaScriptEnabled")
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // 老教务给手机 UA 返回简化页，拿不到课表；伪装桌面浏览器
                            settings.userAgentString =
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.setSupportMultipleWindows(false)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean = false

                                // 同步「能否后退」给系统返回键与顶栏返回按钮
                                override fun doUpdateVisitedHistory(
                                    view: WebView,
                                    url: String?,
                                    isReload: Boolean
                                ) {
                                    canGoBack = view.canGoBack()
                                    // 正方这类 SPA 在站内跳转不触发 onPageFinished，
                                    // 路由一变就补一次探测（已命中就不再打扰）
                                    if (!scheduleDetected) {
                                        probeSchedule(view) { idx ->
                                            if (idx >= 0) scheduleDetected = true
                                        }
                                    }
                                }

                                /**
                                 * v0.4 自动识别：每次页面加载完成都探测一次课表表格
                                 * 特征，命中即把悬浮按钮点亮。探测在页面 JS 里完成，
                                 * 拿不到表格就是 -1，不猜。
                                 */
                                override fun onPageFinished(view: WebView, url: String?) {
                                    probeWithRetry(view) { scheduleDetected = true }
                                }
                            }
                            webChromeClient = WebChromeClient()
                            webView.value = this@apply
                        }
                    },
                    update = { view ->
                        val target = normalizeUrl(urlInput)
                        if (view.url == null && target != null) {
                            view.loadUrl(target)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 悬浮「解析此页」：自动识别命中时变绿高亮；长按 = 把当前页面另存为 HTML
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (parseError != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text(
                                    parseError.orEmpty(),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 12.sp
                                )
                                if (lastSample != null) {
                                    Text(
                                        "已留存失败页面样本 · 点此导出发给开发者适配",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                exportSampleLauncher.launch("obsession_sample.html")
                                            }
                                    )
                                }
                            }
                        }
                    }
                    if (scheduleDetected && !loading) {
                        Text(
                            "检测到课表页面",
                            fontSize = 11.5.sp,
                            color = DETECT_GREEN,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = if (scheduleDetected) DETECT_GREEN else MaterialTheme.colorScheme.primary,
                        shadowElevation = 6.dp,
                        modifier = Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { if (!loading) extractAndParse() },
                            onLongClick = { savePageLauncher.launch("教务页面.html") }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(
                                    "正在解析…",
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(
                                    if (scheduleDetected) "发现课表 · 点击导入" else "解析此页",
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                    if (!loading) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "长按可将页面另存为 HTML",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

/** 把用户输入补全成可访问的 URL；空输入返回 null */
internal fun normalizeUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        else -> "https://$trimmed"
    }
}

/**
 * 课表特征探测（v0.4.1）：遍历主文档与所有同源 iframe，返回课表所在层的
 * 下标（-1 = 没找到）。不少教务把课表页面装在 iframe 里，只抓主文档的
 * outerHTML 永远解析出 0 门课 —— 这是 v0.4「点解析没反应」的直接根因。
 *
 * 只认确定的结构指纹：正方新版列表模式 #kblist_table、课表模式 kbgrid_table_*。
 * 跨域 iframe 的 contentDocument 拿不到（浏览器安全模型），try-catch 跳过不猜。
 */
private const val SCHEDULE_PROBE_JS =
    "(function(){" +
        "var docs=[document];" +
        "var fs=document.querySelectorAll('iframe,frame');" +
        "for(var i=0;i<fs.length;i++){" +
        "try{if(fs[i].contentDocument&&fs[i].contentDocument.documentElement)docs.push(fs[i].contentDocument);}catch(e){}" +
        "}" +
        "for(var j=0;j<docs.length;j++){" +
        "var d=docs[j];" +
        "if(d.getElementById('kblist_table')||d.querySelector('[id^=kbgrid_table]'))return j;" +
        "}" +
        "return -1;" +
        "})()"

/**
 * 按 [SCHEDULE_PROBE_JS] 定下的层序，抓取指定层的完整 HTML；-1 = 主文档。
 *
 * 结果**必须包成单元素数组**：evaluateJavascript 的字符串结果回来时是带引号的
 * JSON 字符串，裸字符串不是合法 JSON 数组（v0.4.1 的必现 bug 根因）。
 * 包成数组后两端都稳：数组形态可被 JSONArray 直接取第 0 项。
 */
private fun grabHtmlJs(frameIndex: Int): String =
    "(function(){" +
        "var docs=[document];" +
        "var fs=document.querySelectorAll('iframe,frame');" +
        "for(var i=0;i<fs.length;i++){" +
        "try{if(fs[i].contentDocument&&fs[i].contentDocument.documentElement)docs.push(fs[i].contentDocument);}catch(e){}" +
        "}" +
        "var d=(" + frameIndex + ">=0&&" + frameIndex + "<docs.length)?docs[" + frameIndex + "]:document;" +
        "return [d?d.documentElement.outerHTML:''];" +
        "})()"

/**
 * 解析 evaluateJavascript 的字符串结果。
 *
 * WebView 回调值统一是 JSON 字面量：字符串会带引号（`"<html>…"`），
 * **不是一个 JSON 数组** —— v0.4.1 用 `JSONArray(rawHtml)` 直接构造，
 * JSONArray 要求首字符是 `[`，于是每次都抛异常、html 恒为 null，
 * 用户侧表现为「点导入必报没有读到页面内容」（v0.4 起一直如此）。
 *
 * 现在两种形态都收：JS 返回 `[html]` 走数组分支，返回裸字符串走 JSONTokener。
 * 拿不到就返回 null，由调用方给出「没读到内容」的提示。
 */
private fun jsonStringValue(raw: String?): String? {
    val text = raw?.trim() ?: return null
    if (text == "null" || text.length < 2) return null
    return if (text.startsWith("[")) {
        runCatching { JSONArray(text).optString(0) }.getOrNull()?.takeIf { it.isNotEmpty() }
    } else {
        runCatching { JSONTokener(text).nextValue() as? String }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * 探测一次并把结果回调到主线程。evaluateJavascript 的回调值是 JSON 字面量，
 * 数字会原样回来，字符串会带引号 —— 两种都兼容。
 */
private fun probeSchedule(view: WebView, onResult: (Int) -> Unit) {
    view.evaluateJavascript(SCHEDULE_PROBE_JS) { raw ->
        val index = raw?.trim()
            ?.removeSurrounding("\"")?.toIntOrNull() ?: -1
        onResult(index)
    }
}

/**
 * 页面加载完成后的探测：立即一次，未命中再延迟补两次 ——
 * 相当一部分教务的课表表格是异步渲染的，onPageFinished 时 DOM 还没长出来。
 * 命中时回调 [onDetected]（内部已保证切回主线程回调）。
 */
private fun probeWithRetry(view: WebView, onDetected: () -> Unit) {
    probeSchedule(view) { idx ->
        if (idx >= 0) {
            onDetected()
            return@probeSchedule
        }
        listOf(1500L, 3500L).forEach { delay ->
            view.postDelayed({
                runCatching { probeSchedule(view) { i -> if (i >= 0) onDetected() } }
            }, delay)
        }
    }
}

/** 自动识别命中的高亮色。绿色在两套主题下都够醒目，也与「解析中」的蓝色区分开 */
private val DETECT_GREEN = androidx.compose.ui.graphics.Color(0xFF1D9E75)
