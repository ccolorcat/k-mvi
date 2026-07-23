package cc.colorcat.mvi

import cc.colorcat.mvi.internal.requireSupportedChannelConfig
import kotlinx.coroutines.channels.Channel

/**
 * Defines the strategy for handling intents in the MVI architecture.
 *
 * The strategy determines how multiple intents are processed concurrently or sequentially.
 * Choosing the right strategy depends on your application's requirements for ordering,
 * performance, and concurrency control.
 *
 * ## Strategy Comparison
 *
 * | Strategy | Processing Model | Performance | Ordering | Use Case |
 * |----------|-----------------|-------------|----------|----------|
 * | [CONCURRENT] | Bounded concurrency | High | No guarantee | Independent operations |
 * | [SEQUENTIAL] | All serial | Lowest | Strict | Order-dependent operations |
 * | [HYBRID] | Mixed | Balanced | Configurable | Most applications ⭐ |
 *
 * ## Implementation Details
 *
 * - **CONCURRENT**: Uses `Flow.flatMapMerge` with its default concurrency limit
 * - **SEQUENTIAL**: Uses `Flow.flatMapConcat` to process intents one-by-one
 * - **HYBRID**: Combines both approaches based on intent type and grouping
 *
 * ## Default Strategy
 *
 * [HYBRID] is the default and recommended strategy for most applications as it provides
 * the flexibility to handle different types of intents optimally.
 *
 * ## Configuration
 *
 * ```kotlin
 * KMvi.configure {
 *     copy(handleStrategy = HandleStrategy.HYBRID)
 * }
 * ```
 *
 * @see HybridStrategyConfig
 * @see Mvi.Intent.Concurrent
 * @see Mvi.Intent.Sequential
 */
enum class HandleStrategy {
    /**
     * Intents are processed concurrently up to `Flow.flatMapMerge`'s default concurrency limit.
     *
     * ## Behavior
     * - Uses `Flow.flatMapMerge` internally
     * - At most 16 handler flows run at once with the project's Coroutines default
     * - Additional intents wait until an active handler flow completes
     * - No ordering guarantees between active intents
     * - The JVM system property `kotlinx.coroutines.flow.defaultConcurrency` can override the
     *   Coroutines default; K-MVI does not set an explicit limit
     *
     * ## Use Cases
     * - Independent UI interactions (clicks, scrolls)
     * - Parallel network requests
     * - Operations with no dependencies
     * - Real-time updates
     *
     * ## Considerations
     * - ⚠️ May cause race conditions if intents modify shared state
     * - ⚠️ Order of state updates is non-deterministic
     * - ✅ Best performance for independent operations
     *
     * ## Example
     * ```kotlin
     * // These intents can execute together while concurrency slots are available
     * viewModel.dispatch(ClickButton)
     * viewModel.dispatch(ScrollList)
     * viewModel.dispatch(LoadMoreData)
     */
    CONCURRENT,

    /**
     * All intents are processed sequentially (one-by-one in strict order).
     *
     * ## Behavior
     * - Uses `Flow.flatMapConcat` internally
     * - Each intent waits for the previous one to complete
     * - Strict FIFO (First-In-First-Out) ordering
     * - Prevents race conditions
     *
     * ## Use Cases
     * - Multi-step workflows (wizards, checkout flows)
     * - Operations that must maintain strict order
     * - State transitions with dependencies
     * - Avoiding race conditions
     *
     * ## Considerations
     * - ⚠️ Long-running intents block all subsequent intents
     * - ⚠️ May reduce responsiveness if not careful
     * - ✅ Simplifies reasoning about state changes
     * - ✅ Prevents race conditions by design
     *
     * ## Example
     * // These intents execute one after another
     * viewModel.dispatch(ValidateForm)      // Completes first
     * viewModel.dispatch(SubmitForm)        // Waits for validation
     * viewModel.dispatch(NavigateToSuccess) // Waits for submission
     *
     * ## Debugging Tip
     * Check logs to identify which intent is blocking the queue:
     * ```
     * [INFO] Handling intent: SlowNetworkRequest  (blocking for 10s)
     * [INFO] Handling intent: QuickUIUpdate       (delayed by 10s)
     * ```
     */
    SEQUENTIAL,

    /**
     * Intents are grouped and processed based on their type and configuration.
     *
     * This is the **default and recommended** strategy for most applications as it
     * combines the benefits of both concurrent and sequential processing.
     *
     * ## Behavior
     *
     * Intents are categorized into three groups:
     *
     * ### 1. Concurrent Intents ([Mvi.Intent.Concurrent])
     * - All concurrent intents share one fixed concurrent group; they are not grouped by class
     * - Processed in parallel using `flatMapMerge` with its default concurrency limit
     * - At most 16 handler flows run at once with the project's Coroutines default
     * - Additional concurrent intents wait for an available slot
     * - Best for independent UI interactions
     *
     * ### 2. Sequential Intents ([Mvi.Intent.Sequential])
     * - All sequential intents share one fixed global sequential group; they are not grouped by class
     * - Processed one-by-one using `flatMapConcat`
     * - All sequential intents form a single queue
     * - Best for operations requiring strict order
     *
     * ### 3. Fallback Intents (neither Concurrent nor Sequential)
     * - Grouped by the result of [GroupTagSelector]
     * - **Within each group**: Processed sequentially
     * - **Between groups**: Processed in parallel
     * - Best for operations that need partial ordering
     *
     * ## Choosing a Group
     *
     * - Use [Mvi.Intent.Concurrent] for independent work that may overlap.
     * - Use [Mvi.Intent.Sequential] only when the operation must be ordered with every other
     *   sequential intent in the contract.
     * - Use fallback grouping when ordering is required only within a business category. Give
     *   operations that must be ordered together the same stable, low-cardinality tag; use different
     *   tags for unrelated work.
     * - Avoid raw user IDs, item IDs, queries, timestamps, and other high-cardinality tags unless
     *   per-value ordering is required. Each distinct tag keeps a group channel alive.
     *
     * ## Backpressure
     *
     * All groups share one routing coroutine. If one group fills its channel, routing pauses for every
     * group until that channel has capacity again. High-frequency inputs should be throttled, sampled,
     * or conflated before dispatch; submission UI should normally disable duplicate actions. Increase
     * [HybridStrategyConfig.groupChannelCapacity] only after measuring sustained backlog, and use
     * [Channel.UNLIMITED] only when the producer is externally bounded.
     *
     * ## Example
     *
     * ```kotlin
     * sealed interface MyIntent : Mvi.Intent {
     *     data object Click : MyIntent, Mvi.Intent.Concurrent
     *     data class LoadUser(val id: String) : MyIntent, Mvi.Intent.Sequential
     *     data class LoadData(val type: String) : MyIntent  // Fallback
     * }
     *
     * // Execution flow:
     * viewModel.dispatch(Click)              // Group: CONCURRENT (parallel)
     * viewModel.dispatch(LoadUser("1"))      // Group: SEQUENTIAL (queued)
     * viewModel.dispatch(LoadData("posts"))  // Group: FALLBACK_posts (queued in group)
     * viewModel.dispatch(LoadData("users"))  // Group: FALLBACK_users (parallel with posts)
     * viewModel.dispatch(LoadData("posts"))  // Group: FALLBACK_posts (waits for first posts)
     * viewModel.dispatch(LoadUser("2"))      // Group: SEQUENTIAL (waits for LoadUser(1))
     *
     * ## Visual Representation
     * ```
     * CONCURRENT Group    ─→ [Click] [Scroll] [Refresh]  (up to 16 active by default)
     *
     * SEQUENTIAL Group    ─→ [LoadUser(1)] → [LoadUser(2)]  (strict order)
     *
     * FALLBACK_posts      ─→ [LoadData("posts")] → [LoadData("posts")]  (queued)
     *                        ↓
     * FALLBACK_users      ─→ [LoadData("users")]  (parallel with posts group)
     * ```
     *
     * ## Configuration
     *
     * Customize grouping for fallback intents:
     * ```kotlin
     * val groupTagSelector = GroupTagSelector<MyIntent> { intent ->
     *     when (intent) {
     *         is LoadData -> intent.type  // Group by data type
     *         else -> intent.javaClass
     *     }
     * }
     *
     * // In your ViewModel:
     * private val contract by contract(
     *     // ...
     *     handleStrategy = HandleStrategy.HYBRID,
     *     groupTagSelector = groupTagSelector,
     * )
     * ```
     *
     * ## Advantages
     * - ✅ Flexible: Supports different processing needs
     * - ✅ Balanced: Good performance without sacrificing safety
     * - ✅ Fine-grained control: Per-intent type strategy
     * - ✅ Parallel groups normally isolate handler execution; see Backpressure for routing limits
     *
     * @see HybridStrategyConfig
     * @see Mvi.Intent.Concurrent
     * @see Mvi.Intent.Sequential
     */
    HYBRID
}


/**
 * Selects the HYBRID fallback group tag for an intent.
 *
 * This selector is business-facing: it decides which fallback intents must be
 * ordered together. Intents with the same tag are processed sequentially within
 * that tag, while different tags process in parallel.
 *
 * The default selector uses runtime [Class] object identity, so grouping is unaffected by
 * ProGuard/R8 class-name obfuscation.
 *
 * ```kotlin
 * val selector = GroupTagSelector<MyIntent> { intent ->
 *     when (intent) {
 *         is MyIntent.LoadUser -> "user"
 *         is MyIntent.LoadPost -> "post"
 *         else -> intent.javaClass
 *     }
 * }
 * ```
 *
 * Tags are equality keys: return values must have stable [Any.equals] and
 * [Any.hashCode] behavior for the lifetime of the contract. Avoid returning newly
 * allocated objects that only compare by identity, random values, or mutable objects
 * whose equality can change after insertion.
 *
 * Avoid high-cardinality data-tied tags such as raw user IDs, item IDs, search
 * queries, or timestamps unless per-value ordering is required. Each distinct tag
 * keeps a group channel active until the contract pipeline completes or the channel
 * is detected as stale/closed and replaced.
 *
 * @param I The intent type.
 * @see HybridStrategyConfig
 */
fun interface GroupTagSelector<in I : Mvi.Intent> {
    fun selectTag(intent: I): Any

    companion object {
        fun <I : Mvi.Intent> byClass(): GroupTagSelector<I> {
            return GroupTagSelector { it.javaClass }
        }
    }
}

/**
 * Runtime configuration for the [HandleStrategy.HYBRID] intent handling strategy.
 *
 * [HybridStrategyConfig] is intentionally business-agnostic. It controls internal group
 * channel capacity and diagnostics only; fallback group selection belongs to
 * [GroupTagSelector].
 *
 * ## Channel Capacity
 *
 * The [groupChannelCapacity] parameter controls the **per-group** buffer size of internal channels.
 * It is not a shared capacity across all groups. The default value ([Channel.BUFFERED]) uses the
 * Coroutines runtime default (64 unless `kotlinx.coroutines.channels.defaultBuffer` overrides it)
 * and is suitable for most low-frequency UI workloads.
 *
 * ### When to Adjust
 * - **Throttle first** with debounce, sampling, conflation, or duplicate-action prevention
 * - **Review grouping** so unrelated work does not share one fallback tag
 * - **Keep handlers non-blocking** and isolate blocking I/O on the appropriate dispatcher
 * - **Increase** only when diagnostics show legitimate, bounded bursts
 * - **Decrease** if you want to limit buffering and apply backpressure earlier
 * - **Use [Channel.UNLIMITED]** only for externally bounded traffic (otherwise memory is unbounded)
 * - **Use [Channel.RENDEZVOUS]** (0) for strict backpressure
 *
 * A full group channel suspends the single HYBRID router, temporarily preventing unrelated groups
 * from receiving later intents. If the contract entry queue then fills, [ReactiveContract.dispatch]
 * returns [DispatchResult.Full].
 *
 * ## Group Count Diagnostics
 *
 * [groupCountWarningThreshold] controls warning logs for high active group channel
 * counts. It is diagnostic only; it does not cap groups, close channels, or change
 * ordering behavior. When the active group count reaches the threshold, a WARN log is
 * emitted. The next warning threshold then doubles, so the default warning points are
 * 256, 512, 1024, and so on. Use [Int.MAX_VALUE] to disable this
 * diagnostic in normal applications. The warning log uses the opened tag's type and
 * hash instead of the raw tag value.
 *
 * The warning observes all active HYBRID group channels, including
 * the fixed concurrent and sequential groups when they have been opened.
 *
 * ## Backlog Diagnostics
 *
 * Backlog warnings use internal constants and require no configuration:
 * - Bounded groups warn once at 80% capacity and rearm after falling to 50% or lower
 * - A full group warns when routing is about to suspend
 * - [Channel.UNLIMITED], [Channel.RENDEZVOUS], and [Channel.CONFLATED] are not monitored
 *   (these capacities have no meaningful percentage-based fill level)
 *
 * These diagnostics do not change buffering, dropping, ordering, or dispatch results. Logs identify
 * groups only by tag type and hash; raw tag values are not logged.
 *
 * @param groupChannelCapacity The capacity of internal channels used for grouping.
 *                             Allowed values: [Channel.BUFFERED], [Channel.CONFLATED],
 *                             [Channel.RENDEZVOUS], or any positive Int.
 *                             Defaults to [Channel.BUFFERED] (runtime default 64).
 *                             Adjust based on your intent frequency and backpressure needs.
 * @param groupCountWarningThreshold The active group channel count that triggers the first
 *                                   warning log. Warnings repeat only when the count reaches
 *                                   the next doubled threshold. Must be positive. Defaults to
 *                                   [DEFAULT_GROUP_COUNT_WARNING_THRESHOLD]. Use [Int.MAX_VALUE]
 *                                   to disable this diagnostic.
 * @see HandleStrategy.HYBRID
 * @see GroupTagSelector
 */
class HybridStrategyConfig(
    val groupChannelCapacity: Int = Channel.BUFFERED,
    val groupCountWarningThreshold: Int = DEFAULT_GROUP_COUNT_WARNING_THRESHOLD,
) {
    init {
        requireSupportedChannelConfig(
            name = "groupChannelCapacity",
            capacity = groupChannelCapacity,
        )
        require(groupCountWarningThreshold > 0) {
            "groupCountWarningThreshold must be positive; use Int.MAX_VALUE to disable warnings."
        }
    }

    override fun toString(): String {
        return "HybridStrategyConfig(" +
            "groupChannelCapacity=$groupChannelCapacity, " +
            "groupCountWarningThreshold=$groupCountWarningThreshold" +
            ")"
    }

    companion object {
        /**
         * Default active group channel count that triggers the first warning.
         *
         * Warnings repeat only when the count reaches the next doubled threshold.
         */
        const val DEFAULT_GROUP_COUNT_WARNING_THRESHOLD: Int = 256
    }
}
