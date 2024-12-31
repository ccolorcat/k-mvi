package cc.colorcat.mvi

/**
 * Author: ccolorcat
 * Date: 2024-12-25
 * GitHub: https://github.com/ccolorcat
 */
typealias RetryPolicy = (attempt: Long, cause: Throwable) -> Boolean

object MVIKit {
    private var default: Configuration = Configuration()

    internal val logger: Logger
        get() = default.logger

    internal val handleStrategy: HandleStrategy
        get() = default.handleStrategy

    internal val hybridConfig: HybridConfig<MVI.Intent>
        get() = default.hybridConfig

    internal val retryPolicy: RetryPolicy
        get() = default.retryPolicy

    fun setup(config: Configuration.() -> Configuration) {
        default = default.config()
        logger.println(Logger.DEBUG, TAG, null) { "setup config: $default" }
    }

    private fun defaultRetryPolicy(attempt: Long, cause: Throwable): Boolean {
        logger.println(Logger.ERROR, TAG, cause) { "retry count: $attempt" }
        return cause is Exception
    }

    data class Configuration(
        val handleStrategy: HandleStrategy = HandleStrategy.HYBRID,
        val hybridConfig: HybridConfig<MVI.Intent> = HybridConfig(),
        val retryPolicy: RetryPolicy = ::defaultRetryPolicy,
        val logger: Logger = Logger(),
    )
}
