package cc.colorcat.mvi

/**
 * Core interfaces and types for the MVI (Model-View-Intent) architecture pattern.
 *
 * ## MVI Architecture Overview
 *
 * MVI is a unidirectional data flow pattern where:
 * - **Intent**: Represents user actions or system events that trigger state changes
 * - **State**: Immutable representation of the UI state at any given time
 * - **Event**: One-time side effects (like showing a toast or navigating)
 *
 * ## Data Flow
 *
 * ```
 * User Action → Intent → PartialChange → State → View
 *                              ↓
 *                            Event (optional)
 * ```
 *
 * ## Basic Usage Example
 *
 * ```
 * // Define your types
 * sealed interface MyIntent : Mvi.Intent {
 *     data class LoadData(val id: String) : MyIntent, Mvi.Intent.Sequential
 *     data object Refresh : MyIntent, Mvi.Intent.Concurrent
 * }
 *
 * data class MyState(val data: String = "", val loading: Boolean = false) : Mvi.State
 *
 * sealed interface MyEvent : Mvi.Event {
 *     data class ShowError(val message: String) : MyEvent
 * }
 *
 * // Process intents and create state changes
 * fun handleIntent(intent: MyIntent): Mvi.PartialChange<MyState, MyEvent> {
 *     return when (intent) {
 *         is MyIntent.LoadData -> Mvi.PartialChange { snapshot ->
 *             snapshot.updateState { copy(loading = true) }
 *         }
 *         // ...
 *     }
 * }
 * ```
 *
 * @see Intent
 * @see State
 * @see Event
 * @see PartialChange
 * @see Snapshot
 */
object Mvi {
    /**
     * Represents a user action or system event that triggers state changes.
     *
     * Intents are the input to your MVI system. They represent what the user
     * wants to do or what events have occurred in the system.
     *
     * ## Intent Processing Modes
     *
     * - **Concurrent**: Multiple intents of this type can be processed in parallel
     * - **Sequential**: Intents of this type are processed one at a time in order
     * - **Fallback**: Intents that don't implement either interface (default behavior)
     *
     * Example:
     * ```
     * sealed interface MyIntent : Mvi.Intent {
     *     // Can run in parallel with other Refresh intents
     *     data object Refresh : MyIntent, Mvi.Intent.Concurrent
     *
     *     // Processed sequentially, one at a time
     *     data class Submit(val data: String) : MyIntent, Mvi.Intent.Sequential
     *
     *     // Default fallback behavior
     *     data object Clear : MyIntent
     * }
     * ```
     *
     * @see Concurrent
     * @see Sequential
     */
    interface Intent {
        /**
         * Marker interface for intents that can be processed concurrently.
         *
         * Intents implementing this interface can be executed in parallel with
         * other concurrent intents, enabling better performance for independent operations.
         *
         * Use this for operations that:
         * - Don't depend on each other's results
         * - Can safely run in parallel
         * - Don't have ordering requirements
         *
         * Example: Multiple data refresh requests, analytics events, etc.
         *
         * **Do not implement both [Concurrent] and [Sequential] on the same intent.**
         * The two markers are mutually exclusive. Doing so is treated as a conflict:
         * a warning is logged once per intent class and the intent is routed to the
         * fallback group instead.
         */
        interface Concurrent : Intent

        /**
         * Marker interface for intents that must be processed sequentially.
         *
         * Intents implementing this interface are processed one at a time in the
         * order they were received, ensuring no race conditions or ordering issues.
         *
         * Use this for operations that:
         * - Must complete in a specific order
         * - Modify shared state
         * - Have dependencies on previous operations
         *
         * Example: Form submissions, data mutations, navigation requests, etc.
         *
         * **Do not implement both [Sequential] and [Concurrent] on the same intent.**
         * The two markers are mutually exclusive. Doing so is treated as a conflict:
         * a warning is logged once per intent class and the intent is routed to the
         * fallback group instead.
         */
        interface Sequential : Intent
    }

    /**
     * Represents the immutable, **persistent** description of the UI.
     *
     * In the frame model (see [PartialChange]), State is the part of a frame that
     * **persists across frames**: it describes the elements that remain on screen,
     * such as the current list, loading flag, or input text. Each new frame carries
     * the previous State forward unless a [PartialChange] migrates some of it.
     *
     * State should be a data class that contains all the information needed to
     * render the UI. It should be immutable - any changes create a new instance.
     *
     * Contrast with [Event], which is transient and lives in exactly one frame.
     *
     * Example:
     * ```
     * data class MyState(
     *     val data: List<Item> = emptyList(),
     *     val loading: Boolean = false,
     *     val error: String? = null
     * ) : Mvi.State
     * ```
     */
    interface State

    /**
     * Represents a one-time side effect — a **transient** part of a single frame.
     *
     * In the frame model (see [PartialChange]), an Event is the **fleeting** part of a
     * frame: it exists in **exactly one frame** and is cleared or replaced the moment the
     * next frame is produced (any [Snapshot.updateState] drops it). While it is present it
     * is delivered to whatever consumer is currently subscribed; if there is no consumer,
     * it is simply dropped — events are never retained or replayed.
     *
     * Use Events for actions that should happen once and not be retained in state, such as
     * showing a toast, navigating to another screen, or playing a sound.
     *
     * Contrast with [State], which is the persistent part that carries across frames.
     *
     * Example:
     * ```
     * sealed interface MyEvent : Mvi.Event {
     *     data class ShowToast(val message: String) : MyEvent
     *     data class NavigateTo(val screen: Screen) : MyEvent
     * }
     * ```
     */
    interface Event


    /**
     * A partial migration of one UI "frame" to the next.
     *
     * ## What is "Partial"?
     *
     * A [Snapshot] is one complete "frame" of the UI description: the current [State]
     * plus an optional, one-shot [Event]. Every frame is **derived from the previous
     * frame** rather than rebuilt from scratch. A `PartialChange` *is* that derivation:
     * it receives the previous frame (`old`) and updates **only part** of it — some
     * state properties, and/or the attached event — while everything it does not touch
     * carries over from the previous frame.
     *
     * "Partial" is therefore relative to the **whole frame ([Snapshot])**, not to the
     * state alone: you almost never replace an entire frame outright, you migrate it
     * incrementally into the next one.
     *
     * For example, when loading data, you might only change the `loading` property and
     * let every other field carry over:
     * ```
     * snapshot.updateState { copy(loading = true) }  // Other properties unchanged
     * ```
     *
     * ## What Can Be Changed?
     *
     * A single PartialChange can perform any one of the following:
     * - **Update state only** (some or all properties)
     * - **Attach an event only**, leaving the state untouched ([Snapshot.withEvent])
     * - **Do both** in one operation ([Snapshot.updateWith])
     *
     * It represents a transformation of the frame: `Snapshot → Snapshot`.
     *
     * ## Typical Flow: Intent → Flow<PartialChange>
     *
     * Each intent is typically transformed into a `Flow<PartialChange>`, representing
     * a series of state changes over time. This allows for:
     * - Complex async operations with multiple state updates
     * - Progressive UI updates (e.g., loading → success → done)
     * - Event emission at any point in the flow
     *
     * ```
     * Intent → Flow<PartialChange> → Accumulated State Changes → New Snapshot
     * ```
     *
     * Each PartialChange in the flow represents one step in the state evolution.
     *
     * ## Usage Examples
     *
     * ```
     * // 1. Partial state update (only change 'loading')
     * Mvi.PartialChange { snapshot ->
     *     snapshot.updateState { copy(loading = true) }
     * }
     *
     * // 2. Update multiple properties
     * Mvi.PartialChange { snapshot ->
     *     snapshot.updateState { copy(loading = false, data = newData, error = null) }
     * }
     *
     * // 3. Update state + emit event
     * Mvi.PartialChange { snapshot ->
     *     snapshot.updateWith(MyEvent.ShowSuccess) {
     *         copy(loading = false, data = newData)
     *     }
     * }
     *
     * // 4. Only emit event (state unchanged)
     * Mvi.PartialChange { snapshot ->
     *     snapshot.withEvent(MyEvent.ShowToast("Done!"))
     * }
     * ```
     *
     * ## Complex Async Operation Example
     *
     * ```
     * fun handleLoadData(id: String): Flow<Mvi.PartialChange<MyState, MyEvent>> = flow {
     *     // First: set loading state
     *     emit(Mvi.PartialChange { snapshot ->
     *         snapshot.updateState { copy(loading = true) }
     *     })
     *
     *     try {
     *         val data = withContext(Dispatchers.IO) {
     *             repository.loadData(id)
     *         }
     *         // Second: update with loaded data and emit success event
     *         emit(Mvi.PartialChange { snapshot ->
     *             snapshot.updateWith(MyEvent.ShowSuccess) {
     *                 copy(loading = false, data = data)
     *             }
     *         })
     *     } catch (e: Exception) {
     *         // Third: handle error and emit error event
     *         emit(Mvi.PartialChange { snapshot ->
     *             snapshot.updateWith(MyEvent.ShowError(e.message)) {
     *                 copy(loading = false)
     *             }
     *         })
     *     }
     * }
     * ```
     *
     * @param S The state type
     * @param E The event type
     * @see Snapshot
     * @see Snapshot.updateState
     * @see Snapshot.updateWith
     * @see Snapshot.withEvent
     */
    fun interface PartialChange<S : State, E : Event> {
        /**
         * Applies this change to the given snapshot, producing a new snapshot.
         *
         * ## Design Contract
         *
         * The framework invokes this function synchronously while accumulating snapshots.
         * It **must** be:
         * - **Pure**: no side effects, I/O, or coroutine launching
         * - **Lightweight**: only `copy()` calls and simple branching; all async / heavy work
         *   belongs in [IntentHandler.handle][cc.colorcat.mvi.IntentHandler.handle], which produces
         *   `PartialChange` values through a `Flow`
         * - **Non-throwing**: exceptions from `apply` are treated as developer errors and are
         *   allowed to fail the processing coroutine. Never rely on throwing to model business
         *   state; encode recoverable outcomes in the returned [Snapshot] instead.
         *
         * @param old The current snapshot containing the current state and any pending event
         * @return A new snapshot with the updated state and/or event
         */
        fun apply(old: Snapshot<S, E>): Snapshot<S, E>
    }


    /**
     * An immutable snapshot — one "frame" of the UI description.
     *
     * A Snapshot pairs a persistent [State] with an optional, transient [Event]. It is one
     * frame in your application's state evolution (see [PartialChange] for the frame model).
     * The snapshot provides methods to derive the next frame with updated state and/or event.
     *
     * ## Key Characteristics
     *
     * - **Immutable**: All methods return new instances; the original is never modified
     * - **Event Lifecycle**: The [event] lives in exactly one frame. It is delivered to the
     *   current subscriber (if any) and must be cleared when the next frame is produced —
     *   that is why [updateState] drops it. Letting an event carry into a later frame would
     *   re-deliver it, so a non-null [event] is meant to survive only a single frame.
     * - **Type-Safe**: Compiler ensures state and event types match
     *
     * ## Construction
     *
     * Business code rarely constructs a Snapshot directly. Derive the next frame from the `old`
     * snapshot passed to [PartialChange.apply] via [updateState] / [withEvent] / [updateWith].
     * Direct construction is mainly for the initial frame (built by the framework from the initial
     * state) and for tests.
     *
     * ```kotlin
     * val snapshot = Mvi.Snapshot(MyState())
     * val snapshotWithEvent = Mvi.Snapshot(MyState(), MyEvent.ShowToast("Hi"))
     * ```
     *
     * @param S The state type
     * @param E The event type
     * @property state The current immutable state
     * @property event An optional one-time event to be consumed
     * @see State
     * @see Event
     */
    data class Snapshot<S : State, E : Event>(
        val state: S,
        val event: E? = null,
    ) {

        /**
         * Creates a new snapshot with an updated state.
         *
         * The [transform] function receives the current state and should return
         * a new state instance. Any pending event is cleared in the new snapshot.
         *
         * **Note**: This method clears any pending event by design — an event lives in
         * exactly one frame (see [Snapshot]). If you need to update state while also
         * setting an event in the same frame, use [updateWith] instead.
         *
         * Example:
         * ```
         * snapshot.updateState { copy(loading = true, error = null) }
         * ```
         *
         * ### Pitfall: don't chain [withEvent] then [updateState] in one change
         *
         * Within a single [PartialChange], setting an event and then calling
         * [updateState] silently drops the event, because [updateState] clears it:
         * ```
         * // ❌ The toast is lost: updateState clears the event just set
         * Mvi.PartialChange { it.withEvent(MyEvent.ShowToast).updateState { copy(loading = false) } }
         *
         * // ✅ Set state and event together in the same frame
         * Mvi.PartialChange { it.updateWith(MyEvent.ShowToast) { copy(loading = false) } }
         * ```
         * Note this only applies *within one change*. Emitting an event in one
         * [PartialChange] and updating state in a later one is safe: the event-carrying
         * frame is delivered before the next frame clears it.
         *
         * @param transform A function that transforms the current state to a new state
         * @return A new snapshot with the updated state and no event
         * @see updateWith
         * @see withEvent
         */
        fun updateState(transform: S.() -> S): Snapshot<S, E> {
            return copy(state = state.transform(), event = null)
        }

        /**
         * Creates a new snapshot with the specified event, keeping the current state.
         *
         * Use this when you want to emit an event without modifying the state.
         *
         * Example:
         * ```
         * snapshot.withEvent(MyEvent.ShowToast("Success!"))
         * ```
         *
         * @param event The event to attach to the snapshot
         * @return A new snapshot with the same state and the specified event
         * @see updateState
         * @see updateWith
         */
        fun withEvent(event: E): Snapshot<S, E> = copy(event = event)

        /**
         * Creates a new snapshot with an updated state and an attached event.
         *
         * This is a convenience method that combines state update and event attachment
         * in a single operation, which is a common pattern in MVI.
         *
         * Example:
         * ```
         * snapshot.updateWith(MyEvent.ShowSuccess) {
         *     copy(loading = false, data = newData)
         * }
         * ```
         *
         * @param event The event to attach to the snapshot
         * @param transform A function that transforms the current state to a new state
         * @return A new snapshot with both the updated state and the specified event
         * @see updateState
         * @see withEvent
         */
        fun updateWith(event: E, transform: S.() -> S): Snapshot<S, E> {
            return copy(state = state.transform(), event = event)
        }
    }
}
