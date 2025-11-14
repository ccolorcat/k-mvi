package cc.colorcat.mvi

import cc.colorcat.mvi.internal.TAG
import cc.colorcat.mvi.internal.i
import cc.colorcat.mvi.internal.logger
import cc.colorcat.mvi.internal.w
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * A handler that processes an intent and produces a flow of partial state changes.
 *
 * IntentHandler is the core mechanism for transforming user actions or system events
 * (represented as [Mvi.Intent]) into state changes (represented as [Mvi.PartialChange]).
 *
 * ## Key Characteristics
 *
 * - **Suspend Function**: Can perform async operations (network calls, database queries, etc.)
 * - **Flow Return**: Supports multiple state updates over time for a single intent
 * - **Type-Safe**: Strongly typed with Intent, State, and Event generics
 *
 * ## Typical Pattern
 *
 * ```
 * Intent → IntentHandler.handle() → Flow<PartialChange> → State Updates
 * ```
 *
 * ## Usage Examples
 *
 * ### Simple Handler (Single State Change)
 *
 * ```kotlin
 * val clearHandler = IntentHandler<ClearIntent, MyState, MyEvent> { intent ->
 *     flowOf(Mvi.PartialChange { snapshot ->
 *         snapshot.updateState { copy(data = emptyList()) }
 *     })
 * }
 * ```
 *
 * ### Complex Handler (Multiple State Changes)
 *
 * ```kotlin
 * val loadDataHandler = IntentHandler<LoadDataIntent, MyState, MyEvent> { intent ->
 *     flow {
 *         // First: Set loading state
 *         emit(Mvi.PartialChange { it.updateState { copy(loading = true) } })
 *
 *         try {
 *             val data = repository.loadData(intent.id)
 *             // Second: Update with loaded data
 *             emit(Mvi.PartialChange { snapshot ->
 *                 snapshot.updateWith(MyEvent.ShowSuccess) {
 *                     copy(loading = false, data = data)
 *                 }
 *             })
 *         } catch (e: Exception) {
 *             // Third: Handle error
 *             emit(Mvi.PartialChange { snapshot ->
 *                 snapshot.updateWith(MyEvent.ShowError(e.message)) {
 *                     copy(loading = false)
 *                 }
 *             })
 *         }
 *     }
 * }
 * ```
 *
 * @param I The intent type this handler processes
 * @param S The state type
 * @param E The event type
 * @see Mvi.Intent
 * @see Mvi.PartialChange
 * @see IntentHandlerRegistry
 *
 * Author: ccolorcat
 * Date: 2024-12-24
 * GitHub: https://github.com/ccolorcat
 */
fun interface IntentHandler<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> {
    /**
     * Handles the given intent and produces a flow of partial state changes.
     *
     * This method is called when an intent needs to be processed. It can:
     * - Perform async operations (network, database, etc.)
     * - Emit multiple state changes over time
     * - Emit events alongside state changes
     *
     * @param intent The intent to handle
     * @return A flow of partial changes to be applied to the current state
     */
    suspend fun handle(intent: I): Flow<Mvi.PartialChange<S, E>>
}


/**
 * A registry for managing intent handlers dynamically.
 *
 * IntentHandlerRegistry allows you to register and unregister handlers for specific
 * intent types at runtime. This is useful for:
 * - Modular architecture where different modules handle different intents
 * - Dynamic feature loading/unloading
 * - Testing with mock handlers
 *
 * ## Usage Pattern
 *
 * ```kotlin
 * // Register a simple handler (single state change) - Kotlin style with extension
 * registry.register<LoadDataIntent> { intent ->
 *     Mvi.PartialChange { snapshot ->
 *         snapshot.updateState { copy(data = loadData(intent.id)) }
 *     }
 * }
 *
 * // Register a complex handler (flow of changes) - Java style
 * registry.register(RefreshIntent::class.java, IntentHandler { intent ->
 *     flow {
 *         emit(Mvi.PartialChange { it.updateState { copy(loading = true) } })
 *         // ... more changes
 *     }
 * })
 *
 * // Unregister when no longer needed - Kotlin style
 * registry.unregister<LoadDataIntent>()
 * ```
 *
 * ## Thread Safety
 *
 * Implementations should ensure thread-safe access to the registry, as registration
 * and intent handling may occur on different threads/coroutines.
 *
 * @param I The base intent type
 * @param S The state type
 * @param E The event type
 * @see IntentHandler
 */
interface IntentHandlerRegistry<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event> {
    /**
     * Registers an intent handler for the specified intent type.
     *
     * When an intent of type [T] is processed, the registered handler will be invoked.
     * If a handler is already registered for this type, it will be replaced.
     *
     * @param T The specific intent type to handle (must be a subtype of [I])
     * @param intentType The class of the intent type to register
     * @param handler The handler that will process intents of this type
     */
    fun <T : I> register(intentType: Class<T>, handler: IntentHandler<T, S, E>)

    /**
     * Unregisters the handler for the specified intent type.
     *
     * After unregistration, intents of this type will fall back to the default handler
     * (if available). If no handler is registered for this type, this method has no effect.
     *
     * @param intentType The class of the intent type to unregister
     */
    fun unregister(intentType: Class<out I>)
}


/**
 * Default implementation of [IntentHandlerRegistry] and [IntentHandler].
 *
 * This internal class provides a thread-safe registry that manages intent handlers
 * and dispatches intents to the appropriate handler. If no handler is registered
 * for a specific intent type, it falls back to the [defaultHandler].
 *
 * ## Implementation Details
 *
 * - Uses [java.util.concurrent.ConcurrentHashMap] for thread-safe handler storage
 * - Provides fallback mechanism via [defaultHandler]
 * - Logs warnings when falling back to default handler
 * - Logs all intent handling (INFO level) to help users track processing state
 *
 * ## Why Log Every Intent?
 *
 * Intent handling logs are kept intentionally to help users diagnose issues across
 * all three processing strategies supported by this framework:
 *
 * ### 1. CONCURRENT Strategy
 * - **Behavior**: All intents are processed in parallel using `flatMapMerge`
 * - **Logging Value**: Helps track concurrent operations and identify race conditions
 * - **Example**: Multiple user actions happening simultaneously
 *
 * ### 2. SEQUENTIAL Strategy
 * - **Behavior**: All intents are processed one-by-one in strict order using `flatMapConcat`
 * - **Logging Value**: **Critical** for identifying blocking intents. When a long-running
 *   intent blocks the queue, logs help pinpoint which intent is causing the delay
 * - **Example**: A slow network request blocking subsequent UI updates
 * - **Without Logs**: Users might think the framework is frozen
 *
 * ### 3. HYBRID Strategy (Most Common)
 * - **Behavior**: Intents are grouped based on their type:
 *   - [Mvi.Intent.Concurrent]: Processed in parallel (merged)
 *   - [Mvi.Intent.Sequential]: Processed one-by-one in a sequential group
 *   - Fallback intents: Grouped by custom tag, sequential within group, parallel between groups
 * - **Logging Value**: Essential for understanding the interplay between different processing modes
 * - **Example**: UI clicks (concurrent) mixed with data loading (sequential in group)
 * - **Complex Scenario**: Group A processes sequentially, Group B processes sequentially,
 *   but A and B run in parallel. Logs help trace which group is blocking
 *
 * ### Real-World Example
 * ```
 * [INFO] Handling intent: LoadUserProfile    (Sequential, starts at T0)
 * [INFO] Handling intent: UpdateTheme        (Concurrent, executes at T0)
 * [INFO] Handling intent: LoadSettings       (Sequential, waits until T3)
 * ```
 * Without logs, if `LoadUserProfile` takes 3 seconds, users would see `LoadSettings`
 * delayed without understanding why.
 *
 * ## Performance Considerations
 *
 * - Logs use lambda syntax for lazy evaluation: `logger.i(TAG) { ... }`
 * - Only class names are logged, not full intent data (avoiding sensitive info leakage)
 * - Log level can be adjusted in production if needed
 *
 * @param I The base intent type
 * @param S The state type
 * @param E The event type
 * @param defaultHandler The fallback handler used when no specific handler is registered
 * @see HandleStrategy
 * @see Mvi.Intent.Concurrent
 * @see Mvi.Intent.Sequential
 */
internal class IntentHandlerDelegate<I : Mvi.Intent, S : Mvi.State, E : Mvi.Event>(
    private val defaultHandler: IntentHandler<I, S, E>
) : IntentHandlerRegistry<I, S, E>, IntentHandler<I, S, E> {
    private val handlers = ConcurrentHashMap<Class<*>, IntentHandler<*, S, E>>()

    override fun <T : I> register(intentType: Class<T>, handler: IntentHandler<T, S, E>) {
        handlers[intentType] = handler
    }

    override fun unregister(intentType: Class<out I>) {
        handlers.remove(intentType)
    }

    override suspend fun handle(intent: I): Flow<Mvi.PartialChange<S, E>> {
        var handler = handlers[intent.javaClass]
        if (handler == null) {
            logger.w(TAG) {
                "No handler registered for ${intent.javaClass.simpleName}, fallback to defaultHandler"
            }
            handler = defaultHandler
        } else {
            @Suppress("UNCHECKED_CAST")
            handler as IntentHandler<I, S, E>
        }
        logger.i(TAG) { "Handling intent: ${intent.javaClass.simpleName}" }
        return handler.handle(intent)
    }
}


// Kotlin-style extension functions for convenient registry usage

/**
 * Registers a simple handler using reified type parameter.
 *
 * This is a Kotlin-friendly convenience method that allows type-safe registration
 * without explicitly passing the class object.
 *
 * Example:
 * ```kotlin
 * registry.register<LoadDataIntent> { intent ->
 *     Mvi.PartialChange { snapshot ->
 *         snapshot.updateState { copy(data = loadData(intent.id)) }
 *     }
 * }
 * ```
 *
 * @param T The specific intent type to handle
 * @param handler A suspend function that transforms the intent into a single partial change
 */
inline fun <reified T : Mvi.Intent, S : Mvi.State, E : Mvi.Event> IntentHandlerRegistry<in T, S, E>.register(
    noinline handler: suspend (intent: T) -> Mvi.PartialChange<S, E>
) {
    register(T::class.java) { handler(it).asSingleFlow() }
}

/**
 * Registers an intent handler using reified type parameter.
 *
 * This is a Kotlin-friendly convenience method that allows type-safe registration
 * without explicitly passing the class object.
 *
 * Example:
 * ```kotlin
 * registry.register<LoadDataIntent>(IntentHandler { intent ->
 *     flow {
 *         emit(Mvi.PartialChange { it.updateState { copy(loading = true) } })
 *         // ... more changes
 *     }
 * })
 * ```
 *
 * @param T The specific intent type to handle
 * @param handler The handler that will process intents of this type
 */
inline fun <reified T : Mvi.Intent, S : Mvi.State, E : Mvi.Event> IntentHandlerRegistry<in T, S, E>.register(
    handler: IntentHandler<T, S, E>
) {
    register(T::class.java, handler)
}

/**
 * Unregisters the handler using reified type parameter.
 *
 * This is a Kotlin-friendly convenience method that allows type-safe unregistration
 * without explicitly passing the class object.
 *
 * Example:
 * ```kotlin
 * registry.unregister<LoadDataIntent>()
 * ```
 *
 * @param T The intent type to unregister
 */
inline fun <reified T : Mvi.Intent, S : Mvi.State, E : Mvi.Event> IntentHandlerRegistry<T, S, E>.unregister() {
    unregister(T::class.java)
}
