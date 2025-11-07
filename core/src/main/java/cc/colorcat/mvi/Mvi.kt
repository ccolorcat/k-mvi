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
 *
 * Author: ccolorcat
 * Date: 2025-11-08
 * GitHub: https://github.com/ccolorcat
 */
sealed interface Mvi {
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
         */
        interface Sequential : Intent
    }

    /**
     * Represents the immutable state of the application at a given point in time.
     *
     * State should be a data class that contains all the information needed to
     * render the UI. It should be immutable - any changes create a new instance.
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
     * Represents a one-time side effect or event.
     *
     * Events are used for actions that should happen once and not be retained in state,
     * such as showing a toast, navigating to another screen, or playing a sound.
     *
     * Unlike State, Events are consumed after being handled and don't persist.
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
     * A function that applies a partial change to a state snapshot.
     *
     * ## What is "Partial"?
     *
     * "Partial" means you typically update **only some properties** of the state
     * (using Kotlin's `copy()` method), rather than replacing the entire state object.
     * This encourages immutable updates and fine-grained state control.
     *
     * For example, when loading data, you might only change the `loading` property:
     * ```
     * snapshot.updateState { copy(loading = true) }  // Other properties unchanged
     * ```
     *
     * ## What Can Be Changed?
     *
     * A PartialChange can perform one or more of the following:
     * - **Update state properties** (partial or complete)
     * - **Attach an event** to the snapshot
     * - **Do both** in one operation
     *
     * It represents a transformation: `Snapshot → Snapshot`
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
     *         val data = repository.loadData(id)
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
         * @param old The current snapshot containing the current state and any pending event
         * @return A new snapshot with the updated state and/or event
         */
        fun apply(old: Snapshot<S, E>): Snapshot<S, E>
    }


    /**
     * An immutable snapshot of the current state and optional event.
     *
     * A Snapshot pairs a [State] with an optional [Event]. It represents a moment
     * in time in your application's state evolution. The snapshot provides methods
     * to create new snapshots with updated state and/or events.
     *
     * ## Key Characteristics
     *
     * - **Immutable**: All methods return new instances; the original is never modified
     * - **Event Lifecycle**: Events are typically cleared when state is updated
     * - **Type-Safe**: Compiler ensures state and event types match
     *
     * ## Constructor
     *
     * The constructor is `internal` to ensure snapshots are created through the
     * proper channels (via [PartialChange] or framework methods), maintaining
     * architectural consistency.
     *
     * @param S The state type
     * @param E The event type
     * @property state The current immutable state
     * @property event An optional one-time event to be consumed
     * @see State
     * @see Event
     */
    @Suppress("NON_PUBLIC_PRIMARY_CONSTRUCTOR_OF_DATA_CLASS")
    data class Snapshot<S : State, E : Event> internal constructor(
        val state: S,
        val event: E? = null
    ) {
        /**
         * Creates a new snapshot with an updated state.
         *
         * The [transform] function receives the current state and should return
         * a new state instance. Any pending event is cleared in the new snapshot.
         *
         * **Note**: This method clears any pending event. If you need to update
         * state while preserving or setting an event, use [updateWith] instead.
         *
         * Example:
         * ```
         * snapshot.updateState { copy(loading = true, error = null) }
         * ```
         *
         * @param transform A function that transforms the current state to a new state
         * @return A new snapshot with the updated state and no event
         * @see updateWith
         * @see withEvent
         */
        fun updateState(transform: S.() -> S): Snapshot<S, E> {
            val newState = this.state.transform()
            return this.copy(state = newState, event = null)
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
        fun withEvent(event: E): Snapshot<S, E> = this.copy(event = event)

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
            val newState = this.state.transform()
            return this.copy(state = newState, event = event)
        }
    }
}
