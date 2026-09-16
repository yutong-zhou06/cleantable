package com.obsession.schedule.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MHTML 解码器的单元测试。
 *
 * 端到端用例的夹具（`zhengfang_mhtml.mhtml`）取自用户真实保存的教务页面
 * （Chrome「网页，单个文件」，Blink 内核）：quoted-printable + UTF-8，
 * 保留真实根头部 / boundary / CSS 部件 / 完整主 HTML 部件。
 * 重点关注两个真实世界特征：
 * 1. QP 软换行会把 UTF-8 多字节序列**从中间切开**，必须按字节解码；
 * 2. 多部件文件里要挑对 text/html 部件（CSS/PNG 全部忽略）。
 */
class MhtmlDecoderTest {

    // ---------------------------------------------------------------------
    // quoted-printable
    // ---------------------------------------------------------------------

    @Test
    fun `QP 解码十六进制转义与软换行`() {
        // =3D → '='、=2C → ','，c 后面的 =\r\n 是软换行，整段吞掉
        val src = "a=3Db=2Cc=\r\nd".toByteArray(Charsets.ISO_8859_1)
        assertEquals("a=b,cd", String(MhtmlDecoder.decodeQuotedPrintable(src), Charsets.ISO_8859_1))
    }

    @Test
    fun `QP 软换行也兼容 LF 风格`() {
        val src = "x=\ny".toByteArray(Charsets.ISO_8859_1)
        assertEquals("xy", String(MhtmlDecoder.decodeQuotedPrintable(src), Charsets.ISO_8859_1))
    }

    @Test
    fun `QP 大小写十六进制都能解`() {
        val src = "=e4=B8=ad".toByteArray(Charsets.ISO_8859_1)
        assertEquals("中", String(MhtmlDecoder.decodeQuotedPrintable(src), Charsets.UTF_8))
    }

    @Test
    fun `QP 被软换行切碎的 UTF-8 多字节序列能拼回`() {
        // 真实 MHTML 里最常见的坑：编码器在第 76 列硬折行，正好切在汉字中间。
        // 「课」= E8 AF BE，拆成 =E8=\r\n=AF=BE
        val src = "=E8=\r\n=AF=BE".toByteArray(Charsets.ISO_8859_1)
        assertEquals("课", String(MhtmlDecoder.decodeQuotedPrintable(src), Charsets.UTF_8))
    }

    @Test
    fun `QP 悬空等号不丢数据`() {
        val src = "abc=".toByteArray(Charsets.ISO_8859_1)
        assertEquals("abc=", String(MhtmlDecoder.decodeQuotedPrintable(src), Charsets.ISO_8859_1))
    }

    // ---------------------------------------------------------------------
    // base64
    // ---------------------------------------------------------------------

    @Test
    fun `base64 解码忽略换行`() {
        // 用标准库现算期望编码（测试代码跑在 JVM 上可用），再模拟编码器每 4 列折行
        val plain = "你好课表"
        val b64 = java.util.Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
        val folded = b64.chunked(4).joinToString("\r\n")
        assertEquals(plain, String(MhtmlDecoder.decodeBase64(folded.toByteArray()), Charsets.UTF_8))
    }

    // ---------------------------------------------------------------------
    // 结构识别与部件挑选
    // ---------------------------------------------------------------------

    @Test
    fun `识别多部件 MHTML`() {
        val bytes = mhtmlFixtureBytes()
        assertTrue(MhtmlDecoder.isMhtml(bytes))
    }

    @Test
    fun `普通 HTML 不误判为 MHTML`() {
        assertFalse(MhtmlDecoder.isMhtml("<!DOCTYPE html><html><body>x</body></html>".toByteArray()))
    }

    @Test
    fun `多部件里挑出最大的 HTML 部件而不是 CSS`() {
        val extracted = MhtmlDecoder.extractHtml(mhtmlFixtureBytes())
        assertNotNull(extracted)
        val text = String(extracted!!, Charsets.UTF_8)
        assertTrue("应含 HTML 标记", text.contains("<"))
        assertTrue("不应是 CSS 部件", !text.trimStart().startsWith("body"))
    }

    @Test
    fun `两个 HTML 部件时取体积大的主页面`() {
        val small = "Content-Type: text/html\r\n" +
            "Content-Transfer-Encoding: quoted-printable\r\n" +
            "\r\n" +
            "<html>tiny</html>\r\n"
        val big = "Content-Type: text/html\r\n" +
            "Content-Transfer-Encoding: quoted-printable\r\n" +
            "\r\n" +
            "<html>" + "x".repeat(500) + "</html>\r\n"
        val raw = buildMultipart(listOf(small, big))
        val text = String(MhtmlDecoder.extractHtml(raw)!!, Charsets.UTF_8)
        assertTrue(text.length > 400)
    }

    @Test
    fun `单部件 mht 没有boundary也能拆`() {
        val body = "<html><body>=E4=BD=A0=E5=A5=BD</body></html>"
        val raw = (
            "From: <Saved by Blink>\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Transfer-Encoding: quoted-printable\r\n" +
                "\r\n" +
                body
            ).toByteArray(Charsets.ISO_8859_1)
        assertTrue(MhtmlDecoder.isMhtml(raw))
        val text = String(MhtmlDecoder.extractHtml(raw)!!, Charsets.UTF_8)
        assertEquals("<html><body>你好</body></html>", text)
    }

    @Test
    fun `multipart 里 base64 编码的 HTML 部件能解`() {
        val html = "<html><body>hello</body></html>"
        val b64 = java.util.Base64.getEncoder().encodeToString(html.toByteArray())
        val part = "Content-Type: text/html\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "\r\n" +
            b64 + "\r\n"
        val raw = buildMultipart(listOf(part))
        assertEquals(html, String(MhtmlDecoder.extractHtml(raw)!!, Charsets.UTF_8))
    }

    // ---------------------------------------------------------------------
    // 与导入链路的集成（openFile 分派语义 + 端到端）
    // ---------------------------------------------------------------------

    @Test
    fun `isHtml 对 MHTML 返回 true 以走进 HTML 分支`() {
        val bytes = mhtmlFixtureBytes()
        assertTrue(HtmlScheduleImporter.isHtml(bytes))
    }

    @Test
    fun `真实 MHTML 夹具端到端解析出课表`() {
        val bytes = mhtmlFixtureBytes()

        val decoded = HtmlScheduleImporter.decodeHtml(bytes)
        assertTrue("应按 UTF-8 解码，实际 ${decoded.charsetName}", decoded.charsetName.startsWith("UTF-8"))
        assertFalse("不应出现替换字符", decoded.text.contains('\uFFFD'))

        val outcome = HtmlScheduleImporter.parse(decoded.text)
        assertTrue("应解析成功，实际 $outcome", outcome is ParseOutcome.Success)
        val success = outcome as ParseOutcome.Success
        assertEquals("2026-2027学年第1学期", success.termName)
        assertEquals(22, success.courses.size)
        success.courses.forEach { course ->
            assertTrue(course.name.isNotBlank())
            assertTrue(course.dayOfWeek in 1..7)
            assertTrue(course.startWeek in 1..60)
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private fun mhtmlFixtureBytes(): ByteArray = readBytes("zhengfang_mhtml.mhtml")

    /** 用合成部件拼一个 multipart/related，boundary 用真实浏览器常见的三段式样式 */
    private fun buildMultipart(parts: List<String>): ByteArray {
        val boundary = "----MultipartBoundary--TESTBOUNDARY----"
        val sb = StringBuilder()
        sb.append("From: <Saved by test>\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: multipart/related; type=\"text/html\";\r\n")
        sb.append("\tboundary=\"$boundary\"\r\n")
        sb.append("\r\n")
        parts.forEach { p ->
            sb.append("--").append(boundary).append("\r\n")
            sb.append(p)
            sb.append("\r\n")
        }
        sb.append("--").append(boundary).append("--\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun readBytes(name: String): ByteArray {
        val path = "html/$name"
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: error("测试夹具缺失：$path")
        return stream.use { it.readBytes() }
    }
}
