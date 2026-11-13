package io.github.timhub135.dsh.core.capability.tools

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.capability.CallContext
import io.github.timhub135.dsh.core.capability.InvalidArgsException
import io.github.timhub135.dsh.core.capability.PermissionMissingException
import io.github.timhub135.dsh.core.capability.Tool
import io.github.timhub135.dsh.core.capability.ToolResult
import io.github.timhub135.dsh.core.capability.ToolSet
import io.github.timhub135.dsh.core.capability.bool
import io.github.timhub135.dsh.core.capability.int
import io.github.timhub135.dsh.core.capability.reqStr
import io.github.timhub135.dsh.core.capability.str
import io.github.timhub135.dsh.core.capability.strs
import io.github.timhub135.dsh.core.model.Risk
import io.github.timhub135.dsh.core.model.ToolGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared-storage file operations ("All files access"). Paths may be absolute or relative to /sdcard.
 * Android/data and Android/obb are closed to ordinary apps on Android 11+ and this version has no
 * shell access, so those paths are refused with `android_data_restricted` ([PrivateStorage]).
 */
@Singleton
class FileTools @Inject constructor(@ApplicationContext private val context: Context) : ToolSet {
    private val root: File = Environment.getExternalStorageDirectory()

    private fun need() { if (!hasAllFilesAccess(context)) throw PermissionMissingException("All files access") }

    /** A restricted storage path: a plain model-facing error, not a permission card. */
    private class Restricted(message: String) : Exception(message)

    private fun resolve(path: String): File {
        val f = (if (path.startsWith("/")) File(path) else File(root, path)).canonicalFile
        val allowed = listOf(root.canonicalPath, "/storage", "/sdcard")
        if (allowed.none { f.path.startsWith(it) })
            throw InvalidArgsException("Path outside shared storage is not accessible: ${f.path}")
        return f
    }

    /** Refuses Android/data and Android/obb on Android 11+ (no shell access in this version). */
    private fun denyRestricted(f: File) {
        if (PrivateStorage.isRestricted(f.path, Build.VERSION.SDK_INT)) throw Restricted(PrivateStorage.ANDROID_DATA_RESTRICTED)
    }

    /** For tools that only work on ordinary paths (archives). */
    private fun localOnly(f: File): File {
        denyRestricted(f)
        need()
        return f
    }

    /** Runs a tool body on the IO dispatcher; a restricted path becomes a clear error. */
    private suspend fun io(block: suspend CoroutineScope.() -> ToolResult): ToolResult = withContext(Dispatchers.IO) {
        try { block() } catch (e: Restricted) { ToolResult.error(PrivateStorage.ERROR_CODE, e.message ?: PrivateStorage.ANDROID_DATA_RESTRICTED) }
    }

    private fun line(dir: Boolean, size: Long, mtime: Long, path: String): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(mtime))
        return if (dir) "d  ${"-".padStart(9)}  $date  $path/" else "-  ${human(size).padStart(9)}  $date  $path"
    }

    private fun File.describe(): String = line(isDirectory, length(), lastModified(), path)

    /** A file for file_read: [open] streams it, [file] gives the local File itself. */
    private class Src(ext: String, val size: Long, val described: String, val open: () -> InputStream, val file: () -> File) {
        val ext = ext.lowercase()
    }

    private fun human(n: Long): String = when {
        n >= 1L shl 30 -> "%.1fG".format(n / (1L shl 30).toDouble())
        n >= 1L shl 20 -> "%.1fM".format(n / (1L shl 20).toDouble())
        n >= 1L shl 10 -> "%.1fK".format(n / (1L shl 10).toDouble())
        else -> "${n}B"
    }

    private companion object {
        const val MAX_CHARS = 100_000
        const val PRIVATE_NOTE = " Android/data and Android/obb are not accessible on Android 11+ (this version has no shell access)."
    }

    override val tools = listOf(
        object : Tool("file_search", ToolGroup.Files, Risk.read, false,
            "Find files on shared storage by name pattern and/or text content. Fast (ripgrep). Use before reading or modifying files. " +
                "Android/data and Android/obb are not accessible to apps on Android 11+ and are skipped.", {
                string("name", "Glob on file name, e.g. '*.pdf' or '*invoice*'")
                string("content", "Text the file must contain (literal, case-insensitive; text files only)")
                bool("regex", "Treat content as a regular expression")
                string("dir", "Directory to search, default /sdcard")
                int("limit", "Max results, default 100")
                string("sort", "Order of results", enum = listOf("recent", "largest", "name"))
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult = io {
                val dir = resolve(args.str("dir") ?: root.path)
                denyRestricted(dir)
                need()
                val limit = (args.int("limit") ?: 100).coerceIn(1, 1000)
                val rg = File(context.filesDir, "bin/rg").path
                val filters = mutableListOf("--hidden", "--no-messages", "-g", "!Android/data/**", "-g", "!Android/obb/**")
                args.str("name")?.let { filters += listOf("--iglob", it) }
                val content = args.str("content")?.takeIf { it.isNotEmpty() }
                // Literal by default (a model's "a.b(c" is not meant as a regex); -e keeps a leading '-' from reading as a flag.
                val cmd = if (content != null) listOf(rg, "-l", "-i") + (if (args.bool("regex") == true) emptyList() else listOf("-F")) + filters + listOf("-e", content, "--", dir.path)
                    else listOf(rg, "--files") + filters + listOf("--", dir.path)
                val (files, problem) = run(cmd)
                if (problem != null && files.isEmpty()) return@io ToolResult.error("failed", problem)
                val sorted = when (args.str("sort")) {
                    "largest" -> files.sortedByDescending { it.length() }
                    "name" -> files.sortedBy { it.name.lowercase() }
                    else -> files.sortedByDescending { it.lastModified() }
                }
                val shown = sorted.take(limit)
                ToolResult.text((if (shown.isEmpty()) "No files found." else
                    "${files.size} match(es)${if (files.size > limit) ", showing $limit" else ""}:\n" + shown.joinToString("\n") { it.describe() }) +
                    (problem?.let { "\n(note: $it)" } ?: ""))
            }

            /** Runs rg with a deadline; output is read on its own thread so a hung rg cannot block forever. */
            private suspend fun run(cmd: List<String>): Pair<List<File>, String?> {
                val p = ProcessBuilder(cmd).start()
                val out = java.util.Collections.synchronizedList(ArrayList<String>())
                val err = StringBuilder()
                val reader = kotlin.concurrent.thread(isDaemon = true) { runCatching { p.inputStream.bufferedReader().forEachLine { if (out.size < 20_000) out += it } } }
                val errReader = kotlin.concurrent.thread(isDaemon = true) { runCatching { err.append(p.errorStream.bufferedReader().readText().take(2000)) } }
                val done = try { runInterruptible { p.waitFor(60, TimeUnit.SECONDS) } } finally { if (p.isAlive) p.destroyForcibly() }
                reader.join(2_000); errReader.join(500)
                val files = out.filter { it.isNotBlank() }.map { File(it) }
                val problem = when {
                    !done -> "search stopped after 60s; results are partial — narrow 'dir' or 'name'"
                    p.exitValue() == 2 && files.isEmpty() -> "search failed: ${err.toString().trim().ifEmpty { "invalid pattern" }}"
                    else -> null
                }
                return files to problem
            }
        },
        object : Tool("file_list", ToolGroup.Files, Risk.read, false, "List a directory (size, modified time).$PRIVATE_NOTE", {
            string("path", "Directory, default /sdcard")
            bool("recursive", "Include subdirectories")
            int("limit", "Max entries, default 300")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = io {
                val dir = resolve(args.str("path") ?: root.path)
                denyRestricted(dir)
                need()
                val limit = (args.int("limit") ?: 300).coerceIn(1, 2000)
                if (!dir.isDirectory) throw InvalidArgsException("Not a directory: ${dir.path}")
                val (entries, total) = if (args.bool("recursive") == true) {
                    val all = dir.walkTopDown().drop(1).take(limit + 1).toList()
                    all.take(limit) to (if (all.size > limit) null else all.size)
                } else {
                    val all = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
                    all.take(limit) to all.size
                }
                val more = when {
                    total == null -> "\n…(more entries not shown; use file_search or a narrower folder)"
                    total > entries.size -> "\n…(${total - entries.size} more not shown; raise limit or use file_search)"
                    else -> ""
                }
                ToolResult.text(if (entries.isEmpty()) "(empty)" else entries.joinToString("\n") { it.describe() } + more)
            }
        },
        object : Tool("file_read", ToolGroup.Files, Risk.read, false,
            "Read a file. Text files return text (use offset/limit lines for big files; GBK/GB18030 is detected); images return the image " +
                "(any size, HEIC included); PDF, Word (docx), Excel (xlsx) and PowerPoint (pptx) return their text.$PRIVATE_NOTE", {
                string("path", "File path", required = true)
                int("offset", "First line (0-based)")
                int("limit", "Number of lines, default 400")
                string("pages", "PDF only: page range like \"1-5\" (default the first 10 pages)")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = io {
                val f = resolve(args.reqStr("path"))
                denyRestricted(f)
                need()
                if (!f.isFile) throw InvalidArgsException("Not a file: ${f.path}")
                read(Src(f.extension, f.length(), f.describe(), { f.inputStream() }, { f }), args)
            }

            private fun read(src: Src, args: JsonObject): ToolResult {
                val ext = src.ext
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: ""
                val off = (args.int("offset") ?: 0).coerceAtLeast(0)
                val lim = (args.int("limit") ?: 400).coerceIn(1, 4000)
                return when {
                    mime.startsWith("image/") || ext in setOf("heic", "heif", "avif") -> {
                        val img = Images.downscale(src.file()) ?: return ToolResult.text("Image file (${mime.ifEmpty { ext }}), ${human(src.size)}, but Android cannot decode it (unsupported format such as SVG, or a damaged file).")
                        ToolResult.image(Images.MIME, Base64.encodeToString(img.bytes, Base64.NO_WRAP), "${src.described} (shown as ${img.width}x${img.height} JPEG)")
                    }
                    ext == "pdf" -> {
                        val (a, b) = pageRange(args.str("pages"))
                        val doc = DocText.pdf(context, src.file(), a, b)
                        ToolResult.text("[${doc.note}]\n" + window(doc.text, off, lim))
                    }
                    ext in setOf("docx", "xlsx", "pptx") -> {
                        val doc = runCatching { DocText.office(src.file()) }.getOrNull() ?: return ToolResult.text("Cannot read this ${ext.uppercase()} file (damaged or not Office Open XML).")
                        ToolResult.text("[${doc.note}]\n" + window(doc.text, off, lim))
                    }
                    looksBinary(src.open) -> ToolResult.text("Binary file (${mime.ifEmpty { ext }}), ${human(src.size)}. Cannot show content" +
                        (if (ext in setOf("doc", "xls", "ppt")) " (legacy Office format; only docx/xlsx/pptx text can be read)." else "."))
                    else -> {
                        val cs = charsetOf(src.open)
                        val lines = src.open().bufferedReader(cs).useLines { it.drop(off).take(lim + 1).toList() }
                        val note = if (cs != Charsets.UTF_8) "[decoded as ${cs.name()}]\n" else ""
                        ToolResult.text(note + capChars(lines.take(lim).joinToString("\n"), off) + if (lines.size > lim) "\n… (more lines; use offset=${off + lim})" else "")
                    }
                }
            }

            private fun pageRange(spec: String?): Pair<Int, Int> {
                val m = spec?.trim()?.let { Regex("^(\\d+)(?:\\s*-\\s*(\\d+))?$").find(it) } ?: return 1 to 10
                val a = m.groupValues[1].toInt().coerceAtLeast(1)
                val b = m.groupValues[2].toIntOrNull() ?: a
                return a to b.coerceIn(a, a + 49)
            }

            /** offset/limit lines of extracted text, with a character cap. */
            private fun window(text: String, off: Int, lim: Int): String {
                val lines = text.lines()
                val part = lines.drop(off).take(lim)
                return capChars(part.joinToString("\n"), off) + if (lines.size > off + lim) "\n… (more lines; use offset=${off + lim})" else ""
            }

            private fun capChars(text: String, off: Int): String =
                if (text.length <= MAX_CHARS) text
                else text.take(MAX_CHARS) + "\n… (truncated at $MAX_CHARS characters; very long lines — use a smaller limit or offset=${off + text.take(MAX_CHARS).count { it == '\n' } + 1})"

            /** UTF-8 when the first 64 KB decode cleanly, otherwise GB18030 (common for Chinese .txt files). */
            private fun charsetOf(open: () -> InputStream): java.nio.charset.Charset {
                val head = open().use { s -> ByteArray(65_536).let { b -> b.copyOf(maxOf(0, s.read(b))) } }
                val ok = runCatching {
                    Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(head))
                }.isSuccess || head.size == 65_536 && runCatching { // a multi-byte char may be cut at the boundary
                    Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(head, 0, head.size - 4))
                }.isSuccess
                return if (ok) Charsets.UTF_8 else java.nio.charset.Charset.forName("GB18030")
            }

            private fun looksBinary(open: () -> InputStream): Boolean = open().use { s ->
                val buf = ByteArray(4096); val n = s.read(buf)
                (0 until maxOf(n, 0)).any { buf[it] == 0.toByte() }
            }
        },
        object : Tool("file_stat", ToolGroup.Files, Risk.read, false, "Get size/type/modified time of files or folders (folder size is computed).", {
            strings("paths", "Paths", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = io {
                need()
                ToolResult.text(args.strs("paths").map { p ->
                    val f = resolve(p)
                    if (PrivateStorage.isRestricted(f.path, Build.VERSION.SDK_INT))
                        return@map "$p: unavailable (android_data_restricted) — ${PrivateStorage.ANDROID_DATA_RESTRICTED}"
                    when {
                        !f.exists() -> "$p: not found"
                        f.isDirectory -> { val files = f.walkTopDown().filter { it.isFile }.toList()
                            "${f.path}/: folder, ${files.size} files, ${human(files.sumOf { it.length() })}" }
                        else -> f.describe()
                    }
                }.joinToString("\n"))
            }
        },
        object : Tool("file_write", ToolGroup.Files, Risk.write, false, "Create or overwrite a text file (or append).$PRIVATE_NOTE", {
            string("path", "File path", required = true)
            string("content", "Text content", required = true)
            bool("append", "Append instead of overwrite")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = io {
                val f = resolve(args.reqStr("path"))
                denyRestricted(f)
                need()
                f.parentFile?.mkdirs()
                if (args.bool("append") == true) f.appendText(args.str("content") ?: "") else f.writeText(args.str("content") ?: "")
                ToolResult.text("Wrote ${f.path} (${human(f.length())})")
            }
        },
        object : Tool("file_mkdir", ToolGroup.Files, Risk.write, false, "Create folders (with parents).", {
            strings("paths", "Folders to create", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = io {
                need()
                ToolResult.text(args.strs("paths").map { p ->
                    val f = resolve(p)
                    if (PrivateStorage.isRestricted(f.path, Build.VERSION.SDK_INT))
                        "refused $p: android_data_restricted — ${PrivateStorage.ANDROID_DATA_RESTRICTED}"
                    else { f.mkdirs(); "ok ${f.path}" }
                }.joinToString("\n"))
            }
        },
        object : Tool("file_move", ToolGroup.Files, Risk.write, false,
            "Move or rename files/folders. Each item is {from,to}; 'to' may be an existing folder.$PRIVATE_NOTE", {
                raw("items", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("array"))
                    put("items", kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                        put("properties", kotlinx.serialization.json.buildJsonObject {
                            put("from", kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("string")) })
                            put("to", kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("string")) })
                        })
                        put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("from"), kotlinx.serialization.json.JsonPrimitive("to"))))
                    })
                }, required = true)
                bool("copy", "Copy instead of move")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = io {
                need()
                val copy = args.bool("copy") == true
                val items = (args["items"] as? kotlinx.serialization.json.JsonArray) ?: throw InvalidArgsException("missing items")
                // Each item is independent: one failure is reported and the rest still run.
                ToolResult.text(items.map { el ->
                    runCatching {
                        val o = el as? JsonObject ?: throw InvalidArgsException("item is not {from,to}")
                        val rawTo = o.reqStr("to")
                        val from = resolve(o.reqStr("from"))
                        var to = resolve(rawTo)
                        if (PrivateStorage.isRestricted(from.path, Build.VERSION.SDK_INT))
                            return@runCatching "refused ${from.path}: android_data_restricted — ${PrivateStorage.ANDROID_DATA_RESTRICTED}"
                        if (PrivateStorage.isRestricted(to.path, Build.VERSION.SDK_INT))
                            return@runCatching "refused ${to.path}: android_data_restricted — ${PrivateStorage.ANDROID_DATA_RESTRICTED}"
                        if (!from.exists()) return@runCatching "failed ${from.path}: not found"
                        // A trailing slash means "into this folder", even if it does not exist yet.
                        if (rawTo.endsWith("/") || to.isDirectory) { to.mkdirs(); to = File(to, from.name) }
                        val caseOnly = to.path != from.path && to.path.equals(from.path, ignoreCase = true)
                        if (to.path == from.path) return@runCatching "skip ${from.path}: same path"
                        if (to.exists() && !caseOnly) return@runCatching "skip ${from.path}: ${to.path} exists"
                        to.parentFile?.mkdirs()
                        when {
                            copy -> { from.copyRecursively(to); "copied ${from.path} → ${to.path}" }
                            caseOnly -> { // case-insensitive storage: rename through a temporary name
                                val tmp = File(from.parentFile, ".dsh-rename-${System.nanoTime()}")
                                if (from.renameTo(tmp) && tmp.renameTo(to)) "renamed ${from.path} → ${to.path}" else "failed ${from.path}: rename refused"
                            }
                            from.renameTo(to) -> "moved ${from.path} → ${to.path}"
                            else -> { from.copyRecursively(to); from.deleteRecursively(); "moved ${from.path} → ${to.path}" }
                        }
                    }.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException || e is Restricted) throw e; "failed ${(el as? JsonObject)?.str("from")}: ${e.message}" }
                }.joinToString("\n"))
            }
        },
        object : Tool("file_delete", ToolGroup.Files, Risk.dangerous, false,
            "Permanently delete files/folders. The user sees the exact list before approving, so pass every path explicitly.$PRIVATE_NOTE", {
                strings("paths", "Exact paths to delete", required = true)
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = io {
                need()
                val paths = args.strs("paths")
                if (paths.isEmpty()) throw InvalidArgsException("no paths")
                val rootPath = root.canonicalPath
                ToolResult.text(paths.map { p ->
                    val f = resolve(p)
                    val path = f.canonicalPath
                    if (PrivateStorage.isRestricted(path, Build.VERSION.SDK_INT))
                        return@map "refused ${f.path}: android_data_restricted — ${PrivateStorage.ANDROID_DATA_RESTRICTED}"
                    when {
                        // The storage root, anything above it (/storage, /storage/emulated) and volume roots (SD cards).
                        path == rootPath || rootPath.startsWith("$path/") || f.parentFile?.canonicalPath == "/storage" || path == "/storage" ->
                            "refused: ${f.path} (storage or volume root)"
                        !f.exists() -> "not found ${f.path}"
                        f.deleteRecursively() -> "deleted ${f.path}"
                        else -> "partially deleted ${f.path}: ${f.walkTopDown().count()} item(s) could not be removed"
                    }
                }.joinToString("\n"))
            }
        },
        object : Tool("archive_create", ToolGroup.Files, Risk.write, false, "Zip files/folders into an archive.", {
            strings("paths", "Inputs", required = true)
            string("output", "Output .zip path", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = io {
                val out = localOnly(resolve(args.reqStr("output")))
                val inputs = args.strs("paths").map { localOnly(resolve(it)) }
                if (inputs.isEmpty()) throw InvalidArgsException("no paths")
                inputs.firstOrNull { !it.exists() }?.let { throw InvalidArgsException("Not found: ${it.path}") }
                out.parentFile?.mkdirs()
                val tmp = File(out.parentFile, ".${out.name}.partial")
                val names = HashSet<String>()
                var files = 0
                try {
                    ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                        inputs.forEach { src ->
                            val base = src.parentFile!!
                            src.walkTopDown().filter { it.canonicalPath != out.canonicalPath && it.canonicalPath != tmp.canonicalPath }.forEach { f ->
                                var name = f.relativeTo(base).path + if (f.isDirectory) "/" else ""
                                if (f.isDirectory && f.listFiles()?.isNotEmpty() == true) return@forEach // only empty folders need an entry
                                // Two inputs with the same name: keep both ("a (2).txt") instead of failing mid-archive.
                                var k = 2
                                while (!names.add(name)) { name = (f.relativeTo(base).parent?.let { "$it/" } ?: "") + "(${k++}) " + f.name + if (f.isDirectory) "/" else "" }
                                zip.putNextEntry(ZipEntry(name))
                                if (f.isFile) { f.inputStream().use { it.copyTo(zip) }; files++ }
                                zip.closeEntry()
                            }
                        }
                    }
                    if (out.exists()) out.delete()
                    if (!tmp.renameTo(out)) throw IllegalStateException("cannot write ${out.path}")
                } finally { tmp.delete() }
                ToolResult.text("Created ${out.describe()} ($files files)")
            }
        },
        object : Tool("archive_extract", ToolGroup.Files, Risk.write, false, "Extract a .zip archive.", {
            string("archive", "Zip path", required = true)
            string("dest", "Destination folder, default next to the archive")
            bool("overwrite", "Replace files that already exist (default: skip them)")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = io {
                val zipFile = localOnly(resolve(args.reqStr("archive")))
                val dest = localOnly(resolve(args.str("dest") ?: File(zipFile.parentFile, zipFile.nameWithoutExtension).path)).apply { mkdirs() }
                val overwrite = args.bool("overwrite") == true
                var n = 0; var skippedUnsafe = 0; var skippedExisting = 0; var replaced = 0
                // Names are UTF-8 when the archive says so; Windows tools (Explorer, WinRAR, 360压缩) write GBK
                // without the flag, which made ZipInputStream throw MALFORMED. ZipFile(charset) handles both:
                // entries flagged UTF-8 are always decoded as UTF-8, the rest use the fallback charset.
                val utf8 = java.util.zip.ZipFile(zipFile, Charsets.UTF_8)
                val zf = if (runCatching { utf8.entries().toList() }.isSuccess) utf8
                    else { utf8.close(); java.util.zip.ZipFile(zipFile, java.nio.charset.Charset.forName("GBK")) }
                zf.use { zip ->
                    val destPath = dest.canonicalPath
                    zip.entries().asSequence().forEach { e ->
                        val out = File(dest, e.name).canonicalFile
                        if (out.path != destPath && !out.path.startsWith("$destPath/")) { skippedUnsafe++; return@forEach }
                        if (e.isDirectory) { out.mkdirs(); return@forEach }
                        if (out.exists()) { if (!overwrite) { skippedExisting++; return@forEach }; replaced++ }
                        out.parentFile?.mkdirs()
                        zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }; n++
                    }
                }
                ToolResult.text(buildString {
                    append("Extracted $n files to ${dest.path}")
                    if (replaced > 0) append("; replaced $replaced existing")
                    if (skippedExisting > 0) append("; skipped $skippedExisting that already exist (overwrite=true to replace)")
                    if (skippedUnsafe > 0) append("; skipped $skippedUnsafe unsafe entries pointing outside the folder")
                })
            }
        },
    )
}

/** Broad shared-storage access: "All files access" on Android 11+, the legacy storage permission on Android 10. */
fun hasAllFilesAccess(context: Context): Boolean =
    if (android.os.Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
