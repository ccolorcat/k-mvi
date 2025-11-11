package cc.colorcat.mvi.internal

import cc.colorcat.mvi.HandleStrategy
import cc.colorcat.mvi.HybridConfig
import cc.colorcat.mvi.IntentHandler
import cc.colorcat.mvi.IntentHandlerDelegate
import cc.colorcat.mvi.IntentHandlerRegistry
import cc.colorcat.mvi.IntentTransformer
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.ReactiveContract
import cc.colorcat.mvi.RetryPolicy
import cc.colorcat.mvi.toPartialChange
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
 *
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
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
        // IMPORTANT: retryWhen must be BEFORE flowOn to only retry intent handling,
        // not the entire downstream pipeline (scan, buffer, etc.)
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

