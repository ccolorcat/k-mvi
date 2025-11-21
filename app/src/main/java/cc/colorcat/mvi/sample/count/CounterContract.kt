package cc.colorcat.mvi.sample.count

import cc.colorcat.mvi.Mvi
import kotlin.math.abs
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
     * @property count The current counter value (raw data)
     * @property countText Computed property for UI presentation - converts count to String
     *
     * **Design Note:**
     * Using computed properties like [countText] separates business logic (count as Int)
     * from presentation logic (formatted string). This allows:
     * - Easy formatting changes without touching ViewModel
     * - Type-safe binding with TextView (String → setText)
     * - Reusability of presentation logic across multiple UI components
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

        val countText: String
            get() = count.toString()

        val countInfo: String
            get() = "Target: $targetCount, Range: $COUNT_MIN ~ $COUNT_MAX"
    }

    /**
     * One-time events that should be consumed by the UI layer.
     * Events are not part of the state and should only be handled once.
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
     * Functional interface for state transformations.
     * A PartialChange takes the current state and returns a new state with optional events.
     */
    fun interface PartialChange : Mvi.PartialChange<State, Event>
}
