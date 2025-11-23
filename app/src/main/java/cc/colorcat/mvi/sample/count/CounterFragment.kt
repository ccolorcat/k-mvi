package cc.colorcat.mvi.sample.count

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import cc.colorcat.mvi.collectEvent
import cc.colorcat.mvi.collectState
import cc.colorcat.mvi.debounceFirst
import cc.colorcat.mvi.doOnClick
import cc.colorcat.mvi.sample.count.CounterContract.Event
import cc.colorcat.mvi.sample.count.CounterContract.Intent
import cc.colorcat.mvi.sample.count.CounterContract.State
import cc.colorcat.mvi.sample.databinding.FragmentCounterBinding
import cc.colorcat.mvi.sample.util.showToast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * Fragment demonstrating MVI pattern usage with [CounterViewModel].
 *
 * This sample demonstrates a **reactive/declarative** MVI implementation using k-mvi library's
 * convenience extensions:
 * 1. **Observe State**: Use [collectState] to efficiently collect partial state changes
 * 2. **Handle Events**: Use [collectEvent] to handle one-time events with type-safe collectors
 * 3. **Dispatch Intents**: Create reactive intent flows using [doOnClick] and merge them
 *
 * **Key Patterns Demonstrated:**
 *
 * **Reactive Intent Collection:**
 * - Create separate intent flows for each user action (button clicks)
 * - Merge all intent flows into a single stream
 * - Subscribe to the merged flow and dispatch to ViewModel
 * - This creates a unidirectional data flow: UI → Intent → ViewModel → State → UI
 *
 * **Efficient State Observation:**
 * - Use [collectState] with lifecycle awareness (automatically handles lifecycle)
 * - Use `collectPartial` to observe only specific state properties (e.g., `State::countText`)
 * - Only update UI when the observed property actually changes (efficient rendering)
 *
 * **Type-Safe Event Handling:**
 * - Use [collectEvent] to handle events with lifecycle awareness
 * - Use `collectParticular` to handle specific event types with type safety
 * - Events are consumed only once and automatically managed
 *
 * **Benefits of This Approach:**
 * - Declarative and functional style (what, not how)
 * - Automatic lifecycle management (no manual repeatOnLifecycle needed)
 * - Type-safe event handling (compile-time safety)
 * - Efficient state updates (only when changed)
 * - Clean separation of concerns
 *
 * Author: ccolorcat
 * Date: 2025-11-14
 * GitHub: https://github.com/ccolorcat
 */
class CounterFragment : Fragment() {
    /**
     * ViewModel instance using the AndroidX ViewModel delegate.
     * Scoped to this Fragment's lifecycle and survives configuration changes.
     */
    private val viewModel: CounterViewModel by viewModels()

    /**
     * ViewBinding instance for type-safe view access.
     * Using nullable backing property with non-null getter for safe access.
     * Must be cleared in onDestroyView to prevent memory leaks.
     */
    private var _binding: FragmentCounterBinding? = null
    private val binding get() = _binding!!

    /**
     * Lazy property that creates a merged Flow of all user intents.
     * This creates a reactive stream of user actions that will be dispatched to the ViewModel.
     * The flow is recreated when accessed, ensuring it uses the current binding instance.
     *
     * **Debouncing Strategy:**
     * - Increment/Decrement intents are debounced with 600ms to prevent rapid clicks
     * - Reset intent is also debounced to prevent accidental double-clicks during async operation
     * - Uses [debounceFirst] to emit the first click and ignore subsequent clicks within the time window
     */
    private val intents: Flow<Intent>
        get() = merge(
            counterIntents().debounceFirst(150L),
            binding.reset.doOnClick { trySend(Intent.Reset) }.debounceFirst(600L),
        )

    /**
     * Creates a merged Flow of increment and decrement intents.
     *
     * These two intents are grouped together because they share similar characteristics:
     * - Both modify the counter value incrementally
     * - Both should be debounced to prevent rapid clicks
     * - Both are frequent user operations that benefit from shared debouncing
     *
     * Separating them from the reset intent allows different debouncing strategies
     * or rate limiting if needed in the future.
     *
     * @return A merged Flow emitting [Intent.Increment] and [Intent.Decrement]
     */
    private fun counterIntents(): Flow<Intent> = merge(
        binding.increment.doOnClick { trySend(Intent.Increment) },
        binding.decrement.doOnClick { trySend(Intent.Decrement) },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCounterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViewModel()
    }

    /**
     * Setup ViewModel connection by subscribing to the merged intent flow.
     *
     * **Pattern: Reactive Intent Dispatch**
     * - Subscribe to the merged intent flow from [intents] property
     * - Dispatch each emitted intent to the ViewModel for processing
     * - Launch in lifecycleScope to automatically cancel when Fragment is destroyed
     *
     * **Flow:**
     * 1. User clicks button → doOnClick emits intent → debounced → merged flow emits intent
     * 2. onEach receives intent → dispatch to ViewModel
     * 3. ViewModel processes intent → updates state or emits event
     * 4. State/Event flows notify observers → UI updates
     *
     * **Debouncing:**
     * - Increment/Decrement intents are debounced (600ms) to prevent rapid firing
     * - Reset intent is also debounced to prevent accidental double-clicks
     * - This improves UX and reduces unnecessary ViewModel processing
     *
     * This creates a complete unidirectional data flow cycle with rate limiting.
     */
    private fun setupViewModel() {
        // **Pattern 1: Efficient Partial State Collection**
        // collectState: Lifecycle-aware state collection (automatically starts/stops with lifecycle)
        // collectPartial: Observe only specific state properties, update UI only when they change
        // Benefits:
        // - Efficient: Only triggers when State::countText actually changes (not on every state emission)
        // - Concise: Direct method reference (binding.count::setText) for clean UI binding
        // - Type-safe: Compiler ensures State::countText returns String compatible with TextView.setText
        // - Computed property: countText is derived from count, providing presentation logic separation
        viewModel.stateFlow.collectState(viewLifecycleOwner) {
            collectPartial(State::countText, binding.count::setText)
            collectPartial(State::countInfo, binding.counterInfo::setText)
            collectPartial(State::showLoading, binding.loadingBar::isVisible::set)
            collectPartial(State::alpha255, Lifecycle.State.RESUMED) {
                binding.root.background?.alpha = it
            }
        }

        // **Pattern 2: Type-Safe Event Handling**
        // collectEvent: Lifecycle-aware event collection (events consumed only once)
        // collectParticular: Handle specific event types with type safety (compile-time checking)
        // Benefits:
        // - Type-safe: Only Event.ShowToast events are handled here
        // - Exhaustive: Compiler helps ensure all event types are handled somewhere
        // - Lifecycle-aware: Automatically stops collecting when Fragment is destroyed
        viewModel.eventFlow.collectEvent(this) {
            collectParticular<Event.ShowToast> { event ->
                context?.showToast(event.message)
            }
        }

        intents.onEach { viewModel.dispatch(it) }.launchIn(lifecycleScope)
    }

    /**
     * Clean up the binding when the view is destroyed to prevent memory leaks.
     *
     * This is a ViewBinding best practice: always set binding to null in onDestroyView
     * to avoid holding references to views that have been destroyed.
     */
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

