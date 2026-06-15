package cc.colorcat.mvi.internal

import cc.colorcat.mvi.IntentHandler
import cc.colorcat.mvi.IntentHandlerDelegate
import cc.colorcat.mvi.IntentQueueConfig
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.TestLogger
import cc.colorcat.mvi.asSingleFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

class IntentHandlersTest {

    @Rule @JvmField val testLog: TestRule = TestLogger()

    @Before
    fun setUp() {
        KMvi.setup {
            copy(
                intentQueueConfig = IntentQueueConfig(capacity = 64),
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    private data class TestState(val value: String = "") : Mvi.State
    private sealed interface TestEvent : Mvi.Event
    private sealed interface TestIntent : Mvi.Intent {
        data object Increment : TestIntent
        data object Decrement : TestIntent
    }

    @Test
    fun `delegates to registered handler`() = runBlocking {
        val defaultHandler = IntentHandler<TestIntent, TestState, TestEvent> { emptyFlow() }
        val delegate = IntentHandlerDelegate(defaultHandler)

        delegate.register(TestIntent.Increment::class.java, IntentHandler { intent ->
            Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(value = "increment-handler") } }
                .asSingleFlow()
        })

        val result = delegate.handle(TestIntent.Increment)
        val changes = result.toList()
        assertEquals("increment-handler", changes[0].apply(Mvi.Snapshot(TestState())).state.value)
    }

    @Test
    fun `falls back to defaultHandler for unregistered type`() = runBlocking {
        var defaultCalled = false
        val defaultHandler = IntentHandler<TestIntent, TestState, TestEvent> {
            defaultCalled = true
            Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(value = "default") } }
                .asSingleFlow()
        }

        val delegate = IntentHandlerDelegate(defaultHandler)
        delegate.register(TestIntent.Increment::class.java, IntentHandler { emptyFlow() })

        val result = delegate.handle(TestIntent.Decrement)
        val changes = result.toList()
        assertTrue(defaultCalled)
        assertEquals("default", changes[0].apply(Mvi.Snapshot(TestState())).state.value)
    }

    @Test
    fun `handle with no registered handlers uses default`() = runBlocking {
        val defaultHandler = IntentHandler<TestIntent, TestState, TestEvent> {
            Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(value = "always-default") } }
                .asSingleFlow()
        }

        val delegate = IntentHandlerDelegate(defaultHandler)

        val result = delegate.handle(TestIntent.Increment)
        val changes = result.toList()
        assertEquals("always-default", changes[0].apply(Mvi.Snapshot(TestState())).state.value)
    }

    @Test
    fun `null defaultHandler produces empty flow and logs WARN for unregistered intent`() = runBlocking {
        val warns = mutableListOf<String>()
        KMvi.setup {
            copy(logger = Logger { priority, _, _, message ->
                if (priority == Logger.WARN) warns.add(message())
            })
        }

        val delegate = IntentHandlerDelegate<TestIntent, TestState, TestEvent>(defaultHandler = null)

        val changes = delegate.handle(TestIntent.Increment).toList()
        assertTrue("emptyFlow expected when no handler and no default", changes.isEmpty())
        assertEquals(1, warns.size)
        assertTrue("warn must mention diagnostic name", warns.single().contains("Increment"))
        assertTrue("warn must mention missing default handler", warns.single().contains("default handler"))
    }

    @Test
    fun `null defaultHandler is silent for intents with registered handler`() = runBlocking {
        val warns = mutableListOf<String>()
        KMvi.setup {
            copy(logger = Logger { priority, _, _, message ->
                if (priority == Logger.WARN) warns.add(message())
            })
        }

        val delegate = IntentHandlerDelegate<TestIntent, TestState, TestEvent>(defaultHandler = null)
        delegate.register(TestIntent.Increment::class.java, IntentHandler {
            Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(value = "handled") } }
                .asSingleFlow()
        })

        val changes = delegate.handle(TestIntent.Increment).toList()
        assertEquals("handled", changes.single().apply(Mvi.Snapshot(TestState())).state.value)
        assertTrue("no WARN expected for handled intent", warns.isEmpty())
    }

    @Test
    fun `register replaces existing handler`() = runBlocking {
        val defaultHandler = IntentHandler<TestIntent, TestState, TestEvent> { emptyFlow() }
        val delegate = IntentHandlerDelegate(defaultHandler)

        delegate.register(TestIntent.Increment::class.java, IntentHandler {
            Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(value = "first") } }
                .asSingleFlow()
        })
        delegate.register(TestIntent.Increment::class.java, IntentHandler {
            Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(value = "second") } }
                .asSingleFlow()
        })

        val result = delegate.handle(TestIntent.Increment)
        val changes = result.toList()
        assertEquals("second", changes[0].apply(Mvi.Snapshot(TestState())).state.value)
    }

    @Test
    fun `handles multiple intents in sequence`() = runBlocking {
        val defaultHandler = IntentHandler<TestIntent, TestState, TestEvent> { emptyFlow() }
        val delegate = IntentHandlerDelegate(defaultHandler)

        delegate.register(TestIntent.Increment::class.java, IntentHandler {
            Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(value = "inc") } }
                .asSingleFlow()
        })
        delegate.register(TestIntent.Decrement::class.java, IntentHandler {
            Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(value = "dec") } }
                .asSingleFlow()
        })

        val r1 = delegate.handle(TestIntent.Increment).toList()
        val r2 = delegate.handle(TestIntent.Decrement).toList()

        assertEquals("inc", r1[0].apply(Mvi.Snapshot(TestState())).state.value)
        assertEquals("dec", r2[0].apply(Mvi.Snapshot(TestState())).state.value)
    }
}
