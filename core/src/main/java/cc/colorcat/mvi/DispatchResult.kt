package cc.colorcat.mvi

/**
 * Result of a non-blocking [ReactiveContract.dispatch] call.
 *
 * Dispatch only reports how the dispatch request interacted with the contract's entry queue. It
 * does not indicate whether the intent handler has started, completed, or produced a state change.
 *
 * Author: ccolorcat
 * Date: 2026-06-15
 * GitHub: https://github.com/ccolorcat
 */
sealed class DispatchResult {
    /**
     * The dispatch request was submitted to the entry queue mechanism.
     *
     * This is not a guarantee that this exact intent will eventually be processed:
     * - With bounded capacity or [kotlinx.coroutines.channels.Channel.BUFFERED] and
     *   [kotlinx.coroutines.channels.BufferOverflow.SUSPEND], [Submitted] means the intent entered
     *   the queue or was received by the pipeline. If the queue is full, dispatch returns [Full]
     *   instead.
     * - With [kotlinx.coroutines.channels.Channel.RENDEZVOUS] and
     *   [kotlinx.coroutines.channels.BufferOverflow.SUSPEND], [Submitted] means a receiver was
     *   ready and took the intent. Otherwise dispatch returns [Full].
     * - With [kotlinx.coroutines.channels.Channel.UNLIMITED], [Submitted] usually means the intent
     *   entered an unbounded queue. It normally does not return [Full], but the queue can grow
     *   memory if producers outrun consumers.
     * - With [kotlinx.coroutines.channels.Channel.CONFLATED], [Submitted] means the latest intent
     *   was submitted to the conflated queue. Any older pending intent may be replaced, and this
     *   submitted intent may itself be replaced by a later dispatch before processing starts.
     * - With [kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST], [Submitted] means the queue
     *   policy accepted this dispatch. If the queue was full, the oldest pending intent was dropped
     *   and will not be processed.
     * - With [kotlinx.coroutines.channels.BufferOverflow.DROP_LATEST], [Submitted] means the queue
     *   policy handled this dispatch. If the queue was full, this latest intent may have been
     *   dropped and may never be processed.
     *
     * In all modes, [Submitted] only describes queue submission. It does not mean the handler has
     * started, completed, changed state, or emitted an event.
     */
    data object Submitted : DispatchResult()

    /**
     * The contract's coroutine scope is inactive, so the intent was not accepted.
     */
    data object Inactive : DispatchResult()

    /**
     * The dispatch entry queue is full, so the intent was not accepted.
     */
    data object Full : DispatchResult()

    /**
     * The dispatch entry queue is closed, so the intent was not accepted.
     */
    data object Closed : DispatchResult()
}
