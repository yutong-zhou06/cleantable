package com.obsession.schedule.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * HTML 文件编码探测。
 *
 * 为什么必须做这件事：国内教务系统大量使用 GB2312 / GBK，另存为 HTML 后
 * 文件里往往还写着 `charset=gb2312`。如果一律按 UTF-8 硬读，
 * 课程名会全变成「����」——而且是能读出来、只是内容错的乱码，最难发现。
 *
 * 探测顺序（先证据、后猜测）：
 * 1. BOM —— 最硬的证据，有 BOM 就不用看别的
 * 2. `<meta charset>` / `charset=xxx` 声明
 * 3. 都没有时：UTF-8 严格解码成功就用 UTF-8，失败则退回 GB18030
 *
 * GB18030 是 GB2312 / GBK 的超集，能用它覆盖三者。
 */
object HtmlTextDecoder {

    private const val SNIFF_BYTES = 8 * 1024

    fun decode(bytes: ByteArray): DecodedHtml {
        bomOf(bytes)?.let { (charset, skip, name) ->
            return DecodedHtml(
                text = String(bytes, skip, bytes.size - skip, charset),
                charsetName = name,
                fromBom = true
            )
        }

        declaredOf(bytes)?.let { name ->
            toCharset(name)?.let { charset ->
                return DecodedHtml(
                    text = String(bytes, charset),
                    charsetName = charset.name(),
                    fromBom = false
                )
            }
        }

        // 没有可用声明：先试严格 UTF-8，失败再退 GB18030
        decodeStrict(bytes, Charsets.UTF_8)?.let {
            return DecodedHtml(it, "UTF-8", false)
        }
        toCharset("GB18030")?.let { gb ->
            decodeStrict(bytes, gb)?.let {
                return DecodedHtml(it, gb.name(), false)
            }
        }

        // 兜底：允许替换字符，至少不崩
        return DecodedHtml(String(bytes, Charsets.UTF_8), "UTF-8", false)
    }

    /** @return (charset, 跳过的字节数, 显示名) */
    private fun bomOf(b: ByteArray): Triple<Charset, Int, String>? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
            Triple(Charsets.UTF_8, 3, "UTF-8 (BOM)")

        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() ->
            Triple(Charset.forName("UTF-16LE"), 2, "UTF-16LE (BOM)")

        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() ->
            Triple(Charset.forName("UTF-16BE"), 2, "UTF-16BE (BOM)")

        else -> null
    }

    private val META_CHARSET = Regex(
        """charset\s*=\s*["']?\s*([A-Za-z0-9_\-]+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 在文件开头找编码声明。
     *
     * 用 ISO-8859-1 读这一段：它是单字节编码，字节与字符一一对应，
     * 不管原始内容是什么编码，都不会在读「声明」这一步就出错。
     */
    private fun declaredOf(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(bytes.size, SNIFF_BYTES), Charsets.ISO_8859_1)
        return META_CHARSET.find(head)?.groupValues?.get(1)
    }

    private fun toCharset(name: String): Charset? = runCatching {
        val normalized = name.trim().lowercase().replace("_", "-")
        val canonical = when (normalized) {
            "gb2312", "gb-2312", "gbk", "gb-2312-80", "csgb2312" -> "GB18030"
            "gb18030" -> "GB18030"
            "utf8", "utf-8" -> "UTF-8"
            "big5", "big-5", "cp950" -> "Big5"
            "euc-cn", "x-euc-cn" -> "GB18030"
            else -> normalized
        }
        Charset.forName(canonical)
    }.getOrNull()

    /** 严格解码：出现非法字节就返回 null，而不是塞一堆替换字符 */
    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
