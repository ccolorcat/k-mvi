package cc.colorcat.mvi.internal

import cc.colorcat.mvi.HybridStrategyConfig
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.TestLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class InternalExtensionsTest {

    @Rule @JvmField val testLog: TestRule = TestLogger()

    private data class LogEntry(
        val priority: Int,
        val tag: String,
        val cause: Throwable?,
        val message: String,
    )

    private class RecordingLogger : Logger {
        private val outputLock = Any()
        val entries = CopyOnWriteArrayList<LogEntry>()

        override fun log(priority: Int, tag: String, cause: Throwable?, message: () -> String) {
            val renderedMessage = message()
            entries += LogEntry(priority, tag, cause, renderedMessage)
            synchronized(outputLock) {
                println("[MVI LOG][${priorityName(priority)}][$tag] $renderedMessage")
                cause?.printStackTrace(System.out)
            }
        }

        fun messages(priority: Int): List<String> =
            entries.filter { it.priority == priority }.map { it.message }

        private fun priorityName(priority: Int): String = when (priority) {
            Logger.VERBOSE -> "VERBOSE"
            Logger.DEBUG -> "DEBUG"
            Logger.INFO -> "INFO"
            Logger.WARN -> "WARN"
            Logger.ERROR -> "ERROR"
            Logger.ASSERT -> "ASSERT"
            else -> priority.toString()
        }
    }

    private lateinit var logs: RecordingLogger

    // --- Test intent types ---

    private interface TestIntent : Mvi.Intent {
        data object OnlyConcurrent : TestIntent, Mvi.Intent.Concurrent
        data class OnlySequential(val id: Int) : TestIntent, Mvi.Intent.Sequential
    }

    // Anonymous implementation for edge cases
    private val anonymousIntent = object : Mvi.Intent {}

    private data class TaggedIntent(val tag: String, val id: Int = 0) : Mvi.Intent

    @Before
    fun setUp() {
        logs = RecordingLogger()
        KMvi.configure { KMvi.Configuration(logger = logs) }
    }

    // --- diagnosticName ---

    @Test
    fun `diagnosticName for named intent`() {
        assertEquals(
            "cc.colorcat.mvi.internal.InternalExtensionsTest.TestIntent.OnlyConcurrent",
            TestIntent.OnlyConcurrent.diagnosticName,
        )
    }

    @Test
    fun `diagnosticName for data class intent`() {
        assertTrue(TestIntent.OnlySequential(42).diagnosticName.contains("OnlySequential"))
    }

    @Test
    fun `diagnosticName for anonymous intent`() {
        assertNotNull(anonymousIntent.diagnosticName)
        assertTrue(anonymousIntent.diagnosticName.isNotBlank())
    }

    @Test
    fun `diagnosticName does not contain sensitive data`() {
        // Should only contain class identity, not field values
        val name = TestIntent.OnlySequential(42).diagnosticName
        assertFalse(name.contains("42"))
    }

    // --- groupHandle diagnostics ---

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle warns only when active group count reaches doubled thresholds`() = runBlocking {
        val results = flow {
            emit(TaggedIntent("a"))
            emit(TaggedIntent("b"))
            emit(TaggedIntent("c"))
            emit(TaggedIntent("d"))
        }.groupHandle(
            config = HybridStrategyConfig(
                groupChannelCapacity = Channel.BUFFERED,
                groupCountWarningThreshold = 2,
            ),
            tagSelector = { it.tag },
        ) { tag ->
            map { "$tag:${it.tag}" }
        }.flattenMerge(Int.MAX_VALUE).toList()

        val warnings = logs.messages(Logger.WARN)
        assertEquals(listOf("a:a", "b:b", "c:c", "d:d"), results.sorted())
        assertEquals(2, warnings.size)
        assertTrue(warnings[0].contains("active groups reached 2"))
        assertTrue(warnings[0].contains("threshold=2"))
        assertTrue(
            warnings[0].contains("openedTag=tag(type=java.lang.String, hash=${Integer.toHexString("b".hashCode())})"),
        )
        assertTrue(warnings[1].contains("active groups reached 4"))
        assertTrue(warnings[1].contains("threshold=4"))
        assertTrue(
            warnings[1].contains("openedTag=tag(type=java.lang.String, hash=${Integer.toHexString("d".hashCode())})"),
        )
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle does not warn below group count threshold`() = runBlocking {
        flow {
            emit(TaggedIntent("a"))
            emit(TaggedIntent("b"))
            emit(TaggedIntent("c"))
        }.groupHandle(
            config = HybridStrategyConfig(
                groupChannelCapacity = Channel.BUFFERED,
                groupCountWarningThreshold = 4,
            ),
            tagSelector = { it.tag },
        ) {
            map { it.tag }
        }.flattenMerge(Int.MAX_VALUE).toList()

        assertTrue(logs.messages(Logger.WARN).isEmpty())
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle Int_MAX_VALUE threshold disables warnings`() = runBlocking {
        flow {
            repeat(128) { emit(TaggedIntent("tag-$it")) }
        }.groupHandle(
            config = HybridStrategyConfig(
                groupChannelCapacity = Channel.BUFFERED,
                groupCountWarningThreshold = Int.MAX_VALUE,
            ),
            tagSelector = { it.tag },
        ) {
            map { it.tag }
        }.flattenMerge(Int.MAX_VALUE).toList()

        assertTrue(logs.messages(Logger.WARN).isEmpty())
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle BUFFERED warns at 52 of default 64 capacity`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val belowThresholdSubmitted = CompletableDeferred<Unit>()
        val submitThresholdIntent = CompletableDeferred<Unit>()

        val collection = async {
            flow {
                emit(TaggedIntent("buffered", 0))
                firstStarted.await()
                repeat(51) { emit(TaggedIntent("buffered", it + 1)) }
                belowThresholdSubmitted.complete(Unit)
                submitThresholdIntent.await()
                emit(TaggedIntent("buffered", 52))
            }.groupHandle(
                config = HybridStrategyConfig(
                    groupChannelCapacity = Channel.BUFFERED,
                    groupCountWarningThreshold = Int.MAX_VALUE,
                ),
                tagSelector = { it.tag },
            ) {
                map { intent ->
                    if (intent.id == 0) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                    intent.id
                }
            }.flattenMerge(Int.MAX_VALUE).toList()
        }

        belowThresholdSubmitted.await()
        assertTrue(backlogWarnings().isEmpty())

        submitThresholdIntent.complete(Unit)
        withTimeout(5_000) {
            while (backlogWarnings().isEmpty()) yield()
        }

        val warning = backlogWarnings().single()
        assertTrue(warning.contains("channel at 52/64 (>=80%)"))
        assertTrue(warning.contains("groupChannelCapacity"))

        releaseFirst.complete(Unit)
        assertEquals((0..52).toList(), collection.await())
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle large bounded capacity does not overflow warning threshold`() = runBlocking {
        val results = flow {
            emit(TaggedIntent("large", 1))
        }.groupHandle(
            config = HybridStrategyConfig(
                groupChannelCapacity = 1_000_000_000,
                groupCountWarningThreshold = Int.MAX_VALUE,
            ),
            tagSelector = { it.tag },
        ) {
            map { it.id }
        }.flattenMerge(Int.MAX_VALUE).toList()

        assertEquals(listOf(1), results)
        assertTrue(monitoringWarnings().isEmpty())
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle backlog warning rearms after backlog drains`() = runBlocking {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val startSecondBurst = CompletableDeferred<Unit>()
        val firstBurstDrained = CompletableDeferred<Unit>()
        val handled = AtomicInteger()

        val collection = async {
            flow {
                repeat(6) { emit(TaggedIntent("hot", it)) }
                startSecondBurst.await()
                repeat(6) { emit(TaggedIntent("hot", it + 6)) }
            }.groupHandle(
                config = HybridStrategyConfig(
                    groupChannelCapacity = 6,
                    groupCountWarningThreshold = Int.MAX_VALUE,
                ),
                tagSelector = { it.tag },
            ) {
                map { intent ->
                    val count = handled.incrementAndGet()
                    when (intent.id) {
                        0 -> firstGate.await()
                        6 -> secondGate.await()
                    }
                    if (count == 6) firstBurstDrained.complete(Unit)
                    intent.id
                }
            }.flattenMerge(Int.MAX_VALUE).toList()
        }

        withTimeout(5_000) {
            while (backlogWarnings().size < 1) yield()
        }
        firstGate.complete(Unit)
        firstBurstDrained.await()
        startSecondBurst.complete(Unit)
        withTimeout(5_000) {
            while (backlogWarnings().size < 2) yield()
        }
        secondGate.complete(Unit)

        val backlogWarnings = backlogWarnings()
        assertEquals((0 until 12).toList(), collection.await())
        assertEquals(2, backlogWarnings.size)
        assertTrue(backlogWarnings.all { it.contains("channel at 5/6 (>=80%)") })
        assertTrue(backlogWarnings.all { it.contains("tag(type=java.lang.String") })
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle full warning rearms after backlog drains`() = runBlocking {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val firstBurstDrained = CompletableDeferred<Unit>()
        val startSecondBurst = CompletableDeferred<Unit>()
        val handled = AtomicInteger()

        val collection = async {
            flow {
                repeat(3) { emit(TaggedIntent("hot", it)) }
                startSecondBurst.await()
                repeat(3) { emit(TaggedIntent("hot", it + 3)) }
            }.groupHandle(
                config = HybridStrategyConfig(
                    groupChannelCapacity = 1,
                    groupCountWarningThreshold = Int.MAX_VALUE,
                ),
                tagSelector = { it.tag },
            ) {
                map { intent ->
                    val count = handled.incrementAndGet()
                    when (intent.id) {
                        0 -> firstGate.await()
                        3 -> secondGate.await()
                    }
                    if (count == 3) firstBurstDrained.complete(Unit)
                    intent.id
                }
            }.flattenMerge(Int.MAX_VALUE).toList()
        }

        withTimeout(5_000) {
            while (fullWarnings().size < 1) yield()
        }
        firstGate.complete(Unit)
        firstBurstDrained.await()
        startSecondBurst.complete(Unit)
        withTimeout(5_000) {
            while (fullWarnings().size < 2) yield()
        }
        secondGate.complete(Unit)

        assertEquals((0..5).toList(), collection.await())
        assertEquals(2, fullWarnings().size)
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle warns and blocks unrelated groups when bounded group becomes full`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val coldHandled = CompletableDeferred<Unit>()

        val collection = async {
            flow {
                repeat(3) { emit(TaggedIntent("hot", it)) }
                emit(TaggedIntent("cold", 99))
            }.groupHandle(
                config = HybridStrategyConfig(
                    groupChannelCapacity = 1,
                    groupCountWarningThreshold = Int.MAX_VALUE,
                ),
                tagSelector = { it.tag },
            ) {
                map { intent ->
                    if (intent.id == 0) gate.await()
                    if (intent.tag == "cold") coldHandled.complete(Unit)
                    intent.id
                }
            }.flattenMerge(Int.MAX_VALUE).toList()
        }

        withTimeout(5_000) {
            while (logs.messages(Logger.WARN).none { it.contains("is FULL") }) yield()
        }
        assertFalse(coldHandled.isCompleted)
        gate.complete(Unit)

        val results = collection.await()
        assertEquals(listOf(0, 1, 2), results.filter { it != 99 })
        assertTrue(coldHandled.isCompleted)
        assertTrue(99 in results)
        val saturationWarnings = logs.messages(Logger.WARN).filter { it.contains("is FULL") }
        assertEquals(1, saturationWarnings.size)
        assertTrue(saturationWarnings.single().contains("capacity=1"))
        assertTrue(saturationWarnings.single().contains("ALL groups are now blocked"))
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle UNLIMITED does not emit percentage or full warnings`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val allSubmitted = CompletableDeferred<Unit>()

        val collection = async {
            flow {
                emit(TaggedIntent("unlimited", 0))
                firstStarted.await()
                repeat(100) { emit(TaggedIntent("unlimited", it + 1)) }
                allSubmitted.complete(Unit)
            }.groupHandle(
                config = HybridStrategyConfig(
                    groupChannelCapacity = Channel.UNLIMITED,
                    groupCountWarningThreshold = Int.MAX_VALUE,
                ),
                tagSelector = { it.tag },
            ) {
                map { intent ->
                    if (intent.id == 0) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                    intent.id
                }
            }.flattenMerge(Int.MAX_VALUE).toList()
        }

        allSubmitted.await()
        assertTrue(monitoringWarnings().isEmpty())
        releaseFirst.complete(Unit)
        assertEquals((0..100).toList(), collection.await())
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle RENDEZVOUS does not emit percentage or full warnings`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondSendStarted = CompletableDeferred<Unit>()

        val collection = async {
            flow {
                emit(TaggedIntent("rendezvous", 0))
                firstStarted.await()
                secondSendStarted.complete(Unit)
                emit(TaggedIntent("rendezvous", 1))
            }.groupHandle(
                config = HybridStrategyConfig(
                    groupChannelCapacity = Channel.RENDEZVOUS,
                    groupCountWarningThreshold = Int.MAX_VALUE,
                ),
                tagSelector = { it.tag },
            ) {
                map { intent ->
                    if (intent.id == 0) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                    intent.id
                }
            }.flattenMerge(Int.MAX_VALUE).toList()
        }

        secondSendStarted.await()
        yield()
        assertFalse(collection.isCompleted)
        assertTrue(monitoringWarnings().isEmpty())
        releaseFirst.complete(Unit)
        assertEquals(listOf(0, 1), collection.await())
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle CONFLATED keeps latest intent without capacity warnings`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val allSubmitted = CompletableDeferred<Unit>()

        val collection = async {
            flow {
                emit(TaggedIntent("conflated", 0))
                firstStarted.await()
                repeat(100) { emit(TaggedIntent("conflated", it + 1)) }
                allSubmitted.complete(Unit)
            }.groupHandle(
                config = HybridStrategyConfig(
                    groupChannelCapacity = Channel.CONFLATED,
                    groupCountWarningThreshold = Int.MAX_VALUE,
                ),
                tagSelector = { it.tag },
            ) {
                map { intent ->
                    if (intent.id == 0) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                    intent.id
                }
            }.flattenMerge(Int.MAX_VALUE).toList()
        }

        allSubmitted.await()
        assertTrue(monitoringWarnings().isEmpty())
        releaseFirst.complete(Unit)
        assertEquals(listOf(0, 100), collection.await())
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle preserves same-group order while different groups run concurrently`() = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val fastHandled = CompletableDeferred<Unit>()

        val collection = async {
            flow {
                emit(TaggedIntent("slow", 0))
                slowStarted.await()
                emit(TaggedIntent("fast", 100))
                emit(TaggedIntent("slow", 1))
            }.groupHandle(
                config = HybridStrategyConfig(
                    groupChannelCapacity = 4,
                    groupCountWarningThreshold = Int.MAX_VALUE,
                ),
                tagSelector = { it.tag },
            ) { tag ->
                map { intent ->
                    if (tag == "slow" && intent.id == 0) {
                        slowStarted.complete(Unit)
                        releaseSlow.await()
                    }
                    if (tag == "fast") fastHandled.complete(Unit)
                    "$tag:${intent.id}"
                }
            }.flattenMerge(Int.MAX_VALUE).toList()
        }

        withTimeout(5_000) { fastHandled.await() }
        assertFalse(releaseSlow.isCompleted)
        releaseSlow.complete(Unit)

        val results = collection.await()
        assertTrue(results.indexOf("fast:100") < results.indexOf("slow:0"))
        assertEquals(listOf("slow:0", "slow:1"), results.filter { it.startsWith("slow:") })
        assertTrue(monitoringWarnings().isEmpty())
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle reopens stale group channel`() = runBlocking {
        val firstGroupClosed = CompletableDeferred<Unit>()
        val openedGroups = AtomicInteger()

        val results = flow {
            emit(TaggedIntent("raw-sensitive-group", 1))
            firstGroupClosed.await()
            emit(TaggedIntent("raw-sensitive-group", 2))
        }.groupHandle(
            config = HybridStrategyConfig(
                groupChannelCapacity = 2,
                groupCountWarningThreshold = Int.MAX_VALUE,
            ),
            tagSelector = { it.tag },
        ) {
            val groupNumber = openedGroups.incrementAndGet()
            map { it.id }
                .take(1)
                .onCompletion {
                    if (groupNumber == 1) firstGroupClosed.complete(Unit)
                }
        }.flattenMerge(Int.MAX_VALUE).toList()

        assertEquals(listOf(1, 2), results)
        assertEquals(2, openedGroups.get())
        val warnings = logs.messages(Logger.WARN).filter { it.contains("Stale channel detected") }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("tag(type=java.lang.String"))
        assertFalse(warnings.single().contains("raw-sensitive-group"))
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle logs complete upstream failure and propagates it`() {
        val expected = IllegalStateException("upstream exploded")

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                flow {
                    emit(TaggedIntent("error", 1))
                    throw expected
                }.groupHandle(
                    config = HybridStrategyConfig(
                        groupChannelCapacity = 2,
                        groupCountWarningThreshold = Int.MAX_VALUE,
                    ),
                    tagSelector = { it.tag },
                ) {
                    map { it.id }
                }.flattenMerge(Int.MAX_VALUE).toList()
            }
        }

        assertEquals(expected::class, thrown::class)
        assertEquals(expected.message, thrown.message)
        val errors = logs.entries.filter { it.priority == Logger.ERROR }
        assertEquals(1, errors.size)
        assertEquals(TAG, errors.single().tag)
        assertSame(expected, errors.single().cause)
        assertEquals("groupHandle failed, upstream will be cancelled", errors.single().message)
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle cancellation does not log failure`() = runBlocking {
        val upstreamWaiting = CompletableDeferred<Unit>()

        val collection = launch {
            flow {
                emit(TaggedIntent("cancel", 1))
                upstreamWaiting.complete(Unit)
                awaitCancellation()
            }.groupHandle(
                config = HybridStrategyConfig(
                    groupChannelCapacity = 2,
                    groupCountWarningThreshold = Int.MAX_VALUE,
                ),
                tagSelector = { it.tag },
            ) {
                map { it.id }
            }.flattenMerge(Int.MAX_VALUE).toList()
        }

        upstreamWaiting.await()
        collection.cancelAndJoin()

        assertTrue(logs.entries.none { it.priority == Logger.ERROR })
    }

    private fun backlogWarnings(): List<String> =
        logs.messages(Logger.WARN).filter { it.contains("HYBRID group") && it.contains(">=80%") }

    private fun fullWarnings(): List<String> =
        logs.messages(Logger.WARN).filter { it.contains("is FULL") }

    private fun monitoringWarnings(): List<String> =
        logs.messages(Logger.WARN).filter { it.contains(">=80%") || it.contains("is FULL") }
}
