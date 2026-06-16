package cc.colorcat.mvi.internal

import cc.colorcat.mvi.DispatchResult
import cc.colorcat.mvi.GroupTagSelector
import cc.colorcat.mvi.HandleStrategy
import cc.colorcat.mvi.HybridConfig
import cc.colorcat.mvi.IntentHandler
import cc.colorcat.mvi.IntentHandlerDelegate
import cc.colorcat.mvi.IntentHandlerRegistry
import cc.colorcat.mvi.IntentQueueConfig
import cc.colorcat.mvi.IntentTransformer
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.ReactiveContract
import cc.colorcat.mvi.RetryPolicy
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

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
 *     ↓ [Default coroutine] toPartialChange + retryWhen + scan
 * snapshot buffer       (fused     : 64, DROP_OLDEST) — flowOn(Default) + buffer fused into one
 *     ↓
 * shareIn (Eagerly, replay = 0)
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
 * **Warning**: Because snapshots carry events, DROP_OLDEST may silently drop events when
 * [eventFlow]'s downstream collector is slower than the state pipeline. For example, if
 * the UI thread is busy and the buffer is full, the oldest snapshot (with its event) is
 * dropped before the collector can read it. Ensure [eventFlow] collectors are lightweight
 * (no heavy computation, I/O, or blocking calls inside `collect`).
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
 * - Uses [retryWhen] with configurable [RetryPolicy]
 * - Allows automatic retry on recoverable errors in intent handlers
 * - Exceptions thrown by [Mvi.PartialChange.apply] are caught inside `scan`: the previous
 *   snapshot is retained and the pipeline continues (`CancellationException` is re-thrown)
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
 * @param intentQueueConfig The dispatch entry queue configuration
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
private const val SNAPSHOT_BUFFER_CAPACITY = 64

internal open class CoreReactiveContract<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event>(
    private val scope: CoroutineScope,
    initState: S,
    intentQueueConfig: IntentQueueConfig,
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
     * The channel is configured by [intentQueueConfig]. Special [Channel] constants retain their
     * native semantics through [IntentQueueConfig.capacity]:
     * - [Channel.RENDEZVOUS]: no entry buffer; [dispatch] only succeeds when the pipeline is ready
     * - [Channel.CONFLATED]: only the latest pending intent is retained
     * - [Channel.BUFFERED]: framework default buffered capacity
     * - [Channel.UNLIMITED]: unbounded entry queue; use with care
     *
     * The pipeline uses [receiveAsFlow] (not `consumeAsFlow`) so the channel is NOT closed when
     * [retryWhen] re-collects, allowing buffered intents to survive a retry. The channel is closed
     * when [scope] completes so late [dispatch] calls fail deterministically.
     */
    private val intentsChannel = Channel<I>(
        capacity = intentQueueConfig.capacity,
        onBufferOverflow = intentQueueConfig.onBufferOverflow,
    ).also { channel ->
        scope.coroutineContext[Job]?.invokeOnCompletion {
            channel.close()
        }
    }

    /**
     * Shared flow of state snapshots produced by processing intents.
     *
     * Processing pipeline:
     * 1. Receive intents from [intentsChannel] and transform to partial changes (with retry on failure)
     * 2. Execute transformation and handler flow collection on [Dispatchers.Default]
     * 3. Accumulate changes into snapshots via [scan] on [Dispatchers.Default]
     * 4. Buffer snapshots between Default computation and [shareIn] ([SNAPSHOT_BUFFER_CAPACITY]
     *    capacity, drop oldest on overflow — see class KDoc for rationale)
     * 5. Share among collectors (started eagerly, no replay)
     *
     * ## Retry Strategy
     *
     * [retryWhen] immediately follows [toPartialChange]. When an unhandled exception escapes
     * an intent handler, [retryWhen] restarts the pipeline subscription so that subsequent
     * intents can still be processed. The intent whose handler threw the exception is **not**
     * replayed — handlers should use try-catch internally for intent-level error recovery.
     * [scan] and all downstream operators are unaffected by the restart.
     *
     * Exceptions thrown inside [Mvi.PartialChange.apply] are caught within [scan]: the
     * previous snapshot is retained unchanged and the pipeline continues processing.
     * `CancellationException` is re-thrown so scope cancellation is unaffected.
     *
     * ## Operator Fusion
     *
     * Adjacent `flowOn` and `buffer` operators are merged by the framework (`ChannelFlow.fuse()`)
     * into a single snapshot buffer regardless of their relative order (see class KDoc). The current
     * order — `flowOn(Default)` then `buffer` — is chosen for readability (mirrors data-flow
     * direction), not because order affects correctness. No redundant intermediate channel is
     * created; DROP_OLDEST applies precisely at the boundary between Default coroutine and [shareIn].
     */
    private val snapshots: SharedFlow<Mvi.Snapshot<S, E>> = intentsChannel.receiveAsFlow()
        .toPartialChange(transformer)
        .retryWhen { cause, attempt -> retryPolicy(attempt, cause) }
        .scan(Mvi.Snapshot<S, E>(initState)) { oldSnapshot, partialChange ->
            try {
                partialChange.apply(oldSnapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.e(TAG, t) { "PartialChange.apply threw, snapshot unchanged" }
                oldSnapshot
            }
        }
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
     * Both are by design — events represent one-time side effects (navigation,
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
     * Update [intentQueueConfig] in [KMvi.setup] or per-contract if your app dispatches intents
     * faster than they can be consumed (e.g. rapid scroll events in a low-latency list).
     *
     * @param intent The user intent to process.
     */
    override fun dispatch(intent: I): DispatchResult {
        if (!scope.isActive) {
            logger.w(TAG) { "Scope inactive, intent discarded: ${intent.diagnosticName}" }
            return DispatchResult.Inactive
        }

        val result = intentsChannel.trySend(intent)
        if (result.isSuccess) {
            return DispatchResult.Submitted
        }
        return if (result.exceptionOrNull() != null) {
            logger.w(TAG) { "Intent queue closed, intent discarded: ${intent.diagnosticName}" }
            DispatchResult.Closed
        } else {
            logger.w(TAG) { "Intent queue full, intent discarded: ${intent.diagnosticName}" }
            DispatchResult.Full
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
    intentQueueConfig: IntentQueueConfig,
    retryPolicy: RetryPolicy,
    handleStrategy: HandleStrategy,
    hybridConfig: HybridConfig,
    groupTagSelector: GroupTagSelector<I>,
    private val delegate: IntentHandlerDelegate<I, S, E>,
) : CoreReactiveContract<I, S, E>(
    scope = scope,
    initState = initState,
    intentQueueConfig = intentQueueConfig,
    retryPolicy = retryPolicy,
    transformer = IntentTransformer(handleStrategy, hybridConfig, groupTagSelector, delegate),
) {
    /**
     * Public constructor that creates the delegate internally.
     *
     * @param scope The coroutine scope for flow collection
     * @param initState The initial state
     * @param intentQueueConfig The dispatch entry queue configuration
     * @param retryPolicy Policy for retrying on errors
     * @param handleStrategy The handling strategy (CONCURRENT/SEQUENTIAL/HYBRID)
     * @param hybridConfig Runtime configuration for HYBRID strategy
     * @param groupTagSelector Selects fallback group tags for HYBRID strategy
     * @param defaultHandler The fallback handler for unregistered intent types, or `null` for
     *                       no fallback. See [contract] for the resulting log behavior.
     */
    constructor(
        scope: CoroutineScope,
        initState: S,
        intentQueueConfig: IntentQueueConfig,
        retryPolicy: RetryPolicy,
        handleStrategy: HandleStrategy,
        hybridConfig: HybridConfig,
        groupTagSelector: GroupTagSelector<I> = GroupTagSelector.byClass(),
        defaultHandler: IntentHandler<I, S, E>?,
    ) : this(
        scope = scope,
        initState = initState,
        intentQueueConfig = intentQueueConfig,
        retryPolicy = retryPolicy,
        handleStrategy = handleStrategy,
        hybridConfig = hybridConfig,
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
