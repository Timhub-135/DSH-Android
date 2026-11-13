package io.github.timhub135.dsh.service

import io.github.timhub135.dsh.core.session.RowStatus

/** Where a tap on the floating ball, its status capsule or an ongoing notification goes (#39). */
sealed interface TapTarget {
    /** The conversation in the app (MainActivity → home showing it, home as the root of the stack). */
    data class Conversation(val sessionId: String) : TapTarget
    /** The quick panel: on [sessionId] (a run started there), or its usual summon (recent panel session / new) when null. */
    data class Panel(val sessionId: String? = null) : TapTarget
}

object TapTargets {
    /**
     * A plain ball tap: the conversation that waits for the user, else the running one (the one the
     * capsule follows, [focused]), so a tap during a background run never starts a new conversation.
     * A run started in the quick panel ([panelSession]) reopens there; nothing running = the panel.
     */
    fun ball(status: Map<String, RowStatus>, focused: String?, panelSession: String?): TapTarget {
        val id = AgentStatus.focus(status, focused) ?: return TapTarget.Panel()
        return of(id, panelSession)
    }

    /** The capsule speaks for [sessionId] (running, waiting, or just ended): a tap opens exactly that one. */
    fun capsule(sessionId: String, panelSession: String?): TapTarget = of(sessionId, panelSession)

    /** The ongoing "engine running" / floating-ball notification: the conversation that needs the user or runs; null = just the app. */
    fun running(status: Map<String, RowStatus>): String? = AgentStatus.focus(status, null)

    private fun of(id: String, panelSession: String?): TapTarget = if (id == panelSession) TapTarget.Panel(id) else TapTarget.Conversation(id)
}
