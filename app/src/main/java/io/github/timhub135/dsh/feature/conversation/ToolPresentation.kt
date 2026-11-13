package io.github.timhub135.dsh.feature.conversation

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.session.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Step-group activities (dsh ChatGroupSeat titles) plus phone activities. [counted] titles take
 * the number of calls ("已点击 3 次").
 */
enum class Activity(@StringRes val live: Int, @StringRes val done: Int, val counted: Boolean = false) {
    Read(R.string.act_read_live, R.string.act_read_done),
    Search(R.string.act_search_live, R.string.act_search_done),
    Edit(R.string.act_edit_live, R.string.act_edit_done),
    Write(R.string.act_write_live, R.string.act_write_done),
    Command(R.string.act_cmd_live, R.string.act_cmd_done),
    WebSearch(R.string.act_websearch_live, R.string.act_websearch_done),
    WebFetch(R.string.act_webfetch_live, R.string.act_webfetch_done),
    Subagent(R.string.act_subagent_live, R.string.act_subagent_done),
    Plan(R.string.act_plan_live, R.string.act_plan_done),
    Question(R.string.act_question_live, R.string.act_question_done),
    ScreenRead(R.string.act_screen_live, R.string.act_screen_done),
    Tap(R.string.act_tap_live, R.string.act_tap_done, counted = true),
    Swipe(R.string.act_swipe_live, R.string.act_swipe_done, counted = true),
    Type(R.string.act_type_live, R.string.act_type_done),
    Key(R.string.act_key_live, R.string.act_key_done, counted = true),
    Apps(R.string.act_apps_live, R.string.act_apps_done),
    System(R.string.act_system_live, R.string.act_system_done),
    Notifications(R.string.act_notif_live, R.string.act_notif_done),
    Messages(R.string.act_msg_live, R.string.act_msg_done),
    Browser(R.string.act_browser_live, R.string.act_browser_done),
    Files(R.string.act_files_live, R.string.act_files_done),
    Clipboard(R.string.act_clip_live, R.string.act_clip_done),
    Media(R.string.act_media_live, R.string.act_media_done),
    Rules(R.string.act_rules_live, R.string.act_rules_done),
    Other(R.string.act_other_live, R.string.act_other_done),
}

/** How one tool renders: icon, row title, group activity, and the one-line summary from its arguments. */
data class ToolInfo(@DrawableRes val icon: Int, @StringRes val title: Int, val activity: Activity, val body: BodyKind = BodyKind.InOut)

/** Which expanded-body renderer a tool uses. */
enum class BodyKind { InOut, Terminal, Read, Search, Web, Todo, Screen, Gesture, Text, Question }

object ToolPresentation {
    fun info(name: String): ToolInfo = when (name) {
        "read", "file_read", "read_image" -> ToolInfo(R.drawable.dsw_browse_outline, R.string.tool_read, Activity.Read, BodyKind.Read)
        "file_stat" -> ToolInfo(R.drawable.dsw_browse_outline, R.string.tool_file_stat, Activity.Read, BodyKind.Text)
        "file_list" -> ToolInfo(R.drawable.dsw_folder_open_outline, R.string.tool_file_list, Activity.Read, BodyKind.Text)
        "grep" -> ToolInfo(R.drawable.dsw_search_outline, R.string.tool_grep, Activity.Search, BodyKind.Search)
        "glob", "file_search" -> ToolInfo(R.drawable.dsw_search_outline, R.string.tool_glob, Activity.Search, BodyKind.Search)
        "edit", "str_replace_editor" -> ToolInfo(R.drawable.dsw_edit_outline, R.string.tool_edit, Activity.Edit, BodyKind.InOut)
        "write", "file_write" -> ToolInfo(R.drawable.dsw_edit_outline, R.string.tool_write, Activity.Write, BodyKind.InOut)
        "file_mkdir", "file_move", "file_delete", "archive_create", "archive_extract" ->
            ToolInfo(if (name == "file_delete") R.drawable.dsw_trash_outline else R.drawable.dsw_folder_close, titleOf(name), Activity.Files, BodyKind.Text)
        "bash", "pwsh", "shell" -> ToolInfo(R.drawable.dsw_api_outline, R.string.tool_bash, Activity.Command, BodyKind.Terminal)
        "adb_shell" -> ToolInfo(R.drawable.dsw_api_outline, R.string.tool_adb_shell, Activity.Command, BodyKind.Terminal)
        "web_search" -> ToolInfo(R.drawable.dsw_globe_outline, R.string.tool_web_search, Activity.WebSearch, BodyKind.Web)
        "web_fetch" -> ToolInfo(R.drawable.dsw_browse_outline, R.string.tool_web_fetch, Activity.WebFetch, BodyKind.Web)
        "todo_write" -> ToolInfo(R.drawable.dsw_checklist_outline, R.string.tool_todo, Activity.Plan, BodyKind.Todo)
        "ask_user_question" -> ToolInfo(R.drawable.dsw_question_outline, R.string.tool_ask, Activity.Question, BodyKind.Question)
        "screen_read" -> ToolInfo(R.drawable.dsw_inspect_outline, R.string.tool_screen_read, Activity.ScreenRead, BodyKind.Text)
        "screen_capture" -> ToolInfo(R.drawable.dsw_fullscreen_outline, R.string.tool_screen_capture, Activity.ScreenRead, BodyKind.Screen)
        "screen_tap" -> ToolInfo(R.drawable.dsw_right_up_outline, R.string.tool_screen_tap, Activity.Tap, BodyKind.Gesture)
        "screen_swipe", "screen_scroll" -> ToolInfo(R.drawable.dsw_chevrons_up_down_outline, if (name == "screen_swipe") R.string.tool_screen_swipe else R.string.tool_screen_scroll, Activity.Swipe, BodyKind.Gesture)
        "screen_set_wheel" -> ToolInfo(R.drawable.dsw_chevrons_up_down_outline, R.string.tool_screen_set_wheel, Activity.Swipe, BodyKind.Gesture)
        "screen_type" -> ToolInfo(R.drawable.dsw_edit_outline, R.string.tool_screen_type, Activity.Type, BodyKind.Gesture)
        "screen_key" -> ToolInfo(R.drawable.dsw_chevron_left_outline, R.string.tool_screen_key, Activity.Key, BodyKind.Gesture)
        "app_list", "app_info", "app_guide", "app_note" -> ToolInfo(R.drawable.dsw_apps_add_outline, titleOf(name), Activity.Apps, BodyKind.Text)
        "app_launch", "app_open_link", "app_send_intent", "share_to_app" -> ToolInfo(R.drawable.dsw_right_up_outline, titleOf(name), Activity.Apps, BodyKind.Gesture)
        "device_status" -> ToolInfo(R.drawable.dsw_gauge_outline, R.string.tool_device_status, Activity.System, BodyKind.Text)
        "sound_mode", "volume_set", "dnd_set", "brightness_set", "flashlight", "alarm_set", "alarm_show", "wifi_toggle", "bluetooth_toggle", "mobile_data_toggle", "setting_put" ->
            ToolInfo(if (name == "alarm_set" || name == "alarm_show") R.drawable.dsw_alarm_clock_outline else R.drawable.dsw_sliders_two_outline, titleOf(name), Activity.System, BodyKind.Gesture)
        "notification_list", "notification_dismiss" -> ToolInfo(R.drawable.dsw_queue_outline, titleOf(name), Activity.Notifications, BodyKind.Text)
        "contacts_search", "sms_read", "call_log_read", "calendar_read", "calendar_add", "calendar_new_event", "sms_send" ->
            ToolInfo(if (name == "sms_send") R.drawable.dsw_paper_plane_outline else R.drawable.dsw_user_outline, titleOf(name), Activity.Messages, BodyKind.Text)
        "browser_open", "browser_read", "browser_click", "browser_type", "browser_scroll", "browser_back" ->
            ToolInfo(R.drawable.dsw_globe_outline, titleOf(name), Activity.Browser, if (name == "browser_read" || name == "browser_open") BodyKind.Text else BodyKind.Gesture)
        "browser_screenshot" -> ToolInfo(R.drawable.dsw_fullscreen_outline, R.string.tool_browser_screenshot, Activity.Browser, BodyKind.Screen)
        "clipboard_get", "clipboard_set" -> ToolInfo(R.drawable.dsw_copy_outline, titleOf(name), Activity.Clipboard, BodyKind.Text)
        "media_control" -> ToolInfo(R.drawable.dsw_play_outline, R.string.tool_media, Activity.Media, BodyKind.Gesture)
        "rule_create", "rule_list", "rule_update", "rule_delete" -> ToolInfo(R.drawable.dsw_clock_outline, titleOf(name), Activity.Rules, BodyKind.InOut)
        "hand_to_user" -> ToolInfo(R.drawable.dsw_user_outline, R.string.tool_hand_to_user, Activity.Other, BodyKind.Text)
        "schedule_create", "schedule_update", "schedule_delete", "schedule_list" -> ToolInfo(R.drawable.dsw_clock_outline, R.string.tool_schedule, Activity.Rules, BodyKind.InOut)
        "skill" -> ToolInfo(R.drawable.dsw_skill_outline, R.string.tool_skill, Activity.Other, BodyKind.InOut)
        // Deferred tool loading (engine plugin-mode, issue #36): activates tool definitions on demand.
        "load_tools" -> ToolInfo(R.drawable.dsw_apps_add_outline, R.string.tool_load_tools, Activity.Other, BodyKind.InOut)
        else -> if ("agent" in name || "subagent" in name || name == "send_message")
            ToolInfo(R.drawable.dsw_users_outline, R.string.tool_subagent, Activity.Subagent, BodyKind.InOut)
        else ToolInfo(R.drawable.dsw_sparkle, R.string.tool_generic, Activity.Other, BodyKind.InOut)
    }

    @StringRes private fun titleOf(name: String): Int = when (name) {
        "file_mkdir" -> R.string.tool_file_mkdir
        "file_move" -> R.string.tool_file_move
        "file_delete" -> R.string.tool_file_delete
        "archive_create" -> R.string.tool_archive_create
        "archive_extract" -> R.string.tool_archive_extract
        "app_list" -> R.string.tool_app_list
        "app_info" -> R.string.tool_app_info
        "app_guide" -> R.string.tool_app_guide
        "app_note" -> R.string.tool_app_note
        "app_launch" -> R.string.tool_app_launch
        "app_open_link" -> R.string.tool_app_open_link
        "app_send_intent" -> R.string.tool_app_send_intent
        "share_to_app" -> R.string.tool_share
        "sound_mode" -> R.string.tool_sound_mode
        "volume_set" -> R.string.tool_volume
        "dnd_set" -> R.string.tool_dnd
        "brightness_set" -> R.string.tool_brightness
        "flashlight" -> R.string.tool_flashlight
        "alarm_set" -> R.string.tool_alarm
        "alarm_show" -> R.string.tool_alarm_show
        "wifi_toggle" -> R.string.tool_wifi
        "bluetooth_toggle" -> R.string.tool_bluetooth
        "mobile_data_toggle" -> R.string.tool_mobile_data
        "setting_put" -> R.string.tool_setting_put
        "notification_list" -> R.string.tool_notif_list
        "notification_dismiss" -> R.string.tool_notif_dismiss
        "contacts_search" -> R.string.tool_contacts
        "sms_read" -> R.string.tool_sms_read
        "call_log_read" -> R.string.tool_call_log
        "calendar_read" -> R.string.tool_calendar_read
        "calendar_add" -> R.string.tool_calendar_add
        "calendar_new_event" -> R.string.tool_calendar_new_event
        "sms_send" -> R.string.tool_sms_send
        "browser_open" -> R.string.tool_browser_open
        "browser_read" -> R.string.tool_browser_read
        "browser_click" -> R.string.tool_browser_click
        "browser_type" -> R.string.tool_browser_type
        "browser_scroll" -> R.string.tool_browser_scroll
        "browser_back" -> R.string.tool_browser_back
        "clipboard_get" -> R.string.tool_clip_get
        "clipboard_set" -> R.string.tool_clip_set
        "rule_create" -> R.string.tool_rule_create
        "rule_list" -> R.string.tool_rule_list
        "rule_update" -> R.string.tool_rule_update
        "rule_delete" -> R.string.tool_rule_delete
        else -> R.string.tool_generic
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** One-line row summary built from the call arguments (path, command, query, target...). */
    fun summary(context: Context, t: Block.Tool): String? {
        val a = t.args ?: return t.arguments.take(80).ifBlank { null }
        return when (t.name) {
            "bash", "pwsh", "shell", "adb_shell" -> a.str("command")?.lineSequence()?.firstOrNull()
            "read", "write", "edit", "str_replace_editor", "read_image" -> a.str("path") ?: a.str("file_path")
            "grep", "glob" -> a.str("pattern") ?: a.str("query")
            "web_search" -> a.str("query")
            "web_fetch" -> a.str("url")
            "todo_write" -> null
            "load_tools" -> (a["names"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(", ")
            "screen_tap" -> a.str("text")?.let { "“$it”" } ?: a.str("node")?.let { "#$it" }
                ?: if (a.str("x") != null) "(${a.str("x")}, ${a.str("y")})" else null
            "screen_swipe" -> "(${a.str("x1")}, ${a.str("y1")}) → (${a.str("x2")}, ${a.str("y2")})"
            "screen_scroll", "browser_scroll" -> a.str("direction")
            "screen_type", "browser_type" -> a.str("text")?.let { "“${it.take(60)}”" }
            "screen_key" -> a.str("key")
            "screen_set_wheel" -> a.str("value")?.let { "→ “$it”" }
            "calendar_new_event" -> a.str("title")
            "app_launch", "app_info", "share_to_app" -> a.str("package")
            "app_open_link" -> a.str("uri")
            "hand_to_user" -> a.str("reason")
            "app_guide" -> a.str("app")
            "app_note" -> a.str("text")?.let { "“${it.take(60)}”" } ?: a.str("id") ?: a.str("app")
            "app_send_intent" -> a.str("action")
            "browser_open" -> a.str("url")
            "browser_click" -> a.str("id")?.let { "[$it]" }
            "sms_send" -> a.str("to")
            "volume_set", "brightness_set" -> a.str("percent")?.let { "$it%" } ?: a.str("stream")
            "wifi_toggle", "bluetooth_toggle", "mobile_data_toggle", "flashlight", "dnd_set" ->
                a.str("on")?.let { if (it == "true") context.getString(R.string.on) else context.getString(R.string.off) } ?: a.str("mode")
            "alarm_set" -> a.str("hour")?.let { h -> "%02d:%02d".format(h.toIntOrNull() ?: 0, a.str("minute")?.toIntOrNull() ?: 0) }
            "sound_mode" -> a.str("mode")
            "media_control" -> a.str("action")
            "contacts_search", "app_list", "file_search" -> a.str("query")
            "file_list", "file_stat", "file_read", "file_write", "file_mkdir", "file_delete" -> a.str("path") ?: a.str("paths")
            "file_move" -> listOfNotNull(a.str("path") ?: a["paths"]?.toString(), a.str("dest")).joinToString(" → ")
            "rule_create", "rule_update" -> (a["rule"] as? JsonObject)?.str("name") ?: a.str("id")
            "setting_put" -> listOfNotNull(a.str("namespace"), a.str("key")).joinToString(".")
            "clipboard_set" -> a.str("text")?.take(60)
            else -> a.entries.firstOrNull { it.value is JsonPrimitive }?.let { (it.value as JsonPrimitive).contentOrNull }?.take(80)
        }
    }

    /** Step-group title: the top two activities joined with 并, overflow gets 等. Counted activities show their count. */
    fun groupTitle(context: Context, tools: List<Block.Tool>, live: Boolean): String {
        val byActivity = LinkedHashMap<Activity, Int>()
        tools.forEach { byActivity.merge(info(it.name).activity, 1, Int::plus) }
        val ordered = byActivity.entries.sortedByDescending { it.value }
        fun label(e: Map.Entry<Activity, Int>) =
            if (live) context.getString(e.key.live)
            else if (e.key.counted) context.getString(e.key.done, e.value) else context.getString(e.key.done)
        val first = label(ordered[0])
        val title = if (ordered.size >= 2) context.getString(R.string.act_join, first, label(ordered[1])) else first
        return if (ordered.size > 2) context.getString(R.string.act_etc, title) else title
    }
}
