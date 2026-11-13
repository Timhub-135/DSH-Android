package io.github.timhub135.dsh.core.extensions

import android.content.Context
import io.github.timhub135.dsh.R

/** User-facing copy for extension errors and results (PLUGINS-DESIGN §15). */
object ExtText {
    /** §15 code → the app's generic sentence (used when the engine sent no message of its own). */
    fun codeRes(code: String): Int? = when (code) {
        "native-addon" -> R.string.err_native_addon
        "prebuilt-binary" -> R.string.err_prebuilt_binary
        "needs-desktop-browser" -> R.string.err_needs_desktop_browser
        "needs-web-services" -> R.string.err_needs_web_services
        "not-bundle" -> R.string.err_not_bundle
        "incompatible-version" -> R.string.err_incompatible_version
        "not-found" -> R.string.err_not_found
        "network" -> R.string.err_network
        "integrity" -> R.string.err_integrity
        "no-space" -> R.string.err_no_space
        "needs-git" -> R.string.err_needs_git
        "needs-build" -> R.string.err_needs_build
        "github-unreachable" -> R.string.err_github_unreachable
        "index-unreachable" -> R.string.err_index_unreachable
        "index-invalid" -> R.string.err_index_invalid
        "bad-archive" -> R.string.err_bad_archive
        "already-installed" -> R.string.err_already_installed
        "installer-broken" -> R.string.err_installer_broken
        "activation-failed", ExtStatus.FAILED -> R.string.err_activation_failed
        "waiting-service", ExtStatus.WAITING -> R.string.err_waiting_service
        "crash-suspended" -> R.string.err_crash_suspended
        "pnpm-failed" -> R.string.err_pnpm_failed
        "skill-invalid" -> R.string.err_skill_invalid
        "mcp-sse-only" -> R.string.err_mcp_sse_only
        "mcp-runtime" -> R.string.err_mcp_runtime_generic
        "mcp-local-path" -> R.string.err_mcp_local_path
        "mcp-auth" -> R.string.err_mcp_auth
        "bad-input" -> R.string.ext_bad_input
        "mcp-json" -> R.string.ext_mcp_json_redirect
        ExtCodes.UNSUPPORTED_ENGINE -> R.string.err_unsupported_engine
        ExtCodes.ENGINE_UNAVAILABLE -> R.string.err_engine_unavailable
        ExtCodes.BAD_RESULT -> R.string.err_bad_result
        ExtCodes.BAD_ENTRY -> R.string.err_bad_entry
        else -> null
    }

    /**
     * One sentence for [e]: the engine's own message (it names the package, versions, host) when it
     * sent one; the app's copy for the code otherwise. App-side codes always use the app's copy.
     */
    fun error(context: Context, e: UserError): String {
        val appOwned = e.code == "bad-input" || e.code == "mcp-json" || e.code == ExtCodes.UNSUPPORTED_ENGINE
        if (!appOwned && e.message.isNotBlank()) return e.message
        if (e.code == ExtCodes.ENGINE_UNAVAILABLE && e.message.isNotBlank()) return context.getString(R.string.err_generic, e.message)
        if ((e.code == ExtCodes.BAD_RESULT || e.code == ExtCodes.BAD_ENTRY) && !e.detail.isNullOrBlank()) return context.getString(codeRes(e.code)!!) + "\n" + e.detail
        codeRes(e.code)?.let { return context.getString(it) }
        return context.getString(R.string.err_generic, e.message.ifBlank { e.code })
    }

    /** "已启用 X：新增工具 a、b，技能 c" / "X 启动失败：…" / "已停用 X". */
    fun activation(context: Context, a: Activation): String {
        if (a.disabled) return context.getString(R.string.ext_disabled_done, a.title)
        if (!a.ok) return context.getString(R.string.ext_activation_failed, a.title, a.error?.let { error(context, it) }.orEmpty())
        val sep = if (context.resources.configuration.locales[0].language == "zh") "、" else ", "
        val parts = listOfNotNull(
            a.tools.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.ext_new_tools, it.take(5).joinToString(sep)) },
            a.skills.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.ext_new_skills, it.take(5).joinToString(sep)) },
            a.commands.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.ext_new_commands, it.take(5).joinToString(sep)) },
        )
        return if (parts.isEmpty()) context.getString(R.string.ext_activated_plain, a.title)
        else context.getString(R.string.ext_activated, a.title, parts.joinToString(if (sep == "、") "，" else "; "))
    }
}
