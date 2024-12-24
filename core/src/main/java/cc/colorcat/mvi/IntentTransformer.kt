package cc.colorcat.mvi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn

/**
 * Author: ccolorcat
 * Date: 2024-12-24
 * GitHub: https://github.com/ccolorcat
 */
fun interface IntentTransformer<I : MVI.Intent, S : MVI.State, E : MVI.Event> {
    fun transform(intentFlow: Flow<I>): Flow<MVI.PartialChange<S, E>>

    companion object {
        operator fun <I : MVI.Intent, S : MVI.State, E : MVI.Event> invoke(
            strategy: HandleStrategy,
            config: HybridConfig<I>,
            handler: IntentHandler<I, S, E>,
        ): IntentTransformer<I, S, E> {
            return StrategyIntentTransformer(strategy, config, handler)
        }

        operator fun <I : MVI.Intent, S : MVI.State, E : MVI.Event> invoke(
            scope: CoroutineScope,
            strategy: HandleStrategy,
            config: HybridConfig<I>,
            handler: IntentHandler<I, S, E>,
        ): IntentTransformer<I, S, E> {
            return StrategyIntentTransformer2(scope, strategy, config, handler)
        }
    }
}


fun <I : MVI.Intent, S : MVI.State, E : MVI.Event> Flow<I>.toPartialChange(
    transform: IntentTransformer<I, S, E>
): Flow<MVI.PartialChange<S, E>> = transform.transform(this)


internal class StrategyIntentTransformer<I : MVI.Intent, S : MVI.State, E : MVI.Event>(
    private val strategy: HandleStrategy,
    private val config: HybridConfig<I>,
    private val handler: IntentHandler<I, S, E>,
) : IntentTransformer<I, S, E> {

    override fun transform(intentFlow: Flow<I>): Flow<MVI.PartialChange<S, E>> {
        @OptIn(FlowPreview::class)
        return when (strategy) {
            HandleStrategy.CONCURRENT -> intentFlow.flatMapMerge { handler.handle(it) }
            HandleStrategy.SEQUENTIAL -> intentFlow.flatMapConcat { handler.handle(it) }
            HandleStrategy.HYBRID -> intentFlow.hybrid().flattenMerge()
        }
    }

    private fun Flow<I>.hybrid(): Flow<Flow<MVI.PartialChange<S, E>>> {
        return groupHandle(config.groupChannelCapacity, ::assignGroupTag) { handleByTag(it) }
    }

    @OptIn(FlowPreview::class)
    private fun Flow<I>.handleByTag(tag: String): Flow<MVI.PartialChange<S, E>> {
        return if (tag == TAG_CONCURRENT) {
            flatMapMerge { handler.handle(it) }
        } else {
            flatMapConcat { handler.handle(it) }
        }
    }

    private fun assignGroupTag(intent: I): String {
        return when {
            intent is MVI.Intent.Concurrent && intent !is MVI.Intent.Sequential -> TAG_CONCURRENT
            intent is MVI.Intent.Sequential && intent !is MVI.Intent.Concurrent -> TAG_SEQUENTIAL
            else -> TAG_PREFIX_FALLBACK + config.groupTagSelector(intent)
        }
    }

    private companion object {
        const val TAG_CONCURRENT = "CONCURRENT"
        const val TAG_SEQUENTIAL = "SEQUENTIAL"
        const val TAG_PREFIX_FALLBACK = "FALLBACK"
    }
}


internal class StrategyIntentTransformer2<I : MVI.Intent, S : MVI.State, E : MVI.Event>(
    private val scope: CoroutineScope,
    private val strategy: HandleStrategy,
    private val config: HybridConfig<I>,
    private val handler: IntentHandler<I, S, E>,
) : IntentTransformer<I, S, E> {

    override fun transform(intentFlow: Flow<I>): Flow<MVI.PartialChange<S, E>> {
        val flow = intentFlow.shareIn(scope, SharingStarted.Eagerly)
        @OptIn(FlowPreview::class)
        return when (strategy) {
            HandleStrategy.CONCURRENT -> flow.flatMapMerge { handle(it) }
            HandleStrategy.SEQUENTIAL -> flow.flatMapConcat { handle(it) }
            HandleStrategy.HYBRID -> merge(
                flow.filter { it.isConcurrent }.flatMapMerge { handle(it) },
                flow.filter { it.isSequential }.flatMapConcat { handle(it) },
                flow.filter { it.isFallback }.segment().flatMapMerge { it.flatMapConcat { i -> handle(i) } },
            )
        }
    }


    private suspend fun handle(intent: I): Flow<MVI.PartialChange<S, E>> {
        return handler.handle(intent)
    }


    private fun Flow<I>.segment(): Flow<Flow<I>> {
        return groupHandle(config.groupChannelCapacity, config.groupTagSelector) { this }
    }
}

