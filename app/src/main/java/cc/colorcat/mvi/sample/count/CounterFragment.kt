package cc.colorcat.mvi.sample.count

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import cc.colorcat.mvi.collectEvent
import cc.colorcat.mvi.collectState
import cc.colorcat.mvi.debounceLeading
import cc.colorcat.mvi.dispatchWithLifecycle
import cc.colorcat.mvi.doOnClick
import cc.colorcat.mvi.sample.R
import cc.colorcat.mvi.sample.count.CounterContract.Event
import cc.colorcat.mvi.sample.count.CounterContract.Intent
import cc.colorcat.mvi.sample.count.CounterContract.State
import cc.colorcat.mvi.sample.databinding.FragmentCounterBinding
import cc.colorcat.mvi.sample.util.showToast
import cc.colorcat.mvi.sample.util.viewBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

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
 * - Use `collectProperty` to observe only specific state properties (e.g., `State::countText`)
 * - Only update UI when the observed property actually changes (efficient rendering)
 *
 * **Type-Safe Event Handling:**
 * - Use [collectEvent] to handle events with lifecycle awareness
 * - Use `collectTyped` to handle specific event types with type safety
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
     * The delegate clears the cached binding when the Fragment view is destroyed.
     */
    private val binding: FragmentCounterBinding by viewBinding(FragmentCounterBinding::bind)

    /**
     * Merged stream of every user intent, dispatched to the ViewModel in [setupViewModel].
     *
     * Evaluated through a `get()` so it always reads the current [binding] instance.
     *
     * **Debouncing strategy** (via [debounceLeading], which emits the first click and ignores
     * rapid follow-ups within the window):
     * - Increment/Decrement: 300 ms — prevents accidental rapid taps.
     * - Reset: 600 ms — longer, since it kicks off an async operation.
     */
    private val intents: Flow<Intent>
        get() = merge(
            counterIntents().debounceLeading(300L),
            binding.reset.doOnClick { trySend(Intent.Reset) }.debounceLeading(600L),
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
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_counter, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViewModel()
    }

    /**
     * Wires the view to the ViewModel: render state, handle events, and dispatch intents.
     *
     * **Flow:**
     * 1. User clicks a button → `doOnClick` emits an intent → debounced → merged into [intents]
     * 2. [dispatchWithLifecycle] forwards each intent to the ViewModel
     * 3. The ViewModel processes the intent → updates state and/or emits an event
     * 4. State/Event flows notify the collectors below → UI updates
     *
     * All three collectors are bound to [viewLifecycleOwner], so they start/stop with the view's
     * lifecycle (not the Fragment's) and never touch a destroyed [binding].
     */
    private fun setupViewModel() {
        // **Pattern 1: Efficient Partial State Collection**
        // collectState: Lifecycle-aware state collection (automatically starts/stops with lifecycle)
        // collectProperty: Observe only specific state properties, update UI only when they change
        // Benefits:
        // - Efficient: Only triggers when State::countText actually changes (not on every state emission)
        // - Concise: Direct method reference (binding.count::setText) for clean UI binding
        // - Type-safe: Compiler ensures State::countText returns String compatible with TextView.setText
        // - Computed property: countText is derived from count, providing presentation logic separation
        viewModel.stateFlow.collectState(viewLifecycleOwner) {
            collectProperty(State::countText, binding.count::setText)
            collectProperty(State::countInfo, binding.counterInfo::setText)
            collectProperty(State::showLoading, binding.loadingBar::isVisible::set)
            collectProperty(State::alpha255, Lifecycle.State.RESUMED) {
                binding.root.background?.alpha = it
            }
        }

        // **Pattern 2: Type-Safe Event Handling**
        // collectEvent: Lifecycle-aware event collection (events consumed only once)
        // collectTyped: Handle specific event types with type safety (compile-time checking)
        // Benefits:
        // - Type-safe: Only Event.ShowToast events are handled here
        // - Exhaustive: Compiler helps ensure all event types are handled somewhere
        // - Lifecycle-aware: Automatically stops collecting when Fragment is destroyed
        viewModel.eventFlow.collectEvent(viewLifecycleOwner) {
            collectTyped<Event.ShowToast> { event ->
                context?.showToast(event.message)
            }
        }

        intents.dispatchWithLifecycle(viewLifecycleOwner) { viewModel.dispatch(it) }
    }
}
