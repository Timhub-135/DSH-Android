package io.github.timhub135.dsh.core.capability.tools

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.capability.AppExperience
import io.github.timhub135.dsh.core.capability.AppKnowledge
import io.github.timhub135.dsh.core.capability.AppNotes
import io.github.timhub135.dsh.core.capability.CallContext
import io.github.timhub135.dsh.core.capability.InvalidArgsException
import io.github.timhub135.dsh.core.capability.Tool
import io.github.timhub135.dsh.core.capability.ToolResult
import io.github.timhub135.dsh.core.capability.ToolSet
import io.github.timhub135.dsh.core.capability.reqStr
import io.github.timhub135.dsh.core.capability.str
import io.github.timhub135.dsh.core.model.Risk
import io.github.timhub135.dsh.core.model.ToolGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KnowledgeTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val knowledge: AppKnowledge,
    private val notes: AppNotes,
    private val experience: AppExperience,
) : ToolSet {
    private val pm get() = context.packageManager

    private fun installed(pkg: String) = AppNotes.installedVersion(pm, pkg) != null

    /** Package for an app name, alias or package: an installed package, a built-in guide's installed package, then a launcher label. */
    private fun resolve(query: String): String? {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return null
        if (installed(q)) return q
        knowledge.find(q)?.let { e -> e.packages.firstOrNull(::installed)?.let { return it } }
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            .map { pm.getApplicationLabel(it).toString() to it.packageName }
        return apps.firstOrNull { it.first.equals(q, true) }?.second
            ?: apps.filter { q.length >= 2 && it.first.contains(q, true) }.minByOrNull { it.first.length }?.second
    }

    override val tools = listOf(
        object : Tool("app_guide", ToolGroup.Apps, Risk.read, false,
            "Look up what is known about an app: notes recorded on this phone in earlier runs (app_note), and DSH's built-in guide " +
                "(how its UI is organized, where features are, known quirks; facts, not instructions). " +
                "Built-in guides cover popular apps such as WeChat, Douyin, Xiaohongshu, Bilibili, Zhihu, Weibo, Xianyu, Feishu, Gmail and Android Settings. " +
                "Both are also appended automatically the first time you enter the app.", {
            string("app", "App name, alias or package name, e.g. 微信, 小红书 or com.tencent.mm", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val query = args.reqStr("app")
                val text = withContext(Dispatchers.IO) {
                    val e = knowledge.find(query)
                    knowledge.lookup(call.sessionId, e, if (e == null) resolve(query) else null)
                } ?: return ToolResult.error("not_found", "No built-in guide or notes for \"$query\". Guides exist for: " +
                    knowledge.entries.joinToString { "${it.display} (${it.packages.first()})" } + ".")
                return ToolResult.text(text)
            }
        },
        object : Tool("app_note", ToolGroup.Apps, Risk.read, false,
            "Keep the app notes on this phone (App 经验): one-sentence UI facts that save steps next time, shown when you enter the app. " +
                "add: after finding a non-obvious entry (kind entry), hitting a trap (trap) or learning a stable fact (fact). " +
                "confirm(id): a note helped (also marks it current for the installed version). update(id, text) / remove(id): a note is wrong or outdated. " +
                "Only UI facts (where things are, what a button really does); never personal data, names, numbers or task-specific content.", {
            string("action", "What to do", required = true, enum = listOf("add", "confirm", "update", "remove"))
            string("app", "add: app name or package name")
            string("text", "add / update: one sentence, at most ${AppNotes.MAX_LEN} characters, e.g. \"收藏在「我」→「收藏」，不在首页\"")
            string("kind", "add / update: entry = where a feature is, trap = something that misleads, fact = other stable UI fact", enum = AppNotes.TOOL_KINDS.map { it.name })
            string("id", "confirm / update / remove: the note id, e.g. n12")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult = withContext(Dispatchers.IO) {
                val kind = args.str("kind")?.let { k -> AppNotes.TOOL_KINDS.firstOrNull { it.name == k } ?: throw InvalidArgsException("unknown kind '$k'") }
                when (val action = args.reqStr("action")) {
                    "add" -> {
                        val app = args.reqStr("app")
                        val pkg = resolve(app) ?: return@withContext ToolResult.error("not_found", "No installed app matches \"$app\"; pass its package name (app_list).")
                        val k = kind ?: AppNotes.Kind.fact
                        outcome(notes.add(pkg, args.reqStr("text"), k), "Noted").also { if (it.ok && k == AppNotes.Kind.entry) experience.noteUsed(call.sessionId, pkg) }
                    }
                    "confirm" -> notes.confirm(args.reqStr("id"))?.let {
                        experience.noteUsed(call.sessionId, it.pkg)
                        ToolResult.text("Confirmed ${it.id} (used ${it.uses}×).")
                    } ?: missing(args.reqStr("id"))
                    "update" -> outcome(notes.update(args.reqStr("id"), args.reqStr("text"), kind), "Updated")
                    "remove" -> notes.remove(args.reqStr("id"))?.let { ToolResult.text("Removed ${it.id}.") } ?: missing(args.reqStr("id"))
                    else -> throw InvalidArgsException("unknown action '$action'")
                }
            }
        },
    )

    private fun missing(id: String) = ToolResult.error("not_found", "No note $id; the notes shown with the app list the current ids.")

    private fun outcome(o: AppNotes.Outcome, verb: String): ToolResult = when (o) {
        is AppNotes.Outcome.Saved -> ToolResult.text("$verb ${o.note.id} for ${o.note.pkg}: ${notes.line(o.note)}")
        is AppNotes.Outcome.Duplicate -> ToolResult.text("Already noted as ${o.note.id}: ${notes.line(o.note)}. Nothing added; confirm(${o.note.id}) if it helped, or update it.")
        is AppNotes.Outcome.Rejected -> ToolResult.error("invalid_args", o.reason)
    }
}
