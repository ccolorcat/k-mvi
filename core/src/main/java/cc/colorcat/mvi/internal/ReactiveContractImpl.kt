package cc.colorcat.mvi.internal

import cc.colorcat.mvi.DispatchResult
import cc.colorcat.mvi.FatalErrorHandler
import cc.colorcat.mvi.GroupTagSelector
import cc.colorcat.mvi.HandleStrategy
import cc.colorcat.mvi.HybridStrategyConfig
import cc.colorcat.mvi.IntentHandler
import cc.colorcat.mvi.IntentHandlerDelegate
import cc.colorcat.mvi.IntentHandlerRegistry
import cc.colorcat.mvi.IntentHandlerScope
import cc.colorcat.mvi.IntentQueueConfig
import cc.colorcat.mvi.IntentTransformer
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.ReactiveContract
import cc.colorcat.mvi.RetryPolicy
import cc.colorcat.mvi.strategyTransformer
import cc.colorcat.mvi.toPartialChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

private const val SNAPSHOT_BUFFER_CAPACITY = 64

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
 * Intent transformation, handler flow collection, and state accumulation run on
 * [Dispatchers.Default]. This keeps the pipeline off the main thread without assuming every
 * intent handler is doing blocking I/O. Handlers that perform blocking network, database, or
 * file operations should isolate that work explicitly with `withContext(Dispatchers.IO)` or by
 * applying `flowOn(Dispatchers.IO)` to the blocking source flow.
 *
 * ## Buffer and Backpressure
 *
 * The pipeline contains two channel boundaries, each with a distinct role:
 *
 * ```
 * intentsChannel        (explicit  : intentQueueConfig) — dispatch entry queue
 *     ↓ [Default coroutine] toPartialChange + scan
 * snapshot buffer       (fused     : SNAPSHOT_BUFFER_CAPACITY, DROP_OLDEST)
 *     — flowOn(Default) + buffer fused into one
 *     ↓
 * shareIn (Lazily, replay = 0)
 * ```
 *
 * **intentsChannel** is the public dispatch mailbox. It is created from [intentQueueConfig],
 * preserving the native [Channel] semantics for special constants such as [Channel.RENDEZVOUS],
 * [Channel.CONFLATED], [Channel.BUFFERED], and [Channel.UNLIMITED].
 *
 * **snapshot buffer** is the result of operator fusion: adjacent `flowOn` and `buffer` operators
 * are merged by the framework (`ChannelFlow.fuse()`) into a single channel regardless of
 * their relative order. The current order (`flowOn` then `buffer`) is chosen for readability —
 * it mirrors the data flow direction and makes the intent clear, not because order is required
 * for correctness. The DROP_OLDEST policy is intentional — stale snapshots (including their
 * events) should be discarded rather than delivered late, keeping events timely and relevant.
 *
 * **Event delivery is best-effort**: Because snapshots carry events, DROP_OLDEST may silently drop
 * an event even when [eventFlow] has an active collector. This happens when producers outrun the
 * downstream pipeline long enough to fill the snapshot buffer, for example while the UI thread is
 * busy or an event collector is suspended. This is intentional: Event is intended for low-frequency,
 * time-sensitive UI effects, and dropping a stale effect is preferable to blocking state processing
 * or delivering a backlog after the UI recovers.
 *
 * Keep [eventFlow] collectors lightweight; do not perform blocking I/O or long-running work in
 * `collect`. Data or work that must not be lost belongs in persistent state with acknowledgement,
 * or in a durable queue if it must survive lifecycle gaps or process death.
 *
 * ## Intent Dispatching
 *
 * Intents arrive through [intentsChannel]. [dispatch] uses [Channel.trySend], so the call remains
 * non-blocking. With [BufferOverflow.SUSPEND], a full entry queue makes [dispatch] return
 * [DispatchResult.Full] and log a warning. With conflated or dropping policies,
 * [DispatchResult.Submitted] means the queue policy handled the submission, not that this exact
 * intent is guaranteed to be processed.
 *
 * ## Error Handling
 *
 * - The handler-based API wraps each returned handler Flow with a per-intent [RetryPolicy]
 *   inside its strategy transformer
 * - The low-level transformer API owns its retry behavior; this core pipeline does not wrap an
 *   arbitrary [IntentTransformer] in a retry operator
 * - Routes failures that escape the transformer to [FatalErrorHandler]
 * - Treats [Mvi.PartialChange.apply] failures as developer errors that fail the
 *   processing coroutine through [FatalErrorHandler]
 * - Treats a transformer that completes while the scope is still active as a fatal
 *   [IllegalStateException] (a terminating transformer would otherwise leave a zombie
 *   contract), routed through [FatalErrorHandler]
 * - Logs warnings when scope is inactive or the dispatch queue is full
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
 * @param scope The coroutine scope for flow collection; its context must contain a [Job]
 * @param initState The initial state
 * @param intentQueueConfig The dispatch entry queue configuration
 * @param transformer Transforms intents to partial changes
 * @see ReactiveContract
 * @see IntentTransformer
 * @see RetryPolicy
 */
internal open class CoreReactiveContract<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event>(
    private val scope: CoroutineScope,
    initState: S,
    intentQueueConfig: IntentQueueConfig,
    errorHandler: FatalErrorHandler,
    transformer: IntentTransformer<I, S, E>,
) : ReactiveContract<I, S, E> {
    private val scopeJob = requireNotNull(scope.coroutineContext[Job]) {
        "CoreReactiveContract scope must contain a Job."
    }

    /**
     * Channel for buffering dispatched intents before they enter the processing pipeline.
     *
     * A [Channel] is used instead of [kotlinx.coroutines.flow.MutableSharedFlow] because there is
     * exactly **one consumer** — the [snapshots] pipeline. A Channel is a lightweight FIFO queue
     * optimised for single-producer → single-consumer scenarios, with lower overhead than the
     * broadcast machinery of a SharedFlow.
     *
     * The channel is configured by [intentQueueConfig]. Special [Channel] constants retain their
     * native semantics through [IntentQueueConfig.capacity]:
     * - [Channel.RENDEZVOUS]: no entry buffer; [dispatch] only succeeds when the pipeline is ready
     * - [Channel.CONFLATED]: only the latest pending intent is retained
     * - [Channel.BUFFERED]: framework default buffered capacity
     * - [Channel.UNLIMITED]: unbounded entry queue; use with care
     *
     * The pipeline uses [receiveAsFlow] (not `consumeAsFlow`) so collecting the receiver view does
     * not transfer ownership of the channel to the Flow. The channel is closed when [scope]
     * completes or the pipeline fails fatally so late [dispatch] calls fail deterministically.
     */
    private val intentsChannel = Channel<I>(
        capacity = intentQueueConfig.capacity,
        onBufferOverflow = intentQueueConfig.onBufferOverflow,
    ).also { channel ->
        scopeJob.invokeOnCompletion {
            channel.close()
        }
    }

    /**
     * Shared flow of state snapshots produced by processing intents.
     *
     * Processing pipeline:
     * 1. Receive intents from [intentsChannel] and transform them to partial changes. The
     *    strategy transformer used by the handler API may retry each handler Flow independently.
     * 2. Execute transformation and handler flow collection on [Dispatchers.Default]
     * 3. Accumulate changes into snapshots via [scan] on [Dispatchers.Default]
     * 4. Buffer snapshots between Default computation and [shareIn] ([SNAPSHOT_BUFFER_CAPACITY]
     *    capacity, drop oldest on overflow — see class KDoc for rationale)
     * 5. Share among collectors (started lazily on the first subscriber, no replay)
     *
     * ## Retry Strategy
     *
     * This core pipeline does not attach `retryWhen` after [toPartialChange]. The strategy
     * transformer used by the handler-based API attaches it to each Flow returned by
     * [IntentHandler.handle], before that Flow enters the strategy merge. A retriable failure is
     * therefore scoped to one intent and re-collects the same returned Flow from the beginning.
     * Sibling intents remain active while that retry succeeds.
     *
     * [IntentHandler.handle] itself is called before the per-intent retry operator is attached, so
     * a synchronous exception thrown while constructing the Flow is not retried. Earlier
     * [Mvi.PartialChange] emissions from a failed attempt may already have reached [scan]; they are
     * not rolled back and may be emitted again when the handler Flow is re-collected.
     *
     * The low-level [IntentTransformer] API receives no automatic retry. An exception escaping a
     * custom transformer or a handler Flow after its policy gives up reaches the downstream
     * [catch] and is routed to [FatalErrorHandler].
     *
     * Exceptions thrown inside [Mvi.PartialChange.apply] are not handled by `retryWhen`
     * because [scan] is downstream of the retry boundary. The downstream [catch] routes
     * reducer failures to [FatalErrorHandler], which must terminate by throwing or otherwise
     * not returning. Recoverable failures should be encoded by handlers or transformers
     * before a [Mvi.PartialChange] is emitted.
     *
     * ## Operator Fusion
     *
     * Adjacent `flowOn` and `buffer` operators are merged by the framework (`ChannelFlow.fuse()`)
     * into a single snapshot buffer regardless of their relative order (see class KDoc). The current
     * order — `flowOn(Default)` then `buffer` — is chosen for readability (mirrors data-flow
     * direction), not because order affects correctness. No redundant intermediate channel is
     * created; DROP_OLDEST applies precisely at the boundary between Default coroutine and [shareIn].
     *
     * ## Startup Ordering
     *
     * Sharing is [SharingStarted.Lazily], not `Eagerly`: the upstream (intent consumption) begins
     * only when the first subscriber attaches. Because [stateFlow] is an eager, permanent subscriber
     * created during construction, it is guaranteed to be subscribed before the shared flow produces
     * any snapshot. This removes a dispatcher-dependent race in which this `replay = 0` shared flow
     * could emit the first snapshot before [stateFlow] had subscribed and thus drop the first state
     * (possible on multi-threaded contract scopes; never on the `Main.immediate` viewModelScope).
     * Intents dispatched before subscription simply wait buffered in [intentsChannel] and are
     * delivered once sharing starts.
     */
    private val snapshots: SharedFlow<Mvi.Snapshot<S, E>> = intentsChannel.receiveAsFlow()
        .toPartialChange(transformer)
        .scan(Mvi.Snapshot<S, E>(initState)) { oldSnapshot, partialChange ->
            partialChange.apply(oldSnapshot)
        }
        .onCompletion { cause ->
            // Flow completion is only valid when the contract scope is also ending. While the
            // scope remains active it violates the transformer lifetime contract, so convert it
            // to a fatal failure; the downstream catch closes the entry queue before reporting it.
            if (cause == null && scopeJob.isActive) {
                throw IllegalStateException(
                    "IntentTransformer completed while the contract scope is still active. " +
                        "A transformer must keep its PartialChange flow open for the contract " +
                        "lifetime; cancel the scope to shut the contract down instead.",
                )
            }
        }
        .catch { cause ->
            if (cause is CancellationException && !scopeJob.isActive) throw cause

            intentsChannel.cancel()
            logger.e(TAG, cause) { "MVI pipeline failed." }
            errorHandler.handle(cause)
        }
        .flowOn(Dispatchers.Default)
        .buffer(capacity = SNAPSHOT_BUFFER_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        .shareIn(scope, SharingStarted.Lazily, 0)

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
     * - [SharingStarted.WhileSubscribed] with a 5 s stop timeout: avoids restarting
     *   the upstream subscription during brief collector absences (configuration
     *   changes, Fragment back-stack transitions)
     * - No replay (events are one-time side effects)
     * - Only emits when an event is present in the snapshot
     *
     * ## ⚠️ Important: Collector Must Be Active Before Dispatch
     *
     * Unlike [stateFlow] (which is persistent and always holds the latest state),
     * events are **fire-and-forget**: they are emitted only to currently active
     * collectors. An event may be permanently lost in two scenarios:
     *
     * 1. **No subscriber**: no collector is subscribed at the moment the event is
     *    produced — the event is never delivered and will never be replayed.
     * 2. **Pipeline congestion**: when downstream collectors are slower than
     *    producers, the snapshot buffer ([SNAPSHOT_BUFFER_CAPACITY], DROP_OLDEST)
     *    discards the oldest snapshots, including their events.
     *
     * Both are by design. Event is a best-effort transport for low-frequency, time-sensitive UI
     * effects (navigation, toasts, dialogs), not a reliable command queue. Dropping a stale event
     * avoids blocking state processing and avoids replaying a backlog after the UI recovers.
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
     *
     * Keep the collector lightweight. If an outcome must not be lost, encode it in [stateFlow] with
     * explicit acknowledgement, or use a durable queue when it must survive lifecycle gaps or process
     * death.
     */
    override val eventFlow: Flow<E> = snapshots.mapNotNull { it.event }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), 0)

    /**
     * Dispatches an [intent] for processing.
     *
     * The call is non-blocking: the intent is enqueued into [intentsChannel], which guarantees
     * FIFO ordering for non-conflated capacities.
     *
     * **Buffer overflow**: [intentsChannel] uses [intentQueueConfig]. For bounded queues using
     * [BufferOverflow.SUSPEND], when the queue is full, the intent is discarded, a warning is
     * logged, and [DispatchResult.Full] is returned. With conflated or dropping policies,
     * [DispatchResult.Submitted] only means the configured queue policy handled the submission.
     * Update [intentQueueConfig] in [KMvi.configure] or per-contract if your app dispatches intents
     * faster than they can be consumed (e.g. rapid scroll events in a low-latency list).
     *
     * @param intent The user intent to process.
     */
    override fun dispatch(intent: I): DispatchResult {
        if (!scopeJob.isActive) {
            logger.w(TAG) { "Contract unavailable, intent discarded: ${intent.diagnosticName}" }
            return DispatchResult.Unavailable
        }

        val result = intentsChannel.trySend(intent)
        return when {
            result.isSuccess -> DispatchResult.Submitted
            !scopeJob.isActive || result.isClosed -> {
                logger.w(TAG, result.exceptionOrNull()) {
                    "Contract unavailable, intent discarded: ${intent.diagnosticName}"
                }
                DispatchResult.Unavailable
            }

            else -> {
                logger.w(TAG) { "Intent queue full, intent discarded: ${intent.diagnosticName}" }
                DispatchResult.Full
            }
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
 *     // Single PartialChange — concise reified form
 *     register<LoadDataIntent> { intent ->
 *         Mvi.PartialChange { snapshot ->
 *             snapshot.updateState { copy(loading = true) }
 *         }
 *     }
 *
 *     // Flow of changes — reified form, SAM-converted to IntentHandler
 *     register<RefreshIntent> { intent ->
 *         flow {
 *             emit(Mvi.PartialChange { ... })
 *             emit(Mvi.PartialChange { ... })
 *         }
 *     }
 * }
 * ```
 *
 * ## Processing Strategy
 *
 * Intents are processed according to the configured [HandleStrategy]:
 * - **CONCURRENT**: Bounded concurrency, up to `flatMapMerge`'s default limit
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
    intentQueueConfig: IntentQueueConfig,
    retryPolicy: RetryPolicy<I>,
    errorHandler: FatalErrorHandler,
    handleStrategy: HandleStrategy,
    hybridStrategyConfig: HybridStrategyConfig,
    groupTagSelector: GroupTagSelector<I>,
    private val delegate: IntentHandlerDelegate<I, S, E>,
) : CoreReactiveContract<I, S, E>(
    scope = scope,
    initState = initState,
    intentQueueConfig = intentQueueConfig,
    errorHandler = errorHandler,
    transformer = strategyTransformer(handleStrategy, hybridStrategyConfig, groupTagSelector, delegate, retryPolicy),
) {
    /**
     * Public constructor that creates the delegate internally.
     *
     * @param scope The coroutine scope for flow collection
     * @param initState The initial state
     * @param intentQueueConfig The dispatch entry queue configuration
     * @param retryPolicy Per-intent policy for handler Flow collection failures before reducer application
     * @param handleStrategy The handling strategy (CONCURRENT/SEQUENTIAL/HYBRID)
     * @param hybridStrategyConfig Runtime configuration for HYBRID strategy
     * @param groupTagSelector Selects fallback group tags for HYBRID strategy
     * @param defaultHandler The fallback handler for unregistered intent types, or `null` for
     *                       no fallback. See [contract] for the resulting log behavior.
     */
    constructor(
        scope: CoroutineScope,
        initState: S,
        intentQueueConfig: IntentQueueConfig,
        retryPolicy: RetryPolicy<I>,
        errorHandler: FatalErrorHandler,
        handleStrategy: HandleStrategy,
        hybridStrategyConfig: HybridStrategyConfig,
        groupTagSelector: GroupTagSelector<I> = GroupTagSelector.byClass(),
        defaultHandler: IntentHandler<I, S, E>?,
    ) : this(
        scope = scope,
        initState = initState,
        intentQueueConfig = intentQueueConfig,
        retryPolicy = retryPolicy,
        errorHandler = errorHandler,
        handleStrategy = handleStrategy,
        hybridStrategyConfig = hybridStrategyConfig,
        groupTagSelector = groupTagSelector,
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
     *     register<SimpleIntent> { intent ->
     *         Mvi.PartialChange { snapshot ->
     *             snapshot.updateState { copy(value = intent.value) }
     *         }
     *     }
     *
     *     // Register complex handler
     *     register<ComplexIntent> { intent ->
     *         flow {
     *             emit(Mvi.PartialChange { it.updateState { copy(loading = true) } })
     *             val result = doSomething(intent)
     *             emit(Mvi.PartialChange { it.updateState { copy(loading = false, data = result) } })
     *         }
     *     }
     *
     *     // Unregister if needed
     *     unregister<OldIntent>()
     * }
     * ```
     *
     * @param setup A lambda with [IntentHandlerScope] receiver that configures the intent handlers
     * @see IntentHandlerScope
     * @see IntentHandlerScope.register
     * @see IntentHandlerScope.unregister
     */
    internal fun setupIntentHandlers(setup: IntentHandlerScope<I, S, E>.() -> Unit) {
        IntentHandlerScope(delegate).apply(setup)
    }
}
