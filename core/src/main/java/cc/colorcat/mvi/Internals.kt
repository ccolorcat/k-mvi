package cc.colorcat.mvi

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap

/**
 * Author: ccolorcat
 * Date: 2024-12-25
 * GitHub: https://github.com/ccolorcat
 */
internal const val TAG = "k-mvi"

internal val logger: Logger
    get() = KMvi.logger


internal val Mvi.Intent.isConcurrent: Boolean
    get() = this is Mvi.Intent.Concurrent && this !is Mvi.Intent.Sequential

internal val Mvi.Intent.isSequential: Boolean
    get() = this is Mvi.Intent.Sequential && this !is Mvi.Intent.Concurrent

/**
 * Checks if the Intent has conflicting types or doesn't fall into either Concurrent or Sequential categories.
 * Logs a warning if an Intent is marked both Concurrent and Sequential, as it may cause unpredictable behavior.
 */
internal val Mvi.Intent.isFallback: Boolean
    get() {
        if (this !is Mvi.Intent.Concurrent && this !is Mvi.Intent.Sequential) {
            return true
        }

        val isConflictingIntent = this is Mvi.Intent.Concurrent && this is Mvi.Intent.Sequential
        if (isConflictingIntent) {
            logger.log(Logger.WARN, TAG, null) {
                "${javaClass.name} implements both Concurrent and Sequential, which may lead to unpredictable behavior."
            }
        }
        return isConflictingIntent
    }

internal fun <I : Mvi.Intent, R> Flow<I>.groupHandle(
    capacity: Int,
    tagSelector: (I) -> String,
    handler: Flow<I>.(tag: String) -> Flow<R>,
): Flow<Flow<R>> = flow {
    val activeChannels = ConcurrentHashMap<String, SendChannel<I>>()
    try {
        collect { intent ->
            val tag = tagSelector(intent)
            val channel = activeChannels.getOrPut(tag) {
                Channel<I>(capacity).also { emit(it.consumeAsFlow().handler(tag)) }
            }
            try {
                channel.send(intent)
            } catch (e: ClosedSendChannelException) {
                activeChannels.remove(tag, channel)
            }
        }
    } finally {
        synchronized(activeChannels) {
            activeChannels.forEach { it.value.close() }
            activeChannels.clear()
        }
    }
}
