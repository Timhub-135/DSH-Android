package io.github.timhub135.dsh.core.capability.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.capability.CallContext
import io.github.timhub135.dsh.core.capability.Tool
import io.github.timhub135.dsh.core.capability.ToolResult
import io.github.timhub135.dsh.core.capability.ToolSet
import io.github.timhub135.dsh.core.capability.reqStr
import io.github.timhub135.dsh.core.model.Risk
import io.github.timhub135.dsh.core.model.ToolGroup
import io.github.timhub135.dsh.core.capability.ShellUse
import io.github.timhub135.dsh.core.platform.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardTools @Inject constructor(@ApplicationContext private val context: Context, private val shizuku: ShizukuShell) : ToolSet {
    private val cm get() = context.getSystemService(ClipboardManager::class.java)

    override val tools = listOf(
        object : Tool("clipboard_get", ToolGroup.Clipboard, Risk.sensitive, false,
            "Read the clipboard text. Android only allows this while DSH is in the foreground (or via Shizuku).", {},
            ShellUse.Fallback, "only work while DSH is in the foreground") {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val (readable, text) = withContext(Dispatchers.Main) {
                    val clip = runCatching { cm.primaryClip }.getOrNull()
                    (clip != null) to clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                }
                if (text != null) return ToolResult.text(text.ifEmpty { "(the clipboard holds empty text)" })
                // In front, a null clip really means empty (in the background Android hides it, so it says nothing).
                if (!readable && io.github.timhub135.dsh.core.platform.AppVisibility.isForeground) return ToolResult.text("The clipboard is empty.")
                if (shizuku.available) {
                    // `cmd clipboard` exists on Android 13+; the old `service call` path prints a binder Parcel dump, which is not text.
                    val r = shizuku.exec("cmd clipboard get-primary-clip 2>/dev/null")
                    val out = r.output.trim()
                    if (r.code == 0 && out.isNotEmpty() && !out.startsWith("Result: Parcel") && !out.contains("Unknown command"))
                        return ToolResult.text("(read via Shizuku) " + out.removePrefix("ClipData { text/plain ").removeSuffix("}"))
                }
                return ToolResult.error("failed", "Clipboard is not readable right now: Android only lets the app in front read it, and ${shizuku.access.state.value.reason}. Open DSH and retry, or ask the user to paste it.")
            }
        },
        object : Tool("clipboard_set", ToolGroup.Clipboard, Risk.write, false, "Put text on the clipboard.", {
            string("text", "", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                withContext(Dispatchers.Main) { cm.setPrimaryClip(ClipData.newPlainText("DSH", args.reqStr("text"))) }
                return ToolResult.text("Copied to clipboard")
            }
        },
    )
}
