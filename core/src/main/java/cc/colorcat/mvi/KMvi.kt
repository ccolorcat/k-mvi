package cc.colorcat.mvi

import cc.colorcat.mvi.internal.TAG
import cc.colorcat.mvi.internal.d
import cc.colorcat.mvi.internal.e

/**
 * Author: ccolorcat
 * Date: 2024-12-25
 * GitHub: https://github.com/ccolorcat
 */
typealias RetryPolicy = (attempt: Long, cause: Throwable) -> Boolean

object KMvi {
    private var default: Configuration = Configuration()

    internal val logger: Logger
        get() = default.logger

    internal val handleStrategy: HandleStrategy
        get() = default.handleStrategy

    internal val hybridConfig: HybridConfig<Mvi.Intent>
        get() = default.hybridConfig

    internal val retryPolicy: RetryPolicy
        get() = default.retryPolicy

    fun setup(config: Configuration.() -> Configuration) {
        default = default.config()
        logger.d(TAG) { "setup config: $default" }
    }

    private fun defaultRetryPolicy(attempt: Long, cause: Throwable): Boolean {
        logger.e(TAG, cause) { "retry count: $attempt" }
        return cause is Exception
    }

    data class Configuration(
        val handleStrategy: HandleStrategy = HandleStrategy.HYBRID,
        val hybridConfig: HybridConfig<Mvi.Intent> = HybridConfig(),
        val retryPolicy: RetryPolicy = ::defaultRetryPolicy,
        val logger: Logger = Logger(),
    )
}
