package cc.colorcat.mvi.internal

import cc.colorcat.mvi.HybridStrategyConfig
import cc.colorcat.mvi.Mvi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

private const val DEFAULT_BUFFERED_CHANNEL_CAPACITY = 64
private const val GROUP_CAPACITY_WARNING_PERCENT = 80
private const val GROUP_CAPACITY_WARNING_RESET_PERCENT = 50
private const val PERCENT_BASE = 100

/**
 * Lightweight fill monitor for a single HYBRID group channel.
 *
 * Counts intents buffered but not yet consumed, and emits sparse WARN logs at two thresholds:
 * 1. **Near-full (>=80%)** — early hint that the handler lags behind the arrival rate
 * 2. **Full (send would suspend)** — confirmation that the shared router is now blocked
 *
 * Both warnings re-arm when depth drops to 50% or below, avoiding log spam during
 * sustained congestion. Depth may read transiently off by one under concurrent
 * send/consume — acceptable for a diagnostic hint.
 *
 * Monitoring is skipped entirely for [Channel.UNLIMITED], [Channel.RENDEZVOUS], and
 * [Channel.CONFLATED] where percentage-based fill has no meaningful interpretation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class GroupChannel<I>(tag: Any, capacity: Int) {
    val channel = Channel<I>(capacity)

    // Delegate channel lifecycle to groupHandle
    val isClosedForSend: Boolean
        get() = channel.isClosedForSend

    // Resolve fixed capacity for percentage thresholds; negative means "skip monitoring".
    // Uses the Coroutines default for BUFFERED; monitoring remains a diagnostic hint.
    private val fixedCapacity = when (capacity) {
        Channel.BUFFERED -> DEFAULT_BUFFERED_CHANNEL_CAPACITY
        in 1 until Channel.UNLIMITED -> capacity
        else -> -1
    }
    private val warnAt = if (fixedCapacity > 0) {
        fixedCapacity.percentageCeiling(GROUP_CAPACITY_WARNING_PERCENT)
    } else {
        -1
    }
    private val rearmAt = if (fixedCapacity > 0) {
        fixedCapacity.percentageFloor(GROUP_CAPACITY_WARNING_RESET_PERCENT)
    } else {
        -1
    }

    private val depth = AtomicInteger(0)
    private val nearFullWarned = AtomicBoolean(false)
    private val fullWarned = AtomicBoolean(false)
    private val tagLabel = tag.tagLabel

    /** Called right after an intent is accepted into [channel] (router coroutine). */
    fun onBuffered() {
        if (fixedCapacity <= 0) return
        val current = depth.incrementAndGet()
        if (current >= warnAt && nearFullWarned.compareAndSet(false, true)) {
            logger.w(TAG) {
                "HYBRID group $tagLabel channel at $current/$fixedCapacity (>=80%). " +
                    "Handler is slower than intent arrival; the shared router will block " +
                    "when this channel fills. Throttle producers, review grouping, " +
                    "or increase groupChannelCapacity."
            }
        }
    }

    /** Called when [channel] is full and [send] will suspend (router coroutine). */
    fun onWillSuspend() {
        if (fixedCapacity <= 0) return
        if (fullWarned.compareAndSet(false, true)) {
            logger.w(TAG) {
                "HYBRID group $tagLabel channel is FULL (capacity=$fixedCapacity). " +
                    "Routing suspended — ALL groups are now blocked. " +
                    "Increase groupChannelCapacity or use Channel.UNLIMITED."
            }
        }
    }

    /** Called when an intent leaves [channel]'s buffer (consumer flow, via [onEach]). */
    fun onConsumed() {
        if (fixedCapacity <= 0) return
        if (depth.decrementAndGet() <= rearmAt) {
            nearFullWarned.set(false)
            fullWarned.set(false)
        }
    }

    fun close(cause: Throwable?) = channel.close(cause)
}

private fun Int.percentageCeiling(percent: Int): Int =
    ((toLong() * percent + PERCENT_BASE - 1L) / PERCENT_BASE).toInt()

private fun Int.percentageFloor(percent: Int): Int =
    (toLong() * percent / PERCENT_BASE).toInt()

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
 * [Channel.send] suspends because a particular group's channel is full (backpressure
 * from a slow handler), **all groups** are blocked — even groups whose channels have
 * free capacity. New intents for unrelated groups cannot be routed until the blocked
 * send completes.
 *
 * **Mitigation order**: throttle or conflate high-frequency producers first; verify that
 * unrelated work does not share a fallback tag; keep handlers non-blocking; then increase
 * [HybridStrategyConfig.groupChannelCapacity] from measurements. Use [Channel.UNLIMITED]
 * only when traffic is externally bounded because a slow group can otherwise grow memory
 * without limit.
 *
 * ## Group Backlog Diagnostics
 *
 * Bounded groups (including the default [Channel.BUFFERED]) log a single WARN when buffered
 * depth reaches 80% of capacity, and re-arm after depth drops to 50% or lower. A second
 * warning is logged if the channel becomes full and routing must suspend. These thresholds
 * are internal constants and do not change queue behavior. [Channel.UNLIMITED],
 * [Channel.RENDEZVOUS], and [Channel.CONFLATED] are not monitored because their capacities
 * have no meaningful percentage-based fill level.
 *
 * Diagnostics log the tag type and hash only; raw tag values are never included.
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
 *   [Channel.send] suspends the single outer `collect` coroutine, blocking *all*
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
    val activeGroups = linkedMapOf<Any, GroupChannel<I>>()
    var cause: Throwable? = null
    var nextWarningThreshold = config.groupCountWarningThreshold

    fun warnIfGroupCountHigh(tag: Any) {
        if (nextWarningThreshold == Int.MAX_VALUE) return
        val count = activeGroups.size
        if (count < nextWarningThreshold) return
        logger.w(TAG) {
            "groupHandle active groups reached $count " +
                "(threshold=$nextWarningThreshold, openedTag=${tag.tagLabel}). " +
                "High-cardinality group tags keep channels active; use bucketed tags unless " +
                "per-value ordering is required."
        }
        nextWarningThreshold = if (nextWarningThreshold <= Int.MAX_VALUE / 2) {
            nextWarningThreshold * 2
        } else {
            Int.MAX_VALUE
        }
    }

    // Local function: creates a fresh GroupChannel for [tag], registers it in [activeGroups]
    // BEFORE calling emit so that if emit suspends the map already holds the new entry.
    // The inner flow produced by [handler] is immediately subscribed by the downstream
    // (e.g. flattenMerge) when emit returns; its onEach decrements the fill monitor as each
    // intent leaves the channel buffer.
    suspend fun openGroup(tag: Any): GroupChannel<I> {
        val group = GroupChannel<I>(tag, config.groupChannelCapacity)
        activeGroups[tag] = group
        emit(group.channel.consumeAsFlow().onEach { group.onConsumed() }.handler(tag))
        warnIfGroupCountHigh(tag)
        return group
    }

    try {
        collect { intent ->
            val tag = tagSelector(intent)
            val existingGroup = activeGroups[tag]
            // Re-open a fresh channel when:
            //   • no channel exists yet for this tag (first intent in the group), OR
            //   • the existing channel is already closed for send (stale channel).
            //     A group channel goes stale only when its inner flow was torn down and
            //     consumeAsFlow cancelled the underlying channel. Routing an intent to it
            //     would re-throw that cancellation cause (a CancellationException), so we
            //     reopen a fresh channel instead of sending to the dead one.
            val group = if (existingGroup == null || existingGroup.isClosedForSend) {
                // Remove the stale entry first so openGroup writes a clean new mapping.
                if (existingGroup != null) {
                    activeGroups.remove(tag)
                    logger.w(TAG) { "Stale channel detected for group ${tag.tagLabel}, reopening." }
                }
                openGroup(tag)
            } else {
                existingGroup
            }

            // Try non-suspending send first to detect a full channel before blocking.
            val result = group.channel.trySend(intent)
            when {
                result.isSuccess -> group.onBuffered()
                result.isClosed -> {
                    // Rare race: the channel was cancelled between the isClosedForSend
                    // check above and this trySend. The handler used here (flatMap* over
                    // the channel) never completes early, so a stale channel means its
                    // inner flow was torn down and the pipeline is already unwinding. The
                    // channel is *cancelled*, so re-issuing send re-throws its cancellation
                    // cause (a CancellationException, never ClosedSendChannelException),
                    // which the CancellationException catch below propagates to stop the
                    // shared router. Reopening is intentionally not done here.
                    group.channel.send(intent)
                    group.onBuffered()
                }

                else -> {
                    group.onWillSuspend()   // warn before blocking the shared router
                    group.channel.send(intent)
                    group.onBuffered()
                }
            }
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
        val groups = activeGroups.values.toList()
        activeGroups.clear()
        groups.forEach { it.close(cause) }
    }
}
