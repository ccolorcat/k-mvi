package cc.colorcat.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.emptyFlow

/**
 * ViewModel extensions for creating ReactiveContract instances.
 *
 * This file provides convenient extension functions to create and configure
 * ReactiveContract instances within ViewModels, with automatic lifecycle management
 * via [ViewModel.viewModelScope].
 *
 * ## Two APIs
 *
 * 1. **Transformer-based API**: Low-level API with full control over Intent transformation
 * 2. **Handler-based API**: High-level API with declarative Intent handler registration
 *
 * Both APIs return a [Lazy] delegate for delayed initialization - the contract is only
 * created when first accessed.
 *
 * Author: ccolorcat
 * Date: 2024-08-01
 * GitHub: https://github.com/ccolorcat
 */

/**
 * Creates a [ReactiveContract] for MVI architecture with a custom transformer.
 *
 * This is the **low-level API** that gives you full control over Intent transformation.
 * The transformer is responsible for converting Intents into Flows of PartialChanges.
 *
 * The contract is lazily initialized - it won't be created until first access.
 * This ensures the contract is only created when needed and automatically uses
 * the ViewModel's [viewModelScope] for lifecycle management.
 *
 * ## When to use this API
 *
 * - You need complete control over the Intent transformation process
 * - You have complex Intent handling logic that doesn't fit the handler pattern
 * - You need custom error handling or flow composition
 *
 * ## Usage Example
 *
 * ```kotlin
 * class MyViewModel : ViewModel() {
 *     private val contract by contract(
 *         initState = MyState(),
 *         transformer = { intent ->
 *             when (intent) {
 *                 is MyIntent.Load -> flow {
 *                     emit(PartialChange.Loading(true))
 *                     try {
 *                         val data = repository.loadData()
 *                         emit(PartialChange.DataLoaded(data))
 *                     } catch (e: Exception) {
 *                         emit(PartialChange.Error(e))
 *                     } finally {
 *                         emit(PartialChange.Loading(false))
 *                     }
 *                 }
 *                 is MyIntent.Refresh -> refreshData()
 *             }
 *         }
 *     )
 *
 *     val stateFlow = contract.stateFlow
 *     val eventFlow = contract.eventFlow
 *
 *     fun dispatch(intent: MyIntent) = contract.dispatch(intent)
 * }
 * ```
 *
 * @param I The Intent type that extends [Mvi.Intent]
 * @param S The State type that extends [Mvi.State]
 * @param E The Event type that extends [Mvi.Event]
 * @param initState The initial state of the contract
 * @param retryPolicy The retry policy for failed Intent processing. Defaults to global config [KMvi.retryPolicy]
 * @param transformer The function that transforms Intents into Flows of PartialChanges
 * @return A [Lazy] delegate that creates the [ReactiveContract] when first accessed
 * @see ReactiveContract
 * @see IntentTransformer
 * @see Mvi.PartialChange
 */
fun <I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> ViewModel.contract(
    initState: S,
    retryPolicy: RetryPolicy = KMvi.retryPolicy,
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

/**
 * Creates a [ReactiveContract] for MVI architecture with Intent handler registration.
 *
 * This is the **high-level API** that allows you to register Intent handlers with
 * different processing strategies (CONCURRENT, SEQUENTIAL, or HYBRID).
 *
 * The contract is lazily initialized - it won't be created until first access.
 * This ensures the contract is only created when needed and automatically uses
 * the ViewModel's [viewModelScope] for lifecycle management.
 *
 * ## When to use this API
 *
 * - Most common use cases (recommended for beginners)
 * - When you want to separate Intent handling logic by type
 * - When you need different processing strategies for different Intent groups
 * - When you prefer declarative code style
 *
 * ## Strategy Comparison
 *
 * - **CONCURRENT**: All Intents are processed in parallel (fastest, but may cause race conditions)
 * - **SEQUENTIAL**: All Intents are processed one at a time (safest, but may cause delays)
 * - **HYBRID**: Intents are grouped, sequential within groups, concurrent between groups (balanced)
 *
 * ## Usage Example
 *
 * ```kotlin
 * class MyViewModel : ViewModel() {
 *     private val contract by contract(
 *         initState = MyState(),
 *         strategy = HandleStrategy.HYBRID
 *     ) {
 *         // Register handlers for different Intent types
 *         registerHandler<LoadIntent> { intent ->
 *             flow {
 *                 emit(PartialChange.Loading(true))
 *                 val data = repository.loadData(intent.id)
 *                 emit(PartialChange.DataLoaded(data))
 *                 emit(PartialChange.Loading(false))
 *             }
 *         }
 *
 *         // Group related Intents for sequential processing
 *         registerHandler<RefreshIntent>(group = "refresh") { intent ->
 *             refreshData()
 *         }
 *
 *         registerHandler<UpdateIntent>(group = "refresh") { intent ->
 *             updateData(intent.data)
 *         }
 *     }
 *
 *     val stateFlow = contract.stateFlow
 *     val eventFlow = contract.eventFlow
 *
 *     fun dispatch(intent: MyIntent) = contract.dispatch(intent)
 * }
 * ```
 *
 * @param I The Intent type that extends [Mvi.Intent]
 * @param S The State type that extends [Mvi.State]
 * @param E The Event type that extends [Mvi.Event]
 * @param initState The initial state of the contract
 * @param retryPolicy The retry policy for failed Intent processing. Defaults to global config [KMvi.retryPolicy]
 * @param strategy The processing strategy for Intents. Defaults to global config [KMvi.handleStrategy]
 * @param config The hybrid configuration when using HYBRID strategy. Defaults to global config [KMvi.hybridConfig]
 * @param defaultHandler The fallback handler for Intents without registered handlers. Defaults to empty flow.
 * @param setup A lambda with receiver to register Intent handlers using [IntentHandlerRegistry]
 * @return A [Lazy] delegate that creates the [ReactiveContract] when first accessed
 * @see ReactiveContract
 * @see HandleStrategy
 * @see IntentHandler
 * @see IntentHandlerRegistry
 */
fun <I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> ViewModel.contract(
    initState: S,
    retryPolicy: RetryPolicy = KMvi.retryPolicy,
    strategy: HandleStrategy = KMvi.handleStrategy,
    config: HybridConfig<I> = KMvi.hybridConfig,
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

/**
 * A [Lazy] implementation for [ReactiveContract] that ensures the contract
 * is only created once and cached for subsequent accesses.
 *
 * This is used internally by [contract] functions to provide lazy initialization
 * of ReactiveContract instances. The contract is created on first access and then
 * cached for all subsequent accesses.
 *
 * ## Thread Safety
 *
 * This implementation is NOT thread-safe. It's designed to be used within
 * ViewModels which are typically accessed from a single thread (main thread).
 *
 * @param I The Intent type that extends [Mvi.Intent]
 * @param S The State type that extends [Mvi.State]
 * @param E The Event type that extends [Mvi.Event]
 * @param create Factory function that creates the ReactiveContract instance
 */
internal class ReactiveContractLazy<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event>(
    private val create: () -> ReactiveContract<I, S, E>
) : Lazy<ReactiveContract<I, S, E>> {
    private var cached: ReactiveContract<I, S, E>? = null

    override val value: ReactiveContract<I, S, E>
        get() = cached ?: create().also { cached = it }

    override fun isInitialized(): Boolean = cached != null
}
