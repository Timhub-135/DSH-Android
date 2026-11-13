package io.github.timhub135.dsh.core.capability.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.capability.ActivityLauncher
import io.github.timhub135.dsh.core.capability.CallContext
import io.github.timhub135.dsh.core.capability.InvalidArgsException
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val launcher: ActivityLauncher,
) : ToolSet {
    private val pm get() = context.packageManager

    private companion object {
        const val SCREEN = "screen"
    }

    private fun label(ai: ApplicationInfo) = pm.getApplicationLabel(ai).toString()

    private suspend fun open(call: CallContext, args: JsonObject, verb: String, intent: Intent, expect: String?, create: Boolean): ToolResult =
        launcher.open(call, args.str(SCREEN), verb, intent, expect, create)

    override val tools = listOf(
        object : Tool("app_list", ToolGroup.Apps, Risk.read, false, "List installed user-launchable apps (label + package). Filter with query.", {
            string("query", "Substring of app name or package")
            bool("includeSystem", "Include system apps")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val q = args.str("query")?.lowercase()
                val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
                val list = launchable.filter { args.bool("includeSystem") == true || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || q != null }
                    .map { label(it) to it.packageName }
                    .filter { q == null || it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
                    .sortedBy { it.first.lowercase() }
                return ToolResult.text(list.joinToString("\n") { "${it.first}  (${it.second})" }.ifEmpty { "No matching apps." })
            }
        },
        object : Tool("app_info", ToolGroup.Apps, Risk.read, false,
            "Details of an installed app: version, install/update time, size, and its exported activities (full names usable as app_launch activity).", {
            string("package", "Package name", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val p = args.reqStr("package")
                val info = try { pm.getPackageInfo(p, PackageManager.GET_ACTIVITIES) } catch (e: PackageManager.NameNotFoundException) {
                    throw InvalidArgsException("Not installed: $p") }
                val ai = info.applicationInfo!!
                return ToolResult.text(buildString {
                    appendLine("${label(ai)} ($p) ${info.versionName}")
                    appendLine("installed ${java.util.Date(info.firstInstallTime)}, updated ${java.util.Date(info.lastUpdateTime)}")
                    appendLine("apk ${File(ai.sourceDir).length() / 1024 / 1024} MB, system=${(ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0}")
                    append("exported activities: ${info.activities?.filter { it.exported }?.take(30)?.joinToString { it.name } ?: "-"}")
                })
            }
        },
        object : Tool("app_launch", ToolGroup.Apps, Risk.write, true,
            "Open an app by package name (optionally a specific exported activity) on the user's screen. " +
                "The screen_* tools then act on what is in front.", {
            string("package", "Package name", required = true)
            string("activity", "Fully-qualified exported activity (optional)")
            screen()
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val p = args.reqStr("package")
                val intent = args.str("activity")?.let { a -> Intent().setComponent(ComponentName(p, if (a.startsWith(".")) p + a else a)) }
                    ?: pm.getLaunchIntentForPackage(p) ?: throw InvalidArgsException("No launchable activity for $p")
                return open(call, args, "Launched", intent, p, create = true)
            }
        },
        object : Tool("app_open_link", ToolGroup.Apps, Risk.write, true,
            "Open a URI/deep link (https:, tel:, geo:, mailto:, app schemes like weixin://, alipays://, etc.) on the user's screen.", {
                string("uri", "The URI", required = true)
                string("package", "Restrict to this app (optional)")
                screen()
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(args.reqStr("uri")))
                args.str("package")?.let { intent.setPackage(it) }
                return open(call, args, "Opened", intent, launcher.targetOf(intent), create = true)
            }
        },
        object : Tool("app_send_intent", ToolGroup.Apps, Risk.write, true,
            "Start an activity with an explicit Android intent (action/data/type/package; extras values may be strings, numbers or booleans).", {
                string("action", "e.g. android.settings.BLUETOOTH_SETTINGS", required = true)
                string("data", "Data URI")
                string("type", "MIME type")
                string("package", "Target package")
                obj("extras", "String extras {key: value}")
                screen()
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val intent = Intent(args.reqStr("action"))
                val data = args.str("data")?.let(Uri::parse)
                val type = args.str("type")
                if (data != null && type != null) intent.setDataAndType(data, type) else { data?.let(intent::setData); type?.let(intent::setType) }
                args.str("package")?.let(intent::setPackage)
                (args["extras"] as? JsonObject)?.forEach { (k, v) ->
                    val p = v as? JsonPrimitive ?: return@forEach
                    val b = p.booleanOrNull; val n = p.longOrNull; val d = p.doubleOrNull
                    when {
                        p.isString -> intent.putExtra(k, p.content)
                        b != null -> intent.putExtra(k, b)
                        n != null -> if (n in Int.MIN_VALUE..Int.MAX_VALUE) intent.putExtra(k, n.toInt()) else intent.putExtra(k, n)
                        d != null -> intent.putExtra(k, d)
                        else -> p.contentOrNull?.let { intent.putExtra(k, it) }
                    }
                }
                return open(call, args, "Sent", intent, launcher.targetOf(intent), create = false)
            }
        },
        object : Tool("share_to_app", ToolGroup.Apps, Risk.write, true, "Share files and/or text to another app (opens the share target).", {
            strings("paths", "Files to share")
            string("text", "Text to share")
            string("package", "Target app package (optional: otherwise the system chooser opens)")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val root = Environment.getExternalStorageDirectory()
                val files = args.strs("paths").map { p -> (if (p.startsWith("/")) File(p) else File(root, p)).canonicalFile }
                files.firstOrNull { !it.isFile }?.let { throw InvalidArgsException("Not a file: ${it.path}") }
                val uris = ArrayList(files.map { f ->
                    try { FileProvider.getUriForFile(context, "${context.packageName}.files", f) }
                    catch (e: IllegalArgumentException) { throw InvalidArgsException("Cannot share ${f.path}: only shared storage and SD cards can be shared") }
                })
                // The real MIME type: several targets (WeChat among them) mishandle */*.
                val types = files.map { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension.lowercase()) ?: "application/octet-stream" }.distinct()
                val mime = when {
                    types.isEmpty() -> "text/plain"
                    types.size == 1 -> types[0]
                    types.map { it.substringBefore('/') }.distinct().size == 1 -> types[0].substringBefore('/') + "/*"
                    else -> "*/*"
                }
                val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                    type = mime
                    args.str("text")?.let { putExtra(Intent.EXTRA_TEXT, it) }
                    if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val target = args.str("package")?.let { intent.setPackage(it) } ?: Intent.createChooser(intent, null)
                return launcher.launchResult("Share sheet opened: ", target, expect = args.str("package"))
            }
        },
    )
}
