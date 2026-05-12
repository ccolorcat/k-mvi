package cc.colorcat.mvi.sample.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import cc.colorcat.mvi.HandleStrategy
import cc.colorcat.mvi.HybridConfig
import cc.colorcat.mvi.contract
import cc.colorcat.mvi.register
import cc.colorcat.mvi.sample.dashboard.DashboardContract.Event
import cc.colorcat.mvi.sample.dashboard.DashboardContract.Intent
import cc.colorcat.mvi.sample.dashboard.DashboardContract.PartialChange
import cc.colorcat.mvi.sample.dashboard.DashboardContract.State
import cc.colorcat.mvi.sample.util.randomDelay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel for the Concurrency Demo Dashboard.
 *
 * Routes intents through HandleStrategy.HYBRID:
 *  - CONCURRENT channel  : LoadBanners / LoadRecommendations / LoadFlashSale
 *  - SEQUENTIAL channel  : AddToCart / RemoveLastFromCart / Checkout
 *  - GROUPED channel     : LoadCategory, grouped by category name
 */
class DashboardViewModel : ViewModel() {

    private val contract by contract(
        initState = State(),
        strategy = HandleStrategy.HYBRID,
        config = HybridConfig { intent: Intent ->
            when (intent) {
                is Intent.LoadCategory -> "category-${intent.category}"
                else -> intent.javaClass.name
            }
        },
    ) {
        // Section 1 - Concurrent (flatMapMerge)
        register(::handleLoadBanners)
        register(::handleLoadRecommendations)
        register(::handleLoadFlashSale)
        // Section 2 - Sequential (flatMapConcat)
        register(::handleAddToCart)
        register(::handleRemoveLastFromCart)
        register(::handleCheckout)
        // Section 3 - Grouped (per-category flatMapConcat, cross-category merge)
        register(::handleLoadCategory)
        // Utility
        register(::handleClearLog)
    }

    val stateFlow: StateFlow<State> = contract.stateFlow
    val eventFlow: Flow<Event> = contract.eventFlow

    fun dispatch(intent: Intent) = contract.dispatch(intent)

    // ── Section 1: Concurrent ──────────────────────────────────────────────────────────────

    private fun handleLoadBanners(@Suppress("UNUSED_PARAMETER") intent: Intent.LoadBanners): Flow<PartialChange> = flow {
        val tag = "LoadBanners"
        emit(PartialChange { s -> s.updateState { copy(bannerLoading = true, operationLog = operationLog + stamp("START $tag")) } })
        try {
            randomDelay(1000, 3000)
            val data = listOf("Summer Sale 50%OFF", "New Arrivals", "Flash Deals", "Gift Cards", "Free Shipping")
            emit(PartialChange { s -> s.updateState { copy(bannerLoading = false, banners = data, operationLog = operationLog + stamp("DONE  $tag")) } })
        } catch (e: Exception) {
            Log.w("k-mvi", "handleLoadBanners failed", e)
            emit(PartialChange { s -> s.updateState { copy(bannerLoading = false, operationLog = operationLog + stamp("FAIL  $tag")) } })
        }
    }

    private fun handleLoadRecommendations(@Suppress("UNUSED_PARAMETER") intent: Intent.LoadRecommendations): Flow<PartialChange> = flow {
        val tag = "LoadRecs"
        emit(PartialChange { s -> s.updateState { copy(recommendationsLoading = true, operationLog = operationLog + stamp("START $tag")) } })
        try {
            randomDelay(1000, 3000)
            val data = listOf("iPhone 16 Pro", "MacBook Air M3", "Sony WH-1000XM5", "iPad Pro 13in", "Apple Watch Ultra 2")
            emit(PartialChange { s -> s.updateState { copy(recommendationsLoading = false, recommendations = data, operationLog = operationLog + stamp("DONE  $tag")) } })
        } catch (e: Exception) {
            Log.w("k-mvi", "handleLoadRecommendations failed", e)
            emit(PartialChange { s -> s.updateState { copy(recommendationsLoading = false, operationLog = operationLog + stamp("FAIL  $tag")) } })
        }
    }

    private fun handleLoadFlashSale(@Suppress("UNUSED_PARAMETER") intent: Intent.LoadFlashSale): Flow<PartialChange> = flow {
        val tag = "LoadFlashSale"
        emit(PartialChange { s -> s.updateState { copy(flashSaleLoading = true, operationLog = operationLog + stamp("START $tag")) } })
        try {
            randomDelay(1000, 3000)
            val data = listOf("Galaxy S25 Ultra -30%", "Pixel 9 Pro -20%", "OnePlus 13 -15%")
            emit(PartialChange { s -> s.updateState { copy(flashSaleLoading = false, flashSale = data, operationLog = operationLog + stamp("DONE  $tag")) } })
        } catch (e: Exception) {
            Log.w("k-mvi", "handleLoadFlashSale failed", e)
            emit(PartialChange { s -> s.updateState { copy(flashSaleLoading = false, operationLog = operationLog + stamp("FAIL  $tag")) } })
        }
    }

    // ── Section 2: Sequential ─────────────────────────────────────────────────────────────

    private fun handleAddToCart(intent: Intent.AddToCart): Flow<PartialChange> = flow {
        val tag = "AddToCart(${intent.item})"
        emit(PartialChange { s -> s.updateState { copy(cartLoading = true, operationLog = operationLog + stamp("START $tag")) } })
        randomDelay(800, 1800)
        emit(PartialChange { s ->
            s.updateState { copy(cartLoading = false, cartItems = cartItems + intent.item, operationLog = operationLog + stamp("DONE  $tag")) }
        })
    }

    private fun handleRemoveLastFromCart(@Suppress("UNUSED_PARAMETER") intent: Intent.RemoveLastFromCart): Flow<PartialChange> = flow {
        val tag = "RemoveLast"
        emit(PartialChange { s -> s.updateState { copy(cartLoading = true, operationLog = operationLog + stamp("START $tag")) } })
        randomDelay(800, 1800)
        emit(PartialChange { s ->
            s.updateState {
                val newCart = if (cartItems.isEmpty()) emptyList() else cartItems.dropLast(1)
                copy(cartLoading = false, cartItems = newCart, operationLog = operationLog + stamp("DONE  $tag"))
            }
        })
    }

    private fun handleCheckout(@Suppress("UNUSED_PARAMETER") intent: Intent.Checkout): Flow<PartialChange> = flow {
        val tag = "Checkout"
        emit(PartialChange { s -> s.updateState { copy(cartLoading = true, operationLog = operationLog + stamp("START $tag")) } })
        randomDelay(1500, 2500)
        emit(PartialChange { s ->
            s.updateWith(Event.ShowToast("Order placed!")) {
                copy(
                    cartLoading = false,
                    cartItems = emptyList(),
                    orderStatus = "Order placed at ${now()}",
                    operationLog = operationLog + stamp("DONE  $tag"),
                )
            }
        })
    }

    // ── Section 3: Grouped ────────────────────────────────────────────────────────────────

    private fun handleLoadCategory(intent: Intent.LoadCategory): Flow<PartialChange> = flow {
        val tag = "[${intent.category}] Batch#${intent.batch}"
        emit(PartialChange { s ->
            s.updateState {
                val log = operationLog + stamp("START $tag")
                when (intent.category) {
                    DashboardContract.CATEGORY_PHONES  -> copy(phonesLoading = true, operationLog = log)
                    DashboardContract.CATEGORY_LAPTOPS -> copy(laptopsLoading = true, operationLog = log)
                    else                               -> copy(tabletsLoading = true, operationLog = log)
                }
            }
        })
        randomDelay(1000, 2500)
        emit(PartialChange { s ->
            s.updateState {
                val items = (1..3).map { i -> "${intent.category} B${intent.batch}-P$i" }
                val log = operationLog + stamp("DONE  $tag")
                when (intent.category) {
                    DashboardContract.CATEGORY_PHONES  -> copy(phonesLoading = false,  phonesProducts  = phonesProducts  + items, operationLog = log)
                    DashboardContract.CATEGORY_LAPTOPS -> copy(laptopsLoading = false, laptopsProducts = laptopsProducts + items, operationLog = log)
                    else                               -> copy(tabletsLoading = false, tabletsProducts = tabletsProducts + items, operationLog = log)
                }
            }
        })
    }

    // ── Utility ───────────────────────────────────────────────────────────────────────────

    private fun handleClearLog(@Suppress("UNUSED_PARAMETER") intent: Intent.ClearLog): PartialChange =
        PartialChange { s -> s.updateState { copy(operationLog = emptyList()) } }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────

    private val fmtTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fmtMs = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private fun now(): String = fmtTime.format(Date())
    private fun stamp(msg: String): String = "[${fmtMs.format(Date())}] $msg"
}

