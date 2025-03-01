package cc.colorcat.mvi

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
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
) {
    StateCollector(this, owner, state).collector()
}

class StateCollector<S : Mvi.State> internal constructor(
    private val flow: Flow<S>,
    private val owner: LifecycleOwner,
    private val state: Lifecycle.State,
) {
    fun <A> collectPartial(prop1: KProperty1<S, A>, block: suspend (A) -> Unit): Job {
        return owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(state) {
                flow.map { prop1.get(it) }
                    .distinctUntilChanged()
                    .collect(block)
            }
        }
    }

    fun collectWhole(block: suspend (S) -> Unit): Job {
        return owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(state) {
                flow.distinctUntilChanged()
                    .collect(block)
            }
        }
    }
}

fun <S : Mvi.State, A> Flow<S>.collectPartialState(
    prop1: KProperty1<S, A>,
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    block: suspend (A) -> Unit,
): Job {
    return owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(state) {
            this@collectPartialState.distinctUntilChanged()
                .map { prop1.get(it) }
                .distinctUntilChanged()
                .collect(block)
        }
    }
}


fun <E : Mvi.Event> Flow<E>.collectEvent(
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    collector: EventCollector<E>.() -> Unit,
) {
    EventCollector(this@collectEvent, owner, state).collector()
}

class EventCollector<E : Mvi.Event> internal constructor(
    private val flow: Flow<E>,
    private val owner: LifecycleOwner,
    private val state: Lifecycle.State,
) {
    inline fun <reified A : E> collectParticular(noinline block: suspend (A) -> Unit): Job {
        return collectParticular(A::class, block)
    }

    fun <A : E> collectParticular(clazz: KClass<A>, block: suspend (A) -> Unit): Job {
        return owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(state) {
                @Suppress("UNCHECKED_CAST")
                (flow.filter { clazz.isInstance(it) } as Flow<A>).collect(block)
            }
        }
    }

    fun collectAll(block: suspend (E) -> Unit): Job {
        return owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(state) {
                flow.collect(block)
            }
        }
    }
}

inline fun <reified E : Mvi.Event> Flow<Mvi.Event>.collectParticularEvent(
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    crossinline block: suspend (E) -> Unit,
): Job {
    return owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(state) {
            this@collectParticularEvent.filterIsInstance<E>().collect {
                block(it)
            }
        }
    }
}


fun <T> Flow<T>.launchCollect(
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend (T) -> Unit,
): Job = owner.lifecycleScope.launch(context, start) {
    owner.lifecycle.repeatOnLifecycle(state) {
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
