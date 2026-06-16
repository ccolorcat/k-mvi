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
 * | [CONCURRENT] | All parallel | Highest | No guarantee | Independent operations |
 * | [SEQUENTIAL] | All serial | Lowest | Strict | Order-dependent operations |
 * | [HYBRID] | Mixed | Balanced | Configurable | Most applications ⭐ |
 *
 * ## Implementation Details
 *
 * - **CONCURRENT**: Uses `Flow.flatMapMerge` to process all intents in parallel
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
 * KMvi.setup {
 *     copy(handleStrategy = HandleStrategy.HYBRID)
 * }
 * ```
 *
 * @see HybridConfig
 * @see Mvi.Intent.Concurrent
 * @see Mvi.Intent.Sequential
 *
 * Author: ccolorcat
 * Date: 2024-12-24
 * GitHub: https://github.com/ccolorcat
 */
enum class HandleStrategy {
    /**
     * All intents are processed concurrently (in parallel).
     *
     * ## Behavior
     * - Uses `Flow.flatMapMerge` internally
     * - All intents execute in parallel without waiting for each other
     * - No ordering guarantees between intents
     * - Maximum throughput and responsiveness
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
     * // All these intents execute in parallel
     * viewModel.sendIntent(ClickButton)
     * viewModel.sendIntent(ScrollList)
     * viewModel.sendIntent(LoadMoreData)
     * ```
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
     * ```kotlin
     * // These intents execute one after another
     * viewModel.sendIntent(ValidateForm)      // Completes first
     * viewModel.sendIntent(SubmitForm)        // Waits for validation
     * viewModel.sendIntent(NavigateToSuccess) // Waits for submission
     * ```
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
     * - Processed in parallel using `flatMapMerge`
     * - All concurrent intents execute simultaneously
     * - Best for independent UI interactions
     *
     * ### 2. Sequential Intents ([Mvi.Intent.Sequential])
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
     * viewModel.sendIntent(Click)              // Group: CONCURRENT (parallel)
     * viewModel.sendIntent(LoadUser("1"))      // Group: SEQUENTIAL (queued)
     * viewModel.sendIntent(LoadData("posts"))  // Group: FALLBACK_posts (queued in group)
     * viewModel.sendIntent(LoadData("users"))  // Group: FALLBACK_users (parallel with posts)
     * viewModel.sendIntent(LoadData("posts"))  // Group: FALLBACK_posts (waits for first posts)
     * viewModel.sendIntent(LoadUser("2"))      // Group: SEQUENTIAL (waits for LoadUser(1))
     * ```
     *
     * ## Visual Representation
     * ```
     * CONCURRENT Group    ─→ [Click] [Scroll] [Refresh]  (all parallel)
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
     * mviViewModel(
     *     // ...
     *     strategy = HandleStrategy.HYBRID,
     *     groupTagSelector = groupTagSelector
     * )
     * ```
     *
     * ## Advantages
     * - ✅ Flexible: Supports different processing needs
     * - ✅ Balanced: Good performance without sacrificing safety
     * - ✅ Fine-grained control: Per-intent type strategy
     * - ✅ Prevents blocking: Parallel groups avoid total queue blocking
     *
     * @see HybridConfig
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
 * The default selector uses the runtime [Class] object, which avoids grouping changes
 * caused by ProGuard/R8 class-name obfuscation.
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
 * @see HybridConfig
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
 * [HybridConfig] is intentionally business-agnostic. It controls internal group
 * channel capacity and diagnostics only; fallback group selection belongs to
 * [GroupTagSelector].
 *
 * ## Channel Capacity
 *
 * The [groupChannelCapacity] parameter controls the buffer size of internal channels
 * used for grouping. The default value ([Channel.BUFFERED] = 64) is suitable for
 * most use cases.
 *
 * ### When to Adjust
 * - **Increase** if you have high-frequency intents and see backpressure
 * - **Decrease** if you want to limit buffering and apply backpressure earlier
 * - **Use [Channel.UNLIMITED]** if you never want to drop intents (may cause memory issues)
 * - **Use [Channel.RENDEZVOUS]** (0) for strict backpressure
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
 * @param groupChannelCapacity The capacity of internal channels used for grouping.
 *                             Allowed values: [Channel.BUFFERED], [Channel.CONFLATED],
 *                             [Channel.RENDEZVOUS], or any positive Int.
 *                             Defaults to [Channel.BUFFERED] (64).
 *                             Adjust based on your intent frequency and backpressure needs.
 * @param groupCountWarningThreshold The active group channel count that triggers the first
 *                                   warning log. Warnings repeat only when the count reaches
 *                                   the next doubled threshold. Must be positive. Defaults to
 *                                   [DEFAULT_GROUP_COUNT_WARNING_THRESHOLD]. Use [Int.MAX_VALUE]
 *                                   to disable this diagnostic.
 * @see HandleStrategy.HYBRID
 * @see GroupTagSelector
 */
class HybridConfig(
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
        return "HybridConfig(" +
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
