package cc.colorcat.mvi

/**
 * Author: ccolorcat
 * Date: 2024-12-25
 * GitHub: https://github.com/ccolorcat
 */
typealias RetryPolicy = (attempt: Long, cause: Throwable) -> Boolean

object MVIKit {
    @Volatile
    private var defaultConfiguration = Configuration()

    internal val handleStrategy: HandleStrategy
        get() = defaultConfiguration.handleStrategy

    internal val hybridConfig: HybridConfig<MVI.Intent>
        get() = defaultConfiguration.hybridConfig

    internal val retryPolicy: RetryPolicy
        get() = defaultConfiguration.retryPolicy

    @Synchronized
    fun setup(config: Configuration.() -> Unit) {
        defaultConfiguration.config()
    }


    class Configuration(
        var handleStrategy: HandleStrategy = HandleStrategy.HYBRID,
        var hybridConfig: HybridConfig<MVI.Intent> = HybridConfig(),
        var retryPolicy: RetryPolicy = { _, _ -> false }
    )
}
