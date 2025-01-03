package cc.colorcat.mvi

import kotlinx.coroutines.channels.Channel

/**
 * Author: ccolorcat
 * Date: 2024-12-24
 * GitHub: https://github.com/ccolorcat
 */
enum class HandleStrategy {
    CONCURRENT, SEQUENTIAL, HYBRID
}


data class HybridConfig<in I : Mvi.Intent>(
    internal val groupChannelCapacity: Int = Channel.BUFFERED,
    internal val groupTagSelector: (I) -> String = { it.javaClass.name }
)
