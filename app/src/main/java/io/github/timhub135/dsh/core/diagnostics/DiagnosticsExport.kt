package io.github.timhub135.dsh.core.diagnostics

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import io.github.timhub135.dsh.core.engine.EngineDiagnostics
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出到文件: the diagnostics report plus the engine log tail, both through [Redactor] (the same
 * rules as 复制), written where the user (Files app) or a developer (`adb pull`, no run-as) can
 * reach it: `Download/DSH-Android/` through MediaStore, else the app's external files directory
 * (`/sdcard/Android/data/<package>/files/diagnostics/`). Only on the user's tap; nothing is sent.
 */
object DiagnosticsExport {
    const val FOLDER = "DSH-Android"
    /** Characters of engine log appended (the newest run, else the previous one). */
    const val LOG_CHARS = 200_000

    /** The file's text: the (already redacted) report, then the redacted log tail. */
    fun text(report: String, logTail: String?): String = buildString {
        append(Redactor.redact(report).trimEnd()).append("\n\n== engine log (tail) ==\n")
        append(logTail?.takeIf { it.isNotBlank() }?.let(Redactor::redact) ?: "(no engine log)")
        if (!endsWith('\n')) append('\n')
    }

    fun fileName(now: Long = System.currentTimeMillis()): String =
        "dsh-diagnostics-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".txt"

    /** Writes the export; returns the path to show (e.g. `Download/DSH-Android/dsh-diagnostics-….txt`). */
    fun export(context: Context, report: String): String {
        val body = text(report, EngineDiagnostics.logTail(context, LOG_CHARS)).toByteArray()
        val name = fileName()
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused the file")
            resolver.openOutputStream(uri)?.use { it.write(body) } ?: error("MediaStore gave no stream")
            return "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$name"
        }
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(body)
        return file.absolutePath
    }
}
