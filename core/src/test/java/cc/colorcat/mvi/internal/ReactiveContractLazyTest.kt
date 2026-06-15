package cc.colorcat.mvi.internal

import cc.colorcat.mvi.DispatchResult
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.ReactiveContract
import cc.colorcat.mvi.ReactiveContractLazy
import cc.colorcat.mvi.TestLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

class ReactiveContractLazyTest {

    @Rule @JvmField val testLog: TestRule = TestLogger()

    private data class TestState(val count: Int = 0) : Mvi.State
    private sealed interface TestEvent : Mvi.Event

    private fun createMockContract(value: Int = 0): ReactiveContract<Mvi.Intent, TestState, TestEvent> {
        return object : ReactiveContract<Mvi.Intent, TestState, TestEvent> {
            override val stateFlow: StateFlow<TestState> = MutableStateFlow(TestState(value))
            override val eventFlow: Flow<TestEvent> = emptyFlow()
            override fun dispatch(intent: Mvi.Intent): DispatchResult = DispatchResult.Submitted
        }
    }

    @Test
    fun `value creates and caches the contract`() {
        var createCount = 0
        val lazy = ReactiveContractLazy<Mvi.Intent, TestState, TestEvent> {
            createCount++
            createMockContract(42)
        }

        assertFalse(lazy.isInitialized())

        val v1 = lazy.value
        assertEquals(42, v1.stateFlow.value.count)
        assertEquals(1, createCount)

        val v2 = lazy.value
        assertSame("should return cached instance", v1, v2)
        assertEquals(1, createCount)
    }

    @Test
    fun `isInitialized returns false before first access`() {
        val lazy = ReactiveContractLazy<Mvi.Intent, TestState, TestEvent> {
            createMockContract(0)
        }
        assertFalse(lazy.isInitialized())
    }

    @Test
    fun `isInitialized returns true after first access`() {
        val lazy = ReactiveContractLazy<Mvi.Intent, TestState, TestEvent> {
            createMockContract(0)
        }
        lazy.value
        assertTrue(lazy.isInitialized())
    }

    @Test
    fun `lazy is not null`() {
        val lazy = ReactiveContractLazy<Mvi.Intent, TestState, TestEvent> {
            createMockContract(99)
        }
        assertNotNull(lazy.value)
    }

    @Test
    fun `multiple calls to value returns same instance`() {
        val lazy = ReactiveContractLazy<Mvi.Intent, TestState, TestEvent> {
            createMockContract(7)
        }

        val first = lazy.value
        repeat(5) {
            assertSame(first, lazy.value)
        }
    }
}
