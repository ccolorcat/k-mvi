@file:OptIn(FlowPreview::class)

package cc.colorcat.mvi

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.TimeUnit

/**
 * MVI Extension functions for converting Android View events to Flow<Intent>.
 *
 * This file provides convenient extension functions to convert various Android UI events
 * (clicks, text changes, etc.) into reactive Flow streams that emit MVI Intents.
 */

/**
 * Converts a single [Mvi.PartialChange] into a Flow that emits it.
 *
 * This is a convenience extension that wraps [flowOf].
 *
 * ## Usage Example
 *
 * ```kotlin
 * LoginChange.ClearError
 *     .asSingleFlow()
 *     .collect { change -> ... }
 * ```
 *
 * @return A Flow that emits this single partial change
 */
fun <S : Mvi.State, E : Mvi.Event, C : Mvi.PartialChange<S, E>> C.asSingleFlow(): Flow<C> = flowOf(this)

/**
 * Debounces the flow with a leading edge trigger and sliding timeout window.
 *
 * This is the opposite of [debounce]:
 * - [debounce]: Responds to the **last** event after a period of silence (trailing edge, delayed response)
 * - [debounceLeading]: Responds to the **first** event immediately (leading edge, instant response)
 *
 * **Key behavior:** Each event (whether emitted or ignored) updates the internal timestamp.
 * This creates a sliding timeout window where rapid continuous events will only trigger once,
 * no matter how long they continue, as long as the gap between consecutive events is less than [timeMillis].
 * The timeout is measured with [System.nanoTime], a monotonic clock that is unaffected by wall-clock changes.
 *
 * ## Behavior Comparison
 *
 * **debounce (trailing edge - waits for silence):**
 * ```
 * Events:  0ms  100ms  200ms  [silence 500ms]  → Emit at 700ms (delayed)
 *                                               ↑ Responds to LAST event after silence
 * ```
 *
 * **debounceLeading (this function - responds immediately with sliding window):**
 * ```
 * Events:  0ms   100ms  200ms  300ms  600ms  [silence]  1200ms  1300ms
 *          ↓                                             ↓
 *        Emit                                          Emit
 *          └────────── window resets on each event ──────┘
 *
 * Timeline analysis (timeMillis = 500):
 * - 0ms:    Emit ✓ (gap from init = 500ms >= 500ms)  → timestamp updated to 0ms
 * - 100ms:  Skip   (gap = 100ms < 500ms)              → timestamp updated to 100ms
 * - 200ms:  Skip   (gap = 100ms < 500ms)              → timestamp updated to 200ms
 * - 300ms:  Skip   (gap = 100ms < 500ms)              → timestamp updated to 300ms
 * - 600ms:  Skip   (gap = 300ms < 500ms)              → timestamp updated to 600ms
 * - 1200ms: Emit ✓ (gap = 600ms >= 500ms)            → timestamp updated to 1200ms
 * - 1300ms: Skip   (gap = 100ms < 500ms)              → timestamp updated to 1300ms
 * ```
 *
 * **Key difference from standard throttleFirst:**
 * - **throttleFirst**: Only updates timestamp when emitting → can emit again sooner
 * - **debounceLeading**: Always updates timestamp → extends silence window on every event
 *
 * ## Real-world Example
 *
 * **Scenario:** User rapidly clicks a submit button 10 times in 2 seconds, then waits 1 second
 *
 * With `debounceLeading(500)`:
 * ```
 * Click 1 (0ms):    Emit ✓ → "Submitting..."
 * Click 2 (200ms):  Skip (gap = 200ms < 500ms)
 * Click 3 (400ms):  Skip (gap = 200ms < 500ms)
 * Click 4 (600ms):  Skip (gap = 200ms < 500ms)
 * ...
 * Click 10 (1800ms): Skip (gap = 200ms < 500ms)
 * [User stops clicking]
 * Click 11 (3000ms): Emit ✓ (gap = 1200ms >= 500ms) → "Submitting again..."
 * ```
 *
 * Result: Even with 10 rapid clicks, only the first one is processed. The timeout window
 * keeps extending with each click, preventing any processing until there's a 500ms silence.
 *
 * ## Usage Examples
 *
 * ```kotlin
 * // Prevent accidental double-clicks on buttons
 * // User's first click is processed immediately, subsequent rapid clicks are ignored
 * button.doOnClick { trySend(SubmitIntent) }
 *     .debounceLeading(500L)  // 500ms sliding window
 *     .launchWithLifecycle(viewLifecycleOwner) { viewModel.dispatch(it) }
 *
 * // Compare with standard debounce (waits for user to stop, then responds)
 * searchEditText.afterTextChanged()
 *     .debounce(500L)  // Waits for user to stop typing
 *     .launchWithLifecycle(viewLifecycleOwner) { viewModel.dispatch(SearchIntent(it)) }
 * ```
 *
 * ## Use Cases
 *
 * - **Button click prevention**: Prevents accidental multi-taps while providing instant feedback
 * - **Form submission protection**: Prevents duplicate submissions from impatient users
 * - **Rate-limiting API calls**: Ensures actions triggered by user events don't fire too frequently
 * - **Pull-to-refresh**: Responds immediately to first pull, ignores subsequent rapid pulls
 *
 * ## When to use debounceLeading vs debounce
 *
 * - Use **debounceLeading** when you want immediate response to the first action (e.g., button clicks)
 * - Use **debounce** when you want to wait for user to finish (e.g., search input, form validation)
 *
 * @param timeMillis The minimum time gap in milliseconds between emitted events. Must be positive (> 0).
 * @return A flow that emits the first event immediately, then only emits subsequent events
 *         if at least [timeMillis] has passed since the **last event** (not last emission)
 * @see kotlinx.coroutines.flow.debounce
 */
fun <T> Flow<T>.debounceLeading(timeMillis: Long): Flow<T> = debounceLeading(timeMillis, System::nanoTime)

internal fun <T> Flow<T>.debounceLeading(timeMillis: Long, nanoTimeSource: () -> Long): Flow<T> = flow {
    require(timeMillis > 0L) { "timeMillis must be positive" }
    val windowNanos = TimeUnit.MILLISECONDS.toNanos(timeMillis)
    var time = -windowNanos
    collect { value ->
        val prev = time
        time = nanoTimeSource()
        if (time - prev >= windowNanos) {
            emit(value)
        }
    }
}


/**
 * Converts a View's click events into a Flow of Intents.
 *
 * The Flow will emit an Intent each time the View is clicked.
 * The click listener is automatically removed when the Flow is cancelled.
 * This Flow must be collected on the main thread because it registers and
 * removes Android View listeners. The lifecycle helpers in this library
 * ([launchWithLifecycle] and [dispatchWithLifecycle]) collect from
 * `LifecycleOwner.lifecycleScope`, so normal Fragment/View usage satisfies this requirement.
 *
 * ## Usage Example
 *
 * ```kotlin
 * button.doOnClick { trySend(LoginIntent.ClickLoginButton) }
 *     .launchWithLifecycle(viewLifecycleOwner) { intent ->
 *         viewModel.dispatch(intent)
 *     }
 * ```
 *
 * @param I The Intent type that extends [Mvi.Intent]
 * @param block A non-suspend lambda with [ProducerScope] receiver. Use [ProducerScope.trySend] (not
 *   `send`) to emit intents — `send` is a suspend function and cannot be called from this context.
 * @return A Flow that emits Intents on each click
 */
fun <I : Mvi.Intent> View.doOnClick(block: ProducerScope<I>.() -> Unit): Flow<I> = callbackFlow {
    setOnClickListener {
        block()
    }
    awaitClose { setOnClickListener(null) }
}

/**
 * Converts a View's long click events into a Flow of Intents.
 *
 * The Flow will emit an Intent each time the View is long-clicked.
 * The long click listener is automatically removed when the Flow is cancelled.
 * This Flow must be collected on the main thread because it registers and
 * removes Android View listeners. The lifecycle helpers in this library
 * ([launchWithLifecycle] and [dispatchWithLifecycle]) collect from
 * `LifecycleOwner.lifecycleScope`, so normal Fragment/View usage satisfies this requirement.
 *
 * ## Usage Example
 *
 * ```kotlin
 * button.doOnLongClick {
 *     trySend(EditIntent.ShowContextMenu)
 *     true  // Consume the event
 * }.launchWithLifecycle(viewLifecycleOwner) { intent ->
 *     viewModel.dispatch(intent)
 * }
 * ```
 *
 * @param I The Intent type that extends [Mvi.Intent]
 * @param block A non-suspend lambda with [ProducerScope] receiver that returns `true` to consume the
 *   event. Use [ProducerScope.trySend] (not `send`) to emit intents.
 * @return A Flow that emits Intents on each long click
 */
fun <I : Mvi.Intent> View.doOnLongClick(
    block: ProducerScope<I>.() -> Boolean,
): Flow<I> = callbackFlow {
    setOnLongClickListener {
        block()
    }
    awaitClose { setOnLongClickListener(null) }
}

/**
 * Converts a CompoundButton's checked state changes into a Flow of Intents.
 *
 * The Flow will emit an Intent each time the checked state changes.
 * The checked change listener is automatically removed when the Flow is cancelled.
 * This Flow must be collected on the main thread because it registers and
 * removes Android View listeners. The lifecycle helpers in this library
 * ([launchWithLifecycle] and [dispatchWithLifecycle]) collect from
 * `LifecycleOwner.lifecycleScope`, so normal Fragment/View usage satisfies this requirement.
 *
 * ## Usage Example
 *
 * ```kotlin
 * switchButton.doOnCheckedChange { isChecked ->
 *     trySend(SettingsIntent.ToggleNotification(isChecked))
 * }.launchWithLifecycle(viewLifecycleOwner) { intent ->
 *     viewModel.dispatch(intent)
 * }
 * ```
 *
 * @param I The Intent type that extends [Mvi.Intent]
 * @param block A non-suspend lambda with [ProducerScope] receiver. Use [ProducerScope.trySend] (not
 *   `send`) to emit intents — `send` is a suspend function and cannot be called from this context.
 * @return A Flow that emits Intents on each checked state change
 */
fun <I : Mvi.Intent> CompoundButton.doOnCheckedChange(
    block: ProducerScope<I>.(isChecked: Boolean) -> Unit,
): Flow<I> = callbackFlow {
    setOnCheckedChangeListener { _, isChecked ->
        block(isChecked)
    }
    awaitClose { setOnCheckedChangeListener(null) }
}

/**
 * Converts a TextView's text change events (after text has changed) into a Flow of Intents
 * with optional debouncing.
 *
 * The Flow will emit an Intent after the text changes (after the user finishes editing).
 * Debouncing helps reduce unnecessary emissions during rapid typing.
 * The text watcher is automatically removed when the Flow is cancelled.
 * This Flow must be collected on the main thread because it registers and
 * removes Android View listeners. The lifecycle helpers in this library
 * ([launchWithLifecycle] and [dispatchWithLifecycle]) collect from
 * `LifecycleOwner.lifecycleScope`, so normal Fragment/View usage satisfies this requirement.
 *
 * ## Usage Example
 *
 * ```kotlin
 * searchEditText.doOnAfterTextChanged(debounceMillis = 500L) { editable ->
 *     trySend(SearchIntent.QueryChanged(editable?.toString().orEmpty()))
 * }.launchWithLifecycle(viewLifecycleOwner) { intent ->
 *     viewModel.dispatch(intent)
 * }
 * ```
 *
 * @param I The Intent type that extends [Mvi.Intent]
 * @param debounceMillis Debounce time in milliseconds. Set to 0 to disable debouncing. Default is 500ms.
 * @param block A non-suspend lambda with [ProducerScope] receiver. Use [ProducerScope.trySend] (not
 *   `send`) to emit intents — `send` is a suspend function and cannot be called from this context.
 * @return A Flow that emits Intents after text changes (with optional debouncing)
 */
fun <I : Mvi.Intent> TextView.doOnAfterTextChanged(
    debounceMillis: Long = 500L,
    block: ProducerScope<I>.(Editable?) -> Unit,
): Flow<I> {
    val flow = callbackFlow {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                this@callbackFlow.block(s)
            }
        }
        addTextChangedListener(watcher)
        awaitClose { removeTextChangedListener(watcher) }
    }
    return if (debounceMillis > 0L) {
        flow.debounce(debounceMillis)
    } else {
        flow
    }
}
