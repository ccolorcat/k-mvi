@file:OptIn(FlowPreview::class)

package cc.colorcat.mvi

import cc.colorcat.mvi.internal.TAG
import cc.colorcat.mvi.internal.diagnosticName
import cc.colorcat.mvi.internal.groupHandle
import cc.colorcat.mvi.internal.i
import cc.colorcat.mvi.internal.logger
import cc.colorcat.mvi.internal.w
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flattenMerge
import java.util.concurrent.ConcurrentHashMap

/**
 * Transforms a flow of intents into a flow of partial state changes.
 *
 * `IntentTransformer` is the low-level extension point between dispatched intents
 * and [Mvi.PartialChange] emissions. Custom implementations can intercept, merge,
 * throttle, split, or otherwise transform the incoming intent stream before state
 * accumulation.
 *
 * The framework's default implementation applies a [HandleStrategy] and delegates
 * each intent to an [IntentHandler]. See [HandleStrategy] for the authoritative
 * semantics of CONCURRENT / SEQUENTIAL / HYBRID processing.
 *
 * ## Custom Transformer Example
 *
 * ```kotlin
 * class LoggingIntentTransformer<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event>(
 *     private val delegate: IntentTransformer<I, S, E>,
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
 * Implementations should be stateless or otherwise safe for the coroutine context
 * in which their returned flow is collected. Concurrency semantics are determined
 * by the flow operators used by the implementation.
 *
 * @param I The intent type
 * @param S The state type
 * @param E The event type
 * @see HandleStrategy
 * @see IntentHandler
 * @see HybridStrategyConfig
 * @see toPartialChange
 */
fun interface IntentTransformer<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> {
    /**
     * Transforms incoming intents into partial changes.
     *
     * @param intentFlow The flow of intents to transform
     * @return A flow of partial changes to be applied to snapshots
     */
    fun transform(intentFlow: Flow<I>): Flow<Mvi.PartialChange<S, E>>
}

/**
 * Creates a strategy-based [IntentTransformer].
 *
 * This is an internal factory used by the framework to build the default
 * [StrategyIntentTransformer] from the configured strategy and runtime settings.
 *
 * @param handleStrategy The handling strategy to apply
 * @param hybridStrategyConfig Runtime configuration for HYBRID strategy
 * @param groupTagSelector Selects fallback group tags for HYBRID strategy
 * @param handler The intent handler to delegate to
 * @return An IntentTransformer that applies the specified strategy
 */
internal fun <I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> strategyTransformer(
    handleStrategy: HandleStrategy,
    hybridStrategyConfig: HybridStrategyConfig,
    groupTagSelector: GroupTagSelector<I>,
    handler: IntentHandler<I, S, E>,
): IntentTransformer<I, S, E> {
    return StrategyIntentTransformer(handleStrategy, hybridStrategyConfig, groupTagSelector, handler)
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
internal fun <I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> Flow<I>.toPartialChange(
    transformer: IntentTransformer<I, S, E>,
): Flow<Mvi.PartialChange<S, E>> = transformer.transform(this)


// Private sentinel tags for fixed HYBRID groups.
private object ConcurrentGroup
private object SequentialGroup

/**
 * Default [IntentTransformer] used by the handler-based contract API.
 *
 * This implementation selects the configured [HandleStrategy], delegates actual
 * work to [handler], and emits the resulting [Mvi.PartialChange] flow. It keeps
 * the strategy wiring in one place while leaving the detailed strategy semantics
 * documented on [HandleStrategy].
 *
 * For HYBRID processing, fallback grouping is selected by [groupTagSelector] and
 * runtime buffering / diagnostics are controlled by [hybridStrategyConfig].
 *
 * @param I The intent type
 * @param S The state type
 * @param E The event type
 * @param handleStrategy The handling strategy to apply
 * @param hybridStrategyConfig Runtime configuration for HYBRID strategy
 * @param groupTagSelector Selects fallback group tags for HYBRID strategy
 * @param handler The intent handler that processes individual intents
 * @see HandleStrategy
 * @see HybridStrategyConfig
 * @see GroupTagSelector
 * @see IntentHandler
 */
internal class StrategyIntentTransformer<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event>(
    private val handleStrategy: HandleStrategy,
    private val hybridStrategyConfig: HybridStrategyConfig,
    private val groupTagSelector: GroupTagSelector<I>,
    private val handler: IntentHandler<I, S, E>,
) : IntentTransformer<I, S, E> {
    private val conflictIntentTypes = ConcurrentHashMap.newKeySet<Class<*>>()

    override fun transform(intentFlow: Flow<I>): Flow<Mvi.PartialChange<S, E>> {
        logger.i(TAG) {
            if (handleStrategy == HandleStrategy.HYBRID) {
                "Transforming intents using handleStrategy=$handleStrategy, hybridStrategyConfig=$hybridStrategyConfig"
            } else {
                "Transforming intents using handleStrategy=$handleStrategy"
            }
        }
        return when (handleStrategy) {
            HandleStrategy.CONCURRENT -> intentFlow.flatMapMerge { handler.handle(it) }
            HandleStrategy.SEQUENTIAL -> intentFlow.flatMapConcat { handler.handle(it) }
            HandleStrategy.HYBRID -> intentFlow.hybrid().flattenMerge(concurrency = Int.MAX_VALUE)
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
        return groupHandle(hybridStrategyConfig, ::assignGroupTag) {
            handleByTag(it)
        }
    }

    /**
     * Processes a flow of intents within a group based on the group's tag.
     *
     * - **Concurrent group**: Uses `flatMapMerge` for parallel processing
     * - **Other groups** (sequential and fallback): Uses `flatMapConcat` for sequential processing
     *
     * This differentiation allows concurrent intents to be processed in parallel
     * while maintaining sequential order for other intent types within their groups.
     *
     * @param tag The group tag assigned by [assignGroupTag]
     * @return A flow of partial changes for this group
     */
    private fun Flow<I>.handleByTag(tag: Any): Flow<Mvi.PartialChange<S, E>> {
        return if (tag === ConcurrentGroup) {
            flatMapMerge { handler.handle(it) }
        } else {
            flatMapConcat { handler.handle(it) }
        }
    }

    /**
     * Assigns a group tag to an intent based on its type.
     *
     * Classification and conflict detection are centralized here. The marker checks are kept local so
     * conflict resolution cannot diverge from tag assignment.
     *
     * The tag determines how the intent will be processed in HYBRID strategy:
     *
     * - **Conflict** (implements both [Mvi.Intent.Concurrent] and [Mvi.Intent.Sequential]):
     *   - The two markers are mutually exclusive; implementing both is incorrect.
     *   - A warning is logged once per intent class for this transformer instance.
     *   - The intent falls through to fallback grouping.
     *   - Checked first so that conflict intents never silently enter a fixed group.
     *
     * - **Concurrent Intent** ([Mvi.Intent.Concurrent] only):
     *   - Tag: private concurrent sentinel
     *   - Processing: Parallel with all other concurrent intents
     *
     * - **Sequential Intent** ([Mvi.Intent.Sequential] only):
     *   - Tag: private sequential sentinel
     *   - Processing: Sequential in a single global queue
     *
     * - **Fallback Intent** (implements neither marker):
     *   - Tag: result of [GroupTagSelector.selectTag]
     *   - Processing: Sequential within the same tag group, parallel with other groups
     *   - This is a valid, intentional pattern — use it for fine-grained grouping control
     *     when neither fixed concurrency mode fits.
     *
     * @param intent The intent to classify
     * @return The group tag for this intent
     */
    private fun assignGroupTag(intent: I): Any {
        val isConcurrent = intent is Mvi.Intent.Concurrent
        val isSequential = intent is Mvi.Intent.Sequential
        return when {
            isConcurrent && isSequential -> {
                if (conflictIntentTypes.add(intent.javaClass)) {
                    logger.w(TAG) {
                        "${intent.diagnosticName} implements both Concurrent and Sequential; " +
                            "falling back to hybrid group selection."
                    }
                }
                groupTagSelector.selectTag(intent)
            }

            isConcurrent -> ConcurrentGroup
            isSequential -> SequentialGroup
            else -> groupTagSelector.selectTag(intent)
        }
    }
}
