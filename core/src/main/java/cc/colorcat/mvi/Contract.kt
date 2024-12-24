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
import kotlinx.coroutines.flow.retry
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
    fun dispatch(intent: I)
}

fun <I : MVI.Intent, S : MVI.State, E : MVI.Event> ReactiveContract<I, S, E>.asContract(): Contract<I, S, E> {
    return this
}


internal open class EssentialReactiveContract<I : MVI.Intent, S : MVI.State, E : MVI.Event>(
    private val scope: CoroutineScope,
    initState: S,
    transformer: IntentTransformer<I, S, E>,
) : ReactiveContract<I, S, E> {
    private val intentFlow = MutableSharedFlow<I>()

    private val snapshotFlow: SharedFlow<MVI.Snapshot<S, E>> = intentFlow.toPartialChange(transformer)
        .scan(MVI.Snapshot<S, E>(initState)) { oldSnapshot, partialChange -> partialChange.apply(oldSnapshot) }
        .retry()
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.Eagerly)

    override val stateFlow: StateFlow<S> = snapshotFlow.map { it.state }
        .stateIn(scope, SharingStarted.Eagerly, initState)

    override val eventFlow: Flow<E> = snapshotFlow.mapNotNull { it.event }
        .shareIn(scope, SharingStarted.Eagerly)

    override fun dispatch(intent: I) {
        scope.launch {
            intentFlow.emit(intent)
        }
    }
}


internal class StrategyReactiveContract<I : MVI.Intent, S : MVI.State, E : MVI.Event> private constructor(
    scope: CoroutineScope,
    initState: S,
    strategy: HandleStrategy,
    config: HybridConfig<I>,
    private val delegate: IntentHandlerDelegate<I, S, E>,
) : EssentialReactiveContract<I, S, E>(
    scope = scope,
    initState = initState,
    transformer = IntentTransformer(strategy, config, delegate)
) {
    constructor(
        scope: CoroutineScope,
        initState: S,
        strategy: HandleStrategy,
        config: HybridConfig<I>,
        defaultHandler: IntentHandler<I, S, E>,
    ) : this(
        scope = scope,
        initState = initState,
        strategy = strategy,
        config = config,
        delegate = IntentHandlerDelegate(defaultHandler),
    )

    internal fun configIntentHandler(setup: IntentHandlerRegistry<I, S, E>.() -> Unit) {
        delegate.setup()
    }
}
