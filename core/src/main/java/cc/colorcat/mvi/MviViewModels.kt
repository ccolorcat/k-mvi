package cc.colorcat.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.colorcat.mvi.internal.CoreReactiveContract
import cc.colorcat.mvi.internal.StrategyReactiveContract

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
 *         // Define your own PartialChange subtypes implementing Mvi.PartialChange.
 *         // The transformer receives a Flow<Intent>, not a single intent.
 *         transformer = IntentTransformer { intentFlow ->
 *             intentFlow.flatMapConcat { intent ->
 *                 when (intent) {
 *                     is MyIntent.Load -> flow {
 *                         emit(PartialChange.Loading(true))
 *                         try {
 *                             val data = withContext(Dispatchers.IO) {
 *                                 repository.loadData()
 *                             }
 *                             emit(PartialChange.DataLoaded(data))
 *                         } catch (e: Exception) {
 *                             emit(PartialChange.Error(e))
 *                         } finally {
 *                             emit(PartialChange.Loading(false))
 *                         }
 *                     }
 *                     is MyIntent.Refresh -> refreshData()
 *                 }
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
 * @param intentQueueConfig The dispatch entry queue configuration. Defaults to global config
 *                          [KMvi.intentQueueConfig]
 * @param retryPolicy The retry policy for failed Intent processing. Defaults to global config [KMvi.retryPolicy]
 * @param fatalErrorHandler Handles unrecoverable pipeline failures. Defaults to global config
 *                          [KMvi.fatalErrorHandler]
 * @param transformer The [IntentTransformer] that transforms Intents into Flows of PartialChanges
 * @return A [Lazy] delegate that creates the [ReactiveContract] when first accessed
 * @see ReactiveContract
 * @see IntentTransformer
 * @see Mvi.PartialChange
 */
fun <I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> ViewModel.contract(
    initState: S,
    intentQueueConfig: IntentQueueConfig = KMvi.intentQueueConfig,
    retryPolicy: RetryPolicy = KMvi.retryPolicy,
    fatalErrorHandler: FatalErrorHandler = KMvi.fatalErrorHandler,
    transformer: IntentTransformer<I, S, E>,
): Lazy<ReactiveContract<I, S, E>> {
    return ReactiveContractLazy {
        CoreReactiveContract(
            scope = viewModelScope,
            initState = initState,
            intentQueueConfig = intentQueueConfig,
            retryPolicy = retryPolicy,
            fatalErrorHandler = fatalErrorHandler,
            transformer = transformer,
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
 *         // Define your own PartialChange subtypes implementing Mvi.PartialChange
 *         handleStrategy = HandleStrategy.HYBRID
 *     ) {
 *         // Register handlers for different Intent types
 *         register<LoadIntent>(IntentHandler { intent ->
 *             flow {
 *                 emit(PartialChange.Loading(true))
 *                 val data = withContext(Dispatchers.IO) {
 *                     repository.loadData(intent.id)
 *                 }
 *                 emit(PartialChange.DataLoaded(data))
 *                 emit(PartialChange.Loading(false))
 *             }
 *         })
 *
 *         // Register a handler returning a single PartialChange
 *         register<RefreshIntent> { intent ->
 *             PartialChange.Refreshing
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
 * @param intentQueueConfig The dispatch entry queue configuration. Defaults to global config
 *                          [KMvi.intentQueueConfig]
 * @param retryPolicy The retry policy for failed Intent processing. Defaults to global config [KMvi.retryPolicy]
 * @param fatalErrorHandler Handles unrecoverable pipeline failures. Defaults to global config
 *                          [KMvi.fatalErrorHandler]
 * @param handleStrategy The processing strategy for Intents. Defaults to global config [KMvi.handleStrategy]
 * @param hybridStrategyConfig The runtime configuration when using HYBRID strategy.
 *                     Defaults to [KMvi.hybridStrategyConfig].
 * @param groupTagSelector Selects fallback group tags when using HYBRID strategy.
 *                         Defaults to [GroupTagSelector.byClass].
 * @param defaultHandler The fallback handler for Intents without a registered handler.
 *                       Defaults to `null`, in which case unhandled Intents are logged at WARN
 *                       and produce no state change. Supply a non-null handler to opt into the
 *                       centralized-dispatch pattern (unhandled Intents are silently routed to
 *                       it).
 * @param setup A lambda with [IntentHandlerScope] receiver to register Intent handlers; its
 *              `reified` helpers take only the intent type (`register<MyIntent> { ... }`)
 * @return A [Lazy] delegate that creates the [ReactiveContract] when first accessed
 * @see ReactiveContract
 * @see HandleStrategy
 * @see IntentHandler
 * @see IntentHandlerScope
 */
fun <I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> ViewModel.contract(
    initState: S,
    intentQueueConfig: IntentQueueConfig = KMvi.intentQueueConfig,
    retryPolicy: RetryPolicy = KMvi.retryPolicy,
    fatalErrorHandler: FatalErrorHandler = KMvi.fatalErrorHandler,
    handleStrategy: HandleStrategy = KMvi.handleStrategy,
    hybridStrategyConfig: HybridStrategyConfig = KMvi.hybridStrategyConfig,
    groupTagSelector: GroupTagSelector<I> = GroupTagSelector.byClass(),
    defaultHandler: IntentHandler<I, S, E>? = null,
    setup: IntentHandlerScope<I, S, E>.() -> Unit = {},
): Lazy<ReactiveContract<I, S, E>> {
    return ReactiveContractLazy {
        StrategyReactiveContract(
            scope = viewModelScope,
            initState = initState,
            intentQueueConfig = intentQueueConfig,
            retryPolicy = retryPolicy,
            fatalErrorHandler = fatalErrorHandler,
            handleStrategy = handleStrategy,
            hybridStrategyConfig = hybridStrategyConfig,
            groupTagSelector = groupTagSelector,
            defaultHandler = defaultHandler,
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
 * This implementation is NOT thread-safe by design. ViewModels are expected to be
 * accessed from the main thread only. Delegation to [lazy] with
 * [LazyThreadSafetyMode.NONE] matches this expectation: the initializer may be
 * called more than once if accessed concurrently from multiple threads. Do not
 * access this lazy from multiple threads.
 *
 * @param I The Intent type that extends [Mvi.Intent]
 * @param S The State type that extends [Mvi.State]
 * @param E The Event type that extends [Mvi.Event]
 * @param create Factory function that creates the ReactiveContract instance
 */
internal class ReactiveContractLazy<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event>(
    create: () -> ReactiveContract<I, S, E>,
) : Lazy<ReactiveContract<I, S, E>> by lazy(LazyThreadSafetyMode.NONE, create)
