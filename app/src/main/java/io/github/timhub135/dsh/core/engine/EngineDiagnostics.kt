package io.github.timhub135.dsh.core.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash evidence for the `:engine` process (review §12):
 *  - `files/logs/engine.log` is Node's stderr for the current run; each start rotates the previous
 *    runs to `engine.log.1` … `engine.log.3` (oldest dropped), so the log of a crashed run survives.
 *  - On every `:main` start and after each unexpected engine exit, [collectExitInfo] reads
 *    ActivityManager#getHistoricalProcessExitReasons for `:engine` (Android 11+) and stores each new
 *    record as `exit-<time>.txt`; for REASON_CRASH_NATIVE the tombstone (protobuf, Android 12+) is
 *    saved raw as `tombstone-<time>.pb` (decode with `pbtombstone` / ndk-stack) and its readable
 *    strings (cause, abort message, frames) are appended to the text record. The last 3 are kept.
 * Doctor / About shows the latest record and the log tail.
 */
object EngineDiagnostics {
    private const val TAG = "EngineDiagnostics"
    private const val KEEP_LOGS = 3
    private const val KEEP_EXITS = 3

    fun logsDir(context: Context) = File(context.filesDir, "logs").apply { mkdirs() }
    fun currentLog(context: Context) = File(logsDir(context), "engine.log")

    /** Runs in `:engine` before Node starts: engine.log → .1 → .2 → .3. Returns the fresh log file. */
    fun rotateLogs(context: Context): File {
        val dir = logsDir(context)
        File(dir, "engine.log.$KEEP_LOGS").delete()
        for (i in KEEP_LOGS - 1 downTo 1) File(dir, "engine.log.$i").takeIf { it.exists() }?.renameTo(File(dir, "engine.log.${i + 1}"))
        currentLog(context).takeIf { it.exists() }?.renameTo(File(dir, "engine.log.1"))
        return currentLog(context)
    }

    /** Tail of the newest non-empty engine log (current run, else the previous one). */
    fun logTail(context: Context, chars: Int): String? = runCatching {
        listOf(currentLog(context), File(logsDir(context), "engine.log.1"))
            .firstOrNull { it.exists() && it.length() > 0 }?.readText()?.trim()?.takeLast(chars)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Called in `:engine` right before it kills itself after a normal Node exit. */
    fun markCleanExit(context: Context, pid: Int) {
        runCatching {
            val dir = logsDir(context)
            dir.listFiles { f -> f.name.startsWith(".clean-") }.orEmpty().sortedBy { it.lastModified() }.dropLast(8).forEach { it.delete() }
            File(dir, ".clean-$pid").writeText("")
        }
    }

    data class ExitRecord(val time: Long, val summary: String, val file: File)

    /** Newest saved exit records first. */
    fun exitRecords(context: Context): List<ExitRecord> =
        logsDir(context).listFiles { f -> f.name.startsWith("exit-") && f.name.endsWith(".txt") }.orEmpty()
            .sortedByDescending { it.name }
            .map { f -> ExitRecord(f.name.removePrefix("exit-").removeSuffix(".txt").toLongOrNull() ?: f.lastModified(), f.useLines { it.firstOrNull() } ?: "", f) }

    /** Imports `:engine` exit reasons recorded by the system since the last call. Safe to call from any thread. */
    @Synchronized fun collectExitInfo(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val dir = logsDir(context)
            val marker = File(dir, ".exit-seen")
            val seen = marker.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            val engine = context.packageName + ":engine"
            val fresh = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
                .filter { it.processName == engine && it.timestamp > seen }
                .sortedBy { it.timestamp }
            fresh.forEach { save(dir, it) }
            fresh.maxOfOrNull { it.timestamp }?.let { marker.writeText(it.toString()) }
            prune(dir)
        }.onFailure { Log.w(TAG, "exit info unavailable", it) }
    }

    @androidx.annotation.RequiresApi(30)
    private fun save(dir: File, info: ApplicationExitInfo) {
        val ts = info.timestamp
        val reason = reasonName(info.reason)
        // User/app-initiated exits (our own idle shutdown kills the process) are not crashes.
        if (info.reason == ApplicationExitInfo.REASON_EXIT_SELF && info.status == 0) return
        if (info.reason == ApplicationExitInfo.REASON_USER_REQUESTED) return
        // EngineService kills its own process after Node exits normally; it leaves this marker first.
        File(dir, ".clean-${info.pid}").let { if (it.exists()) { it.delete(); return } }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))
        val text = StringBuilder()
        text.append("$time  $reason").append(if (info.reason == ApplicationExitInfo.REASON_SIGNALED || info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) " (signal ${info.status})" else " (status ${info.status})")
        info.description?.let { text.append(" — ").append(it) }
        text.append('\n')
        text.append("pid=${info.pid} importance=${info.importance} pss=${info.pss}kB rss=${info.rss}kB\n")
        val trace = runCatching { info.traceInputStream?.use { it.readBytes() } }.getOrNull()
        if (trace != null && trace.isNotEmpty()) {
            if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                File(dir, "tombstone-$ts.pb").writeBytes(trace)
                text.append("\ntombstone-$ts.pb (protobuf; decode with pbtombstone or ndk-stack). Readable strings:\n")
                printableStrings(trace).take(120).forEach { text.append(it).append('\n') }
            } else {
                text.append('\n').append(String(trace, Charsets.UTF_8).take(64_000))
            }
        }
        File(dir, "exit-$ts.txt").writeText(text.toString())
        Log.w(TAG, "engine exit recorded: ${text.lineSequence().first()}")
    }

    private fun prune(dir: File) {
        listOf("exit-" to ".txt", "tombstone-" to ".pb").forEach { (prefix, suffix) ->
            dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(suffix) }.orEmpty()
                .sortedByDescending { it.name }.drop(KEEP_EXITS).forEach { it.delete() }
        }
    }

    /** UTF-8 runs of printable characters (≥ 6), which in a tombstone carry cause, abort message and frame names. */
    internal fun printableStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.length >= 6) out += cur.toString(); cur.setLength(0) }
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) cur.append(c.toChar()) else flush()
        }
        flush()
        return out.distinct()
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "reason $r"
    }
}
