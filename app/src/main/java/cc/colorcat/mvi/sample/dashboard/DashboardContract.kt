package cc.colorcat.mvi.sample.dashboard

import cc.colorcat.mvi.Mvi

/**
 * Contract for the Concurrency Demo Dashboard sample.
 *
 * Demonstrates three MVI intent-handling strategies side by side:
 *
 * 1. **Concurrent** ([Mvi.Intent.Concurrent]):
 *    Three independent data-loading tasks (Banners / Recommendations / Flash Sale).
 *    All three start at the same moment when triggered together, proving true parallelism.
 *
 * 2. **Sequential** ([Mvi.Intent.Sequential]):
 *    Shopping-cart operations (AddToCart / RemoveLastFromCart / Checkout).
 *    Each operation waits for the previous one to finish — strict FIFO order.
 *
 * 3. **Grouped fallback** (neither Concurrent nor Sequential, tag-routed via [HybridConfig]):
 *    Three product-category loaders: Phones / Laptops / Tablets.
 *    Batches of the **same** category queue up sequentially while the three
 *    categories run in **parallel** with each other.
 *
 * A scrollable operation log in the UI records `[HH:mm:ss.SSS] ▶ START` / `✔ DONE`
 * timestamps for every intent so users can visually verify the behaviour.
 */
sealed interface DashboardContract {

    // ──────────────────────────────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────────────────────────────

    /** Full UI state for the dashboard screen. */
    data class State(
        // ── Section 1: Concurrent ──────────────────────────────────────────────────────────
        val banners: List<String> = emptyList(),
        val bannerLoading: Boolean = false,
        val recommendations: List<String> = emptyList(),
        val recommendationsLoading: Boolean = false,
        val flashSale: List<String> = emptyList(),
        val flashSaleLoading: Boolean = false,

        // ── Section 2: Sequential ──────────────────────────────────────────────────────────
        val cartItems: List<String> = emptyList(),
        val cartLoading: Boolean = false,
        val orderStatus: String = "No order yet",

        // ── Section 3: Grouped (fallback) ──────────────────────────────────────────────────
        val phonesProducts: List<String> = emptyList(),
        val phonesLoading: Boolean = false,
        val laptopsProducts: List<String> = emptyList(),
        val laptopsLoading: Boolean = false,
        val tabletsProducts: List<String> = emptyList(),
        val tabletsLoading: Boolean = false,

        // ── Operation timeline ──────────────────────────────────────────────────────────────
        val operationLog: List<String> = emptyList(),
    ) : Mvi.State {

        // Derived display helpers ---------------------------------------------------------

        val cartText: String
            get() = if (cartItems.isEmpty()) "(empty)" else cartItems.joinToString(", ")

        val bannersText: String
            get() = if (banners.isEmpty()) "—" else banners.joinToString("\n")

        val recommendationsText: String
            get() = if (recommendations.isEmpty()) "—" else recommendations.joinToString("\n")

        val flashSaleText: String
            get() = if (flashSale.isEmpty()) "—" else flashSale.joinToString("\n")

        val phonesText: String
            get() = if (phonesProducts.isEmpty()) "—" else "${phonesProducts.size} items loaded"

        val laptopsText: String
            get() = if (laptopsProducts.isEmpty()) "—" else "${laptopsProducts.size} items loaded"

        val tabletsText: String
            get() = if (tabletsProducts.isEmpty()) "—" else "${tabletsProducts.size} items loaded"

        /** Tail-50 log lines joined for display. */
        val logText: String
            get() = operationLog.takeLast(60).joinToString("\n")
    }

    // ──────────────────────────────────────────────────────────────────────────────────────
    // Event
    // ──────────────────────────────────────────────────────────────────────────────────────

    sealed interface Event : Mvi.Event {
        data class ShowToast(val message: String) : Event
    }

    // ──────────────────────────────────────────────────────────────────────────────────────
    // Intent
    // ──────────────────────────────────────────────────────────────────────────────────────

    sealed interface Intent : Mvi.Intent {

        // ── Concurrent: flatMapMerge → all launch immediately ─────────────────────────────

        /** Load promo banners. Independent of every other intent — runs in parallel. */
        data object LoadBanners : Intent, Mvi.Intent.Concurrent

        /** Load recommended products. Independent — runs in parallel. */
        data object LoadRecommendations : Intent, Mvi.Intent.Concurrent

        /** Load flash-sale items. Independent — runs in parallel. */
        data object LoadFlashSale : Intent, Mvi.Intent.Concurrent

        // ── Sequential: flatMapConcat → strict FIFO queue ─────────────────────────────────

        /** Add [item] to the cart. Waits for any preceding sequential intent to finish. */
        data class AddToCart(val item: String) : Intent, Mvi.Intent.Sequential

        /** Remove the last cart item. Queued after any pending cart operation. */
        data object RemoveLastFromCart : Intent, Mvi.Intent.Sequential

        /** Check out. Waits for all pending cart operations. */
        data object Checkout : Intent, Mvi.Intent.Sequential

        // ── Grouped fallback: per-category sequential queue, cross-category parallel ───────

        /**
         * Load a batch of products for [category].
         *
         * The [HybridConfig] in [DashboardViewModel] assigns tag `"category-<category>"`
         * so that multiple batches for the **same** category are serialised while different
         * categories proceed in parallel.
         *
         * @param category One of [CATEGORY_PHONES], [CATEGORY_LAPTOPS], [CATEGORY_TABLETS].
         * @param batch    Batch number shown in the log to track ordering.
         */
        data class LoadCategory(val category: String, val batch: Int) : Intent

        // ── Utility ───────────────────────────────────────────────────────────────────────

        /** Clear the operation log. */
        data object ClearLog : Intent
    }

    // ──────────────────────────────────────────────────────────────────────────────────────
    // PartialChange
    // ──────────────────────────────────────────────────────────────────────────────────────

    /** Type alias kept short to reduce boilerplate in the ViewModel. */
    fun interface PartialChange : Mvi.PartialChange<State, Event>

    // ──────────────────────────────────────────────────────────────────────────────────────
    // Constants
    // ──────────────────────────────────────────────────────────────────────────────────────

    companion object {
        const val CATEGORY_PHONES  = "Phones"
        const val CATEGORY_LAPTOPS = "Laptops"
        const val CATEGORY_TABLETS = "Tablets"
    }
}

