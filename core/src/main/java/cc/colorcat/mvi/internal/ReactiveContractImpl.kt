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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
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
 * 1. **Intent Collection**: Intents are received via `dispatch` and buffered in a [Channel]
 * 2. **Intent Transformation**: [IntentTransformer] converts intents to flows of [Mvi.PartialChange]
 * 3. **State Accumulation**: [scan] accumulates partial changes into [Mvi.Snapshot]
 * 4. **State/Event Extraction**: Snapshot is split into `stateFlow` and `eventFlow`
 *
 * ## Thread Dispatching Strategy
 *
 * The pipeline uses two dispatchers for optimal performance:
 * - **[Dispatchers.IO]**: For intent processing (may involve network/database operations)
 * - **[Dispatchers.Default]**: For state updates (CPU-bound operations)
 *
 * ## Buffer and Backpressure
 *
 * The pipeline contains four channel boundaries, each with a distinct role:
 *
 * ```
 * intentsChannel        (explicit  : 64, SUSPEND)     — preserves every intent
 *     ↓ [IO coroutine]  toPartialChange + retryWhen
 * Channel A             (implicit  : 64, SUSPEND)     — created by flowOn(IO); back-pressures IO
 *     ↓ [Default coroutine] scan
 * Channel B             (fused     : 64, DROP_OLDEST) — flowOn(Default) + buffer fused into one
 *     ↓
 * shareIn (Eagerly, replay = 0)
 * ```
 *
 * **Channel A** is created implicitly by `flowOn(Dispatchers.IO)` with the framework default
 * capacity (64, SUSPEND). It is not configured explicitly because PartialChanges must never
 * be silently dropped: if `scan` is momentarily busy, IO coroutines suspend rather than lose work.
 *
 * **Channel B** is the result of operator fusion: adjacent `flowOn` and `buffer` operators
 * are merged by the framework (`ChannelFlow.fuse()`) into a single channel regardless of
 * their relative order. The current order (`flowOn` then `buffer`) is chosen for readability —
 * it mirrors the data flow direction and makes the intent clear, not because order is required
 * for correctness. The DROP_OLDEST policy is intentional — stale snapshots (including their
 * events) should be discarded rather than delivered late, keeping events timely and relevant.
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
private const val INTENT_BUFFER_CAPACITY = 64
private const val SNAPSHOT_BUFFER_CAPACITY = 64

internal open class CoreReactiveContract<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event>(
    private val scope: CoroutineScope,
    initState: S,
    retryPolicy: RetryPolicy,
    transformer: IntentTransformer<I, S, E>,
) : ReactiveContract<I, S, E> {
    /**
     * Channel for buffering dispatched intents before they enter the processing pipeline.
     *
     * A [Channel] is used instead of [kotlinx.coroutines.flow.MutableSharedFlow] because there is
     * exactly **one consumer** — the [snapshots] pipeline. A Channel is a lightweight FIFO queue
     * optimised for single-producer → single-consumer scenarios, with lower overhead than the
     * broadcast machinery of a SharedFlow.
     *
     * Configured with:
     * - Capacity: [INTENT_BUFFER_CAPACITY] items
     * - Overflow strategy: [BufferOverflow.SUSPEND] — suspends the producer when full,
     *   guaranteeing no intent is silently dropped
     *
     * The channel is closed automatically via [Job.invokeOnCompletion] when the coroutine scope
     * ends, signalling the downstream pipeline to terminate and releasing buffered items.
     *
     * **Note**: the pipeline uses [receiveAsFlow] (not `consumeAsFlow`) so the channel is NOT
     * closed when [retryWhen] re-collects, allowing buffered intents to survive a retry.
     */
    private val intentsChannel = Channel<I>(
        capacity = INTENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    ).also { channel ->
        scope.coroutineContext[Job]?.invokeOnCompletion { channel.close() }
    }

    /**
     * Shared flow of state snapshots produced by processing intents.
     *
     * Processing pipeline:
     * 1. Receive intents from [intentsChannel] and transform to partial changes (with retry on failure)
     * 2. Execute transformation on IO dispatcher (for network/database operations)
     * 3. Accumulate changes into snapshots via [scan]
     * 4. Execute state accumulation on Default dispatcher (CPU-bound operations)
     * 5. Buffer snapshots between Default computation and [shareIn] ([SNAPSHOT_BUFFER_CAPACITY]
     *    capacity, drop oldest on overflow — see class KDoc for rationale)
     * 6. Share among collectors (started eagerly, no replay)
     *
     * ## Retry Strategy
     *
     * [retryWhen] immediately follows [toPartialChange] so that retries are scoped exclusively
     * to Intent handling. When a retry occurs, the flow restarts from the Intent→PartialChange
     * conversion; [scan] and all downstream operators are unaffected.
     *
     * ## Operator Fusion
     *
     * Adjacent `flowOn` and `buffer` operators are merged by the framework (`ChannelFlow.fuse()`)
     * into a **single** Channel B regardless of their relative order (see class KDoc). The current
     * order — `flowOn(Default)` then `buffer` — is chosen for readability (mirrors data-flow
     * direction), not because order affects correctness. No redundant intermediate channel is
     * created; DROP_OLDEST applies precisely at the boundary between Default coroutine and [shareIn].
     */
    private val snapshots: SharedFlow<Mvi.Snapshot<S, E>> = intentsChannel.receiveAsFlow()
        .toPartialChange(transformer)
        // retryWhen immediately follows toPartialChange: retries are scoped to Intent handling
        // only. Channel A (64, SUSPEND) is created implicitly by flowOn(IO).
        .retryWhen { cause, attempt -> retryPolicy(attempt, cause) }
        .flowOn(Dispatchers.IO)
        .scan(Mvi.Snapshot<S, E>(initState)) { oldSnapshot, partialChange ->
            partialChange.apply(oldSnapshot)
        }
        // flowOn(Default) + buffer fuse into single Channel B (64, DROP_OLDEST) via ChannelFlow.fuse().
        // Order (flowOn before buffer) is a readability choice; both orderings produce the same result.
        .flowOn(Dispatchers.Default)
        .buffer(capacity = SNAPSHOT_BUFFER_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
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
     * ## Characteristics
     * - Started lazily to save resources when no collector is active
     * - No replay (events are one-time side effects)
     * - Only emits when an event is present in the snapshot
     *
     * ## ⚠️ Important: Collector Must Be Active Before Dispatch
     *
     * Unlike [stateFlow] (which is persistent and always holds the latest state),
     * events are **fire-and-forget**: they are emitted only to currently active
     * collectors. If no collector is subscribed at the moment an event is produced,
     * that event is **permanently lost** and will never be replayed.
     *
     * This is by design — events represent one-time side effects (navigation,
     * toasts, dialogs) that should not be re-delivered after the fact.
     *
     * **Correct pattern**: subscribe to `eventFlow` before any `dispatch()` call
     * that may produce events (e.g., in `onViewCreated`, before any initial intent).
     *
     * ```kotlin
     * // ✅ Subscribe BEFORE dispatching any intent that may emit events
     * viewModel.eventFlow.collectEvent(viewLifecycleOwner) { ... }
     * viewModel.dispatch(MyIntent.Initialize)
     *
     * // ❌ Events from Initialize may be lost if subscribed too late
     * viewModel.dispatch(MyIntent.Initialize)
     * viewModel.eventFlow.collectEvent(viewLifecycleOwner) { ... }
     * ```
     */
    override val eventFlow: Flow<E> = snapshots.mapNotNull { it.event }
        .shareIn(scope, SharingStarted.Lazily, 0)

    override fun dispatch(intent: I) {
        if (!scope.isActive) {
            logger.w(TAG) { "Scope inactive, intent discarded: ${intent.diagnosticName}" }
            return
        }

        val result = intentsChannel.trySend(intent)
        if (result.isSuccess) return

        if (result.isClosed) {
            logger.w(TAG) { "intentsChannel closed, intent discarded: ${intent.diagnosticName}" }
            return
        }

        scope.launch {
            try {
                intentsChannel.send(intent)
            } catch (_: ClosedSendChannelException) {
                logger.w(TAG) { "Failed to send, channel closed: ${intent.diagnosticName}" }
            }
            // CancellationException is intentionally not caught: it propagates normally,
            // allowing the coroutine to honour structured cancellation.
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

