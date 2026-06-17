package cc.colorcat.mvi.internal

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * Validates channel-like buffer configuration for K-MVI queue configs.
 *
 * Supported capacity values:
 * - Named constants in `[-2, 0]`: [Channel.BUFFERED], [Channel.CONFLATED], [Channel.RENDEZVOUS]
 * - Any positive `Int` (`> 0`), including [Channel.UNLIMITED]
 *
 * [Channel.CONFLATED] already defines conflation semantics internally and only supports
 * [BufferOverflow.SUSPEND]. Other supported capacities can use any [BufferOverflow].
 *
 * @author ccolorcat
 */
internal fun requireSupportedChannelConfig(
    name: String,
    capacity: Int,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
) {
    val isNamedConst = capacity == Channel.BUFFERED || capacity == Channel.CONFLATED || capacity == Channel.RENDEZVOUS
    require(isNamedConst || capacity > 0) {
        "$name must use Channel.BUFFERED, Channel.CONFLATED, Channel.RENDEZVOUS, or any positive Int, but capacity was $capacity"
    }

    require(capacity != Channel.CONFLATED || onBufferOverflow == BufferOverflow.SUSPEND) {
        "$name with Channel.CONFLATED requires BufferOverflow.SUSPEND, but onBufferOverflow was $onBufferOverflow"
    }
}

/**
 * Redacted label for logging a group tag without invoking its raw [Any.toString].
 */
internal val Any.tagLabel: String
    get() = "tag(type=${javaClass.name}, hash=${Integer.toHexString(hashCode())})"
