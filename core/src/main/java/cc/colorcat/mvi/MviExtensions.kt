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

/**
 * MVI Extension functions for converting Android View events to Flow<Intent>.
 *
 * This file provides convenient extension functions to convert various Android UI events
 * (clicks, text changes, etc.) into reactive Flow streams that emit MVI Intents.
 *
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */

/**
 * Converts a single value into a Flow that emits this value.
 *
 * This is a convenience extension that wraps [flowOf].
 *
 * ## Usage Example
 *
 * ```kotlin
 * val intent = LoginIntent.Initialize
 * intent.asSingleFlow()
 *     .launchCollect(this) { viewModel.dispatch(it) }
 * ```
 *
 * @return A Flow that emits this single value
 */
fun <T> T.asSingleFlow(): Flow<T> = flowOf(this)

/**
 * Debounces the flow by responding only to the first event in a series of rapid events.
 *
 * This is the opposite of [debounce]:
 * - [debounce]: Responds to the **last** event after a period of silence (delayed response)
 * - [debounceFirst]: Responds to the **first** event immediately, then ignores subsequent rapid events
 *
 * In a continuous stream of events, only the first event is emitted. Subsequent events
 * are ignored as long as the time gap between consecutive events is less than [timeMillis].
 * Once a gap of at least [timeMillis] occurs, the next event will be emitted as a new "first" event.
 *
 * ## Behavior Comparison
 *
 * **debounce (standard):**
 * ```
 * Events:  0ms  100ms  200ms  [silence 500ms]  → Emit at 700ms (delayed)
 *                                               ↑ Responds to LAST event
 * ```
 *
 * **debounceFirst (this function):**
 * ```
 * Events:  0ms  100ms  200ms
 *          ↓
 *        Emit immediately (responsive)
 *        ↑ Responds to FIRST event
 * ```
 *
 * ## Behavior Example
 *
 * Given events at times: 0ms, 100ms, 200ms, 300ms, 1000ms, 1100ms
 * With timeMillis = 500:
 * - Emit event at 0ms (first event, gap from previous = infinite)
 * - Skip events at 100ms, 200ms, 300ms (gaps = 100ms, 100ms, 100ms < 500ms)
 * - Emit event at 1000ms (gap = 700ms >= 500ms, new "first" event)
 * - Skip event at 1100ms (gap = 100ms < 500ms)
 *
 * ## Usage Example
 *
 * ```kotlin
 * // Prevent double clicks - respond immediately to first click
 * button.doOnClick { send(ClickIntent) }
 *     .debounceFirst(500L)  // Respond to first click, ignore rapid subsequent clicks
 *     .launchCollect(this) { viewModel.dispatch(it) }
 *
 * // Compare with debounce (responds to last event after delay)
 * searchEditText.afterTextChanged()
 *     .debounce(500L)  // Wait for user to stop typing, then respond to last input
 *     .launchCollect(this) { viewModel.dispatch(it) }
 * ```
 *
 * @param timeMillis The minimum time gap in milliseconds. Events with smaller gaps are ignored.
 * @return A flow that emits only the first value in rapid sequences
 * @see kotlinx.coroutines.flow.debounce
 */
fun <T> Flow<T>.debounceFirst(timeMillis: Long): Flow<T> = flow {
    var time = -1L
    collect { value ->
        val lastTime = time
        time = System.currentTimeMillis()
        if (time - lastTime >= timeMillis) {
            emit(value)
        }
    }
}


/**
 * Converts a View's click events into a Flow of Intents.
 *
 * The Flow will emit an Intent each time the View is clicked.
 * The click listener is automatically removed when the Flow is cancelled.
 *
 * ## Usage Example
 *
 * ```kotlin
 * button.doOnClick { send(LoginIntent.ClickLoginButton) }
 *     .launchCollect(viewLifecycleOwner) { intent ->
 *         viewModel.dispatch(intent)
 *     }
 * ```
 *
 * @param I The Intent type that extends [Mvi.Intent]
 * @param block A lambda that produces an Intent when the View is clicked
 * @return A Flow that emits Intents on each click
 */
fun <I : Mvi.Intent> View.doOnClick(block: ProducerScope<I>.() -> Unit): Flow<I> = callbackFlow {
    setOnClickListener {
        this.block()
    }
    awaitClose { setOnClickListener(null) }
}

/**
 * Converts a View's long click events into a Flow of Intents.
 *
 * The Flow will emit an Intent each time the View is long-clicked.
 * The long click listener is automatically removed when the Flow is cancelled.
 *
 * ## Usage Example
 *
 * ```kotlin
 * button.doOnLongClick {
 *     send(EditIntent.ShowContextMenu)
 *     true  // Consume the event
 * }.launchCollect(viewLifecycleOwner) { intent ->
 *     viewModel.dispatch(intent)
 * }
 * ```
 *
 * @param I The Intent type that extends [Mvi.Intent]
 * @param block A lambda that produces an Intent and returns whether the event is consumed
 * @return A Flow that emits Intents on each long click
 */
fun <I : Mvi.Intent> View.doOnLongClick(
    block: ProducerScope<I>.() -> Boolean
): Flow<I> = callbackFlow {
    setOnLongClickListener {
        this.block()
    }
    awaitClose { setOnLongClickListener(null) }
}

/**
 * Converts a CompoundButton's checked state changes into a Flow of Intents.
 *
 * The Flow will emit an Intent each time the checked state changes.
 * The checked change listener is automatically removed when the Flow is cancelled.
 *
 * ## Usage Example
 *
 * ```kotlin
 * switchButton.doOnCheckedChange { isChecked ->
 *     send(SettingsIntent.ToggleNotification(isChecked))
 * }.launchCollect(viewLifecycleOwner) { intent ->
 *     viewModel.dispatch(intent)
 * }
 * ```
 *
 * @param I The Intent type that extends [Mvi.Intent]
 * @param block A lambda that receives the checked state and produces an Intent
 * @return A Flow that emits Intents on each checked state change
 */
fun <I : Mvi.Intent> CompoundButton.doOnCheckedChange(
    block: ProducerScope<I>.(isChecked: Boolean) -> Unit
): Flow<I> = callbackFlow {
    setOnCheckedChangeListener { _, isChecked ->
        this.block(isChecked)
    }
    awaitClose { setOnCheckedChangeListener(null) }
}

/**
 * Converts a TextView's text changes into a Flow of Intents with optional debouncing.
 *
 * The Flow will emit an Intent after the text changes (after the user finishes editing).
 * Debouncing helps reduce unnecessary emissions during rapid typing.
 * The text watcher is automatically removed when the Flow is cancelled.
 *
 * ## Usage Example
 *
 * ```kotlin
 * searchEditText.afterTextChanged(debounceMillis = 500L) { editable ->
 *     send(SearchIntent.QueryChanged(editable?.toString().orEmpty()))
 * }.launchCollect(viewLifecycleOwner) { intent ->
 *     viewModel.dispatch(intent)
 * }
 * ```
 *
 * @param I The Intent type that extends [Mvi.Intent]
 * @param debounceMillis Debounce time in milliseconds. Set to 0 to disable debouncing. Default is 500ms.
 * @param block A lambda that receives the Editable and produces an Intent
 * @return A Flow that emits Intents after text changes (with optional debouncing)
 */
fun <I : Mvi.Intent> TextView.afterTextChanged(
    debounceMillis: Long = 500L,
    block: ProducerScope<I>.(Editable?) -> Unit
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
        @OptIn(FlowPreview::class)
        flow.debounce(debounceMillis)
    } else {
        flow
    }
}
