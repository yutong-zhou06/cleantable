package com.obsession.schedule.importer

import java.io.ByteArrayOutputStream

/**
 * MHTML（.mht / .mhtml）解码器 —— 浏览器「网页，单个文件」格式的拆信封层。
 *
 * MHTML = MIME multipart 把「主 HTML + CSS + 图片」打成一个文件。对课表导入来说，
 * 只有主 HTML 部件有用：把它拆出来、解开传输编码（quoted-printable / base64），
 * 剩下的字节流交给 [HtmlTextDecoder] 做编码探测，再走原有的解析链路。
 *
 * 实测样本结构（Chrome/Edge 保存，Blink 内核）：
 * ```
 * From: <Saved by Blink>
 * Snapshot-Content-Location: http://.../xskbcx_cxXskbcxIndex.html?...
 * MIME-Version: 1.0
 * Content-Type: multipart/related; type="text/html";
 *     boundary="----MultipartBoundary--xxxx----"
 *
 * ------MultipartBoundary--xxxx----
 * Content-Type: text/html
 * Content-Transfer-Encoding: quoted-printable     ← 主页面在这
 * ...
 * ------MultipartBoundary--xxxx----
 * Content-Type: text/css
 * ...（二十多个 CSS/PNG 部件，全部忽略）
 * ```
 *
 * 兼容性考虑（都是真实世界会出现的变体）：
 * - **quoted-printable**：Chrome/Edge/Firefox 的默认编码。`=XX` 十六进制转义、
 *   行尾 `=\r\n` 是软换行（要拼回一行）、**UTF-8 多字节序列会被行边界切开**
 *   （`=E4=` 结尾一行、`=B8=AD` 下一行）——必须先按字节解码、再整体解码字符集，
 *   逐行按字符处理会把中文拆碎。
 * - **base64**：IE / Outlook 系的保存方式。解码时忽略换行。
 * - **单部件 .mht**：没有 boundary，整个文件就是「头部 + 编码后的 HTML」。
 * - **折行头**：`Content-Type: multipart/related;` 后面跟着缩进续行，
 *   boundary 声明常在续行上——判定前要把折行拼回去。
 * - 主 HTML 部件不一定排第一（有的浏览器把某个 iframe 排前面），
 *   多个 `text/html` 部件时取**最大**的那个（主页面的体积必然最大）。
 *
 * 本文件刻意零依赖 Android API（连 `android.util.Base64` 都不用，它会让单测
 * 脱离纯 JVM 环境），base64 手写了一个 MIME 变体解码器。
 */
object MhtmlDecoder {

    /** 内容嗅探：MHTML 的头部特征非常固定，2048 字节足够看清 */
    fun isMhtml(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            .lowercase()
        return head.contains("multipart/related") ||
            head.contains("multipart/mixed") ||
            // 单部件 .mht：没有 boundary，但有 MIME 头 + 传输编码声明
            (head.contains("mime-version:") && head.contains("content-transfer-encoding"))
    }

    /**
     * 从 MHTML 里取出主 HTML 部件的**原始字节**（已完成传输编码解码，
     * 尚未做字符集解码——那是 [HtmlTextDecoder] 的职责）。
     *
     * 不是 MHTML 或找不到 HTML 部件时返回 null，调用方回退到「整个文件就是 HTML」。
     */
    fun extractHtml(bytes: ByteArray): ByteArray? {
        val root = RootHeaders.parse(bytes) ?: return null

        if (root.boundary == null) {
            // 单部件：头部下面的所有内容就是正文
            if (root.contentType != null && !root.contentType.startsWith("text/html")) return null
            return when (root.transferEncoding) {
                TransferEncoding.QUOTED_PRINTABLE ->
                    decodeQuotedPrintable(bytes.copyOfRange(root.bodyStart, bytes.size))
                TransferEncoding.BASE64 ->
                    decodeBase64(bytes.copyOfRange(root.bodyStart, bytes.size))
                TransferEncoding.IDENTITY ->
                    bytes.copyOfRange(root.bodyStart, bytes.size)
            }
        }

        val parts = splitParts(bytes, root.bodyStart, root.boundary)
        val htmlParts = parts.filter { it.contentType?.startsWith("text/html") == true }
        // 递归处理嵌套 multipart（multipart/alternative 里再包一层的情况）
        val nested = parts.filter { it.contentType?.startsWith("multipart/") == true }
            .mapNotNull { extractHtml(it.rawWithHeaders) }
        val direct = htmlParts.map { it.decodedBody() }

        val candidates = direct + nested
        return candidates.maxByOrNull { it.size }  // 主页面体积必然最大
    }

    // ---------- 根头部 ----------

    private class RootHeaders(
        val bodyStart: Int,
        val boundary: String?,
        val contentType: String?,
        val transferEncoding: TransferEncoding
    ) {
        companion object {
            fun parse(bytes: ByteArray): RootHeaders? {
                // 头部与正文之间是第一个空行；兼容 \r\n\r\n 与 \n\n
                var i = 0
                var sep = -1
                while (i < bytes.size - 1) {
                    if (bytes[i] == '\n'.code.toByte()) {
                        if (i + 1 < bytes.size && bytes[i + 1] == '\n'.code.toByte()) { sep = i + 1; break }
                        if (i + 2 < bytes.size && bytes[i + 1] == '\r'.code.toByte() &&
                            bytes[i + 2] == '\n'.code.toByte()
                        ) { sep = i + 2; break }
                    }
                    i++
                }
                if (sep == -1) return null

                // 按折行规则把头拼成逻辑行再匹配（续行以空白开头）
                val headText = String(bytes, 0, sep, Charsets.ISO_8859_1)
                    .replace(Regex("\r?\n[ \t]+"), " ")
                val lower = headText.lowercase()

                val contentType = Regex(
                    """content-type:\s*([^;\r\n]+)""", RegexOption.IGNORE_CASE
                ).find(lower)?.groupValues?.get(1)?.trim()

                val boundary = Regex(
                    """boundary\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE
                ).find(headText)?.groupValues?.get(1)
                    ?: Regex("""boundary\s*=\s*([^\s";]+)""", RegexOption.IGNORE_CASE)
                        .find(headText)?.groupValues?.get(1)

                val te = when {
                    lower.contains("content-transfer-encoding: quoted-printable") ->
                        TransferEncoding.QUOTED_PRINTABLE
                    lower.contains("content-transfer-encoding: base64") ->
                        TransferEncoding.BASE64
                    else -> TransferEncoding.IDENTITY
                }
                return RootHeaders(sep + 1, boundary, contentType, te)
            }
        }
    }

    private enum class TransferEncoding { QUOTED_PRINTABLE, BASE64, IDENTITY }

    // ---------- 部件切分 ----------

    private class Part(
        /** 含头部的完整原始字节（嵌套递归时用） */
        val rawWithHeaders: ByteArray,
        val bodyStart: Int,
        val contentType: String?,
        val transferEncoding: TransferEncoding
    ) {
        fun decodedBody(): ByteArray {
            val body = rawWithHeaders.copyOfRange(bodyStart, rawWithHeaders.size)
            return when (transferEncoding) {
                TransferEncoding.QUOTED_PRINTABLE -> decodeQuotedPrintable(body)
                TransferEncoding.BASE64 -> decodeBase64(body)
                TransferEncoding.IDENTITY -> body
            }
        }
    }

    private fun splitParts(bytes: ByteArray, from: Int, boundary: String): List<Part> {
        // boundary 行一定以行首的 "--" 开头；ISO_8859_1 保证索引与字节一一对应
        val latin = String(bytes, from, bytes.size - from, Charsets.ISO_8859_1)
        val marker = Regex.escape(boundary)
        val regex = Regex("(?m)^--$marker(?:--)?[ \t]*\r?\n?")
        val matches = regex.findAll(latin).toList()
        if (matches.size < 2) return emptyList()

        val parts = mutableListOf<Part>()
        for (i in 0 until matches.size - 1) {
            val start = matches[i].range.last + 1
            val end = matches[i + 1].range.first
            if (end <= start) continue
            val chunk = bytes.copyOfRange(from + start, from + end)
            parsePart(chunk)?.let { parts.add(it) }
        }
        return parts
    }

    private fun parsePart(chunk: ByteArray): Part? {
        var i = 0
        var sep = -1
        while (i < chunk.size - 1) {
            if (chunk[i] == '\n'.code.toByte()) {
                if (i + 1 < chunk.size && chunk[i + 1] == '\n'.code.toByte()) { sep = i + 1; break }
                if (i + 2 < chunk.size && chunk[i + 1] == '\r'.code.toByte() &&
                    chunk[i + 2] == '\n'.code.toByte()
                ) { sep = i + 2; break }
            }
            i++
        }
        if (sep == -1) return null

        val headText = String(chunk, 0, sep, Charsets.ISO_8859_1)
            .replace(Regex("\r?\n[ \t]+"), " ")
            .lowercase()
        val contentType = Regex(
            """content-type:\s*([^;\r\n]+)""", RegexOption.IGNORE_CASE
        ).find(headText)?.groupValues?.get(1)?.trim()

        val te = when {
            headText.contains("content-transfer-encoding: quoted-printable") ->
                TransferEncoding.QUOTED_PRINTABLE
            headText.contains("content-transfer-encoding: base64") ->
                TransferEncoding.BASE64
            else -> TransferEncoding.IDENTITY
        }
        return Part(chunk, sep + 1, contentType, te)
    }

    // ---------- 传输编码解码 ----------

    /**
     * quoted-printable → 原始字节。
     *
     * 关键点：**先按字节解码，字符集解码留给 [HtmlTextDecoder]**。
     * 这样 UTF-8 多字节序列即使被软换行切开（`=E4=\r\n=B8=AD`）也能正确拼回。
     */
    internal fun decodeQuotedPrintable(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        val n = b.size
        while (i < n) {
            val c = b[i].toInt() and 0xFF
            if (c == '='.code) {
                when {
                    // 软换行：行尾的 =\r\n 或 =\n 直接吞掉（拼回一行）
                    i + 2 < n && b[i + 1] == '\r'.code.toByte() && b[i + 2] == '\n'.code.toByte() ->
                        i += 3

                    i + 1 < n && b[i + 1] == '\n'.code.toByte() ->
                        i += 2

                    // =XX 十六进制转义（大小写都要吃）
                    i + 2 < n && isHex(b[i + 1]) && isHex(b[i + 2]) -> {
                        out.write(hexVal(b[i + 1]) * 16 + hexVal(b[i + 2]))
                        i += 3
                    }

                    // 悬空的 = 当普通字符，别丢数据
                    else -> {
                        out.write(c)
                        i += 1
                    }
                }
            } else {
                out.write(c)
                i += 1
            }
        }
        return out.toByteArray()
    }

    private fun isHex(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return c in '0'.code..'9'.code || c in 'a'.code..'f'.code || c in 'A'.code..'F'.code
    }

    private fun hexVal(b: Byte): Int {
        val c = b.toInt() and 0xFF
        return when {
            c in '0'.code..'9'.code -> c - '0'.code
            c in 'a'.code..'f'.code -> c - 'a'.code + 10
            else -> c - 'A'.code + 10
        }
    }

    /**
     * base64 → 原始字节（MIME 变体：忽略换行等一切非字母表字符）。
     *
     * 手写而不用 `java.util.Base64`：那玩意儿 Android 要 API 26+（minSdk 24），
     * 用 `android.util.Base64` 又会把单测拖出纯 JVM 环境。
     */
    internal fun decodeBase64(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size / 4 * 3 + 3)
        var acc = 0
        var bits = 0
        for (byte in b) {
            val c = byte.toInt() and 0xFF
            val v = when (c) {
                in 'A'.code..'Z'.code -> c - 'A'.code
                in 'a'.code..'z'.code -> c - 'a'.code + 26
                in '0'.code..'9'.code -> c - '0'.code + 52
                '+'.code -> 62
                '/'.code -> 63
                else -> -1  // '='、换行、空格等一律跳过
            }
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
