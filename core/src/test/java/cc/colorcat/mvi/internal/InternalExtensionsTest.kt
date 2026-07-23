package cc.colorcat.mvi.internal

import cc.colorcat.mvi.HybridStrategyConfig
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.TestLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class InternalExtensionsTest {

    @Rule @JvmField val testLog: TestRule = TestLogger()

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
        KMvi.configure { KMvi.Configuration(logger = Logger { _, _, _, _ -> }) }
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
        val warnings = mutableListOf<String>()
        KMvi.configure {
            copy(logger = Logger { priority, _, _, message ->
                if (priority == Logger.WARN) warnings.add(message())
            })
        }

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
        val warnings = mutableListOf<String>()
        KMvi.configure {
            copy(logger = Logger { priority, _, _, message ->
                if (priority == Logger.WARN) warnings.add(message())
            })
        }

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

        assertTrue(warnings.isEmpty())
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle Int_MAX_VALUE threshold disables warnings`() = runBlocking {
        val warnings = mutableListOf<String>()
        KMvi.configure {
            copy(logger = Logger { priority, _, _, message ->
                if (priority == Logger.WARN) warnings.add(message())
            })
        }

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

        assertTrue(warnings.isEmpty())
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle backlog warning rearms after backlog drains`() = runBlocking {
        val backlogWarnings = CopyOnWriteArrayList<String>()
        KMvi.configure {
            copy(logger = Logger { priority, _, _, message ->
                if (priority == Logger.WARN) {
                    val value = message()
                    if (value.contains("HYBRID group") && value.contains(">=80%")) {
                        backlogWarnings += value
                    }
                }
            })
        }
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
            while (backlogWarnings.size < 1) yield()
        }
        firstGate.complete(Unit)
        firstBurstDrained.await()
        startSecondBurst.complete(Unit)
        withTimeout(5_000) {
            while (backlogWarnings.size < 2) yield()
        }
        secondGate.complete(Unit)

        assertEquals((0 until 12).toList(), collection.await())
        assertEquals(2, backlogWarnings.size)
        assertTrue(backlogWarnings.all { it.contains("channel at 5/6 (>=80%)") })
        assertTrue(backlogWarnings.all { it.contains("tag(type=java.lang.String") })
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `groupHandle warns when bounded group becomes full`() = runBlocking {
        val warnings = CopyOnWriteArrayList<String>()
        KMvi.configure {
            copy(logger = Logger { priority, _, _, message ->
                if (priority == Logger.WARN) warnings += message()
            })
        }
        val gate = CompletableDeferred<Unit>()

        val collection = async {
            flow {
                repeat(3) { emit(TaggedIntent("hot", it)) }
            }.groupHandle(
                config = HybridStrategyConfig(
                    groupChannelCapacity = 1,
                    groupCountWarningThreshold = Int.MAX_VALUE,
                ),
                tagSelector = { it.tag },
            ) {
                map { intent ->
                    if (intent.id == 0) gate.await()
                    intent.id
                }
            }.flattenMerge(Int.MAX_VALUE).toList()
        }

        withTimeout(5_000) {
            while (warnings.none { it.contains("is FULL") }) yield()
        }
        gate.complete(Unit)

        assertEquals(listOf(0, 1, 2), collection.await())
        val saturationWarnings = warnings.filter { it.contains("is FULL") }
        assertEquals(1, saturationWarnings.size)
        assertTrue(saturationWarnings.single().contains("capacity=1"))
        assertTrue(saturationWarnings.single().contains("ALL groups are now blocked"))
    }
}
