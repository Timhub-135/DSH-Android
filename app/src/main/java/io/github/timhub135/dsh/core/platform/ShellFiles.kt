package io.github.timhub135.dsh.core.platform

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * File operations the shell user service runs for FileTools on paths ordinary apps cannot reach
 * (Android/data and Android/obb on Android 11+; the shell user can). Plain java.io in the shell
 * process: no command strings. Pure JVM code, so it is unit-tested with a temporary directory.
 *
 * Rows are "d|f\tsize\tmtimeMs\tpath" (a folder's size is 0 in listings). Failures throw
 * [IllegalStateException] with "<Class>: <message>": binder carries that type and its message back
 * to the app.
 */
internal class ShellFiles(
    /** Paths the service may touch (canonical); everything else is refused. */
    private val allowed: (String) -> Boolean = ::sharedStorage,
    private val clock: () -> Long = System::nanoTime,
) {
    private fun file(path: String): File {
        val f = File(path).canonicalFile
        if (!allowed(f.path)) throw IllegalArgumentException("not in shared storage: ${f.path}")
        return f
    }

    private inline fun <T> op(block: () -> T): T = try { block() } catch (e: IllegalArgumentException) { throw e } catch (e: IllegalStateException) { throw e } catch (t: Throwable) {
        throw IllegalStateException(ShellIdentity.describe(t))
    }

    /** "d\t<sum of file sizes>\tmtime\tpath\t<file count>" for a folder, the file's row otherwise; null when missing. */
    fun stat(path: String): String? = op {
        val f = file(path)
        when {
            !f.exists() -> null
            f.isDirectory -> {
                var size = 0L; var count = 0
                f.walkTopDown().filter { it.isFile }.forEach { size += it.length(); count++ }
                "d\t$size\t${f.lastModified()}\t${f.path}\t$count"
            }
            else -> row(f)
        }
    }

    /** Folders first, then by name (recursive: walk order), at most [limit] rows, then "#total N" (or "#more" when a walk stopped early). */
    fun list(path: String, recursive: Boolean, limit: Int): String = op {
        val dir = file(path)
        if (!dir.isDirectory) throw IllegalArgumentException("Not a directory: ${dir.path}")
        val lim = limit.coerceIn(1, MAX_ROWS)
        if (recursive) {
            val all = dir.walkTopDown().drop(1).take(lim + 1).toList()
            all.take(lim).joinToString("") { row(it) + "\n" } + if (all.size > lim) "#more" else "#total ${all.size}"
        } else {
            val all = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
            all.take(lim).joinToString("") { row(it) + "\n" } + "#total ${all.size}"
        }
    }

    fun mkdirs(path: String): String = op {
        val f = file(path)
        if (f.isDirectory || f.mkdirs()) "ok ${f.path}" else "failed ${f.path}"
    }

    /** The parent folders of a file about to be written (the shell process opens it afterwards). */
    fun prepareWrite(path: String): File = op { file(path).also { it.parentFile?.mkdirs() } }

    fun openable(path: String): File = op { file(path) }

    /** FileTools' file_move semantics for one item; [to] ending in '/' means "into this folder". */
    fun move(from: String, to: String, copy: Boolean): String = op {
        val src = file(from)
        if (!src.exists()) return@op "failed ${src.path}: not found"
        val named = file(to)
        val into = to.endsWith("/") || named.isDirectory
        val dst = if (into) File(named, src.name) else named
        if (!allowed(dst.path)) throw IllegalArgumentException("not in shared storage: ${dst.path}")
        val caseOnly = dst.path != src.path && dst.path.equals(src.path, ignoreCase = true)
        if (dst.path == src.path) return@op "skip ${src.path}: same path"
        if (dst.path.startsWith(src.path + "/")) return@op "failed ${src.path}: cannot move a folder into itself"
        if (dst.exists() && !caseOnly) return@op "skip ${src.path}: ${dst.path} exists"
        if (into) named.mkdirs()
        dst.parentFile?.mkdirs()
        when {
            copy -> { src.copyRecursively(dst); "copied ${src.path} → ${dst.path}" }
            caseOnly -> {
                val tmp = File(src.parentFile, ".dsh-rename-${clock()}")
                if (src.renameTo(tmp) && tmp.renameTo(dst)) "renamed ${src.path} → ${dst.path}" else "failed ${src.path}: rename refused"
            }
            src.renameTo(dst) -> "moved ${src.path} → ${dst.path}"
            else -> { src.copyRecursively(dst); src.deleteRecursively(); "moved ${src.path} → ${dst.path}" }
        }
    }

    fun delete(path: String): String = op {
        val f = file(path)
        when {
            !f.exists() -> "not found ${f.path}"
            f.deleteRecursively() -> "deleted ${f.path}"
            else -> "partially deleted ${f.path}: ${f.walkTopDown().count()} item(s) could not be removed"
        }
    }

    /**
     * Files under [dir] whose name matches [nameGlob] (rg --iglob style: a glob without '/' matches
     * the file name, one with '/' the path below [dir]) and that contain [content] (case-insensitive;
     * literal unless [regex]; text files up to 32 MB). Sorted by [sort] ("recent", "largest", "name"),
     * at most [limit] rows, then "#total N", and "#note …" when the walk stopped early.
     */
    fun search(dir: String, nameGlob: String?, content: String?, regex: Boolean, limit: Int, sort: String?, timeoutMs: Long = 60_000): String = op {
        val root = file(dir)
        if (!root.isDirectory) throw IllegalArgumentException("Not a directory: ${root.path}")
        val name = nameGlob?.takeIf { it.isNotEmpty() }?.let { globRegex(it) }
        val byPath = nameGlob?.contains('/') == true
        val text = content?.takeIf { it.isNotEmpty() }?.let { if (regex) Regex(it, RegexOption.IGNORE_CASE) else Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }
        val deadline = clock() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val hits = ArrayList<File>()
        var note: String? = null
        for (f in root.walkTopDown()) {
            if (clock() > deadline) { note = "search stopped after ${timeoutMs / 1000}s; results are partial — narrow 'dir' or 'name'"; break }
            if (!f.isFile) continue
            if (name != null && !name.matches(if (byPath) f.relativeTo(root).path else f.name)) continue
            if (text != null && !contains(f, text)) continue
            hits += f
            if (hits.size >= MAX_HITS) { note = "stopped at $MAX_HITS matches"; break }
        }
        val sorted = when (sort) {
            "largest" -> hits.sortedByDescending { it.length() }
            "name" -> hits.sortedBy { it.name.lowercase() }
            else -> hits.sortedByDescending { it.lastModified() }
        }
        sorted.take(limit.coerceIn(1, 1000)).joinToString("") { row(it) + "\n" } + "#total ${hits.size}" + (note?.let { "\n#note $it" } ?: "")
    }

    private fun contains(f: File, re: Regex): Boolean {
        if (f.length() > MAX_SEARCH_BYTES) return false
        return runCatching {
            f.inputStream().buffered().use { s ->
                s.mark(4096)
                val head = ByteArray(4096); val n = s.read(head)
                if ((0 until maxOf(n, 0)).any { head[it] == 0.toByte() }) return false
                s.reset()
                s.bufferedReader().lineSequence().any { re.containsMatchIn(it) }
            }
        }.getOrDefault(false)
    }

    companion object {
        const val MAX_ROWS = 2000
        const val MAX_HITS = 20_000
        const val MAX_SEARCH_BYTES = 32L shl 20

        fun row(f: File) = if (f.isDirectory) "d\t0\t${f.lastModified()}\t${f.path}" else "f\t${f.length()}\t${f.lastModified()}\t${f.path}"

        /** Shared storage as the shell user sees it (the app checks the exact Android/data|obb scope). */
        fun sharedStorage(path: String) = path.startsWith("/storage/") || path.startsWith("/sdcard/") || path == "/sdcard" ||
            path.startsWith("/mnt/user/") || path.startsWith("/data/media/")

        /** rg-style glob → case-insensitive regex: `*` within a segment, `**` across, `?`, `[...]`, `{a,b}`. */
        fun globRegex(glob: String): Regex {
            val sb = StringBuilder()
            var i = 0
            var braces = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && glob.getOrNull(i + 1) == '*' -> {
                        i++
                        if (glob.getOrNull(i + 1) == '/') { i++; sb.append("(?:.*/)?") } else sb.append(".*")
                    }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    c == '[' -> {
                        val end = glob.indexOf(']', i + 2)
                        if (end < 0) sb.append("\\[") else {
                            val body = glob.substring(i + 1, end).let { if (it.startsWith("!")) "^" + it.drop(1) else it }.replace("\\", "\\\\")
                            sb.append('[').append(body).append(']'); i = end
                        }
                    }
                    c == '{' -> { braces++; sb.append("(?:") }
                    c == '}' && braces > 0 -> { braces--; sb.append(')') }
                    c == ',' && braces > 0 -> sb.append('|')
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            repeat(braces) { sb.append(')') }
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }

        /** Parses a row (see the class doc). */
        fun parseRow(line: String): Row? {
            val p = line.split('\t')
            if (p.size < 4 || (p[0] != "d" && p[0] != "f")) return null
            return Row(p[0] == "d", p[1].toLongOrNull() ?: 0, p[2].toLongOrNull() ?: 0, p[3], p.getOrNull(4)?.toIntOrNull())
        }
    }

    data class Row(val dir: Boolean, val size: Long, val mtime: Long, val path: String, val count: Int? = null)
}
