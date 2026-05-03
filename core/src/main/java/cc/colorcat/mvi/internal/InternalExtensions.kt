package cc.colorcat.mvi.internal

import cc.colorcat.mvi.Mvi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow

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
                    "$diagnosticName implements both Concurrent and Sequential, " +
                        "which may lead to unpredictable behavior."
                }
            }
            return true
        }

        return false
    }

/**
 * A stable, human-readable name for this intent, intended for logging and diagnostics only.
 *
 * Returns [kotlin.reflect.KClass.qualifiedName] when available (normal named classes), falling
 * back to [Class.name][java.lang.Class.name] for anonymous or local classes where `qualifiedName`
 * is `null`. The result always contains only class identity — never intent field data — so it is
 * safe to include in logs without risk of leaking sensitive information.
 *
 * **Do not use for persistence or serialization.** Names may be obfuscated by R8/ProGuard in
 * release builds and are not guaranteed to be stable across builds.
 */
internal val Mvi.Intent.diagnosticName: String
    get() = this::class.qualifiedName ?: this.javaClass.name

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
 * **Execution Model**: The `collect` lambda runs sequentially in a single coroutine,
 * so a plain [HashMap] is used for channel management — no concurrent access occurs.
 *
 * **Resource Management**: All channels are closed when the upstream Flow completes
 * or throws. If the upstream throws, the exception is passed as the close cause so
 * that each inner Flow terminates with the same error rather than silently completing.
 *
 * **Dropped intents**: If a channel is closed externally (i.e., by the handler side)
 * while an intent is being sent, the channel entry is removed from the map. If the channel
 * previously existed, it is reopened immediately and the intent is retried once. If the retry
 * fails or the channel was brand new, the intent is logged and discarded.
 *
 * Example usage:
 * ```
 * intentFlow
 *     .groupHandle(
 *         capacity = Channel.BUFFERED,
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
 * @param capacity The capacity of each channel buffer. Use [Channel.BUFFERED] for the
 *                 default size, [Channel.UNLIMITED] to never suspend the sender, or a
 *                 positive integer for a fixed buffer.
 * @param tagSelector Function to extract the grouping tag from an intent
 * @param handler Function that processes the Flow of intents for each tag and produces results
 * @return A Flow of Flows, where each inner Flow represents a tagged group of processed results
 *
 * @see Channel.BUFFERED
 * @see Channel.UNLIMITED
 */
internal fun <I : Mvi.Intent, R> Flow<I>.groupHandle(
    capacity: Int,
    tagSelector: (I) -> String,
    handler: Flow<I>.(tag: String) -> Flow<R>,
): Flow<Flow<R>> = flow {
    val activeChannels = hashMapOf<String, Channel<I>>()
    var cause: Throwable? = null
    try {
        collect { intent ->
            val tag = tagSelector(intent)
            val existingChannel = activeChannels[tag]
            val channel = existingChannel ?: openChannel(tag, capacity, activeChannels, handler)
            try {
                channel.send(intent)
            } catch (_: ClosedSendChannelException) {
                activeChannels.remove(tag, channel)
                if (existingChannel != null) {
                    logger.w(TAG) { "Channel for tag '$tag' was closed externally; recreating and retrying intent: ${intent.diagnosticName}" }
                    val newChannel = openChannel(tag, capacity, activeChannels, handler)
                    try {
                        newChannel.send(intent)
                    } catch (_: ClosedSendChannelException) {
                        activeChannels.remove(tag, newChannel)
                        logger.w(TAG) { "Channel for tag '$tag' was closed before retry could complete; intent dropped: ${intent.diagnosticName}" }
                    }
                } else {
                    logger.w(TAG) { "Channel for tag '$tag' was closed before handler could process the intent; intent dropped: ${intent.diagnosticName}" }
                }
            }
        }
    } catch (t: Throwable) {
        cause = t
        throw t
    } finally {
        // Close all remaining channels, propagating the upstream error (if any) so that
        // inner flows terminate with the same exception instead of silently completing.
        val channels = activeChannels.values.toList()
        activeChannels.clear()
        channels.forEach { it.close(cause) }
    }
}


private suspend fun <I : Mvi.Intent, R> FlowCollector<Flow<R>>.openChannel(
    tag: String,
    capacity: Int,
    activeChannels: MutableMap<String, Channel<I>>,
    handler: Flow<I>.(String) -> Flow<R>,
): Channel<I> {
    val channel = Channel<I>(capacity)
    activeChannels[tag] = channel
    emit(channel.consumeAsFlow().handler(tag))
    return channel
}
