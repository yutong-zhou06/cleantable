package com.obsession.schedule.browser

import android.content.Context
import com.obsession.schedule.importer.HtmlScheduleImporter
import com.obsession.schedule.importer.ParseOutcome
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 教务页面临时文件的全生命周期管理（v0.4.1 重构核心）。
 *
 * 流程：抓到的页面 HTML 先落成 cacheDir 临时文件 → 用与「文件导入」完全
 * 相同的管线（MHTML 信封拆解 + 编码探测 + 正方解析）读取解析 →
 * 成功即删、失败转存 samples/ 留样取证。
 *
 * 为什么多一道「落盘」：直接把 DOM 字符串塞进解析器和文件导入走的是两条
 * 微妙不同的路径（编码探测只有文件路径才有），结果可能不一致；而临时文件
 * 天然给了「失败留样」的落点 —— 用户不用再手动另存网页，每次解析失败都
 * 自动留下现场，导出发给开发者即可适配。
 */
object TempPageStore {

    private const val TEMP_PREFIX = "kbcx_import_"
    private const val SAMPLE_DIR = "samples"
    private val SAMPLE_MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)

    /**
     * 一次「存文件 → 解析」的结果。
     * [outcome] 为失败时 [sampleFile] 指向留下的页面样本，可能为 null（写文件本身失败）。
     */
    data class Result(
        val html: String,
        val outcome: ParseOutcome,
        val sampleFile: File?
    )

    /**
     * 保存并解析。任何异常都不会向上抛 —— 全部收敛为 [ParseOutcome.Failure]，
     * 且临时文件保证被清理或转存，绝不泄漏。
     */
    fun saveAndParse(context: Context, html: String): Result {
        var temp: File? = null
        var sample: File? = null
        try {
            temp = File.createTempFile(TEMP_PREFIX, ".html", context.cacheDir)
            temp.writeText(html, Charsets.UTF_8)

            val bytes = temp.readBytes()
            val decoded = HtmlScheduleImporter.decodeHtml(bytes)
            val outcome = HtmlScheduleImporter.parse(decoded.text)

            if (outcome is ParseOutcome.Success) {
                temp.delete()
                temp = null
            } else {
                sample = moveToSamples(temp)
                temp = null
            }
            return Result(html, outcome, sample)
        } catch (e: Exception) {
            temp?.let { sample = moveToSamples(it) }
            return Result(
                html,
                ParseOutcome.Failure(
                    "页面处理失败：${e.message ?: e.javaClass.simpleName}"
                ),
                sample
            )
        } finally {
            temp?.delete()
        }
    }

    /** 失败样本转存到 cacheDir/samples/，文件名带时间戳便于区分多次失败 */
    private fun moveToSamples(temp: File): File? = runCatching {
        val dir = File(temp.parentFile, SAMPLE_DIR).apply { mkdirs() }
        val sample = File(dir, "sample_${System.currentTimeMillis()}.html")
        if (temp.renameTo(sample)) sample else null
    }.getOrNull()

    /**
     * 打开浏览器时清理：7 天前的失败样本与任何残留的临时文件。
     * 样本的价值只在「发我适配下一版」，过期就没必要再占空间。
     */
    fun cleanStaleFiles(context: Context) {
        val cache = context.cacheDir
        val deadline = System.currentTimeMillis() - SAMPLE_MAX_AGE_MS

        cache.listFiles()?.forEach { file ->
            if (file.name.startsWith(TEMP_PREFIX) && file.lastModified() < deadline) {
                file.delete()
            }
        }
        File(cache, SAMPLE_DIR).listFiles()?.forEach { file ->
            if (file.lastModified() < deadline) file.delete()
        }
    }
}
