package cc.colorcat.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.emptyFlow

/**
 * Author: ccolorcat
 * Date: 2024-08-01
 * GitHub: https://github.com/ccolorcat
 */
fun <I : MVI.Intent, S : MVI.State, E : MVI.Event> ViewModel.contract(
    initState: S,
    retryPolicy: RetryPolicy = MVIKit.retryPolicy,
    transformer: IntentTransformer<I, S, E>
): Lazy<ReactiveContract<I, S, E>> {
    return ReactiveContractLazy {
        CoreReactiveContract(
            scope = viewModelScope,
            initState = initState,
            retryPolicy = retryPolicy,
            transformer = transformer
        )
    }
}


fun <I : MVI.Intent, S : MVI.State, E : MVI.Event> ViewModel.contract(
    initState: S,
    retryPolicy: RetryPolicy = MVIKit.retryPolicy,
    strategy: HandleStrategy = MVIKit.handleStrategy,
    config: HybridConfig<I> = MVIKit.hybridConfig,
    defaultHandler: IntentHandler<I, S, E> = IntentHandler { emptyFlow() },
    setup: IntentHandlerRegistry<I, S, E>.() -> Unit = {}
): Lazy<ReactiveContract<I, S, E>> {
    return ReactiveContractLazy {
        StrategyReactiveContract(
            scope = viewModelScope,
            initState = initState,
            retryPolicy = retryPolicy,
            strategy = strategy,
            config = config,
            defaultHandler = defaultHandler
        ).also { it.setupIntentHandlers(setup) }
    }
}


internal class ReactiveContractLazy<I : MVI.Intent, S : MVI.State, E : MVI.Event>(
    private val create: () -> CoreReactiveContract<I, S, E>
) : Lazy<ReactiveContract<I, S, E>> {
    private var cached: ReactiveContract<I, S, E>? = null

    override val value: ReactiveContract<I, S, E>
        get() = cached ?: create().also { cached = it }

    override fun isInitialized(): Boolean = cached != null
}