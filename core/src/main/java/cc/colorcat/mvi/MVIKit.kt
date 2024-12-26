package cc.colorcat.mvi

/**
 * Author: ccolorcat
 * Date: 2024-12-25
 * GitHub: https://github.com/ccolorcat
 */
typealias RetryPolicy = (attempt: Long, cause: Throwable) -> Boolean

object MVIKit {
    private var default: Configuration = Configuration()

    internal val loggable: Boolean
        get() = default.loggable

    internal val handleStrategy: HandleStrategy
        get() = default.handleStrategy

    internal val hybridConfig: HybridConfig<MVI.Intent>
        get() = default.hybridConfig

    internal val retryPolicy: RetryPolicy
        get() = default.retryPolicy

    fun setup(config: Configuration.() -> Configuration) {
        default = default.config()
    }

    private fun defaultRetryPolicy(attempt: Long, cause: Throwable): Boolean {
        if (loggable) {
            cause.printStackTrace()
        }
        return cause is Exception
    }


    data class Configuration(
        val loggable: Boolean = false,
        val handleStrategy: HandleStrategy = HandleStrategy.HYBRID,
        val hybridConfig: HybridConfig<MVI.Intent> = HybridConfig(),
        val retryPolicy: RetryPolicy = ::defaultRetryPolicy,
    )
}
