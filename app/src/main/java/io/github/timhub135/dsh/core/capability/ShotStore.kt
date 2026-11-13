package io.github.timhub135.dsh.core.capability

import android.content.Context
import android.util.Base64
import java.io.File

/**
 * Images returned by device tools (screenshots, browser captures), kept per session and call so
 * the conversation and trajectory views can show them inline. dsh itself only keeps an
 * attachment reference in the event log.
 */
object ShotStore {
    private fun dir(context: Context, sessionId: String) = File(context.filesDir, "shots/${sessionId.replace('/', '_')}")

    fun save(context: Context, sessionId: String, callId: String, images: List<Content.Image>) {
        if (sessionId.isEmpty() || callId.isEmpty() || images.isEmpty()) return
        val d = dir(context, sessionId).apply { mkdirs() }
        images.forEachIndexed { i, img ->
            val ext = img.mimeType.substringAfter('/').ifBlank { "png" }
            runCatching { File(d, "${safe(callId)}_$i.$ext").writeBytes(Base64.decode(img.base64, Base64.DEFAULT)) }
        }
    }

    fun files(context: Context, sessionId: String, callId: String): List<File> {
        val prefix = safe(callId) + "_"
        return dir(context, sessionId).listFiles { f -> f.name.startsWith(prefix) }?.sortedBy { it.name } ?: emptyList()
    }

    fun deleteSession(context: Context, sessionId: String) { dir(context, sessionId).deleteRecursively() }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
