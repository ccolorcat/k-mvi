package cc.colorcat.mvi

import cc.colorcat.mvi.KMvi.configure
import cc.colorcat.mvi.internal.TAG
import cc.colorcat.mvi.internal.d

/**
 * Global configuration and entry point for the K-MVI framework.
 *
 * This file provides centralized configuration management for the MVI framework,
 * including Intent handling strategies, retry policies, and logging.
 */

/**
 * Per-intent policy that decides whether a failed handler Flow is retried.
 *
 * The strategy-based (`contract(...)`) API attaches this policy to the Flow returned by
 * [IntentHandler.handle] via `retryWhen`. When collecting that Flow throws, [shouldRetry] decides:
 * - `true` — **re-collect the same handler Flow from the beginning** (one more attempt).
 * - `false` — stop; the exception reaches the configured [FatalErrorHandler] after the contract's
 *   intent queue is cancelled.
 *
 * The retry is scoped to a single intent, so concurrent sibling intents keep running.
 *
 * ## ⚠️ Retrying replays the whole handler Flow
 *
 * `retryWhen` restarts the Flow from scratch. Any [Mvi.PartialChange] emitted **before** the
 * failure has already been applied by `scan` and is **not** rolled back — it is produced again on
 * the retry. So:
 * - Non-idempotent reducers (e.g. `copy(count = count + 1)`) run again.
 * - Events are re-delivered ([Contract.eventFlow] does not de-duplicate), so a navigation or toast
 *   effect can fire more than once.
 *
 * Enable retry only for handlers that are safe to re-collect: their reducers are idempotent, and
 * they emit no [Mvi.Event] or external side effect before the last step that can fail. Otherwise,
 * retry the fallible source **inside** the handler (see [IntentHandler]) and emit once after it
 * succeeds, so a retry never replays an earlier emission.
 *
 * A synchronous exception thrown by [IntentHandler.handle] **before** it returns the Flow is outside
 * this boundary and is never retried; treat it as a programming error.
 *
 * ## Cancellation is not retried
 *
 * [shouldRetry] is not called for [kotlinx.coroutines.CancellationException]. Cancellation caused by
 * contract-scope shutdown follows normal structured cancellation. A cancellation exception that
 * escapes a handler Flow while the contract scope is still active is terminal: K-MVI cancels the
 * intent queue and routes it to [FatalErrorHandler]. For expected per-intent timeouts, use
 * [kotlinx.coroutines.withTimeoutOrNull] or convert the timeout to a state/event result inside the
 * handler without swallowing parent-scope cancellation.
 *
 * ## Usage Example
 *
 * ```kotlin
 * // Opt into retrying transient IO, gated on an intent type you know is safe to replay.
 * KMvi.configure {
 *     copy(retryPolicy = { intent, attempt, cause ->
 *         attempt < 3 && cause is IOException && intent is MyApp.RetryableIntent
 *     })
 * }
 * ```
 *
 * @see KMvi.Configuration.retryPolicy
 * @see IntentHandler
 */
fun interface RetryPolicy<in I : Mvi.Intent> {
    fun shouldRetry(intent: I, attempt: Long, cause: Throwable): Boolean
}

/**
 * Global configuration manager for the K-MVI framework.
 *
 * This singleton object provides centralized configuration for the entire MVI framework,
 * including Intent handling strategies, retry policies, and logging settings.
 *
 * ## Configuration
 *
 * Configure the framework by calling [configure] early in your application lifecycle,
 * typically in `Application.onCreate()`:
 *
 * ```kotlin
 * class MyApplication : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         KMvi.configure {
 *             copy(
 *                 handleStrategy = HandleStrategy.CONCURRENT,
 *                 logger = Logger(Logger.DEBUG)
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * ## Default Configuration
 *
 * If not configured, the framework uses sensible defaults — see [Configuration] for details.
 *
 * ## Thread Safety
 *
 * The [configure] method is NOT thread-safe and should only be called once during
 * application initialization on the main thread. The internal configuration reference is
 * volatile only so background readers can observe the latest published configuration; it
 * does not make concurrent [configure] calls atomic.
 *
 * @see Configuration
 * @see configure
 */
object KMvi {
    // Volatile provides cross-thread visibility for readers in Flow pipelines. configure() still
    // performs a non-atomic read-modify-write and must not be called concurrently.
    @Volatile
    private var config: Configuration = Configuration()

    /**
     * The global logger instance used throughout the framework.
     *
     * This is used internally for logging Intent processing, state changes, and errors.
     */
    internal val logger: Logger
        get() = config.logger

    /**
     * The global Intent handling strategy.
     *
     * Determines how Intents are processed: CONCURRENT, SEQUENTIAL, or HYBRID.
     */
    internal val handleStrategy: HandleStrategy
        get() = config.handleStrategy

    /**
     * The global [RetryPolicy]: decides whether a failed handler Flow is retried
     * (re-collected from the start). Default: no automatic retry.
     */
    internal val retryPolicy: RetryPolicy<Mvi.Intent>
        get() = config.retryPolicy

    /**
     * The global handler for terminal pipeline failures. Returning suppresses exception
     * propagation but does not recover the contract; throwing propagates the failure.
     */
    internal val errorHandler: FatalErrorHandler
        get() = config.errorHandler

    /**
     * The global dispatch queue configuration.
     *
     * Controls the dispatch queue buffer for each contract.
     * With the default [IntentQueueConfig] overflow policy, a full buffer makes
     * [ReactiveContract.dispatch] return [DispatchResult.Full] after logging a warning.
     * Dropping and conflated policies follow [DispatchResult.Submitted] semantics.
     */
    internal val intentQueueConfig: IntentQueueConfig
        get() = config.intentQueueConfig

    /**
     * The global HYBRID runtime configuration.
     */
    internal val hybridStrategyConfig: HybridStrategyConfig
        get() = config.hybridStrategyConfig

    /**
     * Configures the global K-MVI framework settings.
     *
     * This should be called once during application initialization, typically in
     * `Application.onCreate()`. Calling it multiple times will replace the previous
     * configuration.
     *
     * ## Usage Example
     *
     * ```kotlin
     * class MyApplication : Application() {
     *     override fun onCreate() {
     *         super.onCreate()
     *         KMvi.configure {
     *             copy(
     *                 handleStrategy = HandleStrategy.CONCURRENT,
     *                 retryPolicy = { intent, attempt, cause ->
     *                     attempt < 3 && cause is IOException && intent is MyApp.RetryableIntent
     *                 },
     *                 logger = if (BuildConfig.DEBUG) Logger(Logger.DEBUG) else Logger()
     *             )
     *         }
     *     }
     * }
     * ```
     *
     * ## Thread Safety
     *
     * This method is NOT thread-safe. It should only be called from the main thread
     * during application initialization. The backing configuration reference is volatile for
     * reader visibility, but concurrent calls can still lose updates because this method performs
     * a non-atomic read-modify-write.
     *
     * @param transform A lambda with receiver that transforms the current configuration.
     *                  Use `copy()` to create a modified configuration.
     */
    fun configure(transform: Configuration.() -> Configuration) {
        config = config.transform()
        logger.d(TAG) { "configure: $config" }
    }

    /**
     * Global configuration for the K-MVI framework.
     *
     * This data class holds all configurable settings for the framework.
     * Use [configure] to modify the configuration.
     *
     * ## Properties
     *
     * - **handleStrategy**: How Intents are processed (CONCURRENT, SEQUENTIAL, or HYBRID)
     * - **hybridStrategyConfig**: Runtime configuration for HYBRID fallback groups
     * - **retryPolicy**: Whether a failed handler Flow is retried (default: no automatic retry)
     * - **logger**: The logger instance used throughout the framework
     *
     * ## Usage Example
     *
     * ```kotlin
     * KMvi.configure {
     *     copy(
     *         handleStrategy = HandleStrategy.SEQUENTIAL,
     *         retryPolicy = { intent, attempt, cause ->
     *             attempt < 3 && cause is IOException && intent is MyApp.RetryableIntent
     *         }
     *     )
     * }
     * ```
     *
     * @property intentQueueConfig The dispatch entry queue configuration per contract.
     *                             Default: [IntentQueueConfig] with capacity 256 and
     *                             [kotlinx.coroutines.channels.BufferOverflow.SUSPEND].
     * @property handleStrategy The Intent handling strategy. Default: HYBRID
     * @property hybridStrategyConfig Runtime configuration for [HandleStrategy.HYBRID].
     * @property retryPolicy Per-intent policy deciding whether a failed handler Flow is retried
     *                       (re-collected from the start). `attempt` is 0-based. Default: no
     *                       automatic retry — opt in per app. See [RetryPolicy] for replay caveats.
     * @property errorHandler Handles terminal pipeline failures after the intent queue is cancelled.
     *                        Returning suppresses exception propagation but leaves the contract
     *                        unavailable; throwing propagates the original or a replacement error.
     *                        Default: [FatalErrorHandler.Rethrow]
     * @property logger The logger instance. Default: Logger with WARN level
     *
     * @see HandleStrategy
     * @see HybridStrategyConfig
     * @see RetryPolicy
     * @see Logger
     */
    data class Configuration(
        val intentQueueConfig: IntentQueueConfig = IntentQueueConfig(),
        val handleStrategy: HandleStrategy = HandleStrategy.HYBRID,
        val hybridStrategyConfig: HybridStrategyConfig = HybridStrategyConfig(),
        val retryPolicy: RetryPolicy<Mvi.Intent> = RetryPolicy { _, _, _ -> false },
        val errorHandler: FatalErrorHandler = FatalErrorHandler.Rethrow,
        val logger: Logger = Logger(),
    ) {
        override fun toString(): String {
            return "Configuration(" +
                "intentQueueConfig=$intentQueueConfig, " +
                "handleStrategy=$handleStrategy, " +
                "hybridStrategyConfig=$hybridStrategyConfig" +
                ")"
        }
    }
}
