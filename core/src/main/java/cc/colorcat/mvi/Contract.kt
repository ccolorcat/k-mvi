package cc.colorcat.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
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
    private val defaultHandler: MVI.IntentHandler<I, S, E>,
) : ReactiveContract<I, S, E> {
    private val handlers = mutableMapOf<Class<*>, MVI.IntentHandler<*, S, E>>()
    private val intentsFlow = MutableSharedFlow<I>()

    @OptIn(FlowPreview::class)
    private val frameFlow: SharedFlow<MVI.Frame<S, E>> = intentsFlow.flatMapConcat { handle(it) }
        .scan(MVI.Frame<S, E>(initState)) { oldFrame, partialChange -> partialChange.reduce(oldFrame) }
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.Eagerly)

    private suspend fun handle(intent: I): Flow<MVI.PartialChange<S, E>> {
        @Suppress("UNCHECKED_CAST")
        val handler = handlers[intent.javaClass] as? MVI.IntentHandler<I, S, E> ?: defaultHandler
        return handler.handle(intent)
    }

    override val stateFlow: StateFlow<S> = frameFlow.map { it.state }
        .stateIn(scope, SharingStarted.Eagerly, initState)

    override val eventFlow: Flow<E> = frameFlow.mapNotNull { it.event }
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
}


fun <I : MVI.Intent, S : MVI.State, E : MVI.Event> ViewModel.contract(
    initState: S,
    defaultHandler: MVI.IntentHandler<I, S, E> = MVI.IntentHandler { emptyFlow() },
    setup: ReactiveContract<I, S, E>.() -> Unit = {}
): Lazy<ReactiveContract<I, S, E>> {
    return ReactiveContractLazy(viewModelScope, initState, defaultHandler, setup)
}


internal class ReactiveContractLazy<I : MVI.Intent, S : MVI.State, E : MVI.Event>(
    private val scope: CoroutineScope,
    private val initState: S,
    private val defaultHandler: MVI.IntentHandler<I, S, E>,
    private val setup: ReactiveContract<I, S, E>.() -> Unit
) : Lazy<ReactiveContract<I, S, E>> {
    private var cached: ReactiveContract<I, S, E>? = null

    override val value: ReactiveContract<I, S, E>
        get() = cached ?: RealReactiveContract(scope, initState, defaultHandler).also {
            cached = it
            it.setup()
        }

    override fun isInitialized(): Boolean = cached != null
}
