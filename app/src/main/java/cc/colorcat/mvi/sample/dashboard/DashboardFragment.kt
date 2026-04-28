package cc.colorcat.mvi.sample.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import cc.colorcat.mvi.collectEvent
import cc.colorcat.mvi.collectState
import cc.colorcat.mvi.sample.R
import cc.colorcat.mvi.sample.dashboard.DashboardContract.Intent
import cc.colorcat.mvi.sample.dashboard.DashboardContract.State
import cc.colorcat.mvi.sample.databinding.FragmentDashboardBinding
import cc.colorcat.mvi.sample.util.showToast
import cc.colorcat.mvi.sample.util.viewBinding

/**
 * Dashboard fragment demonstrating three intent-handling strategies:
 *
 * Section 1 - Concurrent : Trigger LoadBanners, LoadRecommendations and LoadFlashSale at the
 *   same time. Watch the log: all three START lines appear nearly simultaneously.
 *
 * Section 2 - Sequential : Dispatch cart operations. Each intent's START line appears only
 *   after the previous intent's DONE line, proving strict FIFO ordering.
 *
 * Section 3 - Grouped    : Tap B1/B2/B3 for a category to queue three batches. The same-
 *   category batches are serialised (B2 waits for B1, etc.) while the three categories run
 *   in parallel with each other. Trigger All sends 9 intents at once to show this clearly.
 *
 * The monospace Operation Log at the bottom is the key observability tool.
 */
class DashboardFragment : Fragment() {

    private val viewModel: DashboardViewModel by viewModels()
    private val binding: FragmentDashboardBinding by viewBinding()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        setupObservers()
    }

    // ── Click listeners ───────────────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        // Section 1 - individual
        binding.btnLoadBanners.setOnClickListener   { dispatch(Intent.LoadBanners) }
        binding.btnLoadRecs.setOnClickListener      { dispatch(Intent.LoadRecommendations) }
        binding.btnLoadFlashSale.setOnClickListener { dispatch(Intent.LoadFlashSale) }
        // Section 1 - trigger all at once
        binding.btnTriggerAllConcurrent.setOnClickListener {
            dispatch(Intent.LoadBanners)
            dispatch(Intent.LoadRecommendations)
            dispatch(Intent.LoadFlashSale)
        }

        // Section 2 - individual
        binding.btnAddApple.setOnClickListener    { dispatch(Intent.AddToCart("Apple")) }
        binding.btnAddBanana.setOnClickListener   { dispatch(Intent.AddToCart("Banana")) }
        binding.btnAddOrange.setOnClickListener   { dispatch(Intent.AddToCart("Orange")) }
        binding.btnRemoveLast.setOnClickListener  { dispatch(Intent.RemoveLastFromCart) }
        binding.btnCheckout.setOnClickListener    { dispatch(Intent.Checkout) }
        // Section 2 - trigger the full sequence in one tap
        binding.btnTriggerSequence.setOnClickListener {
            dispatch(Intent.AddToCart("Apple"))
            dispatch(Intent.AddToCart("Banana"))
            dispatch(Intent.RemoveLastFromCart)
            dispatch(Intent.Checkout)
        }

        // Section 3 - phones
        binding.btnPhonesB1.setOnClickListener { dispatch(Intent.LoadCategory(DashboardContract.CATEGORY_PHONES, 1)) }
        binding.btnPhonesB2.setOnClickListener { dispatch(Intent.LoadCategory(DashboardContract.CATEGORY_PHONES, 2)) }
        binding.btnPhonesB3.setOnClickListener { dispatch(Intent.LoadCategory(DashboardContract.CATEGORY_PHONES, 3)) }
        // Section 3 - laptops
        binding.btnLaptopsB1.setOnClickListener { dispatch(Intent.LoadCategory(DashboardContract.CATEGORY_LAPTOPS, 1)) }
        binding.btnLaptopsB2.setOnClickListener { dispatch(Intent.LoadCategory(DashboardContract.CATEGORY_LAPTOPS, 2)) }
        binding.btnLaptopsB3.setOnClickListener { dispatch(Intent.LoadCategory(DashboardContract.CATEGORY_LAPTOPS, 3)) }
        // Section 3 - tablets
        binding.btnTabletsB1.setOnClickListener { dispatch(Intent.LoadCategory(DashboardContract.CATEGORY_TABLETS, 1)) }
        binding.btnTabletsB2.setOnClickListener { dispatch(Intent.LoadCategory(DashboardContract.CATEGORY_TABLETS, 2)) }
        binding.btnTabletsB3.setOnClickListener { dispatch(Intent.LoadCategory(DashboardContract.CATEGORY_TABLETS, 3)) }
        // Section 3 - trigger all 9 intents at once
        binding.btnTriggerAllGroups.setOnClickListener {
            for (batch in 1..3) {
                dispatch(Intent.LoadCategory(DashboardContract.CATEGORY_PHONES, batch))
                dispatch(Intent.LoadCategory(DashboardContract.CATEGORY_LAPTOPS, batch))
                dispatch(Intent.LoadCategory(DashboardContract.CATEGORY_TABLETS, batch))
            }
        }

        // Log
        binding.btnClearLog.setOnClickListener { dispatch(Intent.ClearLog) }
    }

    // ── State / Event observers ───────────────────────────────────────────────────────────

    private fun setupObservers() {
        viewModel.stateFlow.collectState(viewLifecycleOwner) {
            // Section 1
            collectPartial(State::bannerLoading)      { loading -> binding.btnLoadBanners.isEnabled = !loading }
            collectPartial(State::recommendationsLoading) { loading -> binding.btnLoadRecs.isEnabled = !loading }
            collectPartial(State::flashSaleLoading)   { loading -> binding.btnLoadFlashSale.isEnabled = !loading }
            collectPartial(State::bannersText)        { text -> binding.tvBannersStatus.text = "Banners: $text" }
            collectPartial(State::recommendationsText){ text -> binding.tvRecsStatus.text = "Recs: $text" }
            collectPartial(State::flashSaleText)      { text -> binding.tvFlashStatus.text = "Flash Sale: $text" }

            // Section 2
            collectPartial(State::cartText)     { text -> binding.tvCartStatus.text = "Cart: $text" }
            collectPartial(State::orderStatus)  { text -> binding.tvOrderStatus.text = "Order: $text" }
            collectPartial(State::cartLoading)  { loading ->
                binding.btnAddApple.isEnabled   = !loading
                binding.btnAddBanana.isEnabled  = !loading
                binding.btnAddOrange.isEnabled  = !loading
                binding.btnRemoveLast.isEnabled = !loading
                binding.btnCheckout.isEnabled   = !loading
            }

            // Section 3
            collectPartial(State::phonesText)   { text -> binding.tvPhonesStatus.text = "Phones: $text" }
            collectPartial(State::laptopsText)  { text -> binding.tvLaptopsStatus.text = "Laptops: $text" }
            collectPartial(State::tabletsText)  { text -> binding.tvTabletsStatus.text = "Tablets: $text" }
            collectPartial(State::phonesLoading)  { loading ->
                binding.btnPhonesB1.isVisible = !loading
                binding.btnPhonesB2.isVisible = !loading
                binding.btnPhonesB3.isVisible = !loading
            }
            collectPartial(State::laptopsLoading) { loading ->
                binding.btnLaptopsB1.isVisible = !loading
                binding.btnLaptopsB2.isVisible = !loading
                binding.btnLaptopsB3.isVisible = !loading
            }
            collectPartial(State::tabletsLoading) { loading ->
                binding.btnTabletsB1.isVisible = !loading
                binding.btnTabletsB2.isVisible = !loading
                binding.btnTabletsB3.isVisible = !loading
            }

            // Log
            collectPartial(State::logText) { text -> binding.tvLog.text = text }
        }

        viewModel.eventFlow.collectEvent(viewLifecycleOwner) {
            collectParticular<DashboardContract.Event.ShowToast> { event ->
                context?.showToast(event.message)
            }
        }
    }

    private fun dispatch(intent: Intent) = viewModel.dispatch(intent)
}

