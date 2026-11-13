package io.github.timhub135.dsh.core.capability.tools

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipFile

/** Text extraction for documents file_read cannot show as plain text: PDF (pdfbox-android), docx, xlsx, pptx. */
object DocText {
    class Extracted(val text: String, val note: String)

    @Volatile private var pdfReady = false

    /** PDF text of pages [from]..[to] (1-based, inclusive; clamped). */
    fun pdf(context: Context, file: File, from: Int, to: Int): Extracted {
        if (!pdfReady) { PDFBoxResourceLoader.init(context.applicationContext); pdfReady = true }
        val doc = try { PDDocument.load(file) } catch (e: Throwable) {
            val encrypted = e.javaClass.simpleName.contains("Password") || e is NoClassDefFoundError || e.message?.contains("encrypt", true) == true
            throw IllegalStateException(if (encrypted) "This PDF is password-protected or encrypted; it cannot be read." else "Cannot open this PDF: ${e.message}")
        }
        doc.use {
            val n = it.numberOfPages
            val a = from.coerceIn(1, maxOf(n, 1)); val b = to.coerceIn(a, maxOf(n, 1))
            val text = PDFTextStripper().apply { startPage = a; endPage = b; sortByPosition = true }.getText(it).trim()
            val more = if (b < n) "; more pages: use pages=\"${b + 1}-${minOf(n, b + (b - a + 1))}\"" else ""
            val scanned = if (text.isBlank()) " — no text layer (a scanned PDF); use screen tools on a viewer or ask the user" else ""
            return Extracted(text, "PDF, $n page(s), showing pages $a-$b$more$scanned")
        }
    }

    /** Word / Excel / PowerPoint (Office Open XML) text, or null for other formats. */
    fun office(file: File): Extracted? {
        val ext = file.extension.lowercase()
        if (ext !in setOf("docx", "xlsx", "pptx")) return null
        ZipFile(file).use { zip ->
            fun entry(name: String) = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }
            return when (ext) {
                "docx" -> Extracted(xmlText(entry("word/document.xml") ?: "", paragraph = "</w:p>", tab = "<w:tab/>"), "Word document")
                "pptx" -> {
                    val slides = zip.entries().asSequence().map { it.name }.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                        .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }.toList()
                    Extracted(slides.mapIndexed { i, s -> "--- slide ${i + 1} ---\n" + xmlText(entry(s) ?: "", paragraph = "</a:p>") }.joinToString("\n"),
                        "PowerPoint, ${slides.size} slide(s)")
                }
                else -> {
                    val shared = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(entry("xl/sharedStrings.xml") ?: "")
                        .map { m -> Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(m.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) } }.toList()
                    val sheets = zip.entries().asSequence().map { it.name }.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                        .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }.toList()
                    val out = StringBuilder()
                    sheets.forEachIndexed { i, s ->
                        out.append("--- sheet ${i + 1} ---\n")
                        Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(entry(s) ?: "").forEach { row ->
                            val cells = Regex("<c ([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1]).map { c ->
                                val attrs = c.groupValues[1]; val body = c.groupValues[2]
                                val v = Regex("<v>(.*?)</v>").find(body)?.groupValues?.get(1)
                                when {
                                    "t=\"s\"" in attrs -> v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                    "t=\"inlineStr\"" in attrs -> Regex("<t[^>]*>(.*?)</t>").findAll(body).joinToString("") { unescape(it.groupValues[1]) }
                                    else -> v?.let(::unescape) ?: ""
                                }
                            }.toList()
                            if (cells.any { it.isNotBlank() }) out.append(cells.joinToString("\t")).append('\n')
                        }
                    }
                    Extracted(out.toString(), "Excel, ${sheets.size} sheet(s) (tab-separated; formulas shown as their last values)")
                }
            }
        }
    }

    private fun xmlText(xml: String, paragraph: String, tab: String? = null): String {
        var s = xml.replace(paragraph, "\n")
        if (tab != null) s = s.replace(tab, "\t")
        s = s.replace(Regex("<w:br[^>]*/>"), "\n").replace(Regex("<[^>]+>"), "")
        return unescape(s).replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
