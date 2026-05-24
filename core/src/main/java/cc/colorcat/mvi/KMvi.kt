package cc.colorcat.mvi

import cc.colorcat.mvi.internal.TAG
import cc.colorcat.mvi.internal.d
import cc.colorcat.mvi.internal.e
import cc.colorcat.mvi.internal.requireSupportedCapacity
import cc.colorcat.mvi.internal.w

/**
 * Global configuration and entry point for the K-MVI framework.
 *
 * This file provides centralized configuration management for the MVI framework,
 * including Intent handling strategies, retry policies, and logging.
 *
 * Author: ccolorcat
 * Date: 2024-12-25
 * GitHub: https://github.com/ccolorcat
 */

/**
 * A policy function that determines whether to restart the pipeline after an
 * unhandled exception during intent processing.
 *
 * When an intent handler throws an uncaught exception, the pipeline subscription
 * is restarted so that subsequent intents can still be processed. The intent
 * whose handler threw the exception is **not** replayed — handlers should use
 * try-catch internally for intent-level error handling.
 *
 * Return `true` to restart the pipeline subscription, or `false` to terminate it.
 *
 * ## Parameters
 *
 * - **attempt**: The restart attempt index from `retryWhen` (0 for first retry, 1 for second, etc.)
 * - **cause**: The throwable that caused the failure
 *
 * ## Usage Example
 *
 * ```kotlin
 * val customRetryPolicy: RetryPolicy = { attempt, cause ->
 *     when {
 *         attempt >= 3 -> false  // Give up after 3 retries (attempt = 0, 1, 2)
 *         cause is NetworkException -> true  // Retry network errors
 *         else -> false  // Don't retry other errors
 *     }
 * }
 *
 * KMvi.setup {
 *     copy(retryPolicy = customRetryPolicy)
 * }
 * ```
 *
 * @see KMvi.Configuration.retryPolicy
 */
typealias RetryPolicy = (attempt: Long, cause: Throwable) -> Boolean

/**
 * Global configuration manager for the K-MVI framework.
 *
 * This singleton object provides centralized configuration for the entire MVI framework,
 * including Intent handling strategies, retry policies, and logging settings.
 *
 * ## Configuration
 *
 * Configure the framework by calling [setup] early in your application lifecycle,
 * typically in `Application.onCreate()`:
 *
 * ```kotlin
 * class MyApplication : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         KMvi.setup {
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
 * If not configured, the framework uses sensible defaults:
 * - **Handle Strategy**: HYBRID (balanced between concurrent and sequential)
 * - **Retry Policy**: Retry on Exceptions (but not Errors), up to 3 retries (`attempt` = 0..2)
 * - **Logger**: Default logger with WARN level
 * - **Hybrid Config**: Empty configuration (no grouping)
 *
 * ## Thread Safety
 *
 * The [setup] method is NOT thread-safe and should only be called once during
 * application initialization on the main thread.
 *
 * @see Configuration
 * @see setup
 */
object KMvi {
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
     * The global hybrid configuration for Intent grouping.
     *
     * Used when [handleStrategy] is HYBRID to determine how to group Intents.
     */
    internal val hybridConfig: HybridConfig<Mvi.Intent>
        get() = config.hybridConfig

    /**
     * The global retry policy for unhandled exceptions during intent processing.
     *
     * Determines whether to restart the pipeline subscription after a failure.
     */
    internal val retryPolicy: RetryPolicy
        get() = config.retryPolicy

    /**
     * The global dispatch queue capacity.
     *
     * Controls the size of the dispatch queue buffer for each contract.
     * When the buffer is full, [ReactiveContract.dispatch] discards the intent
     * and logs a warning.
     */
    internal val intentQueueCapacity: Int
        get() = config.intentQueueCapacity

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
     *         KMvi.setup {
     *             copy(
     *                 handleStrategy = HandleStrategy.CONCURRENT,
     *                 retryPolicy = { attempt, _ -> attempt < 3 }, // 0-based attempt from retryWhen
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
     * during application initialization.
     *
     * @param transform A lambda with receiver that transforms the current configuration.
     *                  Use `copy()` to create a modified configuration.
     */
    fun setup(transform: Configuration.() -> Configuration) {
        config = config.transform()
        logger.d(TAG) { "setup config: $config" }
    }

    /**
     * The default retry policy implementation.
     *
     * This policy:
     * - Retries all [Exception]s (runtime errors that may be transient)
     * - Does NOT retry [Error]s (serious problems that should not be retried)
     * - Limits retries to a maximum of 3 retries (`attempt` = 0..2)
     * - Logs each retry attempt with the exception details
     *
     * ## ⚠️ Production Warning
     *
     * `Exception` is a broad category that includes non-transient errors
     * (e.g., [IllegalStateException], [IllegalArgumentException]). Retrying
     * these unconditionally can mask bugs or cause unexpected side effects.
     *
     * **Recommendation**: Override this in production with a more targeted policy:
     *
     * ```kotlin
     * KMvi.setup {
     *     copy(
     *         retryPolicy = { attempt, cause ->
     *             attempt < 3 && (cause is IOException || cause is HttpException)
     *         }
     *     )
     * }
     * ```
     *
     * @param attempt The retry attempt index from `retryWhen` (0 for first retry, 1 for second, etc.)
     * @param cause The throwable that caused the failure
     * @return `true` if should retry (attempt < 3 and cause is Exception), `false` otherwise
     */
    private fun defaultRetryPolicy(attempt: Long, cause: Throwable): Boolean {
        if (attempt < 3 && cause is Exception) {
            logger.w(TAG, cause) { "retry count: $attempt" }
            return true
        }
        logger.e(TAG, cause) { "give up retry after $attempt attempts" }
        return false
    }

    /**
     * Global configuration for the K-MVI framework.
     *
     * This data class holds all configurable settings for the framework.
     * Use [setup] to modify the configuration.
     *
     * ## Properties
     *
     * - **handleStrategy**: How Intents are processed (CONCURRENT, SEQUENTIAL, or HYBRID)
     * - **hybridConfig**: Configuration for Intent grouping when using HYBRID strategy
     * - **retryPolicy**: Determines whether to retry failed Intent processing
     * - **logger**: The logger instance used throughout the framework
     *
     * ## Usage Example
     *
     * ```kotlin
     * KMvi.setup {
     *     copy(
     *         handleStrategy = HandleStrategy.SEQUENTIAL,
     *         retryPolicy = { attempt, cause ->
     *             attempt < 3 && cause is IOException
     *         }
     *     )
     * }
     * ```
     *
     * @property intentQueueCapacity The dispatch queue buffer size per contract. Allowed values:
     *                               [Channel.BUFFERED], [Channel.CONFLATED], [Channel.RENDEZVOUS], or any positive Int.
     *                               Default: 256
     * @property handleStrategy The Intent handling strategy. Default: HYBRID
     * @property hybridConfig The hybrid grouping configuration. Default: class-name based grouping
     * @property retryPolicy The retry policy for failed processing. `attempt` is 0-based.
     *                       Default: retry on Exception when `attempt < 3` (up to 3 retries)
     * @property logger The logger instance. Default: Logger with WARN level
     *
     * @see HandleStrategy
     * @see HybridConfig
     * @see RetryPolicy
     * @see Logger
     */
    data class Configuration(
        val intentQueueCapacity: Int = 256,
        val handleStrategy: HandleStrategy = HandleStrategy.HYBRID,
        val hybridConfig: HybridConfig<Mvi.Intent> = HybridConfig(),
        val retryPolicy: RetryPolicy = ::defaultRetryPolicy,
        val logger: Logger = Logger(),
    ) {
        init {
            requireSupportedCapacity("intentQueueCapacity", intentQueueCapacity)
        }
    }
}
