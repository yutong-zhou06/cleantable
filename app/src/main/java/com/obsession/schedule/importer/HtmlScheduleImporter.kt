package com.obsession.schedule.importer

import org.jsoup.Jsoup

/**
 * 本地 HTML 课表文件的解析入口。
 *
 * 这里只做两件事：判断「是不是 HTML」，以及把 DOM 交给对应解析器。
 * 编码探测在 [HtmlTextDecoder]，具体解析在 [ZhengFangNewParser]。
 *
 * 整个链路是纯本地、纯离线的 —— 不发起任何网络请求，所以应用不需要
 * INTERNET 权限，也就不存在账号密码被带走的问题。用户在浏览器里自己
 * 登录教务系统、把页存下来，App 只负责读文件。
 */
object HtmlScheduleImporter {

    /**
     * 粗略判断是不是 HTML。
     *
     * 不做严格校验 —— 目的只是把 HTML 和 `.wakeup_schedule`（JSON）区分开，
     * 让同一个「导入课表文件」入口能吃两种文件。真正的判断交给解析器：
     * 解析不出东西会明确报错，不会静默出错。
     */
    fun isHtml(bytes: ByteArray): Boolean {
        // MHTML（浏览器「网页，单个文件」）本质也是 HTML，只是外面套了层 MIME 信封；
        // 它的文件头是 `From: <Saved by ...>`，下面的 HTML 特征检查认不出来，必须先判
        if (MhtmlDecoder.isMhtml(bytes)) return true

        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            .lowercase()
            .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return head.startsWith("<!doctype") ||
            head.startsWith("<html") ||
            head.startsWith("<?xml") ||
            head.contains("<table") ||
            head.contains("<meta") ||
            head.contains("<body")
    }

    /**
     * 统一的解码入口：先拆 MHTML 信封（如果是），再做字符集探测。
     *
     * 顺序不能反——MHTML 里主 HTML 部件是 quoted-printable 编码的字节流，
     * 直接对整个文件做字符集探测会读到一堆 MIME 头，声明探测必然错乱。
     */
    fun decodeHtml(bytes: ByteArray): DecodedHtml {
        val htmlBytes = if (MhtmlDecoder.isMhtml(bytes)) {
            MhtmlDecoder.extractHtml(bytes) ?: bytes
        } else {
            bytes
        }
        return HtmlTextDecoder.decode(htmlBytes)
    }

    fun parse(html: String): ParseOutcome {
        if (html.isBlank()) {
            return ParseOutcome.Failure("文件是空的，没有可解析的内容")
        }

        val doc = try {
            Jsoup.parse(html)
        } catch (e: Exception) {
            return ParseOutcome.Failure("HTML 解析失败：${e.message ?: e.javaClass.simpleName}")
        }

        val source = ZhengFangNewParser.detect(doc)

        if (!source.supported) {
            return ParseOutcome.Unsupported(source, hintFor(source))
        }

        val primary = ZhengFangNewParser.parse(doc, source)
        if (primary != null && primary.courses.isNotEmpty()) return primary

        // 首选解析器没出结果时，试另一种正方模式：有的学校只导出了其中一张表
        val alternate = when (source) {
            ScheduleSource.ZHENG_FANG_NEW_LIST -> ScheduleSource.ZHENG_FANG_NEW_GRID
            ScheduleSource.ZHENG_FANG_NEW_GRID -> ScheduleSource.ZHENG_FANG_NEW_LIST
            else -> null
        }
        if (alternate != null) {
            ZhengFangNewParser.parse(doc, alternate)?.let {
                if (it.courses.isNotEmpty()) return it
            }
        }

        return ParseOutcome.Failure(
            "识别为${source.label}，但没能从里面解析出任何课程。\n" +
                "常见原因：课表数据是登录后异步加载的，另存为 HTML 时没存进去。" +
                "可以先用记事本打开这个文件，搜一下某门课的名字——如果搜不到，就属于这种情况。"
        )
    }

    private fun hintFor(source: ScheduleSource): String = when (source) {
        ScheduleSource.ZHENG_FANG_OLD ->
            "已识别为正方教务旧版页面，这一版还没适配它的解析规则。\n" +
                "把文件发我，我按它的表格结构补一个解析器（改动很小，解析器是独立模块）。"

        ScheduleSource.QIANG_ZHI ->
            "已识别为强智教务页面，这一版还没适配它的解析规则。\n" +
                "强智的同一格里会并用 `-----` 分隔多门课，处理方式和正方不同，需要样本才能写对。"

        ScheduleSource.URP ->
            "已识别为 URP 系教务页面，这一版还没适配它的解析规则。\n" +
                "URP 是靠 `valign=top` 累加天数来定位星期的，没有显式的星期标记，必须有样本才能验证。"

        ScheduleSource.UNKNOWN ->
            "没能识别出这是哪一套教务系统。\n" +
                "如果这是教务系统的课表页，把文件发我，我加一套解析规则；" +
                "如果是从别的课表软件导出的，告诉我来源也同理。"

        else -> "暂不支持这个来源。"
    }
}
