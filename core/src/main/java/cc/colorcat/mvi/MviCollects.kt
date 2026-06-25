package cc.colorcat.mvi

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * Lifecycle-aware utilities for collecting MVI state and event flows.
 *
 * This file provides convenient extension functions and DSL builders for collecting
 * state and event flows with automatic lifecycle management. All collectors respect
 * the Android lifecycle and automatically start/stop collection based on lifecycle state.
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
 *             collectProperty(MyState::loading) { isLoading ->
 *                 progressBar.isVisible = isLoading
 *             }
 *
 *             collectProperty(MyState::data) { data ->
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
 * @see StateCollector.collectProperty
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
 * - **Property-level collection**: Collect individual state properties with [collectProperty]
 * - **Whole state collection**: Collect entire state with [collectWhole]
 * - **Deduplication**: Automatically filters duplicate values using [distinctUntilChanged]
 * - **Supervised**: One collector's failure doesn't affect others
 *
 * ## Example
 *
 * ```kotlin
 * stateFlow.collectState(viewLifecycleOwner) {
 *     collectProperty(MyState::isLoading) { loading ->
 *         showLoadingIndicator(loading)
 *     }
 *
 *     collectProperty(MyState::errorMessage) { message ->
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
 * @see StateCollector.collectProperty
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
     * created by [collectProperty] and [collectWhole].
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
    fun <A> collectProperty(
        property: KProperty1<S, A>,
        block: suspend (A) -> Unit,
    ): Job = collectProperty(property, state, block)

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
     *     collectProperty(MyState::sensitiveData, Lifecycle.State.RESUMED) { data ->
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
    fun <A> collectProperty(
        property: KProperty1<S, A>,
        state: Lifecycle.State,
        block: suspend (A) -> Unit,
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
 * where you only need to collect one property. It is the free-function counterpart of
 * [StateCollector.collectProperty]: same behavior, but you pass [owner] explicitly instead
 * of collecting inside a [collectState] block. When collecting several properties, prefer the
 * [collectState] DSL and its [StateCollector.collectProperty] member.
 *
 * ## Usage Example
 *
 * ```kotlin
 * stateFlow.collectProperty(
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
 * @see StateCollector.collectProperty
 * @see collectState
 */
fun <S : Mvi.State, A> Flow<S>.collectProperty(
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
 *             collectTyped<ShowToast> { event ->
 *                 Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
 *             }
 *
 *             collectTyped<Navigate> { event ->
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
 * @see EventCollector.collectTyped
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
 * - **Type-safe filtering**: Collect specific event types with [collectTyped]
 * - **Collect all**: Collect all event types with [collectAll]
 * - **Supervised**: One collector's failure doesn't affect others
 *
 * ## Example
 *
 * ```kotlin
 * eventFlow.collectEvent(viewLifecycleOwner) {
 *     collectTyped<ShowError> { error ->
 *         showErrorDialog(error.message)
 *     }
 *
 *     collectTyped<ShowToast>(Lifecycle.State.RESUMED) { toast ->
 *         // Only show toasts when RESUMED
 *         showToast(toast.message)
 *     }
 * }
 * ```
 *
 * @param E The event type
 * @see collectEvent
 * @see EventCollector.collectTyped
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
     * created by [collectTyped] and [collectAll].
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
     *     collectTyped<ShowToast> { event ->
     *         showToast(event.message)
     *     }
     * }
     * ```
     *
     * @param A The specific event type to collect (must be a subtype of E)
     * @param block The suspend function to call with each event of type A
     * @return A Job for this specific collector
     */
    inline fun <reified A : E> collectTyped(
        noinline block: suspend (A) -> Unit,
    ): Job = collectTyped(state, block)

    /**
     * Collects events of a specific type with a specific lifecycle state.
     *
     * @param A The specific event type to collect
     * @param state The minimum lifecycle state for collection
     * @param block The suspend function to call with each event of type A
     * @return A Job for this specific collector
     */
    inline fun <reified A : E> collectTyped(
        state: Lifecycle.State,
        noinline block: suspend (A) -> Unit,
    ): Job = collectTyped(A::class, state, block)

    /**
     * Collects events of a specific type using a [KClass] reference with a specific lifecycle state.
     *
     * This is the non-inline implementation used by the reified [collectTyped] overloads internally.
     * Prefer the reified overloads (`collectTyped<MyEvent> { ... }`) at call sites where the type
     * is known at compile time. Use this overload only when the type must be supplied dynamically
     * at runtime.
     *
     * @param A The specific event type to collect
     * @param clazz The class of the event type
     * @param state The minimum lifecycle state for collection
     * @param block The suspend function to call with each event of type A
     * @return A Job for this specific collector
     */
    fun <A : E> collectTyped(
        clazz: KClass<A>,
        state: Lifecycle.State,
        block: suspend (A) -> Unit,
    ): Job {
        return flow
            .filter { clazz.isInstance(it) }
            .map { clazz.java.cast(it) as A }
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
 * This is a standalone function (not part of [EventCollector]) for simple cases where you only
 * need to collect one event type. It is the free-function counterpart of
 * [EventCollector.collectTyped]: same behavior, but you pass [owner] explicitly instead of
 * collecting inside a [collectEvent] block. When collecting several event types, prefer the
 * [collectEvent] DSL and its [EventCollector.collectTyped] member (collectors then share one
 * supervisor job).
 *
 * ## Usage Example
 *
 * ```kotlin
 * eventFlow.collectTyped<ShowToast>(
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
 * @see EventCollector.collectTyped
 * @see collectEvent
 */
inline fun <reified E : Mvi.Event> Flow<Mvi.Event>.collectTyped(
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
    crossinline block: suspend (E) -> Unit,
): Job = filterIsInstance<E>().launchWithLifecycle(owner, state, context, block)


/**
 * Dispatches intents with full lifecycle awareness using [repeatOnLifecycle].
 *
 * Collection starts when the lifecycle reaches [state] and **stops** when the
 * lifecycle drops below that state. Collection automatically restarts when the
 * lifecycle returns to the required state. This matches the behavior of
 * [launchWithLifecycle] and [collectState] / [collectEvent].
 *
 * ## Usage Example
 *
 * ```kotlin
 * intentFlow.dispatchWithLifecycle(
 *     owner = viewLifecycleOwner,
 *     state = Lifecycle.State.STARTED,
 *     dispatch = { intent -> viewModel.dispatch(intent) }
 * )
 * ```
 *
 * ## Lifecycle Behavior
 *
 * - Collection pauses when lifecycle drops below [state] (e.g., Fragment goes to background)
 * - Collection resumes when lifecycle returns to [state]
 * - Collection stops permanently when the lifecycle is destroyed
 *
 * For hot flows with no replay, intents emitted while the lifecycle is below [state]
 * are not collected and therefore are not dispatched later. Use an upstream replaying
 * or persistent source if those intents must survive lifecycle stops.
 *
 * @param I The intent type
 * @param owner The lifecycle owner
 * @param state The minimum lifecycle state required for collection (default: STARTED)
 * @param dispatch The function to dispatch each intent.
 * @return A Job that can be cancelled
 * @see launchWithLifecycle
 */
fun <I : Mvi.Intent> Flow<I>.dispatchWithLifecycle(
    owner: LifecycleOwner,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    dispatch: (I) -> Unit,
): Job = owner.lifecycleScope.launch {
    owner.repeatOnLifecycle(state) {
        collect { dispatch(it) }
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
    crossinline block: suspend (T) -> Unit,
): Job = owner.lifecycleScope.launch(context) {
    owner.repeatOnLifecycle(state) {
        this@launchWithLifecycle.collect { block(it) }
    }
}
