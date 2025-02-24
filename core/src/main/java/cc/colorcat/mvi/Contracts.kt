package cc.colorcat.mvi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val intentFlow = MutableSharedFlow<I>()

    private val snapshotFlow: SharedFlow<Mvi.Snapshot<S, E>> = intentFlow.toPartialChange(transformer)
            .scan(Mvi.Snapshot<S, E>(initState)) { oldSnapshot, partialChange ->
                partialChange.apply(oldSnapshot)
            }
            .retryWhen { cause, attempt -> retryPolicy(attempt, cause) }
            .flowOn(Dispatchers.Default)
            .shareIn(scope, SharingStarted.Eagerly)

    override val stateFlow: StateFlow<S> = snapshotFlow.map { it.state }
        .stateIn(scope, stateConfig, initState)

    override val eventFlow: Flow<E> = snapshotFlow.mapNotNull { it.event }
        .shareIn(scope, eventConfig, 0)

    override fun dispatch(intent: I) {
        if (scope.isActive) {
            scope.launch { intentFlow.emit(intent) }
        } else {
            logger.log(Logger.WARN, TAG, null) {
                "Scope inactive, intent discarded: ${intent.javaClass.simpleName}"
            }
        }
    }

    private companion object {
        const val STATE_STOP_TIMEOUT_MILLIS = 5_000L
        const val EVENT_STOP_TIMEOUT_MILLIS = 3_000L

        const val REPLAY_EXPIRATION_MILLIS = 0L

        val stateConfig: SharingStarted
            get() = SharingStarted.WhileSubscribed(
                stopTimeoutMillis = STATE_STOP_TIMEOUT_MILLIS,
                replayExpirationMillis = REPLAY_EXPIRATION_MILLIS
            )

        val eventConfig: SharingStarted
            get() = SharingStarted.WhileSubscribed(
                stopTimeoutMillis = EVENT_STOP_TIMEOUT_MILLIS,
                replayExpirationMillis = REPLAY_EXPIRATION_MILLIS
            )
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
