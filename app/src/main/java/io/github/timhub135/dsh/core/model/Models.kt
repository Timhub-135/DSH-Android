package io.github.timhub135.dsh.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Risk { read, write, sensitive, dangerous }

enum class ToolGroup(val id: String) {
    Files("files"), Apps("apps"), Screen("screen"), System("system"), Notifications("notifications"),
    Comms("comms"), Media("media"), Clipboard("clipboard"), Browser("browser"), Rules("rules");

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
}

/**
 * dsh permission presets (ui-permission-presets), mapped onto the app's approval gate:
 * read-only refuses every action that needs approval, ask (dsh "workspace-write") asks for each
 * one, full access (the default) approves everything except messages sent to other people.
 */
enum class PermissionPreset(val id: String) {
    ReadOnly("read-only"), Ask("workspace-write"), Full("danger-full-access");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Full }
}

const val AUTO_SESSION_PREFIX = "auto_"

fun ruleIdOfSession(sessionId: String): Long? =
    if (sessionId.startsWith(AUTO_SESSION_PREFIX)) sessionId.removePrefix(AUTO_SESSION_PREFIX).substringBefore('_').toLongOrNull() else null
