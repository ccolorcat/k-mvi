package cc.colorcat.mvi.sample.count

import cc.colorcat.mvi.Mvi
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Contract defining the MVI architecture components for a simple counter feature.
 *
 * This demonstrates the typical structure of an MVI contract with:
 * - [State]: Represents the UI state
 * - [Event]: One-time events (side effects) to be consumed by the UI
 * - [Intent]: User actions/intentions
 * - [PartialChange]: State transformation functions
 *
 * Author: ccolorcat
 * Date: 2025-11-14
 * GitHub: https://github.com/ccolorcat
 */

sealed interface CounterContract {
    companion object {
        const val COUNT_MAX = 100
        const val COUNT_MIN = 0

        /**
         * Generates a random count value within the valid range [COUNT_MIN, COUNT_MAX] (inclusive).
         */
        fun randomCount(): Int = Random.nextInt(COUNT_MIN, COUNT_MAX + 1)
    }

    /**
     * Represents the current state of the counter.
     *
     * Constructor properties hold the raw data; the rest of the class exposes **computed**
     * properties ([countText], [countInfo], [alpha], [alpha255]) derived from that data.
     *
     * **Design Note:**
     * Keeping presentation values (e.g. [countText], [alpha]) as computed properties separates
     * business data (count as `Int`) from how it is rendered. This allows formatting changes
     * without touching the ViewModel, enables type-safe binding (`String` → `TextView.setText`),
     * and keeps the presentation logic reusable across UI components.
     *
     * @property count The current counter value
     * @property targetCount The randomly chosen target the user is aiming for
     * @property showLoading Whether an async operation (e.g. reset) is in progress
     */
    data class State(
        val count: Int = 0,
        val targetCount: Int = randomCount(),
        val showLoading: Boolean = false,
    ) : Mvi.State {

        /**
         * Calculates the alpha value based on proximity to the target number.
         *
         * The alpha value represents how close the current count is to the target:
         * - When count == targetCount, alpha = 1.0 (fully opaque)
         * - As the distance increases, alpha decreases linearly
         * - Minimum alpha is 0.0 (fully transparent)
         *
         * Formula: alpha = 1 - (distance / maxPossibleDistance)
         *
         * @return Alpha value between 0.0 and 1.0
         */
        val alpha: Float
            get() {
                val distance = abs(count - targetCount)
                val maxDistance = COUNT_MAX - COUNT_MIN
                return (1f - distance.toFloat() / maxDistance).coerceAtLeast(0f)
            }

        val alpha255: Int
            get() = (alpha * 255).roundToInt()

        val countText: String
            get() = count.toString()

        val countInfo: String
            get() = "Target: $targetCount, Range: $COUNT_MIN ~ $COUNT_MAX"
    }

    /**
     * One-time, fire-and-forget side effects emitted to the UI layer.
     *
     * The counter feature defines a single event, [ShowToast], used to surface
     * boundary feedback (e.g. when the value hits [State.COUNT_MIN]/[State.COUNT_MAX]).
     * Events are delivered via the contract's `eventFlow` and consumed exactly once,
     * unlike [State] which is observable and conflated.
     */
    sealed interface Event : Mvi.Event {
        /**
         * Event to display a toast message to the user.
         *
         * @property message The message to display
         */
        data class ShowToast(val message: CharSequence) : Event
    }

    /**
     * User intents/actions for the counter feature.
     * Marked as [Mvi.Intent.Sequential] to ensure intents are processed sequentially.
     */
    sealed interface Intent : Mvi.Intent.Sequential {
        /**
         * Intent to increment the counter by 1.
         */
        object Increment : Intent

        /**
         * Intent to decrement the counter by 1.
         */
        object Decrement : Intent

        /**
         * Intent to reset the counter to its initial value.
         */
        object Reset : Intent
    }

    /**
     * State transformations for the counter feature.
     *
     * This contract uses the **inline lambda** pattern: [PartialChange] instances are
     * created directly in the ViewModel handlers via `PartialChange { old -> ... }`,
     * rather than sealed subtypes. Compare with [cc.colorcat.mvi.sample.login.LoginContract.PartialChange]
     * for the centralized sealed-subtype pattern.
     *
     * **Principle:** a [PartialChange] runs synchronously inside the state accumulator, so it must
     * stay minimal — its single job is to migrate the snapshot (update state and/or attach an
     * event). Keep decision-making, branching, and any heavy or async work in the handler, not
     * inside the change. See [cc.colorcat.mvi.sample.count.CounterViewModel.handleDecrement] for the
     * preferred shape and [cc.colorcat.mvi.sample.count.CounterViewModel.handleIncrement] for the
     * concise inline form that is only acceptable when the branch is trivial.
     */
    fun interface PartialChange : Mvi.PartialChange<State, Event>
}
