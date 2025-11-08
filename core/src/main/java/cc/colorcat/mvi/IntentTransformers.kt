package cc.colorcat.mvi

import cc.colorcat.mvi.internal.TAG
import cc.colorcat.mvi.internal.groupHandle
import cc.colorcat.mvi.internal.i
import cc.colorcat.mvi.internal.isConcurrent
import cc.colorcat.mvi.internal.isFallback
import cc.colorcat.mvi.internal.isSequential
import cc.colorcat.mvi.internal.logger
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flattenMerge

/**
 * Transforms a flow of intents into a flow of partial state changes.
 *
 * IntentTransformer is a critical component in the MVI architecture that bridges
 * intents and state changes. It applies a [HandleStrategy] to determine how intents
 * are processed (concurrently, sequentially, or in a hybrid manner) before being
 * passed to intent handlers.
 *
 * ## Architecture Flow
 *
 * ```
 * User Action → Intent → IntentTransformer → IntentHandler → PartialChange → State
 *                             ↓
 *                       Apply Strategy
 *                    (CONCURRENT/SEQUENTIAL/HYBRID)
 * ```
 *
 * ## Key Responsibilities
 *
 * 1. **Strategy Application**: Applies the configured [HandleStrategy] to the intent flow
 * 2. **Flow Transformation**: Converts `Flow<Intent>` to `Flow<PartialChange>`
 * 3. **Concurrency Control**: Manages how intents are processed based on their type
 * 4. **Handler Delegation**: Delegates actual intent handling to [IntentHandler]
 *
 * ## Strategy Implementation
 *
 * ### CONCURRENT Strategy
 * ```kotlin
 * intentFlow.flatMapMerge { handler.handle(it) }
 * ```
 * All intents are processed in parallel.
 *
 * ### SEQUENTIAL Strategy
 * ```kotlin
 * intentFlow.flatMapConcat { handler.handle(it) }
 * ```
 * All intents are processed one-by-one in order.
 *
 * ### HYBRID Strategy
 * ```kotlin
 * intentFlow.groupByType().flattenMerge()
 * ```
 * Intents are grouped by type, with sequential processing within groups
 * and parallel processing between groups.
 *
 * ## Usage Example
 *
 * ### Basic Usage (typically done by framework)
 * ```kotlin
 * val transformer = IntentTransformer<MyIntent, MyState, MyEvent>(
 *     strategy = HandleStrategy.HYBRID,
 *     config = HybridConfig(),
 *     handler = myIntentHandler
 * )
 *
 * intentFlow
 *     .toPartialChange(transformer)
 *     .collect { partialChange ->
 *         // Apply partial change to state
 *     }
 * ```
 *
 * ### Custom Transformer
 * ```kotlin
 * class LoggingIntentTransformer<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event>(
 *     private val delegate: IntentTransformer<I, S, E>
 * ) : IntentTransformer<I, S, E> {
 *     override fun transform(intentFlow: Flow<I>): Flow<Mvi.PartialChange<S, E>> {
 *         return delegate.transform(
 *             intentFlow.onEach { intent ->
 *                 println("Processing intent: $intent")
 *             }
 *         )
 *     }
 * }
 * ```
 *
 * ## Performance Considerations
 *
 * - **CONCURRENT**: Best performance, but may have race conditions
 * - **SEQUENTIAL**: Safest, but may block on long-running intents
 * - **HYBRID**: Balanced approach, recommended for most applications
 *
 * ## Thread Safety
 *
 * The transformer itself is stateless and thread-safe. Concurrency control
 * is handled by the underlying Flow operators (flatMapMerge/flatMapConcat).
 *
 * @param I The intent type
 * @param S The state type
 * @param E The event type
 * @see HandleStrategy
 * @see IntentHandler
 * @see HybridConfig
 * @see toPartialChange
 *
 * Author: ccolorcat
 * Date: 2024-12-24
 * GitHub: https://github.com/ccolorcat
 */
fun interface IntentTransformer<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> {
    /**
     * Transforms a flow of intents into a flow of partial state changes.
     *
     * This method applies the configured strategy to process intents and delegates
     * to the intent handler to produce state changes.
     *
     * @param intentFlow The flow of intents to transform
     * @return A flow of partial state changes to be applied to the current state
     */
    fun transform(intentFlow: Flow<I>): Flow<Mvi.PartialChange<S, E>>

    companion object {
        /**
         * Creates an IntentTransformer with the specified strategy and configuration.
         *
         * This is an internal factory method used by the framework to create
         * strategy-based transformers.
         *
         * @param strategy The handling strategy to apply
         * @param config Configuration for HYBRID strategy
         * @param handler The intent handler to delegate to
         * @return An IntentTransformer that applies the specified strategy
         */
        internal operator fun <I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> invoke(
            strategy: HandleStrategy,
            config: HybridConfig<I>,
            handler: IntentHandler<I, S, E>,
        ): IntentTransformer<I, S, E> {
            return StrategyIntentTransformer(strategy, config, handler)
        }
    }
}


/**
 * Extension function to transform a flow of intents into a flow of partial state changes.
 *
 * This is a convenience extension that delegates to [IntentTransformer.transform].
 * It provides a more fluent API for applying transformers to intent flows.
 *
 * ## Usage Example
 *
 * ```kotlin
 * intentFlow
 *     .toPartialChange(transformer)
 *     .collect { partialChange ->
 *         // Apply to state
 *     }
 * ```
 *
 * @param I The intent type
 * @param S The state type
 * @param E The event type
 * @param transformer The transformer to apply
 * @return A flow of partial state changes
 * @see IntentTransformer
 */
fun <I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> Flow<I>.toPartialChange(
    transformer: IntentTransformer<I, S, E>
): Flow<Mvi.PartialChange<S, E>> = transformer.transform(this)


// Group tag constants for HYBRID strategy
private const val TAG_CONCURRENT = "CONCURRENT"
private const val TAG_SEQUENTIAL = "SEQUENTIAL"
private const val TAG_PREFIX_FALLBACK = "FALLBACK"

/**
 * Default implementation of [IntentTransformer] that applies a [HandleStrategy].
 *
 * This internal class is the main implementation that handles the three processing
 * strategies: CONCURRENT, SEQUENTIAL, and HYBRID. It orchestrates how intents are
 * processed based on the configured strategy and delegates actual intent handling
 * to the [IntentHandler].
 *
 * ## Implementation Details
 *
 * ### CONCURRENT Strategy
 * - Uses `Flow.flatMapMerge` to process all intents in parallel
 * - No grouping or ordering guarantees
 * - Maximum throughput
 *
 * ### SEQUENTIAL Strategy
 * - Uses `Flow.flatMapConcat` to process intents one-by-one
 * - Strict FIFO ordering
 * - Each intent waits for the previous to complete
 *
 * ### HYBRID Strategy (Most Complex)
 * 1. **Group intents by type**:
 *    - [Mvi.Intent.Concurrent] → CONCURRENT group (parallel processing)
 *    - [Mvi.Intent.Sequential] → SEQUENTIAL group (sequential processing)
 *    - Fallback intents → Custom groups based on [HybridConfig.groupTagSelector]
 * 2. **Process groups**:
 *    - Within each group: Sequential processing (`flatMapConcat`)
 *    - Between groups: Parallel processing (`flattenMerge`)
 *
 * ## Hybrid Strategy Flow Diagram
 *
 * ```
 * Intent Flow
 *     ↓
 * assignGroupTag()  ← Classify each intent
 *     ↓
 * groupHandle()     ← Group by tag
 *     ↓
 * ┌─────────────────┬─────────────────┬──────────────────┐
 * │ CONCURRENT      │ SEQUENTIAL      │ FALLBACK_xxx     │
 * │ (flatMapMerge)  │ (flatMapConcat) │ (flatMapConcat)  │
 * └─────────────────┴─────────────────┴──────────────────┘
 *     ↓                   ↓                   ↓
 * flattenMerge()  ← Merge all groups in parallel
 *     ↓
 * PartialChange Flow
 * ```
 *
 * ## Logging
 *
 * Logs the strategy being applied at the start of transformation:
 * - CONCURRENT/SEQUENTIAL: Logs strategy name only
 * - HYBRID: Logs strategy and config details (useful for debugging grouping)
 *
 * @param I The intent type
 * @param S The state type
 * @param E The event type
 * @param strategy The handling strategy to apply
 * @param config Configuration for HYBRID strategy (unused for other strategies)
 * @param handler The intent handler that processes individual intents
 * @see HandleStrategy
 * @see HybridConfig
 * @see IntentHandler
 */
internal class StrategyIntentTransformer<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event>(
    private val strategy: HandleStrategy,
    private val config: HybridConfig<I>,
    private val handler: IntentHandler<I, S, E>,
) : IntentTransformer<I, S, E> {

    override fun transform(intentFlow: Flow<I>): Flow<Mvi.PartialChange<S, E>> {
        logger.i(TAG) {
            if (strategy == HandleStrategy.HYBRID) {
                "Transforming intents using strategy: strategy = $strategy, config = $config"
            } else {
                "Transforming intents using strategy: $strategy"
            }
        }
        @OptIn(FlowPreview::class)
        return when (strategy) {
            HandleStrategy.CONCURRENT -> intentFlow.flatMapMerge { handler.handle(it) }
            HandleStrategy.SEQUENTIAL -> intentFlow.flatMapConcat { handler.handle(it) }
            HandleStrategy.HYBRID -> intentFlow.hybrid().flattenMerge()
        }
    }

    /**
     * Applies HYBRID strategy by grouping intents and processing each group.
     *
     * This method:
     * 1. Groups intents by tag (assigned by [assignGroupTag])
     * 2. Processes each group according to its tag (via [handleByTag])
     * 3. Returns a flow of flows (outer flow = groups, inner flow = changes)
     *
     * The result is then flattened by `flattenMerge()` to merge all groups in parallel.
     *
     * @return A flow of flows, where each inner flow represents a group's partial changes
     */
    private fun Flow<I>.hybrid(): Flow<Flow<Mvi.PartialChange<S, E>>> {
        return groupHandle(config.groupChannelCapacity, ::assignGroupTag) { handleByTag(it) }
    }

    /**
     * Processes a flow of intents within a group based on the group's tag.
     *
     * - **CONCURRENT group**: Uses `flatMapMerge` for parallel processing
     * - **Other groups** (SEQUENTIAL, FALLBACK_*): Uses `flatMapConcat` for sequential processing
     *
     * This differentiation allows concurrent intents to be processed in parallel
     * while maintaining sequential order for other intent types within their groups.
     *
     * @param tag The group tag assigned by [assignGroupTag]
     * @return A flow of partial changes for this group
     */
    @OptIn(FlowPreview::class)
    private fun Flow<I>.handleByTag(tag: String): Flow<Mvi.PartialChange<S, E>> {
        return if (tag == TAG_CONCURRENT) {
            flatMapMerge { handler.handle(it) }
        } else {
            flatMapConcat { handler.handle(it) }
        }
    }

    /**
     * Assigns a group tag to an intent based on its type.
     *
     * The tag determines how the intent will be processed in HYBRID strategy:
     *
     * - **Concurrent Intent** ([Mvi.Intent.Concurrent]):
     *   - Tag: [TAG_CONCURRENT]
     *   - Processing: Parallel with all other concurrent intents
     *
     * - **Sequential Intent** ([Mvi.Intent.Sequential]):
     *   - Tag: [TAG_SEQUENTIAL]
     *   - Processing: Sequential in a single global queue
     *
     * - **Fallback Intent** (neither Concurrent nor Sequential):
     *   - Tag: [TAG_PREFIX_FALLBACK] + result of [HybridConfig.groupTagSelector]
     *   - Processing: Sequential within the same tag group, parallel with other groups
     *
     * ## Example Tags
     * ```
     * ClickButton (Concurrent)         → "CONCURRENT"
     * LoadUser (Sequential)            → "SEQUENTIAL"
     * LoadData("posts") (Fallback)     → "FALLBACK_posts"
     * LoadData("users") (Fallback)     → "FALLBACK_users"
     * ```
     *
     * @param intent The intent to classify
     * @return The group tag for this intent
     * @throws AssertionError if the intent doesn't match any known type (should never happen)
     */
    private fun assignGroupTag(intent: I): String {
        return when {
            intent.isFallback -> TAG_PREFIX_FALLBACK + config.groupTagSelector(intent)
            intent.isConcurrent -> TAG_CONCURRENT
            intent.isSequential -> TAG_SEQUENTIAL
            else -> throw AssertionError("Unexpected Intent type reached in assignGroupTag: $intent")
        }
    }
}


//internal class StrategyIntentTransformer2<I : MVI.Intent, S : MVI.State, E : MVI.Event>(
//    private val scope: CoroutineScope,
//    private val strategy: HandleStrategy,
//    private val config: HybridConfig<I>,
//    private val handler: IntentHandler<I, S, E>,
//) : IntentTransformer<I, S, E> {
//
//    override fun transform(intentFlow: Flow<I>): Flow<MVI.PartialChange<S, E>> {
//        val flow = intentFlow.shareIn(scope, SharingStarted.Eagerly)
//        @OptIn(FlowPreview::class)
//        return when (strategy) {
//            HandleStrategy.CONCURRENT -> flow.flatMapMerge { handler.handle(it) }
//            HandleStrategy.SEQUENTIAL -> flow.flatMapConcat { handler.handle(it) }
//            HandleStrategy.HYBRID -> merge(
//                flow.filter { it.isConcurrent }.flatMapMerge { handler.handle(it) },
//                flow.filter { it.isSequential }.flatMapConcat { handler.handle(it) },
//                flow.filter { it.isFallback }.segment().flatMapMerge { it.flatMapConcat { i -> handler.handle(i) } },
//            )
//        }
//    }
//
//    private fun Flow<I>.segment(): Flow<Flow<I>> {
//        return groupHandle(config.groupChannelCapacity, config.groupTagSelector) { this }
//    }
//}
