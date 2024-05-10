package cc.colorcat.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
interface Container<A : MVI.Action, S : MVI.State, E : MVI.Event> {
    val stateFlow: StateFlow<S>

    val eventFlow: Flow<E>
}

interface ReactiveContainer<A : MVI.Action, S : MVI.State, E : MVI.Event> : Container<A, S, E> {
    fun <T : A> register(actionType: Class<T>, handler: MVI.ActionHandler<T, S, E>)

    fun unregister(actionType: Class<out A>)

    fun dispatch(action: A)
}

fun <A : MVI.Action, S : MVI.State, E : MVI.Event> ReactiveContainer<A, S, E>.asContainer(): Container<A, S, E> {
    return this
}


internal class RealReactiveContainer<A : MVI.Action, S : MVI.State, E : MVI.Event>(
    private val scope: CoroutineScope,
    initState: S,
    private val defaultHandler: MVI.ActionHandler<A, S, E>,
) : ReactiveContainer<A, S, E> {
    private val handlers = mutableMapOf<Class<*>, MVI.ActionHandler<*, S, E>>()
    private val actionsFlow = MutableSharedFlow<A>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val frameFlow: SharedFlow<MVI.Frame<S, E>> = actionsFlow.flatMapConcat { handle(it) }
        .scan(MVI.Frame<S, E>(initState)) { oldFrame, partialChange -> partialChange.reduce(oldFrame) }
        .flowOn(Dispatchers.IO)
        .shareIn(scope, SharingStarted.Eagerly)

    private suspend fun handle(action: A): Flow<MVI.PartialChange<S, E>> {
        @Suppress("UNCHECKED_CAST")
        val handler = handlers[action.javaClass] as? MVI.ActionHandler<A, S, E> ?: defaultHandler
        return handler.handle(action)
    }

    override val stateFlow: StateFlow<S> = frameFlow.map { it.state }
        .stateIn(scope, SharingStarted.Eagerly, initState)

    override val eventFlow: Flow<E> = frameFlow.mapNotNull { it.event }
        .shareIn(scope, SharingStarted.Eagerly)

    override fun <T : A> register(actionType: Class<T>, handler: MVI.ActionHandler<T, S, E>) {
        handlers[actionType] = handler
    }

    override fun unregister(actionType: Class<out A>) {
        handlers.remove(actionType)
    }

    override fun dispatch(action: A) {
        scope.launch {
            actionsFlow.emit(action)
        }
    }
}


fun <A : MVI.Action, S : MVI.State, E : MVI.Event> ViewModel.containers(
    initState: S,
    defaultHandler: MVI.ActionHandler<A, S, E> = MVI.ActionHandler { emptyFlow() },
    setup: ReactiveContainer<A, S, E>.() -> Unit = {}
): Lazy<ReactiveContainer<A, S, E>> {
    return ReactiveContainerLazy(viewModelScope, initState, defaultHandler, setup)
}


internal class ReactiveContainerLazy<A : MVI.Action, S : MVI.State, E : MVI.Event>(
    private val scope: CoroutineScope,
    private val initState: S,
    private val defaultHandler: MVI.ActionHandler<A, S, E>,
    private val setup: ReactiveContainer<A, S, E>.() -> Unit
) : Lazy<ReactiveContainer<A, S, E>> {
    private var cached: ReactiveContainer<A, S, E>? = null

    override val value: ReactiveContainer<A, S, E>
        get() = cached ?: RealReactiveContainer(scope, initState, defaultHandler).also {
            cached = it
            it.setup()
        }

    override fun isInitialized(): Boolean = cached != null
}
