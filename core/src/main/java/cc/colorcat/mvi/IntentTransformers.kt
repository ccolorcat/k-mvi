package cc.colorcat.mvi

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flattenMerge

/**
 * Author: ccolorcat
 * Date: 2024-12-24
 * GitHub: https://github.com/ccolorcat
 */
fun interface IntentTransformer<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> {
    fun transform(intentFlow: Flow<I>): Flow<Mvi.PartialChange<S, E>>

    companion object {
        internal operator fun <I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> invoke(
            strategy: HandleStrategy,
            config: HybridConfig<I>,
            handler: IntentHandler<I, S, E>,
        ): IntentTransformer<I, S, E> {
            return StrategyIntentTransformer(strategy, config, handler)
        }
    }
}


fun <I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> Flow<I>.toPartialChange(
    transformer: IntentTransformer<I, S, E>
): Flow<Mvi.PartialChange<S, E>> = transformer.transform(this)


internal class StrategyIntentTransformer<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event>(
    private val strategy: HandleStrategy,
    private val config: HybridConfig<I>,
    private val handler: IntentHandler<I, S, E>,
) : IntentTransformer<I, S, E> {

    override fun transform(intentFlow: Flow<I>): Flow<Mvi.PartialChange<S, E>> {
        logger.log(Logger.INFO, TAG, null) {
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

    private fun Flow<I>.hybrid(): Flow<Flow<Mvi.PartialChange<S, E>>> {
        return groupHandle(config.groupChannelCapacity, ::assignGroupTag) { handleByTag(it) }
    }

    @OptIn(FlowPreview::class)
    private fun Flow<I>.handleByTag(tag: String): Flow<Mvi.PartialChange<S, E>> {
        return if (tag == TAG_CONCURRENT) {
            flatMapMerge { handler.handle(it) }
        } else {
            flatMapConcat { handler.handle(it) }
        }
    }

    private fun assignGroupTag(intent: I): String {
        return when {
            intent.isFallback -> TAG_PREFIX_FALLBACK + config.groupTagSelector(intent)
            intent.isConcurrent -> TAG_CONCURRENT
            intent.isSequential -> TAG_SEQUENTIAL
            else -> throw AssertionError("Unexpected Intent type reached in assignGroupTag: $intent")
        }
    }

    private companion object {
        const val TAG_CONCURRENT = "CONCURRENT"
        const val TAG_SEQUENTIAL = "SEQUENTIAL"
        const val TAG_PREFIX_FALLBACK = "FALLBACK"
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
