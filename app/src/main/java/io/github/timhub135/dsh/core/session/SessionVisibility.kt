package io.github.timhub135.dsh.core.session

import io.github.timhub135.dsh.core.data.SessionEntity
import io.github.timhub135.dsh.core.model.ruleIdOfSession

/**
 * Which sessions belong to the user (#43). The engine reports every dsh session it runs, also the
 * ones plugins start for themselves (a heartbeat, a QQ bot: cwd = their own data dir). dsh's session
 * header has no marker a plugin could set for that (`origin` only ever says "subagent"), so the rule
 * is ownership: the app writes a `sessions` row only for what it starts itself — a prompt from the
 * composer or an automation (SessionStore.prompt) and the subagents of those (`subagent.started`).
 * A session without a row is a plugin's background session and is never shown or announced.
 *
 * One rule for the session list, the notifications and the pages a tap can open, so a session never
 * shows up in one of them (e.g. as 「新会话」 after a notification tap) while being absent from the list.
 */
object SessionVisibility {
    /** In the session list (drawer, quick panel): the user's own conversations. */
    fun listed(row: SessionEntity): Boolean = row.parentId == null && row.ruleId == null

    /**
     * Has a page the user can reach and may be announced: a listed conversation, or an automation run
     * (自动化 → rule → its runs; rule steps without an agent have no row but are still the user's).
     * Subagents speak through their root conversation; plugin sessions (no row) never.
     */
    fun userFacing(sessionId: String, row: SessionEntity?): Boolean =
        if (row != null) row.parentId == null else ruleIdOfSession(sessionId) != null
}
