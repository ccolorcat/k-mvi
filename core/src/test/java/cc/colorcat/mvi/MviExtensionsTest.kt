package cc.colorcat.mvi

import kotlinx.coroutines.delay
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
    private data class TestState(val value: Int = 0) : Mvi.State
    private sealed interface TestEvent : Mvi.Event {
        data object Done : TestEvent
    }

    private sealed interface TestIntent : Mvi.Intent

    private fun interface TestChange : Mvi.PartialChange<TestState, TestEvent>

    private object Clear : TestIntent, TestChange {
        override fun apply(old: Mvi.Snapshot<TestState, TestEvent>): Mvi.Snapshot<TestState, TestEvent> {
            return old.updateState { copy(value = 0) }
        }
    }

    @Rule @JvmField val testLog: TestRule = TestLogger()

    @Test
    fun `asSingleFlow emits the partial change`() = runBlocking {
        val change = Mvi.PartialChange<TestState, TestEvent> { snapshot ->
            snapshot.updateState { copy(value = value + 1) }
        }

        val result = change.asSingleFlow().single()

        assertTrue(result === change)
    }

    @Test
    fun `asSingleFlow emits exactly one partial change`() = runBlocking {
        val change = TestChange { snapshot ->
            snapshot.withEvent(TestEvent.Done)
        }

        val results = change.asSingleFlow().toList()

        assertEquals(1, results.size)
        assertTrue(results.single() === change)
    }

    @Test
    fun `asSingleFlow supports intent that is also partial change`() = runBlocking {
        val result = Clear.asSingleFlow().single()

        assertTrue(result === Clear)
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
