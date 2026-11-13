package io.github.timhub135.dsh.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Built-in engine tool categories a mode can switch (ENGINE-CONTRACT §2.5; plugin-mode maps each
 * id to dsh tool names). Tools outside every category (ask_user_question, todo, goals, …) stay.
 */
enum class EngineTool(val id: String) {
    Shell("shell"), FilesRead("files_read"), FilesWrite("files_write"), Web("web"), Subagent("subagent"),
    /**
     * 扩展工具 (PLUGINS-DESIGN §8.2): every tool a third-party plugin registers and every `mcp__*`
     * tool. Nobody can promise those are read-only, so read-only modes leave this out.
     */
    Extensions("extensions");
    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
        /** The categories that existed before [Extensions] (modes stored with all of them get it added, see [SessionModes.migrate]). */
        val legacy: Set<String> = setOf(Shell.id, FilesRead.id, FilesWrite.id, Web.id, Subagent.id)
    }
}

/** The fixed icon set a mode can use (ids are stored; the drawable mapping lives in the UI). */
enum class ModeIcon(val id: String) {
    Default("default"), Phone("phone"), Code("code"), Chat("chat"), Globe("globe"), Pen("pen"), Goal("goal"), Skill("skill");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Default }
}

/**
 * A session mode (issue #3): which tools a conversation gets, extra system-prompt text, and the
 * permission preset picked with it. Chosen per conversation before its first message, then fixed.
 * The app owns the definitions (built-ins below + the user's own, in settings) and sends the
 * resolved mode to the engine with `android/session/mode` before every prompt.
 */
@Serializable
data class SessionMode(
    val id: String,
    /** Display name; built-ins leave it empty and are named from resources ([SessionModes.nameRes]). */
    val name: String = "",
    val icon: String = ModeIcon.Default.id,
    /** Extra system-prompt text (model-facing; English reads best, any language works). */
    val prompt: String = "",
    /** Device tool groups ([ToolGroup] ids) the mode offers; null = every group the user enabled. */
    val deviceGroups: Set<String>? = null,
    /** Built-in engine tool categories ([EngineTool] ids); null = all of them. */
    val engineTools: Set<String>? = null,
    /** [PermissionPreset] id applied when the mode is picked; null = the settings default. */
    val preset: String? = null,
    val skills: Boolean = true,
    val builtIn: Boolean = false,
    /** Which [EngineTool] set the stored [engineTools] was chosen from ([SessionModes.TOOLS_VERSION]; 0 = before 扩展工具). */
    val toolsVersion: Int = 0,
) {
    val isDefault: Boolean get() = id == SessionModes.DEFAULT_ID

    /** Whether the mode lets the conversation use device tools of [group] (the user's global switch still applies). */
    fun allowsGroup(group: ToolGroup): Boolean = deviceGroups == null || group.id in deviceGroups

    fun allowsTool(tool: EngineTool): Boolean = engineTools == null || tool.id in engineTools

    val presetOrNull: PermissionPreset? get() = preset?.let { id -> PermissionPreset.entries.firstOrNull { it.id == id } }

    /** The `android/session/mode` payload's `mode` object, named [displayName]. */
    fun payload(displayName: String): JsonObject = buildJsonObject {
        put("id", id)
        put("name", displayName)
        if (prompt.isNotBlank()) put("prompt", prompt.trim())
        deviceGroups?.let { g -> putJsonArray("deviceGroups") { g.sorted().forEach { add(JsonPrimitive(it)) } } }
        engineTools?.let { t -> putJsonArray("tools") { t.sorted().forEach { add(JsonPrimitive(it)) } } }
        put("skills", skills)
    }

    /** An editable copy (a user mode), e.g. 复制 on a built-in. */
    fun duplicate(newId: String, newName: String) = copy(id = newId, name = newName, builtIn = false)
}

object SessionModes {
    const val DEFAULT_ID = "default"
    const val PHONE_ID = "phone"
    const val CODE_ID = "code"
    const val CHAT_ID = "chat"
    /** User modes get ids with this prefix, so they never collide with a future built-in. */
    const val CUSTOM_PREFIX = "u_"
    /** Bumped when [EngineTool] gains a category that existing all-on selections should include. */
    const val TOOLS_VERSION = 1

    /**
     * A user mode stored before 扩展工具 existed: if it had every built-in category on, it gets
     * [EngineTool.Extensions] too (it meant "everything"); otherwise it stays as chosen.
     */
    fun migrate(m: SessionMode): SessionMode {
        if (m.toolsVersion >= TOOLS_VERSION) return m
        val tools = m.engineTools
        val next = if (tools != null && tools.containsAll(EngineTool.legacy)) tools + EngineTool.Extensions.id else tools
        return m.copy(engineTools = next, toolsVersion = TOOLS_VERSION)
    }

    /** 默认: today's behaviour — every tool, the settings' permission default, no extra prompt. */
    val Default = SessionMode(DEFAULT_ID, icon = ModeIcon.Default.id, builtIn = true)

    /** 手机操作: device and screen tools, phone-oriented prompt; asks before acting. */
    val Phone = SessionMode(
        PHONE_ID, icon = ModeIcon.Phone.id, builtIn = true,
        prompt = "Focus on getting things done on this phone with the device tools: settings, apps, notifications, messages, files, " +
            "media and operating other apps on screen. Prefer doing the task over explaining how to do it, and keep the user informed in one line per step.",
        deviceGroups = ToolGroup.entries.map { it.id }.toSet(),
        engineTools = setOf(EngineTool.FilesRead.id, EngineTool.Web.id, EngineTool.Subagent.id, EngineTool.Extensions.id),
        preset = PermissionPreset.Ask.id, skills = true,
    )

    /** 编程: shell, files, skills, web; no screen or device-control groups. */
    val Code = SessionMode(
        CODE_ID, icon = ModeIcon.Code.id, builtIn = true,
        prompt = "You are a programming assistant. Work in your private home folder with the shell and file tools: write, run and debug code " +
            "and scripts, and explain briefly. The shell is Android's /system/bin/sh with toybox; there is no root and often no compilers or " +
            "interpreters, so check what exists (e.g. `command -v python3`) before relying on it. Show code in fenced blocks.",
        deviceGroups = setOf(ToolGroup.Files.id, ToolGroup.Clipboard.id),
        engineTools = EngineTool.entries.map { it.id }.toSet(),
        preset = PermissionPreset.Ask.id, skills = true,
    )

    /** 只聊天: conversation plus web search / fetch; read-only. */
    val Chat = SessionMode(
        CHAT_ID, icon = ModeIcon.Chat.id, builtIn = true,
        prompt = "This is a chat conversation: answer from your own knowledge, and use web search or web fetch when current or external " +
            "information would help. You cannot act on the phone, read its data or run commands here.",
        deviceGroups = emptySet(), engineTools = setOf(EngineTool.Web.id),
        preset = PermissionPreset.ReadOnly.id, skills = false,
    )

    val builtIns: List<SessionMode> = listOf(Default, Phone, Code, Chat)

    /** Built-ins first, then the user's modes (ids clashing with a built-in are ignored). */
    fun all(custom: List<SessionMode>): List<SessionMode> =
        builtIns + custom.filter { c -> builtIns.none { it.id == c.id } }.map { it.copy(builtIn = false) }

    /**
     * The mode of a conversation whose row says [id]. null (sessions from before modes existed) and
     * ids that no longer exist (a deleted user mode) are 默认, i.e. unchanged behaviour.
     */
    fun resolve(id: String?, custom: List<SessionMode>): SessionMode =
        id?.let { want -> all(custom).firstOrNull { it.id == want } } ?: Default

    fun newCustomId(existing: Collection<String>, random: () -> String = { java.util.UUID.randomUUID().toString().take(8) }): String {
        while (true) { val id = CUSTOM_PREFIX + random(); if (id !in existing) return id }
    }
}

/**
 * Whether a conversation's mode can still be changed: only before its first message. After that
 * the engine already ran with its tool set and prompt (changing them mid-history would confuse the
 * model and re-bill the cached prompt), so the composer shows the mode but locks it.
 */
fun modeChangeable(exists: Boolean, running: Boolean, hasEngineEvents: Boolean): Boolean = !exists && !running && !hasEngineEvents
