package cc.colorcat.mvi

/**
 * Result of a non-blocking [ReactiveContract.dispatch] call.
 *
 * Dispatch only reports how the dispatch request interacted with the contract's entry queue. It
 * does not indicate whether the intent handler has started, completed, or produced a state change.
 *
 * ## Queue Policy Semantics
 *
 * [Submitted] is not a guarantee that the exact intent will eventually be processed:
 * - With bounded capacity or [kotlinx.coroutines.channels.Channel.BUFFERED] and
 *   [kotlinx.coroutines.channels.BufferOverflow.SUSPEND], the intent entered the queue or was
 *   received by the pipeline. If the queue is full, dispatch returns [Full] instead.
 * - With [kotlinx.coroutines.channels.Channel.RENDEZVOUS] and
 *   [kotlinx.coroutines.channels.BufferOverflow.SUSPEND], a receiver was ready and took the intent.
 *   Otherwise dispatch returns [Full].
 * - With [kotlinx.coroutines.channels.Channel.UNLIMITED], the intent usually entered an unbounded
 *   queue. It normally does not return [Full], but the queue can grow memory if producers outrun
 *   consumers.
 * - With [kotlinx.coroutines.channels.Channel.CONFLATED], the latest intent was submitted to the
 *   conflated queue. Any older pending intent may be replaced, and this submitted intent may itself
 *   be replaced by a later dispatch before processing starts.
 * - With [kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST], the queue policy accepted this
 *   dispatch. If the queue was full, the oldest pending intent was dropped and will not be processed.
 * - With [kotlinx.coroutines.channels.BufferOverflow.DROP_LATEST], the queue policy handled this
 *   dispatch. If the queue was full, this latest intent may have been dropped and may never be
 *   processed.
 *
 */
sealed class DispatchResult {
    /**
     * The dispatch request was submitted to the entry queue mechanism.
     *
     * [Submitted] only describes queue submission. It does not mean the handler has started,
     * completed, changed state, or emitted an event. See [DispatchResult] for queue-policy details.
     */
    data object Submitted : DispatchResult()

    /**
     * The contract is no longer available, so the intent was not accepted and never will be.
     *
     * This is a **terminal** result: the contract's coroutine scope has completed, which also
     * closes the entry queue. It covers both ways that completion surfaces at dispatch time —
     * the scope already being inactive, and the entry queue already being closed — because they
     * share the same root cause and leave the caller with the same (in)action: do not retry.
     *
     * Contrast with [Full], which is **transient**: the contract is still alive and may accept
     * the intent if dispatched again later.
     */
    data object Unavailable : DispatchResult()

    /**
     * The dispatch entry queue is full, so the intent was not accepted.
     *
     * This is **transient back-pressure** on a live contract: the queue is momentarily at
     * capacity. Dispatching again later may succeed. Contrast with [Unavailable], which is
     * terminal.
     */
    data object Full : DispatchResult()
}
