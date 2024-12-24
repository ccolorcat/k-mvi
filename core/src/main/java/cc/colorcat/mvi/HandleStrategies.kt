package cc.colorcat.mvi

import kotlinx.coroutines.channels.Channel

/**
 * Author: ccolorcat
 * Date: 2024-12-24
 * GitHub: https://github.com/ccolorcat
 */
enum class HandleStrategy {
    CONCURRENT, SEQUENTIAL, HYBRID;

    companion object {
        val default: HandleStrategy
            get() = HYBRID
    }
}


class HybridConfig<in I : MVI.Intent>(
    internal val groupChannelCapacity: Int = Channel.BUFFERED,
    internal val groupTagSelector: (I) -> String = { it.javaClass.name }
) {
    companion object {
        val default: HybridConfig<MVI.Intent> by lazy { HybridConfig() }
    }
}
