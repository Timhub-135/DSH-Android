package io.github.timhub135.dsh.core.capability

import dagger.Lazy
import io.github.timhub135.dsh.core.platform.SHIZUKU_WHAT
import io.github.timhub135.dsh.core.platform.ShellAccess
import io.github.timhub135.dsh.core.platform.ShellState
import io.github.timhub135.dsh.core.platform.ShizukuShell
import io.github.timhub135.dsh.core.session.SessionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import javax.inject.Inject
import javax.inject.Singleton

/** How a tool depends on the shell user (Shizuku). */
enum class ShellUse {
    None,
    /** Uses Shizuku when ready, otherwise a non-shell path (system panel, intent, accessibility). */
    Fallback,
    /** Only works through Shizuku. */
    Required,
}

/**
 * Just-in-time Shizuku for tool calls. Reuses the permission-card wait (SessionStore.requestPermission):
 * the composer slot renders the Shizuku card for [SHIZUKU_WHAT], and the call resumes by itself the
 * moment [ShellAccess] reports Ready (binder received + permission granted) — no 继续 tap needed.
 */
@Singleton
class ShellGate @Inject constructor(val shell: ShizukuShell, private val sessions: Lazy<SessionStore>) {
    val access: ShellAccess get() = shell.access

    /**
     * True when the shell can be used now (possibly after the user set Shizuku up from the card).
     * [optional] callers have a fallback, so they only ask a user who already has Shizuku installed
     * (e.g. it stopped after a reboot); someone who never installed it is not interrupted.
     * A 不用了 is remembered for the session: later calls go straight to their fallback.
     */
    suspend fun acquire(call: CallContext, toolName: String, optional: Boolean): Boolean {
        val s = access.refresh()
        if (s.ready) return true
        if (call.unattended || call.sessionId.isEmpty() || access.isDeclined(call.sessionId)) return false
        if (optional && (s == ShellState.NotInstalled || s is ShellState.Unsupported)) return false
        val answered = call.waitFor("set up Shizuku") { coroutineScope {
            val ready = async { access.state.first { it.ready }; true }
            val asked = async { sessions.get().requestPermission(call.sessionId, toolName, SHIZUKU_WHAT) }
            select { ready.onAwait { it }; asked.onAwait { it } }.also { ready.cancel(); asked.cancel() }
        } }
        if (access.refresh().ready) return true
        if (!answered) access.decline(call.sessionId)
        return false
    }

    /** Model-facing result for a shell-only step that cannot run. */
    fun unavailable(call: CallContext): ToolResult {
        val reason = access.state.value.reason
        return ToolResult.error("shizuku_unavailable", when {
            call.unattended -> "This step needs Shizuku (shell access), but $reason. Automations cannot ask the user to set it up; skip this step or report it."
            else -> "This needs Shizuku (shell access), but $reason and the user chose not to set it up now. Do not call Shizuku-only tools again " +
                "in this session; do it another way (system intents, settings panels, screen tools) or tell the user it can be enabled in DSH → Settings → Phone."
        })
    }

    companion object {
        /** Appended to model-facing descriptions. Never includes live state (see CapabilityHub.listed). */
        fun staticNote(use: ShellUse, fallback: String?): String = when (use) {
            ShellUse.None -> ""
            ShellUse.Fallback -> " [Uses Shizuku (shell access) when it is ready; otherwise it will ${fallback ?: "use a non-shell fallback"}. The result says which path ran.]"
            ShellUse.Required -> " [Needs Shizuku (shell access). If it is not ready, the call shows the user a Shizuku setup card; if it returns shizuku_unavailable, don't call Shizuku-only tools again this session.]"
        }
    }
}
