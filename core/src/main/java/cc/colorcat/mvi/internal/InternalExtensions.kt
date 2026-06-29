package cc.colorcat.mvi.internal

import cc.colorcat.mvi.HybridStrategyConfig
import cc.colorcat.mvi.Mvi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow

/**
 * Internal extension functions for MVI diagnostics and Flow processing.
 *
 * This file provides utility extensions for diagnostic-safe intent names and
 * Flow grouping/handling by tag for parallel processing.
 */

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
 * so no concurrent access occurs. Channel management preserves insertion order so
 * cleanup closes groups deterministically.
 *
 * ## ⚠️ Bottleneck: All Groups Share One Sender Coroutine
 *
 * The outer `collect { }` loop is a **single coroutine** shared by all groups. When
 * [channel.send] suspends because a particular group's channel is full (backpressure
 * from a slow handler), **all groups** are blocked — even groups whose channels have
 * free capacity. New intents for unrelated groups cannot be routed until the blocked
 * send completes.
 *
 * **Mitigation**: Choose [capacity] large enough for your peak per-group throughput
 * (default [Channel.BUFFERED] = 64). Consider [Channel.UNLIMITED] if you never want
 * group-level backpressure to block the router (at the cost of unbounded memory).
 *
 * ## Active Group Lifetime
 *
 * Each distinct tag keeps an active channel until the upstream Flow completes, fails,
 * or the channel is detected as stale/closed and replaced. Tags are equality keys:
 * return values must have stable [Any.equals] and [Any.hashCode] behavior for the
 * lifetime of the flow. This preserves the core guarantee that intents with the same
 * tag are processed sequentially by the same group pipeline.
 *
 * Avoid high-cardinality tags such as resource IDs, user IDs, raw search queries, or
 * timestamps unless you intentionally want a long-lived group for each value. Prefer
 * bucketed tags such as `"user"` or `"search"` when per-value ordering is unnecessary.
 *
 * ## Group Count Diagnostics
 *
 * [warningThreshold] controls sparse warning logs for active group counts. Each time
 * a new channel is opened, the active group count is checked. When the count reaches
 * the threshold, a WARN log is emitted and the next warning threshold doubles. Set it
 * to [Int.MAX_VALUE] to disable these warning logs.
 *
 * This is diagnostic only; it never closes or evicts group channels. The log includes
 * the opened tag's type and hash, not the raw tag value, because tags may contain
 * user IDs, search queries, or other sensitive data.
 *
 * **Resource Management**: All channels are closed when the upstream Flow completes
 * or throws. If the upstream throws, the exception is passed as the close cause so
 * that each inner Flow terminates with the same error rather than silently completing.
 *
 * Example usage:
 * ```
 * intentFlow
 *     .groupHandle(
 *         config = HybridStrategyConfig(),
 *         tagSelector = { it.userId },
 *         handler = { tag ->
 *             map { intent -> processIntent(tag, intent) }
 *         }
 *     )
 *     .flattenMerge(Int.MAX_VALUE)
 *     .collect { result -> /* handle result */ }
 * ```
 *
 * @param I The intent type, must extend [Mvi.Intent]
 * @param R The result type produced by the handler
 * @param config Runtime configuration for the HYBRID strategy.
 *
 *   **Performance note**: When a group's channel is full (e.g. handler is slow),
 *   [channel.send] suspends the single outer `collect` coroutine, blocking *all*
 *   groups (see ⚠️ above). Increase [HybridStrategyConfig.groupChannelCapacity] for
 *   high-throughput scenarios, or use [Channel.UNLIMITED] to eliminate per-group
 *   backpressure (risk: unbounded memory).
 * @param tagSelector Function to extract the grouping tag from an intent
 * @param handler Function that processes the Flow of intents for each tag and produces results
 * @return A Flow of Flows, where each inner Flow represents a tagged group of processed results.
 *         **Important**: The caller must flatten this flow with a sufficiently large `concurrency`
 *         value (e.g. `flattenMerge(Int.MAX_VALUE)`). Using the default concurrency of 16 will
 *         cause `emit` to suspend when more than 16 groups exist, blocking all intent processing
 *         in this single-coroutine context.
 *
 * @see Channel.BUFFERED
 * @see Channel.UNLIMITED
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <I : Mvi.Intent, R> Flow<I>.groupHandle(
    config: HybridStrategyConfig,
    tagSelector: (I) -> Any,
    handler: Flow<I>.(tag: Any) -> Flow<R>,
): Flow<Flow<R>> = flow {
    val activeChannels = linkedMapOf<Any, Channel<I>>()
    var cause: Throwable? = null
    var nextWarningThreshold = config.groupCountWarningThreshold

    fun warnIfGroupCountHigh(tag: Any) {
        if (nextWarningThreshold == Int.MAX_VALUE) return
        val count = activeChannels.size
        if (count < nextWarningThreshold) return
        logger.w(TAG) {
            "groupHandle active groups reached $count (threshold=$nextWarningThreshold, openedTag=${tag.tagLabel}). " +
                "High-cardinality group tags keep channels active; use bucketed tags unless per-value ordering is required."
        }
        nextWarningThreshold = if (nextWarningThreshold <= Int.MAX_VALUE / 2) {
            nextWarningThreshold * 2
        } else {
            Int.MAX_VALUE
        }
    }

    // Local function: creates a fresh Channel for [tag], registers it in [activeChannels]
    // BEFORE calling emit so that if emit suspends the map already holds the new entry.
    // The inner flow produced by [handler] is immediately subscribed by the downstream
    // (e.g. flattenMerge) when emit returns, so subsequent sends are safely received.
    suspend fun openChannel(tag: Any): Channel<I> {
        val channel = Channel<I>(config.groupChannelCapacity)
        activeChannels[tag] = channel
        emit(channel.consumeAsFlow().handler(tag))
        warnIfGroupCountHigh(tag)
        return channel
    }

    try {
        collect { intent ->
            val tag = tagSelector(intent)
            val existingChannel = activeChannels[tag]
            // Re-open a fresh channel when:
            //   • no channel exists yet for this tag (first intent in the group), OR
            //   • the existing channel was closed/cancelled externally (stale channel).
            //     A stale channel can occur if flattenMerge cancelled an inner flow while
            //     the outer pipeline was still running.  Sending to a closed channel would
            //     otherwise throw ClosedSendChannelException and kill the entire pipeline.
            val channel = if (existingChannel == null || existingChannel.isClosedForSend) {
                // Remove the stale entry first so openChannel writes a clean new mapping.
                if (existingChannel != null) {
                    activeChannels.remove(tag)
                    logger.w(TAG) { "Stale channel detected for group ${tag.tagLabel}, reopening." }
                }
                openChannel(tag)
            } else {
                existingChannel
            }
            channel.send(intent)
        }
    } catch (e: CancellationException) {
        cause = e
        throw e
    } catch (e: Exception) {
        logger.e(TAG, e) { "groupHandle failed, upstream will be cancelled" }
        cause = e
        throw e
    } finally {
        // Close all remaining channels, propagating the upstream error (if any) so that
        // inner flows terminate with the same exception instead of silently completing.
        val channels = activeChannels.values.toList()
        activeChannels.clear()
        channels.forEach { it.close(cause) }
    }
}
