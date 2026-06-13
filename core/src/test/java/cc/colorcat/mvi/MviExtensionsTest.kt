package cc.colorcat.mvi

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.util.concurrent.TimeUnit

class MviExtensionsTest {

    @Rule @JvmField val testLog: TestRule = TestLogger()

    @Test
    fun `asSingleFlow emits the value`() = runBlocking {
        val result = "hello".asSingleFlow().single()
        assertEquals("hello", result)
    }

    @Test
    fun `asSingleFlow for integer`() = runBlocking {
        val result = 42.asSingleFlow().first()
        assertEquals(42, result)
    }

    @Test
    fun `asSingleFlow emits exactly one item`() = runBlocking {
        val results = "single".asSingleFlow().toList()
        assertEquals(1, results.size)
    }

    @Test
    fun `asSingleFlow for Mvi Intent`() = runBlocking {
        val intent = object : Mvi.Intent {}
        val result = intent.asSingleFlow().single()
        assertTrue(result === intent)
    }

    @Test
    fun `asSingleFlow for null value`() = runBlocking {
        val result = null.asSingleFlow().single()
        assertEquals(null, result)
    }

    @Test
    fun `asSingleFlow for data class`() = runBlocking {
        data class TestData(val x: Int, val y: String)
        val data = TestData(1, "test")
        val result = data.asSingleFlow().single()
        assertEquals(TestData(1, "test"), result)
    }

    @Test
    fun `asSingleFlow for list`() = runBlocking {
        val list = listOf(1, 2, 3)
        val result = list.asSingleFlow().single()
        assertEquals(listOf(1, 2, 3), result)
    }

    @Test
    fun `asSingleFlow flow completes after emission`() = runBlocking {
        val results = mutableListOf<Int>()
        99.asSingleFlow().collect { results.add(it) }
        assertEquals(listOf(99), results)
    }
}

class DebounceLeadingTest {

    @Rule @JvmField val testLog: TestRule = TestLogger()

    // Threshold small enough to complete quickly; synchronous emissions always fall well below it.
    private val windowMs = 50L

    @Test
    fun `debounceLeading emits first value immediately`() = runBlocking {
        val results = flow { emit(1) }.debounceLeading(windowMs).toList()
        assertEquals(listOf(1), results)
    }

    @Test
    fun `debounceLeading suppresses rapid successive events`() = runBlocking {
        // Synchronous emissions have sub-millisecond gaps — all below windowMs.
        val results = flow {
            emit(1)
            emit(2)
            emit(3)
        }.debounceLeading(windowMs).toList()
        assertEquals(listOf(1), results)
    }

    @Test
    fun `debounceLeading emits after sufficient gap`() = runBlocking {
        val results = mutableListOf<Int>()
        flow {
            emit(1)
            delay(windowMs * 2)   // 2× window → guaranteed to pass
            emit(2)
        }.debounceLeading(windowMs).collect { results.add(it) }
        assertEquals(listOf(1, 2), results)
    }

    @Test
    fun `debounceLeading sliding window suppresses mid-burst event after gap`() = runBlocking {
        // Even after a near-miss gap, another rapid event resets the window.
        val results = mutableListOf<Int>()
        flow {
            emit(1)            // emitted
            delay(windowMs * 2)
            emit(2)            // emitted — gap was large
            emit(3)            // suppressed — immediately follows 2
        }.debounceLeading(windowMs).collect { results.add(it) }
        assertEquals(listOf(1, 2), results)
    }

    @Test
    fun `debounceLeading uses monotonic nanosecond clock`() = runBlocking {
        val timestamps = listOf(
            0L,
            TimeUnit.MILLISECONDS.toNanos(10L),
            TimeUnit.MILLISECONDS.toNanos(70L),
        ).iterator()

        val results = flow {
            emit(1)
            emit(2)
            emit(3)
        }.debounceLeading(windowMs) { timestamps.next() }.toList()

        assertEquals(listOf(1, 3), results)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `debounceLeading with zero millis throws`() = runBlocking<Unit> {
        flow { emit(1) }.debounceLeading(0L).collect {}
    }

    @Test(expected = IllegalArgumentException::class)
    fun `debounceLeading with negative millis throws`() = runBlocking<Unit> {
        flow { emit(1) }.debounceLeading(-1L).collect {}
    }
}
