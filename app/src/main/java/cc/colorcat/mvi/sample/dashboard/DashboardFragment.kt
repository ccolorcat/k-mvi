package cc.colorcat.mvi.sample.dashboard

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import cc.colorcat.mvi.collectEvent
import cc.colorcat.mvi.collectState
import cc.colorcat.mvi.debounceLeading
import cc.colorcat.mvi.doOnClick
import cc.colorcat.mvi.sample.R
import cc.colorcat.mvi.sample.dashboard.DashboardContract.Intent
import cc.colorcat.mvi.sample.dashboard.DashboardContract.State
import cc.colorcat.mvi.sample.databinding.FragmentDashboardBinding
import cc.colorcat.mvi.sample.util.showToast
import cc.colorcat.mvi.sample.util.viewBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * Dashboard fragment demonstrating three intent-handling strategies.
 *
 * Follows the same **reactive intent dispatch** pattern as CounterFragment:
 * - Each button is converted to a [Flow]<[Intent]> via [doOnClick]
 * - Related button flows are combined with [merge] into per-section helpers
 * - A single top-level [intents] property merges all sections
 * - One `intents.onEach { viewModel.dispatch(it) }.launchIn(lifecycleScope)` drives everything
 *
 * ### Debouncing strategy
 * Single-emit buttons use [debounceLeading] to prevent accidental double-taps.
 * Multi-emit "Trigger All" buttons intentionally skip debounce — [debounceLeading]
 * would suppress the 2nd, 3rd… intents fired synchronously in the same click callback.
 */
class DashboardFragment : Fragment() {

    private val viewModel: DashboardViewModel by viewModels()
    private val binding: FragmentDashboardBinding by viewBinding(FragmentDashboardBinding::bind)

    // ── Intent flows ──────────────────────────────────────────────────────────────────────

    /**
     * Merged stream of every user intent on this screen.
     *
     * Declared as a computed property (with `get()`) so the flow is rebuilt on each access,
     * always referencing the current live [binding] instance — matching the pattern in
     * CounterFragment.
     */
    private val intents: Flow<Intent>
        get() = merge(
            concurrentIntents(),
            sequentialIntents(),
            groupedIntents(),
            binding.btnClearLog.doOnClick { trySend(Intent.ClearLog) },
        )

    /**
     * Section 1 — Concurrent intent flows.
     *
     * Individual buttons are debounced. "Trigger All 3" emits three intents per click
     * synchronously, so debounce is omitted on that flow to avoid dropping two of them.
     */
    private fun concurrentIntents(): Flow<Intent> = binding.run {
        merge(
            btnLoadBanners.doOnClick { trySend(Intent.LoadBanners) },
            btnLoadRecs.doOnClick { trySend(Intent.LoadRecommendations) },
            btnLoadFlashSale.doOnClick { trySend(Intent.LoadFlashSale) },
            btnTriggerAllConcurrent.doOnClick {
                trySend(Intent.LoadBanners)
                trySend(Intent.LoadRecommendations)
                trySend(Intent.LoadFlashSale)
            },
        )
    }

    /**
     * Section 2 — Sequential intent flows.
     *
     * Individual cart buttons are debounced. "Trigger Sequence" fires four intents per click,
     * so debounce is omitted there.
     */
    private fun sequentialIntents(): Flow<Intent> = binding.run {
        merge(
            btnAddApple.doOnClick { trySend(Intent.AddToCart("Apple")) },
            btnAddBanana.doOnClick { trySend(Intent.AddToCart("Banana")) },
            btnAddOrange.doOnClick { trySend(Intent.AddToCart("Orange")) },
            btnRemoveLast.doOnClick { trySend(Intent.RemoveLastFromCart) },
            btnCheckout.doOnClick { trySend(Intent.Checkout) },
            // 4 intents per click — no debounce
            btnTriggerSequence.doOnClick {
                trySend(Intent.AddToCart("Apple"))
                trySend(Intent.AddToCart("Banana"))
                trySend(Intent.RemoveLastFromCart)
                trySend(Intent.Checkout)
            },
        )
    }

    /**
     * Section 3 — Grouped intent flows.
     *
     * Individual batch buttons are debounced. "Trigger All Groups" fires 9 intents per click
     * (3 categories × 3 batches), so debounce is omitted.
     */
    private fun groupedIntents(): Flow<Intent> = binding.run {
        merge(
            // Phones
            btnPhonesB1.doOnClick { trySend(Intent.LoadCategory(DashboardContract.CATEGORY_PHONES, 1)) },
            btnPhonesB2.doOnClick { trySend(Intent.LoadCategory(DashboardContract.CATEGORY_PHONES, 2)) },
            btnPhonesB3.doOnClick { trySend(Intent.LoadCategory(DashboardContract.CATEGORY_PHONES, 3)) },
            // Laptops
            btnLaptopsB1.doOnClick { trySend(Intent.LoadCategory(DashboardContract.CATEGORY_LAPTOPS, 1)) },
            btnLaptopsB2.doOnClick { trySend(Intent.LoadCategory(DashboardContract.CATEGORY_LAPTOPS, 2)) },
            btnLaptopsB3.doOnClick { trySend(Intent.LoadCategory(DashboardContract.CATEGORY_LAPTOPS, 3)) },
            // Tablets
            btnTabletsB1.doOnClick { trySend(Intent.LoadCategory(DashboardContract.CATEGORY_TABLETS, 1)) },
            btnTabletsB2.doOnClick { trySend(Intent.LoadCategory(DashboardContract.CATEGORY_TABLETS, 2)) },
            btnTabletsB3.doOnClick { trySend(Intent.LoadCategory(DashboardContract.CATEGORY_TABLETS, 3)) },
            // 9 intents per click — no debounce
            btnTriggerAllGroups.doOnClick {
                for (batch in 1..3) {
                    trySend(Intent.LoadCategory(DashboardContract.CATEGORY_PHONES, batch))
                    trySend(Intent.LoadCategory(DashboardContract.CATEGORY_LAPTOPS, batch))
                    trySend(Intent.LoadCategory(DashboardContract.CATEGORY_TABLETS, batch))
                }
            },
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupObservers()
        intents.onEach { viewModel.dispatch(it) }.launchIn(lifecycleScope)
    }

    // ── State / Event observers ───────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun setupObservers(): Unit = binding.run {
        viewModel.stateFlow.collectState(viewLifecycleOwner) {
            // Section 1
            collectProperty(State::bannerLoading) { loading ->
                btnLoadBanners.isEnabled = !loading
            }
            collectProperty(State::recommendationsLoading) { loading ->
                btnLoadRecs.isEnabled = !loading
            }
            collectProperty(State::flashSaleLoading) { loading ->
                btnLoadFlashSale.isEnabled = !loading
            }
            collectProperty(State::bannersText) { text ->
                tvBannersStatus.text = "Banners: $text"
            }
            collectProperty(State::recommendationsText) { text ->
                tvRecsStatus.text = "Recs: $text"
            }
            collectProperty(State::flashSaleText) { text ->
                tvFlashStatus.text = "Flash Sale: $text"
            }

            // Section 2
            collectProperty(State::cartText) { text -> tvCartStatus.text = "Cart: $text" }
            collectProperty(State::orderStatus) { text ->
                tvOrderStatus.text = "Order: $text"
            }
            collectProperty(State::cartLoading) { loading ->
                btnAddApple.isEnabled = !loading
                btnAddBanana.isEnabled = !loading
                btnAddOrange.isEnabled = !loading
                btnRemoveLast.isEnabled = !loading
                btnCheckout.isEnabled = !loading
            }

            // Section 3
            collectProperty(State::phonesText) { text ->
                tvPhonesStatus.text = "Phones: $text"
            }
            collectProperty(State::laptopsText) { text ->
                tvLaptopsStatus.text = "Laptops: $text"
            }
            collectProperty(State::tabletsText) { text ->
                tvTabletsStatus.text = "Tablets: $text"
            }
            collectProperty(State::phonesLoading) { loading ->
                btnPhonesB1.isVisible = !loading
                btnPhonesB2.isVisible = !loading
                btnPhonesB3.isVisible = !loading
            }
            collectProperty(State::laptopsLoading) { loading ->
                btnLaptopsB1.isVisible = !loading
                btnLaptopsB2.isVisible = !loading
                btnLaptopsB3.isVisible = !loading
            }
            collectProperty(State::tabletsLoading) { loading ->
                btnTabletsB1.isVisible = !loading
                btnTabletsB2.isVisible = !loading
                btnTabletsB3.isVisible = !loading
            }

            // Log
            collectProperty(State::logText) { text -> tvLog.text = text }
        }

        viewModel.eventFlow.collectEvent(viewLifecycleOwner) {
            collectTyped<DashboardContract.Event.ShowToast> { event ->
                context?.showToast(event.message)
            }
        }
    }
}
