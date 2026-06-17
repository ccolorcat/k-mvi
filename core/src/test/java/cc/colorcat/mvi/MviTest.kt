package cc.colorcat.mvi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

class MviTest {

    @Rule @JvmField val testLog: TestRule = TestLogger()

    private data class TestState(
        val count: Int = 0,
        val loading: Boolean = false,
        val error: String? = null,
    ) : Mvi.State

    private sealed interface TestEvent : Mvi.Event {
        data object Toast : TestEvent
        data class Navigation(val route: String) : TestEvent
    }

    // --- Snapshot creation ---

    @Test
    fun `Snapshot constructor with state and event`() {
        val snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(5), TestEvent.Toast)
        assertEquals(TestState(5), snapshot.state)
        assertEquals(TestEvent.Toast, snapshot.event)
    }

    @Test
    fun `Snapshot constructor`() {
        val snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(1))
        assertEquals(TestState(1), snapshot.state)
        assertNull(snapshot.event)
    }

    // --- updateState ---

    @Test
    fun `updateState updates state`() {
        val snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(0))
        val result = snapshot.updateState { copy(count = 10) }
        assertEquals(TestState(10), result.state)
    }

    @Test
    fun `updateState clears pending event`() {
        val snapshot = Mvi.Snapshot(TestState(0), TestEvent.Toast)
        val result = snapshot.updateState { copy(count = 5) }
        assertEquals(5, result.state.count)
        assertNull("event must be cleared after updateState", result.event)
    }

    @Test
    fun `updateState returns new instance`() {
        val snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(0))
        val result = snapshot.updateState { copy(count = 1) }
        assertNotSame(snapshot, result)
    }

    @Test
    fun `updateState is pure - original unchanged`() {
        val snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(0))
        snapshot.updateState { copy(count = 99) }
        assertEquals(0, snapshot.state.count)
    }

    @Test
    fun `updateState uses receiver context`() {
        val snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(5))
        val result = snapshot.updateState { copy(count = count * 2) }
        assertEquals(10, result.state.count)
    }

    // --- withEvent ---

    @Test
    fun `withEvent attaches event and preserves state`() {
        val snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(0))
        val result = snapshot.withEvent(TestEvent.Toast)
        assertEquals(TestState(0), result.state)
        assertEquals(TestEvent.Toast, result.event)
    }

    @Test
    fun `withEvent preserves same state reference`() {
        val snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(42))
        val result = snapshot.withEvent(TestEvent.Navigation("home"))
        assertTrue(result.state === snapshot.state)
    }

    @Test
    fun `withEvent overwrites existing event`() {
        val snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(1), TestEvent.Toast)
        val result = snapshot.withEvent(TestEvent.Navigation("detail"))
        assertEquals(TestEvent.Navigation("detail"), result.event)
    }

    // --- updateWith ---

    @Test
    fun `updateWith updates state and attaches event`() {
        val snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(0))
        val result = snapshot.updateWith(TestEvent.Navigation("home")) {
            copy(count = 10, loading = true)
        }
        assertEquals(10, result.state.count)
        assertEquals(true, result.state.loading)
        assertEquals(TestEvent.Navigation("home"), result.event)
    }

    @Test
    fun `updateWith overwrites previous event`() {
        val snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(0), TestEvent.Toast)
        val result = snapshot.updateWith(TestEvent.Navigation("next")) { copy(count = 3) }
        assertEquals(TestEvent.Navigation("next"), result.event)
    }

    // --- PartialChange ---

    @Test
    fun `PartialChange apply updates state`() {
        val change = Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = 42) } }
        val result = change.apply(Mvi.Snapshot(TestState(0)))
        assertEquals(42, result.state.count)
    }

    @Test
    fun `PartialChange apply emits event`() {
        val change = Mvi.PartialChange<TestState, TestEvent> { it.withEvent(TestEvent.Toast) }
        val result = change.apply(Mvi.Snapshot(TestState(0)))
        assertEquals(TestEvent.Toast, result.event)
    }

    @Test
    fun `PartialChange apply combined state and event`() {
        val change = Mvi.PartialChange<TestState, TestEvent> {
            it.updateWith(TestEvent.Navigation("detail")) { copy(count = 7, loading = true) }
        }
        val result = change.apply(Mvi.Snapshot(TestState(0)))
        assertEquals(7, result.state.count)
        assertEquals(true, result.state.loading)
        assertEquals(TestEvent.Navigation("detail"), result.event)
    }

    @Test
    fun `multiple PartialChanges compose correctly`() {
        val step1 = Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = 1) } }
        val step2 = Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = count + 1) } }
        val step3 = Mvi.PartialChange<TestState, TestEvent> { it.withEvent(TestEvent.Toast) }

        var snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(0))
        snapshot = step1.apply(snapshot)
        assertEquals(1, snapshot.state.count)
        assertNull(snapshot.event)

        snapshot = step2.apply(snapshot)
        assertEquals(2, snapshot.state.count)

        snapshot = step3.apply(snapshot)
        assertEquals(2, snapshot.state.count)
        assertEquals(TestEvent.Toast, snapshot.event)
    }

    @Test
    fun `PartialChange can clear error state`() {
        val change = Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = 0, error = null) } }
        val snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(5, error = "error"))
        val result = change.apply(snapshot)
        assertEquals(TestState(0), result.state)
    }

    // --- asContract ---

    @Test
    fun `asContract wraps ReactiveContract`() {
        val reactive = object : ReactiveContract<Mvi.Intent, TestState, TestEvent> {
            override val stateFlow: StateFlow<TestState> = MutableStateFlow(TestState(1))
            override val eventFlow: Flow<TestEvent> = emptyFlow()
            override fun dispatch(intent: Mvi.Intent): DispatchResult = DispatchResult.Submitted
        }

        val readOnly = reactive.asContract()
        assertEquals(TestState(1), readOnly.stateFlow.value)

        // Verify dispatch is not accessible on readOnly
        assertFalse("read only contract should not be reactive", readOnly is ReactiveContract<*, *, *>)
    }

    @Test
    fun `asContract preserves state updates`() {
        val state = MutableStateFlow(TestState(0))
        val reactive = object : ReactiveContract<Mvi.Intent, TestState, TestEvent> {
            override val stateFlow: StateFlow<TestState> = state
            override val eventFlow: Flow<TestEvent> = emptyFlow()
            override fun dispatch(intent: Mvi.Intent): DispatchResult {
                state.value = TestState(2)
                return DispatchResult.Submitted
            }
        }

        val readOnly = reactive.asContract()
        reactive.dispatch(object : Mvi.Intent {})
        assertEquals(TestState(2), readOnly.stateFlow.value)
    }

    // --- Edge cases ---

    @Test
    fun `updateState with no-op transform`() {
        val snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(42))
        val result = snapshot.updateState { this }
        assertEquals(42, result.state.count)
        assertNull(result.event)
    }

    @Test
    fun `snapshot with null event`() {
        val snapshot: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(0))
        assertNull(snapshot.event)
    }

    @Test
    fun `repeated updateState`() {
        val s1: Mvi.Snapshot<TestState, TestEvent> = Mvi.Snapshot(TestState(0))
        val s2 = s1.updateState { copy(count = 1, loading = true) }
        val s3 = s2.updateState { copy(loading = false) }
        val s4 = s3.withEvent(TestEvent.Toast)

        assertNull("updateState clears event", s2.event)
        assertEquals(1, s2.state.count)
        assertEquals(true, s2.state.loading)

        assertEquals(1, s3.state.count)
        assertEquals(false, s3.state.loading)
        assertNull("subsequent updateState also clears event", s3.event)

        assertEquals(TestEvent.Toast, s4.event)
    }
}
