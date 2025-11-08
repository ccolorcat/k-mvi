package cc.colorcat.mvi

import cc.colorcat.mvi.internal.TAG
import cc.colorcat.mvi.internal.logger
import cc.colorcat.mvi.internal.w
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
 * class MyFragment : Fragment() {
 *     private val contract: Contract<MyIntent, MyState, MyEvent> by viewModels()
 *
 *     override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
 *         // Observe state changes
 *         viewLifecycleOwner.lifecycleScope.launch {
 *             contract.stateFlow.collect { state ->
 *                 updateUI(state)
 *             }
 *         }
 *
 *         // Observe one-time events
 *         viewLifecycleOwner.lifecycleScope.launch {
 *             contract.eventFlow.collect { event ->
 *                 handleEvent(event)
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * ## State vs Event
 *
 * - **State** ([stateFlow]): Represents the current UI state. Always has a value.
 *   UI should render based on this state. State is retained and replayed to new collectors.
 *
 * - **Event** ([eventFlow]): Represents one-time side effects (toasts, navigation, etc.).
 *   Events are consumed once and not retained. No replay for new collectors.
 *
 * ## Relationship with ReactiveContract
 *
 * [Contract] is the read-only interface of [ReactiveContract]. If you need to dispatch
 * intents, use [ReactiveContract] instead.
 *
 * @param I The intent type
 * @param S The state type
 * @param E The event type
 * @see ReactiveContract
 * @see StateFlow
 * @see Flow
 *
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
interface Contract<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> {
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
     * - **Conflated**: Only the latest state is kept, intermediate states may be skipped
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
 * - **Output**: [stateFlow] and [eventFlow] to observe state and events
 *
 * This interface is typically used in the ViewModel layer where both intent dispatching
 * and state/event observation are needed.
 *
 * ## Typical Usage in ViewModel
 *
 * ```kotlin
 * class MyViewModel : ViewModel() {
 *     private val contract: ReactiveContract<MyIntent, MyState, MyEvent> = mviViewModel(
 *         scope = viewModelScope,
 *         initState = MyState(),
 *         defaultHandler = ::handleIntent
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
 * val readOnlyContract: Contract<I, S, E> = reactiveContract.asContract()
 * ```
 *
 * @param I The intent type
 * @param S The state type
 * @param E The event type
 * @see Contract
 * @see dispatch
 * @see asContract
 */
interface ReactiveContract<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> : Contract<I, S, E> {
    /**
     * Dispatches an intent to be processed.
     *
     * This method is the entry point for all user actions and system events in the
     * MVI pattern. Intents are processed asynchronously according to the configured
     * [HandleStrategy] (CONCURRENT, SEQUENTIAL, or HYBRID).
     *
     * ## Processing Behavior
     *
     * - Intent is added to an internal queue (buffer capacity: 64)
     * - If buffer is full, the dispatch is suspended until space is available
     * - If the coroutine scope is inactive, the intent is discarded with a warning log
     *
     * ## Thread Safety
     *
     * This method is thread-safe and can be called from any thread. The actual
     * processing happens on configured dispatchers (typically IO and Default).
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
     * @see Mvi.Intent
     * @see HandleStrategy
     */
    fun dispatch(intent: I)
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
 *     val contract: Contract<MyIntent, MyState, MyEvent> = reactiveContract.asContract()
 *
 *     // ViewModel can dispatch, UI cannot
 *     fun onUserAction() {
 *         reactiveContract.dispatch(MyIntent.SomeAction)
 *     }
 * }
 *
 * // In UI
 * viewModel.contract.stateFlow.collect { state -> updateUI(state) }
 * // viewModel.contract.dispatch(...) // ❌ Compile error - dispatch not available
 * ```
 *
 * ## Note
 *
 * Since [ReactiveContract] extends [Contract], this function simply returns `this`.
 * It serves as a semantic marker for read-only access rather than creating a wrapper.
 *
 * @param I The intent type
 * @param S The state type
 * @param E The event type
 * @return The same instance cast to [Contract]
 * @see Contract
 * @see ReactiveContract
 */
fun <I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> ReactiveContract<I, S, E>.asContract(): Contract<I, S, E> {
    return this
}


/**
 * Core implementation of [ReactiveContract] that handles the MVI flow processing.
 *
 * This class implements the fundamental MVI flow:
 * ```
 * Intent → Transformer → PartialChange → Snapshot → State/Event
 * ```
 *
 * ## Processing Pipeline
 *
 * 1. **Intent Collection**: Intents are received via [dispatch] and buffered in a [MutableSharedFlow]
 * 2. **Intent Transformation**: [IntentTransformer] converts intents to flows of [Mvi.PartialChange]
 * 3. **State Accumulation**: [scan] accumulates partial changes into [Mvi.Snapshot]
 * 4. **State/Event Extraction**: Snapshot is split into [stateFlow] and [eventFlow]
 *
 * ## Thread Dispatching Strategy
 *
 * The pipeline uses two dispatchers for optimal performance:
 * - **[Dispatchers.IO]**: For intent processing (may involve network/database operations)
 * - **[Dispatchers.Default]**: For state updates (CPU-bound operations)
 *
 * ## Buffer and Backpressure
 *
 * - **Intent buffer**: 64 items with [BufferOverflow.SUSPEND] (waits when full)
 * - **Snapshot buffer**: 64 items with [BufferOverflow.DROP_OLDEST] (drops old snapshots)
 *
 * ## Error Handling
 *
 * - Uses [retryWhen] with configurable [RetryPolicy]
 * - Allows automatic retry on recoverable errors
 * - Logs warnings when scope is inactive
 *
 * ## Lifecycle
 *
 * - Flow processing starts eagerly when the scope is active
 * - Stops automatically when the scope is cancelled
 * - State is retained across configuration changes (if scope survives)
 *
 * @param I The intent type
 * @param S The state type
 * @param E The event type
 * @param scope The coroutine scope for flow collection
 * @param initState The initial state
 * @param retryPolicy Policy for retrying on errors
 * @param transformer Transforms intents to partial changes
 * @see ReactiveContract
 * @see IntentTransformer
 * @see RetryPolicy
 */
internal open class CoreReactiveContract<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event>(
    private val scope: CoroutineScope,
    initState: S,
    retryPolicy: RetryPolicy,
    transformer: IntentTransformer<I, S, E>,
) : ReactiveContract<I, S, E> {
    /**
     * Internal flow for collecting dispatched intents.
     *
     * Configured with:
     * - Buffer capacity: 64 (reasonable for most use cases)
     * - Overflow strategy: SUSPEND (waits when buffer is full, avoiding intent loss)
     */
    private val intents = MutableSharedFlow<I>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    /**
     * Shared flow of state snapshots produced by processing intents.
     *
     * Processing pipeline:
     * 1. Transform intents to partial changes (with retry on failure)
     * 2. Execute transformation on IO dispatcher (for network/database operations)
     * 3. Accumulate changes into snapshots via [scan]
     * 4. Buffer snapshots (64 capacity, drop oldest on overflow)
     * 5. Execute state updates on Default dispatcher (for CPU-bound operations)
     * 6. Share among collectors (started eagerly, no replay)
     *
     * ## Retry Strategy
     *
     * [retryWhen] is positioned before [flowOn] to only retry the Intent processing
     * ([toPartialChange]). This ensures:
     * - Only the potentially failing operation (Intent handling) is retried
     * - State accumulation ([scan]) and buffering are not affected by retries
     * - Better performance (no unnecessary re-computation of state)
     */
    private val snapshots: SharedFlow<Mvi.Snapshot<S, E>> = intents.toPartialChange(transformer)
        .retryWhen { cause, attempt -> retryPolicy(attempt, cause) }
        .flowOn(Dispatchers.IO)
        .scan(Mvi.Snapshot<S, E>(initState)) { oldSnapshot, partialChange ->
            partialChange.apply(oldSnapshot)
        }
        .buffer(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.Eagerly, 0)

    /**
     * Extracts state from snapshots and converts to [StateFlow].
     *
     * Characteristics:
     * - Started eagerly to ensure timely state updates
     * - Replays the current state to new collectors
     * - Initial value is [initState]
     */
    override val stateFlow: StateFlow<S> = snapshots.map { it.state }
        .stateIn(scope, SharingStarted.Eagerly, initState)

    /**
     * Extracts non-null events from snapshots.
     *
     * Characteristics:
     * - Started lazily to save resources when no collector is active
     * - No replay (events are one-time side effects)
     * - Only emits when an event is present in the snapshot
     */
    override val eventFlow: Flow<E> = snapshots.mapNotNull { it.event }
        .shareIn(scope, SharingStarted.Lazily, 0)

    override fun dispatch(intent: I) {
        if (scope.isActive) {
            scope.launch { intents.emit(intent) }
        } else {
            // Only log class name to avoid exposing sensitive data in intent
            logger.w(TAG) { "Scope inactive, intent discarded: ${intent::class.simpleName}" }
        }
    }
}


/**
 * Strategy-based implementation of [ReactiveContract] with dynamic handler registration.
 *
 * This class extends [CoreReactiveContract] with:
 * - Support for [HandleStrategy] (CONCURRENT, SEQUENTIAL, HYBRID)
 * - Dynamic intent handler registration via [IntentHandlerRegistry]
 * - Fallback to a default handler for unregistered intent types
 *
 * ## Dual Constructor Pattern
 *
 * - **Private constructor**: Accepts a pre-created [IntentHandlerDelegate]
 * - **Public constructor**: Accepts a default handler, creates delegate internally
 *
 * This design allows flexible initialization while keeping internal details hidden.
 *
 * ## Handler Management
 *
 * Intent handlers can be dynamically registered/unregistered using [setupIntentHandlers]:
 *
 * ```kotlin
 * contract.setupIntentHandlers {
 *     register(LoadDataIntent::class.java) { intent ->
 *         // Handle LoadDataIntent
 *         Mvi.PartialChange { snapshot ->
 *             snapshot.updateState { copy(loading = true) }
 *         }
 *     }
 *
 *     register(RefreshIntent::class.java, IntentHandler { intent ->
 *         flow {
 *             // Handle RefreshIntent with multiple state changes
 *             emit(Mvi.PartialChange { ... })
 *             emit(Mvi.PartialChange { ... })
 *         }
 *     })
 * }
 * ```
 *
 * ## Processing Strategy
 *
 * Intents are processed according to the configured [HandleStrategy]:
 * - **CONCURRENT**: All intents in parallel
 * - **SEQUENTIAL**: All intents one-by-one
 * - **HYBRID**: Mixed (based on intent type and grouping)
 *
 * @param I The intent type
 * @param S The state type
 * @param E The event type
 * @see CoreReactiveContract
 * @see IntentHandlerRegistry
 * @see HandleStrategy
 */
internal class StrategyReactiveContract<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> private constructor(
    scope: CoroutineScope,
    initState: S,
    retryPolicy: RetryPolicy,
    strategy: HandleStrategy,
    config: HybridConfig<I>,
    private val delegate: IntentHandlerDelegate<I, S, E>,
) : CoreReactiveContract<I, S, E>(
    scope = scope,
    initState = initState,
    retryPolicy = retryPolicy,
    transformer = IntentTransformer(strategy, config, delegate)
) {
    /**
     * Public constructor that creates the delegate internally.
     *
     * @param scope The coroutine scope for flow collection
     * @param initState The initial state
     * @param retryPolicy Policy for retrying on errors
     * @param strategy The handling strategy (CONCURRENT/SEQUENTIAL/HYBRID)
     * @param config Configuration for HYBRID strategy
     * @param defaultHandler The fallback handler for unregistered intent types
     */
    constructor(
        scope: CoroutineScope,
        initState: S,
        retryPolicy: RetryPolicy,
        strategy: HandleStrategy,
        config: HybridConfig<I>,
        defaultHandler: IntentHandler<I, S, E>,
    ) : this(
        scope = scope,
        initState = initState,
        retryPolicy = retryPolicy,
        strategy = strategy,
        config = config,
        delegate = IntentHandlerDelegate(defaultHandler),
    )

    /**
     * Sets up intent handlers using a DSL-style configuration.
     *
     * This method provides a convenient way to register multiple handlers at once.
     * Handlers can be registered or unregistered dynamically at any time.
     *
     * ## Example
     *
     * ```kotlin
     * contract.setupIntentHandlers {
     *     // Register simple handler
     *     register(SimpleIntent::class.java) { intent ->
     *         Mvi.PartialChange { snapshot ->
     *             snapshot.updateState { copy(value = intent.value) }
     *         }
     *     }
     *
     *     // Register complex handler
     *     register(ComplexIntent::class.java, IntentHandler { intent ->
     *         flow {
     *             emit(Mvi.PartialChange { it.updateState { copy(loading = true) } })
     *             val result = doSomething(intent)
     *             emit(Mvi.PartialChange { it.updateState { copy(loading = false, data = result) } })
     *         }
     *     })
     *
     *     // Unregister if needed
     *     unregister(OldIntent::class.java)
     * }
     * ```
     *
     * @param setup A lambda with receiver that configures the intent handler registry
     * @see IntentHandlerRegistry
     * @see IntentHandlerRegistry.register
     * @see IntentHandlerRegistry.unregister
     */
    internal fun setupIntentHandlers(setup: IntentHandlerRegistry<I, S, E>.() -> Unit) {
        delegate.apply(setup)
    }
}
