package cc.colorcat.mvi.sample.count

import androidx.lifecycle.ViewModel
import cc.colorcat.mvi.contract
import cc.colorcat.mvi.register
import cc.colorcat.mvi.sample.count.CounterContract.Companion.COUNT_MAX
import cc.colorcat.mvi.sample.count.CounterContract.Companion.COUNT_MIN
import cc.colorcat.mvi.sample.count.CounterContract.Companion.randomCount
import cc.colorcat.mvi.sample.count.CounterContract.Event
import cc.colorcat.mvi.sample.count.CounterContract.Intent
import cc.colorcat.mvi.sample.count.CounterContract.PartialChange
import cc.colorcat.mvi.sample.count.CounterContract.State
import cc.colorcat.mvi.sample.randomDelay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * ViewModel demonstrating three different approaches to return PartialChange in MVI pattern.
 *
 * This sample showcases how to use the `register` function to handle intents with different
 * return patterns:
 * 1. **Inline conditional** ([handleIncrement]): Single PartialChange with internal branching
 * 2. **Early return branching** ([handleDecrement]): Multiple return paths with separate PartialChange instances
 * 3. **Flow-based async** ([handleReset]): Flow<PartialChange> for complex/async operations
 *
 * Each approach is suitable for different scenarios:
 * - Use inline conditional for simple, synchronous logic with minimal branching
 * - Use early return for clearer separation of different execution paths
 * - Use Flow for async operations, multiple emissions, or complex state transformations
 *
 * Author: ccolorcat
 * Date: 2025-11-14
 * GitHub: https://github.com/ccolorcat
 */
class CounterViewModel : ViewModel() {
    /**
     * MVI contract instance initialized with default state.
     * The contract builder registers intent handlers using the `register` function.
     * Each handler can return either PartialChange or Flow<PartialChange>.
     */
    private val contract by contract(
        initState = State()
    ) {
        register(::handleIncrement)  // Pattern 1: Inline conditional
        register(::handleDecrement)  // Pattern 2: Early return branching
        register(::handleReset)      // Pattern 3: Flow-based async
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
     * The contract will route the intent to the appropriate registered handler.
     *
     * @param intent The user intent to process
     */
    fun dispatch(intent: Intent) = contract.dispatch(intent)

    /**
     * Pattern 1: Inline conditional - Single PartialChange with internal branching.
     *
     * This approach returns a single PartialChange that contains all branching logic inside.
     * The PartialChange lambda receives a snapshot of the current state via `it.state` and
     * decides what to do based on that state.
     *
     * **Advantages:**
     * - Compact and readable for simple logic
     * - All logic is contained in one place
     * - Uses state snapshot from PartialChange context (thread-safe)
     *
     * **Best for:**
     * - Simple synchronous operations with 2-3 branches
     * - When all branches are similar in complexity
     *
     * **Important Note:**
     * While this pattern appears more concise than [handleDecrement], it is **NOT recommended**
     * to put complex logic and decision-making inside the PartialChange lambda, especially when
     * the logic becomes complicated. PartialChange should focus on **updating the snapshot**
     * (state and event) and remain simple and clear. Complex business logic should be handled
     * outside the PartialChange, with the PartialChange itself only responsible for applying
     * the determined state transformation. See [handleDecrement] for the preferred approach
     * when dealing with conditional logic.
     *
     * @param intent The increment intent (unused but required by register signature)
     * @return A PartialChange that either increments count or emits a toast event
     */
    private fun handleIncrement(intent: Intent.Increment): PartialChange {
        return PartialChange {
            if (it.state.count == COUNT_MAX) {
                it.withEvent(Event.ShowToast("Already reached $COUNT_MAX"))
            } else {
                it.updateState { copy(count = count + 1) }
            }
        }
    }

    /**
     * Pattern 2: Early return branching - Multiple return paths with separate PartialChange instances.
     *
     * This approach checks conditions early and returns different PartialChange instances
     * for different execution paths. Each branch returns its own PartialChange, making
     * the separation of concerns more explicit.
     *
     * **Advantages:**
     * - Clear separation between different execution paths
     * - Easier to understand complex branching logic
     * - Each branch can be tested independently
     * - Better for guard clauses and early exits
     *
     * **Note:** This example reads `stateFlow.value` to demonstrate the pattern, but
     * for production code, prefer using `it.state` inside the PartialChange (like Pattern 1)
     * to avoid potential race conditions.
     *
     * **Best for:**
     * - Complex conditional logic with distinct execution paths
     * - When branches have significantly different logic
     * - Guard clause patterns
     *
     * @param intent The decrement intent (unused but required by register signature)
     * @return A PartialChange that either decrements count or emits a toast event
     */
    private fun handleDecrement(intent: Intent.Decrement): PartialChange {
        return if (stateFlow.value.count == COUNT_MIN) {
            PartialChange {
                it.withEvent(Event.ShowToast("Already reached $COUNT_MIN"))
            }
        } else {
            PartialChange {
                it.updateState { copy(count = count - 1) }
            }
        }
    }

    /**
     * Pattern 3: Flow-based async - Returns Flow<PartialChange> for complex/async operations.
     *
     * This approach returns a Flow of PartialChanges, which is ideal for:
     * - Asynchronous operations (network calls, database queries)
     * - Multiple state emissions over time
     * - Operations that can be cancelled
     * - Complex state transformations with intermediate states
     *
     * **Advantages:**
     * - Supports reactive/async operations naturally
     * - Can emit multiple PartialChanges over time (loading, success, error)
     * - Integrates seamlessly with other Flow-based APIs
     * - Supports cancellation and backpressure
     *
     * **Implementation Pattern:**
     * This example demonstrates a complete async operation lifecycle:
     * 1. **Loading phase**: Emit loading state (`showLoading = true`)
     * 2. **Async work**: Perform simulated async operation with [randomDelay]
     * 3. **Error simulation**: 10% chance of failure to demonstrate error handling
     * 4. **Success phase**: Generate random count/target and emit success state with toast
     * 5. **Error phase**: Catch exceptions and emit failure event
     * 6. **Completion**: Always hide loading state in finally block
     *
     * **Flow creation options:**
     * - `flow { emit(...) }`: Build custom Flow for complex scenarios (used here)
     * - `asSingleFlow()`: Convert single PartialChange to Flow
     * - `flowOf(...)`: Create Flow from multiple PartialChanges
     * - Use suspend functions with `flow { ... }` for async work
     *
     * **Best for:**
     * - Async operations (API calls, DB queries, file I/O)
     * - Multi-step state transformations (loading → success/error → complete)
     * - Operations that need cancellation support
     * - Integrating with existing Flow-based data sources
     * - Error handling with try-catch-finally pattern
     *
     * @param intent The reset intent (unused but required by register signature)
     * @return A Flow emitting multiple PartialChanges representing the complete operation lifecycle
     */
    private fun handleReset(intent: Intent.Reset): Flow<PartialChange> = flow {
        try {
            emit(PartialChange { it.updateState { copy(showLoading = true) } })

            randomDelay()

            if (Random.nextInt(0, 101) > 90) {
                throw RuntimeException("test exception")
            }

            val count = randomCount()
            val target = randomCount()
            emit(PartialChange {
                it.updateWith(Event.ShowToast("Reset Successfully")) {
                    copy(count = count, targetCount = target)
                }
            })
        } catch (e: Exception) {
            emit(PartialChange { it.withEvent(Event.ShowToast("Reset Failure")) })
        } finally {
            emit(PartialChange { it.updateState { copy(showLoading = false) } })
        }
    }
}
