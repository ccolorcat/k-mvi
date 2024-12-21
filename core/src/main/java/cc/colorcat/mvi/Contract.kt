package cc.colorcat.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
enum class HandleStrategy {
    CONCURRENT, SEQUENTIAL, HYBRID;

    companion object {
        val default: HandleStrategy
            get() = HYBRID
    }
}


class HybridConfig<in I : MVI.Intent>(
    internal val groupChannelCapacity: Int = Channel.BUFFERED,
    internal val groupTagSelector: (I) -> String = { it.javaClass.name }
) {
    companion object {
        val default: HybridConfig<MVI.Intent> by lazy { HybridConfig() }
    }
}


interface Contract<I : MVI.Intent, S : MVI.State, E : MVI.Event> {
    val stateFlow: StateFlow<S>

    val eventFlow: Flow<E>
}

interface ReactiveContract<I : MVI.Intent, S : MVI.State, E : MVI.Event> : Contract<I, S, E> {
    fun <T : I> register(intentType: Class<T>, handler: MVI.IntentHandler<T, S, E>)

    fun unregister(intentType: Class<out I>)

    fun dispatch(intent: I)
}

fun <I : MVI.Intent, S : MVI.State, E : MVI.Event> ReactiveContract<I, S, E>.asContract(): Contract<I, S, E> {
    return this
}


internal class RealReactiveContract<I : MVI.Intent, S : MVI.State, E : MVI.Event>(
    private val scope: CoroutineScope,
    initState: S,
    private val strategy: HandleStrategy,
    private val config: HybridConfig<I>,
    private val defaultHandler: MVI.IntentHandler<I, S, E>,
) : ReactiveContract<I, S, E> {
    private val handlers = mutableMapOf<Class<*>, MVI.IntentHandler<*, S, E>>()
    private val intentsFlow = MutableSharedFlow<I>()

    private val snapshotFlow: SharedFlow<MVI.Snapshot<S, E>> = intentsFlow.toPartialChangeFlow()
        .scan(MVI.Snapshot<S, E>(initState)) { oldSnapshot, partialChange -> partialChange.apply(oldSnapshot) }
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.Eagerly)

    override val stateFlow: StateFlow<S> = snapshotFlow.map { it.state }
        .stateIn(scope, SharingStarted.Eagerly, initState)

    override val eventFlow: Flow<E> = snapshotFlow.mapNotNull { it.event }
        .shareIn(scope, SharingStarted.Eagerly)

    override fun <T : I> register(intentType: Class<T>, handler: MVI.IntentHandler<T, S, E>) {
        handlers[intentType] = handler
    }

    override fun unregister(intentType: Class<out I>) {
        handlers.remove(intentType)
    }

    override fun dispatch(intent: I) {
        scope.launch {
            intentsFlow.emit(intent)
        }
    }

    private suspend fun handle(intent: I): Flow<MVI.PartialChange<S, E>> {
        @Suppress("UNCHECKED_CAST")
        val handler = handlers[intent.javaClass] as? MVI.IntentHandler<I, S, E> ?: defaultHandler
        return handler.handle(intent)
    }

    @OptIn(FlowPreview::class)
    private fun Flow<I>.toPartialChangeFlow(): Flow<MVI.PartialChange<S, E>> {
//        val flow = shareIn(scope, SharingStarted.Eagerly) // solution 1
        val flow = this // solution 2
        return when (strategy) {
            HandleStrategy.CONCURRENT -> flow.flatMapMerge { handle(it) }
            HandleStrategy.SEQUENTIAL -> flow.flatMapConcat { handle(it) }
            HandleStrategy.HYBRID -> {
                flow.hybrid().flattenMerge() // solution 2
                // solution 1
//                merge(
//                    flow.filter { it.isConcurrent }.flatMapMerge { handle(it) },
//                    flow.filter { it.isSequential }.flatMapConcat { handle(it) },
//                    flow.filter { it.isFallback }.segment().flatMapMerge { it.flatMapConcat { i -> handle(i) } },
//                )
            }
        }
    }

    private fun Flow<I>.hybrid(): Flow<Flow<MVI.PartialChange<S, E>>> {
        return groupHandle(config.groupChannelCapacity, ::assignGroupTag) { handleByTag(it) }
    }

    @OptIn(FlowPreview::class)
    private fun Flow<I>.handleByTag(tag: String): Flow<MVI.PartialChange<S, E>> {
        return if (tag == TAG_CONCURRENT) {
            flatMapMerge { handle(it) }
        } else {
            flatMapConcat { handle(it) }
        }
    }

    private fun assignGroupTag(intent: I): String {
        return when {
            intent is MVI.Intent.Concurrent && intent !is MVI.Intent.Sequential -> TAG_CONCURRENT
            intent is MVI.Intent.Sequential && intent !is MVI.Intent.Concurrent -> TAG_SEQUENTIAL
            else -> TAG_PREFIX_FALLBACK + config.groupTagSelector(intent)
        }
    }

    private fun Flow<I>.segment(): Flow<Flow<I>> {
        return groupHandle(config.groupChannelCapacity, config.groupTagSelector) { this }
    }

    private fun <R> Flow<I>.groupHandle(
        capacity: Int,
        tagSelector: (I) -> String,
        handler: Flow<I>.(tag: String) -> Flow<R>,
    ): Flow<Flow<R>> = flow {
        val activeChannels = ConcurrentHashMap<String, SendChannel<I>>()
        try {
            collect { intent ->
                val tag = tagSelector(intent)
                val channel = activeChannels.getOrPut(tag) {
                    Channel<I>(capacity).also { emit(it.consumeAsFlow().handler(tag)) }
                }
                try {
                    channel.send(intent)
                } catch (e: Throwable) {
                    if (e is ClosedSendChannelException) {
                        activeChannels.remove(tag, channel)
                    } else {
                        throw e
                    }
                }
            }
        } finally {
            val iterator = activeChannels.iterator()
            while (iterator.hasNext()) {
                iterator.next().value.also { it.close() }
                iterator.remove()
            }
        }
    }

    private companion object {
        const val TAG_CONCURRENT = "CONCURRENT"
        const val TAG_SEQUENTIAL = "SEQUENTIAL"
        const val TAG_PREFIX_FALLBACK = "FALLBACK"
    }
}


fun <I : MVI.Intent, S : MVI.State, E : MVI.Event> ViewModel.contract(
    initState: S,
    strategy: HandleStrategy = HandleStrategy.default,
    config: HybridConfig<I> = HybridConfig.default,
    defaultHandler: MVI.IntentHandler<I, S, E> = MVI.IntentHandler { emptyFlow() },
    setup: ReactiveContract<I, S, E>.() -> Unit = {}
): Lazy<ReactiveContract<I, S, E>> {
    return ReactiveContractLazy(
        scope = viewModelScope,
        initState = initState,
        strategy = strategy,
        config = config,
        defaultHandler = defaultHandler,
        setup = setup
    )
}


internal class ReactiveContractLazy<I : MVI.Intent, S : MVI.State, E : MVI.Event>(
    private val scope: CoroutineScope,
    private val initState: S,
    private val strategy: HandleStrategy,
    private val config: HybridConfig<I>,
    private val defaultHandler: MVI.IntentHandler<I, S, E>,
    private val setup: ReactiveContract<I, S, E>.() -> Unit
) : Lazy<ReactiveContract<I, S, E>> {
    private var cached: ReactiveContract<I, S, E>? = null

    override val value: ReactiveContract<I, S, E>
        get() = cached ?: RealReactiveContract(scope, initState, strategy, config, defaultHandler).also {
            cached = it
            it.setup()
        }

    override fun isInitialized(): Boolean = cached != null
}
