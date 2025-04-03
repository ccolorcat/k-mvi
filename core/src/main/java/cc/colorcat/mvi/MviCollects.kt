package cc.colorcat.mvi

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStateAtLeast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
fun <S : Mvi.State> Flow<S>.collectState(
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    collector: StateCollector<S>.() -> Unit,
): Job = StateCollector(this, owner, state).apply(collector).job

class StateCollector<S : Mvi.State> internal constructor(
    private val flow: Flow<S>,
    private val owner: LifecycleOwner,
    private val state: Lifecycle.State,
) {
    private val _job = SupervisorJob()
    internal val job: Job
        get() = _job

    fun <A> collectPartial(
        prop1: KProperty1<S, A>,
        block: suspend (A) -> Unit
    ): Job = collectPartial(prop1, state, block)

    fun <A> collectPartial(
        prop1: KProperty1<S, A>,
        state: Lifecycle.State,
        block: suspend (A) -> Unit
    ): Job {
        return flow.map { prop1.get(it) }
            .distinctUntilChanged()
            .launchWithLifecycle(owner, state, _job, block)
    }

    fun collectWhole(block: suspend (S) -> Unit): Job = collectWhole(state, block)

    fun collectWhole(state: Lifecycle.State, block: suspend (S) -> Unit): Job {
        return flow.distinctUntilChanged()
            .launchWithLifecycle(owner, state, _job, block)
    }

    fun cancelAll() {
        _job.cancel()
    }
}

fun <S : Mvi.State, A> Flow<S>.collectPartialState(
    prop1: KProperty1<S, A>,
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend (A) -> Unit,
): Job {
    return map { prop1.get(it) }
        .distinctUntilChanged()
        .launchWithLifecycle(owner, state, context, block)
}


fun <E : Mvi.Event> Flow<E>.collectEvent(
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    collector: EventCollector<E>.() -> Unit,
): Job = EventCollector(this, owner, state).apply(collector).job

class EventCollector<E : Mvi.Event> internal constructor(
    private val flow: Flow<E>,
    private val owner: LifecycleOwner,
    val state: Lifecycle.State,
) {
    private val _job = SupervisorJob()
    internal val job: Job
        get() = _job

    inline fun <reified A : E> collectParticular(
        noinline block: suspend (A) -> Unit
    ): Job = collectParticular(state, block)

    inline fun <reified A : E> collectParticular(
        state: Lifecycle.State,
        noinline block: suspend (A) -> Unit
    ): Job = collectParticular(A::class, state, block)

    fun <A : E> collectParticular(
        clazz: KClass<A>,
        block: suspend (A) -> Unit
    ): Job = collectParticular(clazz, state, block)

    fun <A : E> collectParticular(
        clazz: KClass<A>,
        state: Lifecycle.State,
        block: suspend (A) -> Unit
    ): Job {
        @Suppress("UNCHECKED_CAST")
        return (flow.filter { clazz.isInstance(it) } as Flow<A>)
            .launchWithLifecycle(owner, state, _job, block)
    }

    fun collectAll(block: suspend (E) -> Unit): Job = collectAll(state, block)

    fun collectAll(state: Lifecycle.State, block: suspend (E) -> Unit): Job {
        return flow.launchWithLifecycle(owner, state, _job, block)
    }

    fun cancelAll() {
        _job.cancel()
    }
}

inline fun <reified E : Mvi.Event> Flow<Mvi.Event>.collectParticularEvent(
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
    crossinline block: suspend (E) -> Unit,
): Job = filterIsInstance<E>().launchWithLifecycle(owner, state, context, block)


fun <T> Flow<T>.launchCollect(
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend (T) -> Unit,
): Job = owner.lifecycleScope.launch(context, start) {
    owner.repeatOnLifecycle(state) {
        this@launchCollect.collect(block)
    }
}

fun <T> Flow<T>.launchCollect(
    scope: CoroutineScope,
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend (T) -> Unit,
): Job = scope.launch(context, start) {
    this@launchCollect.collect(block)
}


fun <I : Mvi.Intent> Flow<I>.dispatchAtLeast(
    owner: LifecycleOwner,
    state: Lifecycle.State,
    dispatch: (I) -> Unit
): Job {
    return owner.lifecycleScope.launch {
        owner.withStateAtLeast(state) {
            onEach { dispatch(it) }.launchIn(owner.lifecycleScope)
        }
    }
}


inline fun <T> Flow<T>.launchWithLifecycle(
    owner: LifecycleOwner,
    state: Lifecycle.State,
    context: CoroutineContext = EmptyCoroutineContext,
    crossinline block: suspend (T) -> Unit
): Job = owner.lifecycleScope.launch(context) {
    owner.repeatOnLifecycle(state) {
        this@launchWithLifecycle.collect { block(it) }
    }
}
