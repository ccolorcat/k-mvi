package cc.colorcat.mvi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A read-only contract that exposes state and event flows in the MVI architecture.
 *
 * Contract defines the output side of the MVI pattern, providing observable streams
 * of state and events. It is designed to be used in the UI layer where components
 * only need to observe and react to state changes and one-time events.
 *
 * ## Key Characteristics
 *
 * - **Read-only**: No methods to dispatch intents or modify state
 * - **Observable**: Exposes state via [StateFlow] and events via [Flow]
 * - **Type-safe**: Strongly typed with Intent, State, and Event generics
 * - **Unidirectional**: Data flows in one direction (from Contract to UI)
 *
 * ## Usage in UI Layer
 *
 * ```kotlin
 * // The ViewModel owns the contract and exposes its read-only surface:
 * class MyViewModel : ViewModel() {
 *     private val contract by contract(initState = MyState(), defaultHandler = ::handleIntent)
 *     val stateFlow: StateFlow<MyState> = contract.stateFlow
 *     val eventFlow: Flow<MyEvent> = contract.eventFlow
 * }
 *
 * class MyFragment : Fragment() {
 *     private val viewModel: MyViewModel by viewModels()
 *
 *     override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
 *         // Observe state changes
 *         viewLifecycleOwner.lifecycleScope.launch {
 *             viewModel.stateFlow.collect { state ->
 *                 updateUI(state)
 *             }
 *         }
 *
 *         // Observe one-time events
 *         viewLifecycleOwner.lifecycleScope.launch {
 *             viewModel.eventFlow.collect { event ->
 *                 handleEvent(event)
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * ## State vs Event
 *
 * - **State** [[stateFlow]]: Represents the current UI state. Always has a value.
 *   UI should render based on this state. State is retained and replayed to new collectors.
 *
 * - **Event** [[eventFlow]]: Represents one-time side effects (toasts, navigation, etc.).
 *   Events are consumed once and not retained. No replay for new collectors.
 *
 * ## Relationship with ReactiveContract
 *
 * [Contract] is the read-only interface of [ReactiveContract]. If you need to dispatch
 * intents, use [ReactiveContract] instead.
 *
 * @param S The state type
 * @param E The event type
 * @see ReactiveContract
 * @see StateFlow
 * @see Flow
 */
interface Contract<S : Mvi.State, E : Mvi.Event> {
    /**
     * A hot flow that emits the current state and all subsequent state changes.
     *
     * [StateFlow] always has a value (the current state) and replays the latest value
     * to new collectors. This makes it ideal for representing UI state.
     *
     * ## Characteristics
     * - **Hot**: Emits values regardless of collectors
     * - **Stateful**: Always has a current value
     * - **Replay**: New collectors immediately receive the current state
     * - **Conflated**: Deduplicates by [Any.equals] — no emission when the new state equals the
     *   current one; additionally, slow collectors may skip intermediate values (this is value-based,
     *   not time-based, conflation)
     *
     * ## Collection Pattern
     * ```kotlin
     * lifecycleScope.launch {
     *     stateFlow.collect { state ->
     *         // Update UI based on current state
     *         updateUI(state)
     *     }
     * }
     * ```
     */
    val stateFlow: StateFlow<S>

    /**
     * A flow that emits one-time events (side effects).
     *
     * Events represent actions that should happen once, such as showing a toast,
     * navigating to another screen, or displaying a dialog. Events are not retained
     * and are only emitted to active collectors.
     *
     * ## Characteristics
     * - **Hot**: Shared among collectors
     * - **No state**: Doesn't retain values
     * - **No replay**: Late collectors don't receive past events
     * - **One-time**: Each event should be consumed once
     *
     * ## Collection Pattern
     * ```kotlin
     * lifecycleScope.launch {
     *     eventFlow.collect { event ->
     *         when (event) {
     *             is ShowToast -> showToast(event.message)
     *             is Navigate -> navigate(event.destination)
     *         }
     *     }
     * }
     * ```
     *
     * ## Important: Event Lifecycle
     * Events are emitted only to active collectors. If no collector is active when
     * an event is emitted, the event is lost. Make sure to start collecting before
     * dispatching intents that may produce events.
     */
    val eventFlow: Flow<E>
}

/**
 * A reactive contract that extends [Contract] with the ability to dispatch intents.
 *
 * ReactiveContract is the full-featured interface for the MVI pattern, combining:
 * - **Input**: [dispatch] method to send intents
 * - **Output**: [Contract.stateFlow] and [Contract.eventFlow] to observe state and events
 *
 * This interface is typically used in the ViewModel layer where both intent dispatching
 * and state/event observation are needed.
 *
 * ## Typical Usage in ViewModel
 *
 * ```kotlin
 * class MyViewModel : ViewModel() {
 *     private val contract by contract(
 *         initState = MyState(),
 *         defaultHandler = ::handleIntent,
 *     )
 *
 *     // Expose read-only contract to UI
 *     val state: StateFlow<MyState> = contract.stateFlow
 *     val events: Flow<MyEvent> = contract.eventFlow
 *
 *     // Dispatch intents from UI actions
 *     fun onUserClick() {
 *         contract.dispatch(MyIntent.Click)
 *     }
 *
 *     fun onLoadData(id: String) {
 *         contract.dispatch(MyIntent.LoadData(id))
 *     }
 * }
 * ```
 *
 * ## Intent Processing Flow
 *
 * ```
 * User Action → dispatch(intent) → Intent Handler → PartialChange → State Update
 *                                                          ↓
 *                                                        Event (optional)
 * ```
 *
 * ## Converting to Read-only Contract
 *
 * Use [asContract] extension to expose only the read-only interface:
 * ```kotlin
 * val readOnlyContract: Contract<S, E> = reactiveContract.asContract()
 * ```
 *
 * @param I The intent type
 * @param S The state type
 * @param E The event type
 * @see Contract
 * @see dispatch
 * @see asContract
 */
interface ReactiveContract<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> : Contract<S, E> {
    /**
     * Dispatches an intent to be processed.
     *
     * This method is the entry point for all user actions and system events in the
     * MVI pattern. Intents are processed asynchronously according to the configured
     * [HandleStrategy] (CONCURRENT, SEQUENTIAL, or HYBRID).
     *
     * ## Processing Behavior
     *
     * - Intent is enqueued into the dispatch entry queue (capacity configurable, default: 256)
     * - With the default [IntentQueueConfig] overflow policy, if the queue is full, the intent is
     *   discarded with a warning log and [DispatchResult.Full] is returned
     * - With conflated or dropping queue policies, [DispatchResult.Submitted] does not guarantee
     *   every individual intent will be processed; see [DispatchResult.Submitted]
     * - If the contract is no longer available (its coroutine scope has completed, which also
     *   closes the entry queue), the intent is discarded with a warning log and
     *   [DispatchResult.Unavailable] is returned
     *
     * ## Thread Safety
     *
     * This method is thread-safe and can be called from any thread. Intent processing and
     * state accumulation run off the caller thread on the framework processing dispatcher
     * ([kotlinx.coroutines.Dispatchers.Default] by default).
     *
     * ## Example
     *
     * ```kotlin
     * // Simple intent
     * contract.dispatch(MyIntent.Refresh)
     *
     * // Intent with data
     * contract.dispatch(MyIntent.LoadUser(userId = "123"))
     *
     * // From UI event
     * button.setOnClickListener {
     *     contract.dispatch(MyIntent.ButtonClicked)
     * }
     * ```
     *
     * @param intent The intent to dispatch
     * @return The enqueue result. It does not indicate whether intent handling has completed.
     * @see DispatchResult
     * @see Mvi.Intent
     * @see HandleStrategy
     * @see KMvi.Configuration
     */
    fun dispatch(intent: I): DispatchResult
}

/**
 * Converts a [ReactiveContract] to a read-only [Contract].
 *
 * This extension function provides an explicit way to expose only the observable
 * (read-only) interface of a ReactiveContract. It's useful when you want to:
 * - Prevent UI layer from dispatching intents directly
 * - Enforce separation of concerns (ViewModel dispatches, UI observes)
 * - Provide a cleaner API for UI components
 *
 * ## Usage Example
 *
 * ```kotlin
 * class MyViewModel : ViewModel() {
 *     private val reactiveContract: ReactiveContract<MyIntent, MyState, MyEvent> = ...
 *
 *     // Expose read-only contract to UI
 *     val contract: Contract<MyState, MyEvent> = reactiveContract.asContract()
 *
 *     // ViewModel can dispatch, UI cannot
 *     fun onUserAction() {
 *         reactiveContract.dispatch(MyIntent.SomeAction)
 *     }
 * }
 *
 * // In UI
 * viewModel.contract.stateFlow.collect { state -> updateUI(state) }
 * ```
 *
 * ## Implementation Details
 *
 * This function returns a wrapper that delegates [Contract.stateFlow] and [Contract.eventFlow] to the
 * underlying [ReactiveContract] but does not expose the [ReactiveContract.dispatch] method. Callers cannot
 * recover `dispatch` by casting the returned [Contract] reference.
 *
 * ## Performance Note
 *
 * A lightweight wrapper object is created, but it only delegates to the original contract's
 * flows without any additional overhead during collection.
 *
 * @param I The intent type
 * @param S The state type
 * @param E The event type
 * @return A read-only [Contract] wrapper that prevents access to [ReactiveContract.dispatch]
 * @see Contract
 * @see ReactiveContract
 */
fun <I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> ReactiveContract<I, S, E>.asContract(): Contract<S, E> {
    return ReadOnlyContract(this)
}

/**
 * A read-only wrapper around [ReactiveContract] that only exposes [Contract] interface.
 *
 * This private wrapper re-exposes only the [Contract] surface of the delegate.
 *
 * @param S The state type
 * @param E The event type
 * @param delegate The underlying [Contract] whose read-only surface is re-exposed
 */
private class ReadOnlyContract<S : Mvi.State, E : Mvi.Event>(delegate: Contract<S, E>) : Contract<S, E> by delegate
