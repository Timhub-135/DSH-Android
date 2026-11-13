package io.github.timhub135.dsh.core.session

import java.util.concurrent.ConcurrentHashMap

/**
 * Maps dsh `session.event` seqs to the seqs they are logged under, per session and engine
 * generation.
 *
 * dsh notifies an event before it is durable, so when `:engine` dies the tail it already sent may
 * never reach its journal. The next generation resumes from the journal and hands out those seqs
 * again for new events — host repro (js/test/host-ac8-resume.mjs): killed after notifying seq 11
 * with the journal ending at 7, the restarted engine sent the continuation's `turn/start` as seq 9.
 * Used as identities, those seqs made the app drop the continuation turn as duplicates of the dead
 * generation's events. So each generation's first event for a session fixes an offset that places
 * it after everything already logged; within a generation seqs map 1:1 (offset 0 in the normal
 * case, where a new generation continues past the log).
 */
internal class LogSeqs {
    /** sessionId -> (engine generation, offset added to that generation's seqs). */
    private val offsets = ConcurrentHashMap<String, Pair<Int, Long>>()

    /** The log seq for [seq], or null when [gen] has not been [start]ed for this session. */
    fun mapped(sessionId: String, gen: Int, seq: Long): Long? =
        offsets[sessionId]?.takeIf { it.first == gen }?.let { seq + it.second }

    /** First event of [gen] for this session; [lastLogged] is the newest log seq (-1 if none). */
    fun start(sessionId: String, gen: Int, seq: Long, lastLogged: Long): Long {
        val offset = if (seq > lastLogged) 0L else lastLogged + 1 - seq
        offsets[sessionId] = gen to offset
        return seq + offset
    }

    /** The session was deleted. */
    fun forget(sessionId: String) { offsets.remove(sessionId) }
}
