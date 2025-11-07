package cc.colorcat.mvi

import cc.colorcat.mvi.internal.TAG
import cc.colorcat.mvi.internal.logger
import cc.colorcat.mvi.internal.w
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
interface Contract<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> {
    val stateFlow: StateFlow<S>

    val eventFlow: Flow<E>
}

interface ReactiveContract<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> : Contract<I, S, E> {
    fun dispatch(intent: I)
}

fun <I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> ReactiveContract<I, S, E>.asContract(): Contract<I, S, E> {
    return this
}


internal open class CoreReactiveContract<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event>(
    private val scope: CoroutineScope,
    initState: S,
    retryPolicy: RetryPolicy,
    transformer: IntentTransformer<I, S, E>,
) : ReactiveContract<I, S, E> {
    private val intents = MutableSharedFlow<I>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    private val snapshots: SharedFlow<Mvi.Snapshot<S, E>> = intents.toPartialChange(transformer)
        .flowOn(Dispatchers.IO)
        .scan(Mvi.Snapshot<S, E>(initState)) { oldSnapshot, partialChange ->
            partialChange.apply(oldSnapshot)
        }
        .buffer(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        .flowOn(Dispatchers.Default)
        .retryWhen { cause, attempt -> retryPolicy(attempt, cause) }
        .shareIn(scope, SharingStarted.Eagerly, 0)

    override val stateFlow: StateFlow<S> = snapshots.map { it.state }
        .stateIn(scope, SharingStarted.Eagerly, initState)

    override val eventFlow: Flow<E> = snapshots.mapNotNull { it.event }
        .shareIn(scope, SharingStarted.Lazily, 0)

    override fun dispatch(intent: I) {
        if (scope.isActive) {
            scope.launch { intents.emit(intent) }
        } else {
            logger.w(TAG) { "Scope inactive, intent discarded: $intent" }
        }
    }
}


internal class StrategyReactiveContract<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> private constructor(
    scope: CoroutineScope,
    initState: S,
    retryPolicy: RetryPolicy,
    strategy: HandleStrategy,
    config: HybridConfig<I>,
    private val delegate: IntentHandlerDelegate<I, S, E>,
) : CoreReactiveContract<I, S, E>(
    scope = scope,
    initState = initState,
    retryPolicy = retryPolicy,
    transformer = IntentTransformer(strategy, config, delegate)
) {
    constructor(
        scope: CoroutineScope,
        initState: S,
        retryPolicy: RetryPolicy,
        strategy: HandleStrategy,
        config: HybridConfig<I>,
        defaultHandler: IntentHandler<I, S, E>,
    ) : this(
        scope = scope,
        initState = initState,
        retryPolicy = retryPolicy,
        strategy = strategy,
        config = config,
        delegate = IntentHandlerDelegate(defaultHandler),
    )

    internal fun setupIntentHandlers(setup: IntentHandlerRegistry<I, S, E>.() -> Unit) {
        delegate.apply(setup)
    }
}
