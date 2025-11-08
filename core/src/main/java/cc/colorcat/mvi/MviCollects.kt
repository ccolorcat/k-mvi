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
 * Lifecycle-aware utilities for collecting MVI state and event flows.
 *
 * This file provides convenient extension functions and DSL builders for collecting
 * state and event flows with automatic lifecycle management. All collectors respect
 * the Android lifecycle and automatically start/stop collection based on lifecycle state.
 *
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */

/**
 * Collects state flow with lifecycle awareness using a DSL-style builder.
 *
 * This function provides a convenient way to collect multiple state properties
 * simultaneously, with each collection managed by the same lifecycle. All collectors
 * are supervised - failure of one doesn't affect others.
 *
 * ## Usage Example
 *
 * ```kotlin
 * class MyFragment : Fragment() {
 *     private val viewModel: MyViewModel by viewModels()
 *
 *     override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
 *         viewModel.stateFlow.collectState(viewLifecycleOwner) {
 *             // Collect individual properties
 *             collectPartial(MyState::loading) { isLoading ->
 *                 progressBar.isVisible = isLoading
 *             }
 *
 *             collectPartial(MyState::data) { data ->
 *                 adapter.submitList(data)
 *             }
 *
 *             // Collect entire state
 *             collectWhole { state ->
 *                 updateTitle(state.title)
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * ## Lifecycle Behavior
 *
 * - Collection starts when lifecycle reaches the specified state (default: STARTED)
 * - Collection stops when lifecycle falls below that state
 * - Collection automatically resumes when lifecycle returns to the required state
 *
 * @param S The state type
 * @param owner The lifecycle owner (typically Fragment or Activity)
 * @param state The minimum lifecycle state required for collection (default: STARTED)
 * @param collector A lambda with receiver to configure state collectors
 * @return A Job that manages all collectors. Cancelling this job will cancel all collectors at once.
 * @see StateCollector
 * @see StateCollector.collectPartial
 * @see StateCollector.collectWhole
 */
fun <S : Mvi.State> Flow<S>.collectState(
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    collector: StateCollector<S>.() -> Unit,
): Job = StateCollector(this, owner, state).apply(collector).job

/**
 * A DSL builder for collecting state flow with lifecycle awareness.
 *
 * StateCollector manages multiple state collectors under a single supervisor.
 * Each collector is independent - if one fails, others continue to run.
 *
 * ## Key Features
 *
 * - **Lifecycle aware**: Automatically starts/stops based on lifecycle
 * - **Property-level collection**: Collect individual state properties with [collectPartial]
 * - **Whole state collection**: Collect entire state with [collectWhole]
 * - **Deduplication**: Automatically filters duplicate values using [distinctUntilChanged]
 * - **Supervised**: One collector's failure doesn't affect others
 *
 * ## Example
 *
 * ```kotlin
 * stateFlow.collectState(viewLifecycleOwner) {
 *     collectPartial(MyState::isLoading) { loading ->
 *         showLoadingIndicator(loading)
 *     }
 *
 *     collectPartial(MyState::errorMessage) { message ->
 *         message?.let { showError(it) }
 *     }
 *
 *     collectWhole(Lifecycle.State.RESUMED) { state ->
 *         // Only collect when RESUMED
 *         updateSensitiveUI(state)
 *     }
 * }
 * ```
 *
 * @param S The state type
 * @see collectState
 * @see StateCollector.collectPartial
 * @see StateCollector.collectWhole
 */
class StateCollector<S : Mvi.State> internal constructor(
    private val flow: Flow<S>,
    private val owner: LifecycleOwner,
    private val state: Lifecycle.State,
) {
    /**
     * The supervisor job that manages all collectors.
     *
     * This job is a child of [owner]'s lifecycleScope, ensuring automatic cleanup
     * when the lifecycle is destroyed. Cancelling this job will cancel all collectors
     * created by [collectPartial] and [collectWhole].
     * This job is returned by [collectState] and can be used to cancel all collectors at once.
     */
    internal val job: Job = SupervisorJob(owner.lifecycleScope.coroutineContext[Job])

    /**
     * Collects a single property of the state.
     *
     * Uses [distinctUntilChanged] to emit only when the property value changes.
     * Uses the default lifecycle state specified in [StateCollector] constructor.
     *
     * @param A The property type
     * @param property The state property to collect (e.g., `MyState::loading`)
     * @param block The suspend function to call with each distinct property value
     * @return A Job for this specific collector
     */
    fun <A> collectPartial(
        property: KProperty1<S, A>,
        block: suspend (A) -> Unit
    ): Job = collectPartial(property, state, block)

    /**
     * Collects a single property of the state with a specific lifecycle state.
     *
     * Uses [distinctUntilChanged] to emit only when the property value changes.
     *
     * ## Example
     *
     * ```kotlin
     * stateFlow.collectState(viewLifecycleOwner) {
     *     // Collect only when RESUMED
     *     collectPartial(MyState::sensitiveData, Lifecycle.State.RESUMED) { data ->
     *         displaySensitiveInfo(data)
     *     }
     * }
     * ```
     *
     * @param A The property type
     * @param property The state property to collect
     * @param state The minimum lifecycle state for collection
     * @param block The suspend function to call with each distinct property value
     * @return A Job for this specific collector
     */
    fun <A> collectPartial(
        property: KProperty1<S, A>,
        state: Lifecycle.State,
        block: suspend (A) -> Unit
    ): Job {
        return flow.map { property.get(it) }
            .distinctUntilChanged()
            .launchWithLifecycle(owner, state, job, block)
    }

    /**
     * Collects the entire state object.
     *
     * Uses [distinctUntilChanged] to emit only when the state changes.
     * Relies on the state class implementing [equals] correctly (data classes do this automatically).
     * Uses the default lifecycle state specified in [StateCollector] constructor.
     *
     * @param block The suspend function to call with each distinct state
     * @return A Job for this specific collector
     */
    fun collectWhole(block: suspend (S) -> Unit): Job = collectWhole(state, block)

    /**
     * Collects the entire state object with a specific lifecycle state.
     *
     * Uses [distinctUntilChanged] to emit only when the state changes.
     *
     * @param state The minimum lifecycle state for collection
     * @param block The suspend function to call with each distinct state
     * @return A Job for this specific collector
     */
    fun collectWhole(state: Lifecycle.State, block: suspend (S) -> Unit): Job {
        return flow.distinctUntilChanged()
            .launchWithLifecycle(owner, state, job, block)
    }
}

/**
 * Collects a single property of the state flow with lifecycle awareness.
 *
 * This is a standalone function (not part of [StateCollector]) for simple cases
 * where you only need to collect one property.
 *
 * ## Usage Example
 *
 * ```kotlin
 * stateFlow.collectPartialState(
 *     property = MyState::loading,
 *     owner = viewLifecycleOwner
 * ) { isLoading ->
 *     progressBar.isVisible = isLoading
 * }
 * ```
 *
 * @param S The state type
 * @param A The property type
 * @param property The state property to collect
 * @param owner The lifecycle owner
 * @param state The minimum lifecycle state (default: STARTED)
 * @param context Additional coroutine context
 * @param block The suspend function to call with each distinct property value
 * @return A Job that can be cancelled
 */
fun <S : Mvi.State, A> Flow<S>.collectPartialState(
    property: KProperty1<S, A>,
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend (A) -> Unit,
): Job {
    return map { property.get(it) }
        .distinctUntilChanged()
        .launchWithLifecycle(owner, state, context, block)
}


/**
 * Collects event flow with lifecycle awareness using a DSL-style builder.
 *
 * This function provides a convenient way to collect multiple event types
 * simultaneously, with each collection managed by the same lifecycle.
 *
 * ## Usage Example
 *
 * ```kotlin
 * class MyFragment : Fragment() {
 *     private val viewModel: MyViewModel by viewModels()
 *
 *     override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
 *         viewModel.eventFlow.collectEvent(viewLifecycleOwner) {
 *             collectParticular<ShowToast> { event ->
 *                 Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
 *             }
 *
 *             collectParticular<Navigate> { event ->
 *                 findNavController().navigate(event.destination)
 *             }
 *
 *             // Collect all events
 *             collectAll { event ->
 *                 Log.d(TAG, "Event: $event")
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @param E The event type
 * @param owner The lifecycle owner
 * @param state The minimum lifecycle state (default: STARTED)
 * @param collector A lambda with receiver to configure event collectors
 * @return A Job that manages all collectors. Cancelling this job will cancel all collectors at once.
 * @see EventCollector
 * @see EventCollector.collectParticular
 * @see EventCollector.collectAll
 */
fun <E : Mvi.Event> Flow<E>.collectEvent(
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    collector: EventCollector<E>.() -> Unit,
): Job = EventCollector(this, owner, state).apply(collector).job

/**
 * A DSL builder for collecting event flow with lifecycle awareness.
 *
 * EventCollector manages multiple event collectors under a single supervisor.
 * Each collector is independent - if one fails, others continue to run.
 *
 * ## Key Features
 *
 * - **Lifecycle aware**: Automatically starts/stops based on lifecycle
 * - **Type-safe filtering**: Collect specific event types with [collectParticular]
 * - **Collect all**: Collect all event types with [collectAll]
 * - **Supervised**: One collector's failure doesn't affect others
 *
 * ## Example
 *
 * ```kotlin
 * eventFlow.collectEvent(viewLifecycleOwner) {
 *     collectParticular<ShowError> { error ->
 *         showErrorDialog(error.message)
 *     }
 *
 *     collectParticular<ShowToast>(Lifecycle.State.RESUMED) { toast ->
 *         // Only show toasts when RESUMED
 *         showToast(toast.message)
 *     }
 * }
 * ```
 *
 * @param E The event type
 * @see collectEvent
 * @see EventCollector.collectParticular
 * @see EventCollector.collectAll
 */
class EventCollector<E : Mvi.Event> internal constructor(
    private val flow: Flow<E>,
    private val owner: LifecycleOwner,
    @PublishedApi
    internal val state: Lifecycle.State,
) {
    /**
     * The supervisor job that manages all collectors.
     *
     * This job is a child of [owner]'s lifecycleScope, ensuring automatic cleanup
     * when the lifecycle is destroyed. Cancelling this job will cancel all collectors
     * created by [collectParticular] and [collectAll].
     * This job is returned by [collectEvent] and can be used to cancel all collectors at once.
     */
    internal val job: Job = SupervisorJob(owner.lifecycleScope.coroutineContext[Job])

    /**
     * Collects events of a specific type using reified type parameter.
     *
     * Uses the default lifecycle state specified in [EventCollector] constructor.
     *
     * ## Example
     *
     * ```kotlin
     * eventFlow.collectEvent(viewLifecycleOwner) {
     *     collectParticular<ShowToast> { event ->
     *         showToast(event.message)
     *     }
     * }
     * ```
     *
     * @param A The specific event type to collect (must be a subtype of E)
     * @param block The suspend function to call with each event of type A
     * @return A Job for this specific collector
     */
    inline fun <reified A : E> collectParticular(
        noinline block: suspend (A) -> Unit
    ): Job = collectParticular(state, block)

    /**
     * Collects events of a specific type with a specific lifecycle state.
     *
     * @param A The specific event type to collect
     * @param state The minimum lifecycle state for collection
     * @param block The suspend function to call with each event of type A
     * @return A Job for this specific collector
     */
    inline fun <reified A : E> collectParticular(
        state: Lifecycle.State,
        noinline block: suspend (A) -> Unit
    ): Job = collectParticular(A::class, state, block)

    /**
     * Collects events of a specific type using KClass.
     *
     * This is the non-inline version that accepts a class reference.
     *
     * @param A The specific event type to collect
     * @param clazz The class of the event type
     * @param block The suspend function to call with each event of type A
     * @return A Job for this specific collector
     */
    fun <A : E> collectParticular(
        clazz: KClass<A>,
        block: suspend (A) -> Unit
    ): Job = collectParticular(clazz, state, block)

    /**
     * Collects events of a specific type using KClass with a specific lifecycle state.
     *
     * @param A The specific event type to collect
     * @param clazz The class of the event type
     * @param state The minimum lifecycle state for collection
     * @param block The suspend function to call with each event of type A
     * @return A Job for this specific collector
     */
    fun <A : E> collectParticular(
        clazz: KClass<A>,
        state: Lifecycle.State,
        block: suspend (A) -> Unit
    ): Job {
        @Suppress("UNCHECKED_CAST")
        return (flow.filter { clazz.isInstance(it) } as Flow<A>)
            .launchWithLifecycle(owner, state, job, block)
    }

    /**
     * Collects all events regardless of type.
     *
     * Uses the default lifecycle state specified in [EventCollector] constructor.
     *
     * @param block The suspend function to call with each event
     * @return A Job for this specific collector
     */
    fun collectAll(block: suspend (E) -> Unit): Job = collectAll(state, block)

    /**
     * Collects all events with a specific lifecycle state.
     *
     * @param state The minimum lifecycle state for collection
     * @param block The suspend function to call with each event
     * @return A Job for this specific collector
     */
    fun collectAll(state: Lifecycle.State, block: suspend (E) -> Unit): Job {
        return flow.launchWithLifecycle(owner, state, job, block)
    }
}

/**
 * Collects events of a specific type with lifecycle awareness.
 *
 * This is a standalone function for collecting a specific event type without
 * using [EventCollector]. Useful for simple cases where you only need to
 * collect one event type.
 *
 * ## Usage Example
 *
 * ```kotlin
 * eventFlow.collectParticularEvent<ShowToast>(
 *     owner = viewLifecycleOwner
 * ) { event ->
 *     showToast(event.message)
 * }
 * ```
 *
 * @param E The specific event type to collect
 * @param owner The lifecycle owner
 * @param state The minimum lifecycle state (default: STARTED)
 * @param context Additional coroutine context
 * @param block The suspend function to call with each event of type E
 * @return A Job that can be cancelled
 */
inline fun <reified E : Mvi.Event> Flow<Mvi.Event>.collectParticularEvent(
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
    crossinline block: suspend (E) -> Unit,
): Job = filterIsInstance<E>().launchWithLifecycle(owner, state, context, block)


/**
 * Launches collection of a flow with lifecycle awareness.
 *
 * This is a lower-level function for cases where you need fine-grained control
 * over the collection process. For collecting MVI state and events, prefer using
 * [collectState] or [collectEvent] which provide more convenient DSL features.
 *
 * Collection starts when the lifecycle reaches the specified state and stops
 * when it falls below that state. Collection automatically resumes when the
 * lifecycle returns to the required state.
 *
 * ## When to use this
 *
 * - When you need to collect a generic Flow (not MVI state/event)
 * - When you need custom [CoroutineStart] behavior (e.g., ATOMIC, LAZY)
 * - When you don't need the DSL features of [StateCollector] or [EventCollector]
 *
 * ## Usage Example
 *
 * ```kotlin
 * someFlow.launchCollect(
 *     owner = viewLifecycleOwner,
 *     state = Lifecycle.State.STARTED
 * ) { value ->
 *     processValue(value)
 * }
 * ```
 *
 * @param T The flow value type
 * @param owner The lifecycle owner
 * @param state The minimum lifecycle state (default: STARTED)
 * @param context Additional coroutine context
 * @param start The coroutine start mode (default: DEFAULT, suitable for most cases)
 * @param block The suspend function to call with each value
 * @return A Job that can be cancelled
 * @see collectState
 * @see collectEvent
 * @see launchWithLifecycle
 */
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

/**
 * Launches collection of a flow in a coroutine scope.
 *
 * This version doesn't have lifecycle awareness - collection continues until
 * the scope is cancelled.
 *
 * ## Usage Example
 *
 * ```kotlin
 * someFlow.launchCollect(
 *     scope = viewModelScope
 * ) { value ->
 *     processValue(value)
 * }
 * ```
 *
 * @param T The flow value type
 * @param scope The coroutine scope
 * @param context Additional coroutine context
 * @param start The coroutine start mode (default: DEFAULT)
 * @param block The suspend function to call with each value
 * @return A Job that can be cancelled
 */
fun <T> Flow<T>.launchCollect(
    scope: CoroutineScope,
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend (T) -> Unit,
): Job = scope.launch(context, start) {
    this@launchCollect.collect(block)
}


/**
 * Dispatches intents when lifecycle reaches at least the specified state.
 *
 * This function waits for the lifecycle to reach the specified state, then
 * starts dispatching intents from the flow. If the lifecycle falls below the
 * required state, dispatching continues (unlike [launchCollect] which pauses).
 *
 * ## Usage Example
 *
 * ```kotlin
 * intentFlow.dispatchAtLeast(
 *     owner = viewLifecycleOwner,
 *     state = Lifecycle.State.RESUMED,
 *     dispatch = viewModel::dispatch
 * )
 * ```
 *
 * ## Note
 *
 * This uses [withStateAtLeast] rather than [repeatOnLifecycle], meaning:
 * - Waits for the lifecycle to reach the state once
 * - Then dispatches all intents continuously
 * - Does NOT pause dispatching if lifecycle state drops
 *
 * @param I The intent type
 * @param owner The lifecycle owner
 * @param state The minimum lifecycle state required to start dispatching
 * @param dispatch The function to dispatch each intent
 * @return A Job that can be cancelled
 */
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


/**
 * Launches collection of a flow with lifecycle awareness.
 *
 * This is the base utility function used by other lifecycle-aware collectors.
 * Collection starts when the lifecycle reaches the specified state and stops
 * when it falls below that state.
 *
 * All collections are launched on the main thread (via [LifecycleOwner.lifecycleScope]).
 * The [context] parameter can include a parent Job to manage cancellation.
 *
 * ## Usage Example
 *
 * ```kotlin
 * someFlow.launchWithLifecycle(
 *     owner = viewLifecycleOwner
 * ) { value ->
 *     processValue(value)
 * }
 * ```
 *
 * @param T The flow value type
 * @param owner The lifecycle owner
 * @param state The minimum lifecycle state for collection (default: STARTED)
 * @param context Additional coroutine context (typically contains a parent Job)
 * @param block The suspend function to call with each value
 * @return A Job that can be cancelled
 */
inline fun <T> Flow<T>.launchWithLifecycle(
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
    crossinline block: suspend (T) -> Unit
): Job = owner.lifecycleScope.launch(context) {
    owner.repeatOnLifecycle(state) {
        this@launchWithLifecycle.collect { block(it) }
    }
}
