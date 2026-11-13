package io.github.timhub135.dsh.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Why an in-flight engine request was cancelled: its engine generation ended (stop, restart, crash, idle shutdown). */
class EngineCallCancelled(reason: String) : CancellationException(reason)

/**
 * Cancellation contract for requests the engine sends to the app: dsh server requests on the SDK
 * channel (approvals, user questions) and `capability/invoke` on the bridge.
 *
 * 1. Every incoming request runs in its own coroutine, a child of its [JsonRpcPeer]'s handler Job,
 *    carrying a [Call] element (so code deep in a tool can tell it serves the engine).
 * 2. When the request's engine generation ends, EngineManager's teardown cancels all of them with
 *    [EngineCallCancelled] (the same happens when the channel reaches EOF). This covers
 *    SessionStore.stop / restartEngine, crashes, failed starts and the idle shutdown. A cancelled
 *    request is never answered, and a late or failed write to a dead channel is dropped, not thrown.
 * 3. Handlers (CapabilityHub, tools, SessionStore cards) must therefore:
 *    - wait only in cancellable suspensions, and bound blocking work (runInterruptible / timeouts),
 *      so a cancelled call stops instead of acting later (e.g. after the phone is unlocked);
 *    - never swallow [CancellationException] (rethrow it from any catch-all);
 *    - put UI/state cleanup (cards, waiting-for-unlock flags) in `finally`, wrapped in
 *      `withContext(NonCancellable)` when the cleanup suspends;
 *    - check [ensureCallActive] before starting an irreversible side effect after a long wait.
 * 4. CapabilityHub.detach() is still called on every teardown, for state that is not owned by a
 *    handler coroutine.
 */
object EngineCalls {
    class Call(val channel: String, val method: String) : AbstractCoroutineContextElement(Call) {
        companion object Key : CoroutineContext.Key<Call>
    }

    /** The engine request the current coroutine serves, or null (e.g. a rule's own steps). */
    suspend fun current(): Call? = currentCoroutineContext()[Call]

    /** Throws if the current call was cancelled (its engine generation ended). */
    suspend fun ensureCallActive() = currentCoroutineContext().ensureActive()
}
