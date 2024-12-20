package cc.colorcat.mvi

import android.util.Log
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
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
    internal val groupKeySelector: (I) -> Any = { it.javaClass }
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

    @OptIn(FlowPreview::class)
    private fun Flow<I>.toPartialChangeFlow(): Flow<MVI.PartialChange<S, E>> {
        val flow = shareIn(scope, SharingStarted.Eagerly)
        return when (strategy) {
            HandleStrategy.CONCURRENT -> flow.flatMapMerge { handle(it) }
            HandleStrategy.SEQUENTIAL -> flow.flatMapConcat { handle(it) }
            HandleStrategy.HYBRID -> merge(
                flow.handleConcurrentIntents(),
                flow.handleSequentialIntents(),
                flow.handleFallbackIntents(),
            )
        }
    }

    @OptIn(FlowPreview::class)
    private fun Flow<I>.handleConcurrentIntents(): Flow<MVI.PartialChange<S, E>> {
        return filter { it is MVI.Intent.Concurrent && it !is MVI.Intent.Sequential }
            .flatMapMerge { handle(it) }
    }

    @OptIn(FlowPreview::class)
    private fun Flow<I>.handleSequentialIntents(): Flow<MVI.PartialChange<S, E>> {
        return filter { it is MVI.Intent.Sequential && it !is MVI.Intent.Concurrent }
            .flatMapConcat { handle(it) }
    }

    @OptIn(FlowPreview::class)
    private fun Flow<I>.handleFallbackIntents(): Flow<MVI.PartialChange<S, E>> {
        return filter { isFallbackIntent(it) }
            .partitionBy(config.groupChannelCapacity, config.groupKeySelector)
            .flatMapMerge { partition -> partition.flatMapConcat { handle(it) } }
    }

    private fun isFallbackIntent(intent: I): Boolean {
        val needWarning = intent is MVI.Intent.Concurrent && intent is MVI.Intent.Sequential
        if (needWarning) {
            Log.w(
                "k-mvi",
                "${intent.javaClass} implements both ${MVI.Intent.Concurrent::class.java.simpleName} and ${MVI.Intent.Sequential::class.java.simpleName}, which may lead to unpredictable behavior."
            )
        }
        return needWarning || (intent !is MVI.Intent.Concurrent && intent !is MVI.Intent.Sequential)
    }

    private suspend fun handle(intent: I): Flow<MVI.PartialChange<S, E>> {
        @Suppress("UNCHECKED_CAST")
        val handler = handlers[intent.javaClass] as? MVI.IntentHandler<I, S, E> ?: defaultHandler
        return handler.handle(intent)
    }

    private fun <T, K> Flow<T>.partitionBy(capacity: Int, keySelector: (T) -> K): Flow<Flow<T>> = flow {
        val cached = ConcurrentHashMap<K, SendChannel<T>>()
        try {
            collect { t ->
                val key = keySelector(t)
                val channel = cached.getOrPut(key) {
                    Channel<T>(capacity).also { emit(it.consumeAsFlow()) }
                }
                try {
                    channel.send(t)
                } catch (e: Throwable) {
                    if (e is ClosedSendChannelException) {
                        cached.remove(key, channel)
                    } else {
                        throw e
                    }
                }
            }
        } finally {
            val iterator = cached.iterator()
            while (iterator.hasNext()) {
                iterator.next().value.also { it.close() }
                iterator.remove()
            }
        }
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
