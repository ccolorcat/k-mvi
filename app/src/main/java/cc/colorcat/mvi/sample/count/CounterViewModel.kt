package cc.colorcat.mvi.sample.count

import android.util.Log
import androidx.lifecycle.ViewModel
import cc.colorcat.mvi.contract
import cc.colorcat.mvi.sample.count.CounterContract.Companion.COUNT_MAX
import cc.colorcat.mvi.sample.count.CounterContract.Companion.COUNT_MIN
import cc.colorcat.mvi.sample.count.CounterContract.Companion.randomCount
import cc.colorcat.mvi.sample.count.CounterContract.Event
import cc.colorcat.mvi.sample.count.CounterContract.Intent
import cc.colorcat.mvi.sample.count.CounterContract.PartialChange
import cc.colorcat.mvi.sample.count.CounterContract.State
import cc.colorcat.mvi.sample.util.randomDelay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * ViewModel demonstrating how an intent handler produces a [PartialChange] — and the discipline a
 * [PartialChange] itself should follow.
 *
 * ## Core principle: keep a [PartialChange] minimal
 *
 * A [PartialChange] runs synchronously inside the `scan` accumulator, so it should do exactly **one
 * thing**: migrate the snapshot (update state and/or attach an event). Keep it tiny and lightweight
 * — no business branching, no I/O, no heavy computation. Decide *what* the change should be in the
 * handler; let the change only *apply* that decision.
 *
 * Handlers are registered per intent type via the `register` DSL (the distributed style; compare
 * with [cc.colorcat.mvi.sample.login.LoginViewModel], which uses a single centralized
 * `defaultHandler`). The three handlers below are written to make the principle concrete:
 *
 * 1. [handleIncrement] — decides **inside** the change. The most concise form, acceptable here only
 *    because the branch is a trivial one-line guard.
 * 2. [handleDecrement] — also decides **inside** the change via `old.state`, the freshest
 *    accumulated value at `apply()` time. Its KDoc explains why reading [stateFlow.value] in
 *    the handler body is unsafe under CONCURRENT/HYBRID strategies and how `old.state` avoids it.
 * 3. [handleReset] — returns an asynchronous [Flow] of changes (loading → success/error → done),
 *    for async work, multiple emissions, or cancellable operations.
 *
 * Author: ccolorcat
 * Date: 2025-11-14
 * GitHub: https://github.com/ccolorcat
 */
class CounterViewModel : ViewModel() {
    /**
     * MVI contract initialized with the default [State].
     *
     * Each handler is registered against its concrete intent type. `register` infers that type
     * from the handler reference, so a plain method reference is enough; the synchronous handlers
     * return a [PartialChange] and the async one returns `Flow<PartialChange>`.
     */
    private val contract by contract(
        initState = State(),
    ) {
        register(::handleIncrement) // synchronous, single PartialChange
        register(::handleDecrement) // synchronous, single PartialChange
        register(::handleReset)     // asynchronous, Flow<PartialChange>
    }

    /**
     * StateFlow exposing the current UI state.
     * Observe this in the UI layer to render the current counter value.
     */
    val stateFlow: StateFlow<State> = contract.stateFlow

    /**
     * Flow of one-time events to be consumed by the UI.
     * Events like toast messages should be collected and handled once.
     */
    val eventFlow: Flow<Event> = contract.eventFlow

    /**
     * Dispatch user intents to the contract for processing.
     *
     * The dispatch is non-blocking — the intent is enqueued via the contract's internal channel.
     * Returns a [cc.colorcat.mvi.DispatchResult] indicating whether the intent was
     * accepted, queued at capacity, or rejected because the scope is inactive.
     *
     * @param intent The user intent to process
     * @return Result indicating submission status
     * @see cc.colorcat.mvi.DispatchResult
     */
    fun dispatch(intent: Intent) = contract.dispatch(intent)

    /**
     * Synchronous handler: increment the counter, or warn when it is already at [COUNT_MAX].
     *
     * This handler keeps the decision **inside** the change: a single [PartialChange] branches on
     * `old.state` (the snapshot passed in at the exact moment the change is applied by the `scan`
     * accumulator). It is the most concise form and is acceptable here precisely because the branch
     * is a trivial one-line guard — the lambda still does nothing but pick one snapshot update.
     *
     * Even so, a [PartialChange] must never grow beyond this: as soon as a decision needs more than
     * a tiny guard, push it out into the handler so the change stays minimal — see [handleDecrement],
     * which is deliberately written the other way to illustrate the preferred shape. Heavy or async
     * work never belongs inside `apply`; use a [Flow]-returning handler (see [handleReset]) instead.
     *
     * @param intent The increment intent (unused; its type selects this handler)
     * @return A change that either increments the count or emits a "limit reached" toast
     */
    private fun handleIncrement(intent: Intent.Increment) = PartialChange { old ->
        // The branch is a trivial one-liner, so deciding inside the change is acceptable here.
        if (old.state.count == COUNT_MAX) {
            old.withEvent(Event.ShowToast("Already reached $COUNT_MAX"))
        } else {
            old.updateState { copy(count = count + 1) }
        }
    }

    /**
     * Synchronous handler: decrement the counter, or warn when it is already at [COUNT_MIN].
     *
     * The boundary check happens **inside** the [PartialChange] lambda, reading `old.state.count`
     * — the snapshot accumulated up to the exact moment `apply()` is called by `scan`. This value
     * is always fresh: every preceding [PartialChange] has already been folded in before this
     * lambda runs, regardless of which [cc.colorcat.mvi.HandleStrategy] is active.
     *
     * Contrast this with reading [stateFlow]`.value` in the handler body (the previous form of
     * this handler): that value is captured at *handler-invocation* time. Under CONCURRENT or
     * HYBRID strategies another intent's change may be applied between invocation and `apply()`,
     * making the handler-body read stale and the boundary decision incorrect. `old.state` inside
     * the lambda is the strategy-agnostic, always-correct alternative.
     *
     * @param intent The decrement intent (unused; its type selects this handler)
     * @return A change that either decrements the count or emits a "limit reached" toast
     */
    private fun handleDecrement(intent: Intent.Decrement) = PartialChange { old ->
        if (old.state.count == COUNT_MIN) {
            old.withEvent(Event.ShowToast("Already reached $COUNT_MIN"))
        } else {
            old.updateState { copy(count = count - 1) }
        }
    }

    /**
     * Asynchronous handler: reset to a fresh random count/target, with a loading indicator.
     *
     * Returning a [Flow] lets one intent drive several changes over time, which is the right tool
     * for async work, cancellable operations, or any multi-step (loading → success/error → done)
     * transition. The handler stays lightweight — all suspending work happens inside `flow { }` so
     * the configured handle strategy controls the intent's lifecycle.
     *
     * Lifecycle modeled here:
     * 1. Emit a loading change (`showLoading = true`).
     * 2. Simulate async work via [randomDelay].
     * 3. Fail ~10% of the time to exercise the error path.
     * 4. On success, emit the new count/target together with a toast (via `updateWith`).
     * 5. On failure, emit a toast-only change.
     * 6. Always clear the loading flag in `finally`.
     *
     * @param intent The reset intent (unused; its type selects this handler)
     * @return A flow of changes describing the full reset lifecycle
     */
    private fun handleReset(intent: Intent.Reset): Flow<PartialChange> = flow {
        try {
            emit(PartialChange { it.updateState { copy(showLoading = true) } })

            randomDelay()

            // Simulate an occasional failure (~10%) to demonstrate the error path.
            if (Random.nextInt(100) < 10) {
                throw RuntimeException("Simulated reset failure")
            }

            val count = randomCount()
            val target = randomCount()
            emit(
                PartialChange {
                    it.updateWith(Event.ShowToast("Reset Successfully")) {
                        copy(count = count, targetCount = target)
                    }
                },
            )
        } catch (e: Exception) {
            Log.w("k-mvi", "handleReset failed", e)
            emit(PartialChange { it.withEvent(Event.ShowToast("Reset Failure")) })
        } finally {
            emit(PartialChange { it.updateState { copy(showLoading = false) } })
        }
    }
}
