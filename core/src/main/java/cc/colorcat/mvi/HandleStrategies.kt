package cc.colorcat.mvi

import cc.colorcat.mvi.HandleStrategy.CONCURRENT
import cc.colorcat.mvi.HandleStrategy.HYBRID
import cc.colorcat.mvi.HandleStrategy.SEQUENTIAL
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
     * - Grouped by the result of [HybridConfig.groupTagSelector]
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
     * val config = HybridConfig<MyIntent> { intent ->
     *     when (intent) {
     *         is LoadData -> intent.type  // Group by data type
     *         else -> intent.javaClass.name
     *     }
     * }
     *
     * mviViewModel(
     *     // ...
     *     strategy = HandleStrategy.HYBRID,
     *     config = config
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
 * Configuration for the [HandleStrategy.HYBRID] intent handling strategy.
 *
 * Controls how fallback intents (those not explicitly marked as [Mvi.Intent.Concurrent]
 * or [Mvi.Intent.Sequential]) are grouped and processed. Intents with the same group tag
 * are processed sequentially within their group, while different groups process in parallel.
 *
 * ## Default Behavior
 *
 * By default, fallback intents are grouped by their class name:
 * ```kotlin
 * HybridConfig()  // Equivalent to: groupTagSelector = { it.javaClass.name }
 * ```
 *
 * This means each intent type gets its own sequential queue:
 * ```kotlin
 * // These form separate groups, processed in parallel
 * LoadUserIntent(1)  → Group: "LoadUserIntent"
 * LoadPostIntent(1)  → Group: "LoadPostIntent"
 *
 * // These are in the same group, processed sequentially
 * LoadUserIntent(1)  → Executes first
 * LoadUserIntent(2)  → Waits for first to complete
 * ```
 *
 * ## Custom Grouping
 *
 * You can customize grouping to achieve more sophisticated processing patterns.
 *
 * ### Example 1: Group by Business Entity
 * ```kotlin
 * sealed interface MyIntent : Mvi.Intent {
 *     data class LoadUser(val userId: String) : MyIntent
 *     data class LoadPost(val postId: String) : MyIntent
 *     data class UpdateUser(val userId: String, val data: UserData) : MyIntent
 * }
 *
 * val config = HybridConfig<MyIntent> { intent ->
 *     when (intent) {
 *         is LoadUser, is UpdateUser -> "user-operations"  // Same group
 *         is LoadPost -> "post-operations"
 *         else -> intent.javaClass.name
 *     }
 * }
 * ```
 * Now all user operations are sequential with each other, but parallel with post operations.
 *
 * ### Example 2: Group by Resource ID
 * ```kotlin
 * data class LoadData(val type: String, val id: String) : Mvi.Intent
 *
 * val config = HybridConfig<MyIntent> { intent ->
 *     when (intent) {
 *         is LoadData -> "${intent.type}-${intent.id}"  // Group by type and id
 *         else -> intent.javaClass.name
 *     }
 * }
 * ```
 * Now `LoadData("user", "123")` and `LoadData("user", "456")` process in parallel,
 * but multiple `LoadData("user", "123")` requests are sequential.
 *
 * ### Example 3: Global Sequential for Specific Types
 * ```kotlin
 * val config = HybridConfig<MyIntent> { intent ->
 *     when (intent) {
 *         is CriticalOperation -> "critical"  // All critical ops in one queue
 *         is RegularOperation -> intent.javaClass.name  // Each type separate
 *         else -> intent.javaClass.name
 *     }
 * }
 * ```
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
 * ```kotlin
 * HybridConfig(
 *     groupChannelCapacity = 128,  // Double the default buffer
 *     groupTagSelector = { it.javaClass.name }
 * )
 * ```
 *
 * ## Best Practices
 *
 * ### ✅ Good Grouping Strategies
 * - Group by resource type (user operations, post operations)
 * - Group by resource ID (operations on the same entity)
 * - Group by logical workflow (checkout steps, form validation)
 *
 * ### ⚠️ Avoid These Pitfalls
 * - Don't return different tags for the same logical operation
 * - Don't create too many groups (defeats the purpose of grouping)
 * - Don't use random or time-based tags (breaks sequential guarantees)
 *
 * ### 💡 Tips
 * - Use distinct tags for truly independent operations
 * - Use the same tag for operations that must be ordered
 * - Consider using prefixes for related groups (e.g., "user-load", "user-update")
 * - Log your tags during development to verify grouping behavior
 *
 * ## Performance Considerations
 *
 * - **More groups** = More parallelism but more overhead
 * - **Fewer groups** = Less overhead but more sequential bottlenecks
 * - Default grouping (by class name) is a good balance for most apps
 *
 * ## Relationship with Intent Types
 *
 * This configuration **only affects fallback intents**:
 * - [Mvi.Intent.Concurrent] intents ignore this config (always parallel)
 * - [Mvi.Intent.Sequential] intents ignore this config (always one global queue)
 * - Other intents use this config for grouping
 *
 * @param I The intent type
 * @param groupChannelCapacity The capacity of internal channels used for grouping.
 *                             Defaults to [Channel.BUFFERED] (64).
 *                             Adjust based on your intent frequency and backpressure needs.
 * @param groupTagSelector A function that assigns a group tag to each fallback intent.
 *                         Intents with the same tag are processed sequentially within
 *                         their group. Different tags process in parallel.
 *                         Defaults to the intent's class name.
 *
 *                         ## ⚠️ ProGuard / R8 Warning
 *
 *                         The default implementation uses `it.javaClass.name`, which relies on
 *                         the class's fully-qualified name at runtime. In release builds with
 *                         ProGuard or R8 minification enabled, class names are typically
 *                         **obfuscated** (e.g., `a`, `b`, `c`). This can cause previously
 *                         distinct intent types to collide on the same group tag, silently
 *                         converting what should be parallelism into unintended serialization.
 *
 *                         **Recommendation**: Always supply an explicit, stable groupTagSelector
 *                         in production apps, or add a `-keepnames` ProGuard rule for your
 *                         Intent classes:
 *
 *                         ```kotlin
 *                         // ✅ Explicit, obfuscation-safe selector
 *                         HybridConfig<MyIntent> { intent ->
 *                             when (intent) {
 *                                 is LoadUser   -> "user"
 *                                 is LoadPost   -> "post"
 *                                 else          -> "default"
 *                             }
 *                         }
 *                         ```
 *
 *                         ```proguard
 *                         # ProGuard rule alternative
 *                         -keepnames class cc.example.intent.**
 *                         ```
 *
 * ## Note on `internal` Properties
 *
 * [groupChannelCapacity] and [groupTagSelector] are declared `internal` so they cannot
 * be accessed from outside the `cc.colorcat.mvi` library module. They are configurable
 * **only** via the primary constructor or `copy()` when inside the module. End-users
 * interact with them solely through the constructor or [KMvi.setup].
 *
 * @see HandleStrategy.HYBRID
 * @see Mvi.Intent.Concurrent
 * @see Mvi.Intent.Sequential
 */
data class HybridConfig<in I : Mvi.Intent>(
    internal val groupChannelCapacity: Int = Channel.BUFFERED,
    internal val groupTagSelector: (I) -> String = { it.javaClass.name }
)
