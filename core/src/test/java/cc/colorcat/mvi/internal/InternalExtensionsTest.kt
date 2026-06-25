package cc.colorcat.mvi.internal

import cc.colorcat.mvi.HybridStrategyConfig
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.TestLogger
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

class InternalExtensionsTest {

    @Rule @JvmField val testLog: TestRule = TestLogger()

    // --- Test intent types ---

    private interface TestIntent : Mvi.Intent {
        data object OnlyConcurrent : TestIntent, Mvi.Intent.Concurrent
        data class OnlySequential(val id: Int) : TestIntent, Mvi.Intent.Sequential
    }

    // Anonymous implementation for edge cases
    private val anonymousIntent = object : Mvi.Intent {}

    private data class TaggedIntent(val tag: String) : Mvi.Intent

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
}
