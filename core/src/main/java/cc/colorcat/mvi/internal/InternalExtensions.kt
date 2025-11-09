package cc.colorcat.mvi.internal

import cc.colorcat.mvi.Mvi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap

/**
 * Internal extension functions for MVI Intent handling and Flow processing.
 *
 * This file provides utility extensions for:
 * - Intent type checking (Concurrent, Sequential, Fallback)
 * - Flow grouping and handling by tag for parallel processing
 *
 * Author: ccolorcat
 * Date: 2025-11-08
 * GitHub: https://github.com/ccolorcat
 */


/**
 * Checks if the Intent is purely of type [Mvi.Intent.Concurrent].
 *
 * Returns `true` only if the Intent implements [Mvi.Intent.Concurrent] and does NOT
 * implement [Mvi.Intent.Sequential]. This ensures the Intent has unambiguous concurrent semantics.
 *
 * @return `true` if the Intent is purely concurrent, `false` otherwise
 * @see isSequential
 * @see isFallback
 */
internal val Mvi.Intent.isConcurrent: Boolean
    get() = this is Mvi.Intent.Concurrent && this !is Mvi.Intent.Sequential

/**
 * Checks if the Intent is purely of type [Mvi.Intent.Sequential].
 *
 * Returns `true` only if the Intent implements [Mvi.Intent.Sequential] and does NOT
 * implement [Mvi.Intent.Concurrent]. This ensures the Intent has unambiguous sequential semantics.
 *
 * @return `true` if the Intent is purely sequential, `false` otherwise
 * @see isConcurrent
 * @see isFallback
 */
internal val Mvi.Intent.isSequential: Boolean
    get() = this is Mvi.Intent.Sequential && this !is Mvi.Intent.Concurrent

/**
 * Checks if the Intent is a fallback type (neither concurrent nor sequential, or both).
 *
 * This property returns `true` in two cases:
 * 1. The Intent implements neither [Mvi.Intent.Concurrent] nor [Mvi.Intent.Sequential]
 * 2. The Intent implements both interfaces (conflicting - logs a warning)
 *
 * When an Intent implements both Concurrent and Sequential, a warning is logged because
 * this leads to unpredictable behavior. The framework will treat such intents as fallback.
 *
 * @return `true` if the Intent should be treated as fallback, `false` otherwise
 * @see isConcurrent
 * @see isSequential
 */
internal val Mvi.Intent.isFallback: Boolean
    get() {
        val concurrent = this is Mvi.Intent.Concurrent
        val sequential = this is Mvi.Intent.Sequential

        // If neither or both, it's a fallback
        if (concurrent == sequential) {
            // If both are true, it's a conflict - log warning
            if (concurrent) {
                logger.w(TAG) {
                    "${javaClass.name} implements both Concurrent and Sequential, " +
                            "which may lead to unpredictable behavior."
                }
            }
            return true
        }

        return false
    }

/**
 * Groups intents by tag and handles each group independently with parallel processing.
 *
 * This function creates a separate Flow for each unique tag. Intents with the same tag
 * are sent to the same channel and processed by the same handler Flow. This enables
 * parallel processing of different intent groups while maintaining order within each group.
 *
 * The function maintains a map of active channels (one per tag). When a new tag is
 * encountered, a new channel is created and a handler Flow is emitted. Subsequent
 * intents with the same tag are sent to the existing channel.
 *
 * **Thread Safety**: This function uses [ConcurrentHashMap] to ensure thread-safe
 * channel management across concurrent Flow collectors.
 *
 * **Resource Management**: All channels are properly closed when the upstream Flow
 * completes or when an error occurs.
 *
 * Example usage:
 * ```
 * intentFlow
 *     .groupHandle(
 *         capacity = Channel.UNLIMITED,
 *         tagSelector = { it.userId },
 *         handler = { tag ->
 *             map { intent -> processIntent(tag, intent) }
 *         }
 *     )
 *     .flattenMerge()
 *     .collect { result -> /* handle result */ }
 * ```
 *
 * @param I The intent type, must extend [Mvi.Intent]
 * @param R The result type produced by the handler
 * @param capacity The capacity of each channel. Use [Channel.BUFFERED] for default buffering,
 *                 [Channel.UNLIMITED] for no limit, or a specific number for fixed buffer size.
 * @param tagSelector Function to extract the grouping tag from an intent
 * @param handler Function that processes the Flow of intents for each tag and produces results
 * @return A Flow of Flows, where each inner Flow represents a tagged group of processed results
 *
 * @see Channel.BUFFERED
 * @see Channel.UNLIMITED
 * @see ConcurrentHashMap
 */
internal fun <I : Mvi.Intent, R> Flow<I>.groupHandle(
    capacity: Int,
    tagSelector: (I) -> String,
    handler: Flow<I>.(tag: String) -> Flow<R>,
): Flow<Flow<R>> = flow {
    val activeChannels = ConcurrentHashMap<String, Channel<I>>()
    try {
        collect { intent ->
            val tag = tagSelector(intent)
            var shouldEmitFlow = false
            val channel = activeChannels.computeIfAbsent(tag) {
                shouldEmitFlow = true
                Channel(capacity)
            }
            if (shouldEmitFlow) {
                emit(channel.consumeAsFlow().handler(tag))
            }
            try {
                channel.send(intent)
            } catch (e: ClosedSendChannelException) {
                // Channel was closed externally, log and clean up
                logger.w(TAG) { "Channel for tag '$tag' was closed externally" }
                if (activeChannels.remove(tag, channel)) {
                    channel.close()
                }
            }
        }
    } finally {
        // Clean up: close all remaining channels
        val channels = activeChannels.values.toList()
        activeChannels.clear()
        channels.forEach { it.close() }
    }
}
