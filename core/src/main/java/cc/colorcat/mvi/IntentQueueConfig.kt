package cc.colorcat.mvi

import cc.colorcat.mvi.internal.requireSupportedChannelConfig
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * Configuration for a contract's dispatch entry queue.
 *
 * The queue is the first buffer that receives [Mvi.Intent] values from [ReactiveContract.dispatch].
 * It is implemented with a [Channel] internally, but this type keeps the public API focused on
 * intent-queue behavior instead of exposing channel construction.
 *
 * The default configuration uses a bounded queue with [BufferOverflow.SUSPEND]. In that default
 * mode, [ReactiveContract.dispatch] returns [DispatchResult.Full] when the queue is full.
 *
 * [BufferOverflow.DROP_OLDEST], [BufferOverflow.DROP_LATEST], and [Channel.CONFLATED] are advanced
 * options for replaceable or discardable intents. With those policies, [DispatchResult.Submitted]
 * only means the dispatch request was handed to the queue policy; it does not guarantee that every
 * individual intent will eventually be processed.
 *
 * @property capacity The queue capacity. Allowed values are [Channel.BUFFERED], [Channel.CONFLATED],
 *                    [Channel.RENDEZVOUS], or any positive Int including [Channel.UNLIMITED].
 * @property onBufferOverflow The overflow policy used when the queue has no free capacity.
 */
data class IntentQueueConfig(
    val capacity: Int = DEFAULT_CAPACITY,
    val onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
) {
    init {
        requireSupportedChannelConfig(
            name = "IntentQueueConfig",
            capacity = capacity,
            onBufferOverflow = onBufferOverflow,
        )
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 256
    }
}
