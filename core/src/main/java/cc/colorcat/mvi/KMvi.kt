package cc.colorcat.mvi

import cc.colorcat.mvi.internal.TAG
import cc.colorcat.mvi.internal.d
import cc.colorcat.mvi.internal.e

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
 * A policy function that determines whether to retry after a failure.
 *
 * This function is called when Intent processing fails, and should return `true`
 * to retry or `false` to give up.
 *
 * ## Parameters
 *
 * - **attempt**: The retry attempt number (1 for first retry, 2 for second, etc.)
 * - **cause**: The throwable that caused the failure
 *
 * ## Usage Example
 *
 * ```kotlin
 * val customRetryPolicy: RetryPolicy = { attempt, cause ->
 *     when {
 *         attempt > 3 -> false  // Give up after 3 retries
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
 *                 logger = Logger(minLevel = LogLevel.DEBUG)
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
 * - **Retry Policy**: Retry on Exceptions (but not Errors), up to 3 attempts
 * - **Logger**: Default logger with INFO level
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
    private var default: Configuration = Configuration()

    /**
     * The global logger instance used throughout the framework.
     *
     * This is used internally for logging Intent processing, state changes, and errors.
     */
    internal val logger: Logger
        get() = default.logger

    /**
     * The global Intent handling strategy.
     *
     * Determines how Intents are processed: CONCURRENT, SEQUENTIAL, or HYBRID.
     */
    internal val handleStrategy: HandleStrategy
        get() = default.handleStrategy

    /**
     * The global hybrid configuration for Intent grouping.
     *
     * Used when [handleStrategy] is HYBRID to determine how to group Intents.
     */
    internal val hybridConfig: HybridConfig<Mvi.Intent>
        get() = default.hybridConfig

    /**
     * The global retry policy for failed Intent processing.
     *
     * Determines whether to retry after an Intent processing failure.
     */
    internal val retryPolicy: RetryPolicy
        get() = default.retryPolicy

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
     *                 retryPolicy = { attempt, _ -> attempt <= 3 },
     *                 logger = Logger(
     *                     minLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN
     *                 )
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
     * @param config A lambda with receiver that transforms the current configuration.
     *               Use `copy()` to create a modified configuration.
     */
    fun setup(config: Configuration.() -> Configuration) {
        default = default.config()
        logger.d(TAG) { "setup config: $default" }
    }

    /**
     * The default retry policy implementation.
     *
     * This policy:
     * - Retries all [Exception]s (runtime errors that may be transient)
     * - Does NOT retry [Error]s (serious problems that should not be retried)
     * - Limits retries to a maximum of 3 attempts
     * - Logs each retry attempt with the exception details
     *
     * @param attempt The retry attempt number (1 for first retry, 2 for second, etc.)
     * @param cause The throwable that caused the failure
     * @return `true` if should retry (attempt <= 3 and cause is Exception), `false` otherwise
     */
    private fun defaultRetryPolicy(attempt: Long, cause: Throwable): Boolean {
        logger.e(TAG, cause) { "retry count: $attempt" }
        return attempt <= 3 && cause is Exception
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
     *             attempt <= 3 && cause is IOException
     *         }
     *     )
     * }
     * ```
     *
     * @property handleStrategy The Intent handling strategy. Default: HYBRID
     * @property hybridConfig The hybrid grouping configuration. Default: empty config
     * @property retryPolicy The retry policy for failed processing. Default: retry on Exception up to 3 times
     * @property logger The logger instance. Default: Logger with INFO level
     *
     * @see HandleStrategy
     * @see HybridConfig
     * @see RetryPolicy
     * @see Logger
     */
    data class Configuration(
        val handleStrategy: HandleStrategy = HandleStrategy.HYBRID,
        val hybridConfig: HybridConfig<Mvi.Intent> = HybridConfig(),
        val retryPolicy: RetryPolicy = ::defaultRetryPolicy,
        val logger: Logger = Logger(),
    )
}
