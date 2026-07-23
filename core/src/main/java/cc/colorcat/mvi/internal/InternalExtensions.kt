package cc.colorcat.mvi.internal

import cc.colorcat.mvi.HybridStrategyConfig
import cc.colorcat.mvi.Mvi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val DEFAULT_BUFFERED_CHANNEL_CAPACITY = 64
private const val GROUP_CAPACITY_WARNING_PERCENT = 80
private const val GROUP_CAPACITY_WARNING_RESET_PERCENT = 50
private const val UNLIMITED_GROUP_BACKLOG_WARNING_THRESHOLD = 256L

private val defaultBufferedChannelCapacity: Int =
    System.getProperty(Channel.DEFAULT_BUFFER_PROPERTY_NAME)
        ?.toIntOrNull()
        ?.takeIf { it in 1 until Channel.UNLIMITED }
        ?: DEFAULT_BUFFERED_CHANNEL_CAPACITY

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

/** Tracks intents handed to a group but not yet pulled by that group's handler Flow. */
private class GroupBacklogTracker(
    private val tagLabel: String,
    private val configuredCapacity: Int,
) {
    private val backlog = AtomicLong()
    private val nearCapacityWarningArmed = AtomicBoolean(true)
    private val saturationWarningArmed = AtomicBoolean(true)
    private val nextUnlimitedWarning = AtomicLong(UNLIMITED_GROUP_BACKLOG_WARNING_THRESHOLD)

    private val boundedCapacity: Long? = when {
        configuredCapacity == Channel.BUFFERED -> defaultBufferedChannelCapacity.toLong()
        configuredCapacity > 0 && configuredCapacity != Channel.UNLIMITED -> configuredCapacity.toLong()
        else -> null
    }
    private val warningCount: Long? = boundedCapacity?.percentageCeiling(GROUP_CAPACITY_WARNING_PERCENT)
    private val resetCount: Long? = boundedCapacity?.percentageFloor(GROUP_CAPACITY_WARNING_RESET_PERCENT)

    fun trackEnqueued() {
        val pending = backlog.incrementAndGet()
        when {
            configuredCapacity == Channel.UNLIMITED -> warnIfUnlimitedBacklogHigh(pending)
            boundedCapacity != null -> warnIfNearCapacity(pending)
        }
    }

    fun trackRemoved() {
        val pending = decrementBacklog()
        val reset = resetCount ?: return
        if (pending <= reset) {
            nearCapacityWarningArmed.set(true)
            saturationWarningArmed.set(true)
        }
    }

    fun warnIfSendWillSuspend() {
        if (configuredCapacity == Channel.CONFLATED || configuredCapacity == Channel.UNLIMITED) return
        if (!saturationWarningArmed.compareAndSet(true, false)) return

        val pending = backlog.get()
        if (configuredCapacity == Channel.RENDEZVOUS) {
            logger.w(TAG) {
                "HYBRID rendezvous group has no ready receiver (pending=$pending, group=$tagLabel). " +
                    "Intent routing is suspended, so unrelated groups are blocked until a receiver is ready."
            }
        } else {
            logger.w(TAG) {
                "HYBRID group channel is full (pending=$pending, capacity=$boundedCapacity, group=$tagLabel). " +
                    "Intent routing is suspended, so unrelated groups are blocked until capacity is available. " +
                    "Throttle producers, review grouping, or increase groupChannelCapacity."
            }
        }
    }

    private fun warnIfNearCapacity(pending: Long) {
        val capacity = requireNotNull(boundedCapacity)
        val threshold = requireNotNull(warningCount)
        if (pending < threshold || !nearCapacityWarningArmed.compareAndSet(true, false)) return

        val percent = pending * 100L / capacity
        logger.w(TAG) {
            "HYBRID group backlog reached $pending/$capacity ($percent%, warningAt=" +
                "$GROUP_CAPACITY_WARNING_PERCENT%, group=$tagLabel). This group is nearing capacity; " +
                "a full group blocks routing for unrelated groups. Throttle producers, review grouping, " +
                "or increase groupChannelCapacity."
        }
    }

    private fun warnIfUnlimitedBacklogHigh(pending: Long) {
        while (true) {
            val threshold = nextUnlimitedWarning.get()
            if (pending < threshold || threshold == Long.MAX_VALUE) return
            val next = if (threshold <= Long.MAX_VALUE / 2L) threshold * 2L else Long.MAX_VALUE
            if (!nextUnlimitedWarning.compareAndSet(threshold, next)) continue

            logger.w(TAG) {
                "HYBRID unlimited group backlog reached $pending intents " +
                    "(warningThreshold=$threshold, group=$tagLabel). The queue has no capacity bound; " +
                    "throttle producers or use a bounded groupChannelCapacity to prevent memory growth."
            }
            return
        }
    }

    private fun decrementBacklog(): Long {
        while (true) {
            val current = backlog.get()
            if (current == 0L) return 0L
            if (backlog.compareAndSet(current, current - 1L)) return current - 1L
        }
    }
}

private class TrackedIntent<I : Mvi.Intent>(
    val intent: I,
    private val tracker: GroupBacklogTracker,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) {
            tracker.trackRemoved()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class IntentGroup<I : Mvi.Intent>(tag: Any, capacity: Int) {
    private val tracker = GroupBacklogTracker(tag.tagLabel, capacity)
    private val channel = Channel<TrackedIntent<I>>(
        capacity = capacity,
        onUndeliveredElement = TrackedIntent<I>::release,
    )

    val isClosedForSend: Boolean
        get() = channel.isClosedForSend

    fun asFlow(): Flow<I> = channel.consumeAsFlow().map { tracked ->
        tracked.release()
        tracked.intent
    }

    suspend fun send(intent: I) {
        tracker.trackEnqueued()
        val tracked = TrackedIntent(intent, tracker)
        try {
            val result = channel.trySend(tracked)
            if (result.isSuccess) return
            if (!result.isClosed) tracker.warnIfSendWillSuspend()
            channel.send(tracked)
        } catch (error: Throwable) {
            tracked.release()
            throw error
        }
    }

    fun close(cause: Throwable?) {
        channel.close(cause)
    }
}

private fun Long.percentageCeiling(percent: Int): Long =
    (this * percent + 99L) / 100L

private fun Long.percentageFloor(percent: Int): Long =
    this * percent / 100L

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
 * A bounded group logs once when its pending backlog reaches 80% of capacity, then rearms
 * only after the backlog falls to 50% or lower. A second warning is logged if the channel
 * becomes full and routing must suspend. These percentages are internal constants and do
 * not change queue behavior. [Channel.BUFFERED] uses the Coroutines runtime capacity
 * (64 unless `kotlinx.coroutines.channels.defaultBuffer` overrides it).
 *
 * [Channel.RENDEZVOUS] logs when no receiver is ready. [Channel.CONFLATED] does not log
 * capacity warnings because it replaces pending values instead of filling. [Channel.UNLIMITED]
 * logs sparse absolute backlog warnings at 256, 512, 1024, and subsequent doubled thresholds.
 * Logs use the tag type and hash only; raw tag values are never included.
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
    val activeGroups = linkedMapOf<Any, IntentGroup<I>>()
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

    // Local function: creates a fresh Channel for [tag], registers it in [activeGroups]
    // BEFORE calling emit so that if emit suspends the map already holds the new entry.
    // The inner flow produced by [handler] is immediately subscribed by the downstream
    // (e.g. flattenMerge) when emit returns, so subsequent sends are safely received.
    suspend fun openGroup(tag: Any): IntentGroup<I> {
        val group = IntentGroup<I>(tag, config.groupChannelCapacity)
        activeGroups[tag] = group
        emit(group.asFlow().handler(tag))
        warnIfGroupCountHigh(tag)
        return group
    }

    try {
        collect { intent ->
            val tag = tagSelector(intent)
            val existingGroup = activeGroups[tag]
            // Re-open a fresh channel when:
            //   • no channel exists yet for this tag (first intent in the group), OR
            //   • the existing channel was closed/cancelled externally (stale channel).
            //     A stale channel can occur if flattenMerge cancelled an inner flow while
            //     the outer pipeline was still running.  Sending to a closed channel would
            //     otherwise throw ClosedSendChannelException and kill the entire pipeline.
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
            group.send(intent)
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
