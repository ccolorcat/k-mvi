package cc.colorcat.mvi.internal

import cc.colorcat.mvi.HandleStrategy
import cc.colorcat.mvi.HybridConfig
import cc.colorcat.mvi.IntentHandler
import cc.colorcat.mvi.IntentTransformer
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.TestLogger
import cc.colorcat.mvi.asSingleFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveContractImplTest {

    @Rule @JvmField val testLog: TestRule = TestLogger()

    private data class TestState(
        val count: Int = 0,
        val data: String? = null,
    ) : Mvi.State

    private sealed interface TestEvent : Mvi.Event {
        data object Updated : TestEvent
        data class Message(val text: String) : TestEvent
    }

    private sealed interface TestIntent : Mvi.Intent {
        data object Increment : TestIntent
        data object Decrement : TestIntent
        data class SetData(val value: String) : TestIntent
    }

    @Before
    fun setUp() {
        KMvi.setup {
            copy(
                intentQueueCapacity = 64,
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @After
    fun tearDown() {
        KMvi.setup { this }
    }

    private lateinit var testScope: CoroutineScope
    private lateinit var testExecutor: ExecutorService

    @Before
    fun setUpScope() {
        testExecutor = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "mvi-test").also { it.isDaemon = true }
        }
        testScope = CoroutineScope(testExecutor.asCoroutineDispatcher() + SupervisorJob())
    }

    @After
    fun tearDownScope() {
        testScope.cancel()
        testExecutor.shutdown()
    }

    // --- Basic dispatch → stateFlow ---

    @Test
    fun `dispatch updates stateFlow`() = runBlocking {
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueCapacity = 64,
            retryPolicy = { _, _ -> false },
            transformer = IntentTransformer(
                strategy = HandleStrategy.CONCURRENT,
                config = HybridConfig(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> {
                    Mvi.PartialChange<TestState, TestEvent> { it.updateState {
                        if (this is TestState) copy(count = count + 1)
                        else this
                    } }.asSingleFlow()
                },
            ),
        )

        assertEquals(0, contract.stateFlow.value.count)

        contract.dispatch(TestIntent.Increment)
        val state = contract.stateFlow.first { it.count == 1 }
        assertEquals(1, state.count)
    }

    @Test
    fun `dispatch multiple intents accumulate state`() = runBlocking {
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueCapacity = 64,
            retryPolicy = { _, _ -> false },
            transformer = IntentTransformer(
                strategy = HandleStrategy.SEQUENTIAL,
                config = HybridConfig(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> {
                    Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = count + 1) } }
                        .asSingleFlow()
                },
            ),
        )

        contract.dispatch(TestIntent.Increment)
        contract.dispatch(TestIntent.Increment)
        contract.dispatch(TestIntent.Increment)

        val state = contract.stateFlow.first { it.count == 3 }
        assertEquals(3, state.count)
    }

    @Test
    fun `PartialChange apply exception keeps pipeline alive`() = runBlocking {
        var callCount = 0
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueCapacity = 64,
            retryPolicy = { _, _ -> false },
            transformer = IntentTransformer(
                strategy = HandleStrategy.SEQUENTIAL,
                config = HybridConfig(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> {
                    callCount++
                    if (callCount == 1) {
                        Mvi.PartialChange<TestState, TestEvent> {
                            throw RuntimeException("intentional apply error")
                        }.asSingleFlow()
                    } else {
                        Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = count + 1) } }
                            .asSingleFlow()
                    }
                },
            ),
        )

        contract.dispatch(TestIntent.Increment)  // apply throws → state unchanged
        contract.dispatch(TestIntent.Increment)  // apply succeeds → count = 1
        val state = contract.stateFlow.first { it.count == 1 }
        assertEquals(1, state.count)  // first dispatch had no effect; second did; pipeline alive
    }


    @Test
    fun `initial state is correct`() = runBlocking {
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(count = 99, data = "init"),
            intentQueueCapacity = 64,
            retryPolicy = { _, _ -> false },
            transformer = IntentTransformer(
                strategy = HandleStrategy.CONCURRENT,
                config = HybridConfig(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> { emptyFlow() },
            ),
        )

        assertEquals(99, contract.stateFlow.value.count)
        assertEquals("init", contract.stateFlow.value.data)
    }

    @Test
    fun `invalid intentQueueCapacity throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreReactiveContract(
                scope = testScope,
                initState = TestState(),
                intentQueueCapacity = -3,
                retryPolicy = { _, _ -> false },
                transformer = IntentTransformer(
                    strategy = HandleStrategy.CONCURRENT,
                    config = HybridConfig(),
                    handler = IntentHandler<TestIntent, TestState, TestEvent> { emptyFlow() },
                ),
            )
        }
    }

    // --- eventFlow ---
    //
    // IMPORTANT: eventFlow uses WhileSubscribed(5000), so events emitted
    // before any subscriber connects are LOST.  Always subscribe BEFORE
    // dispatching intents that produce events.

    @Test
    fun `dispatch produces events`() = runTest(UnconfinedTestDispatcher()) {
        // Use a local scope backed by UnconfinedTestDispatcher so that every coroutine
        // launched by shareIn (WhileSubscribed) runs eagerly — the upstream subscription
        // to `snapshots` is fully established before dispatch() is called.
        val contractScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
        val contract = CoreReactiveContract(
            scope = contractScope,
            initState = TestState(),
            intentQueueCapacity = 64,
            retryPolicy = { _, _ -> false },
            transformer = IntentTransformer(
                strategy = HandleStrategy.CONCURRENT,
                config = HybridConfig(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> {
                    Mvi.PartialChange<TestState, TestEvent> { it.withEvent(TestEvent.Updated) }.asSingleFlow()
                },
            ),
        )

        try {
            // async runs eagerly on UnconfinedTestDispatcher: by the time this line returns,
            // eventFlow.collect has been entered and WhileSubscribed's upstream coroutine
            // (also on UnconfinedTestDispatcher) has subscribed to snapshots.  No yield() needed.
            val eventDeferred = async { contract.eventFlow.first { it is TestEvent.Updated } }

            contract.dispatch(TestIntent.Increment)
            assertTrue(eventDeferred.await() is TestEvent.Updated)
        } finally {
            contractScope.cancel()
        }
    }

    @Test
    fun `eventFlow emits different event types`() = runTest(UnconfinedTestDispatcher()) {
        val contractScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
        val contract = CoreReactiveContract(
            scope = contractScope,
            initState = TestState(),
            intentQueueCapacity = 64,
            retryPolicy = { _, _ -> false },
            transformer = IntentTransformer(
                strategy = HandleStrategy.CONCURRENT,
                config = HybridConfig(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> {
                    Mvi.PartialChange<TestState, TestEvent> { it.withEvent(TestEvent.Message("hello")) }
                        .asSingleFlow()
                },
            ),
        )

        try {
            val eventDeferred = async { contract.eventFlow.first { it is TestEvent.Message } }

            contract.dispatch(TestIntent.Increment)
            val event = eventDeferred.await()
            assertEquals("hello", (event as TestEvent.Message).text)
        } finally {
            contractScope.cancel()
        }
    }

    @Test
    fun `eventFlow drops oldest events when snapshot buffer is congested`() = runBlocking {
        val totalEvents = 500
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueCapacity = 64,
            retryPolicy = { _, _ -> false },
            transformer = IntentTransformer(
                strategy = HandleStrategy.CONCURRENT,
                config = HybridConfig(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> {
                    flow {
                        repeat(totalEvents) { index ->
                            emit(
                                Mvi.PartialChange<TestState, TestEvent> {
                                    it.updateWith(TestEvent.Message("$index")) {
                                        copy(count = count + 1)
                                    }
                                },
                            )
                        }
                    }
                },
            ),
        )
        val received = mutableListOf<Int>()

        val collector = launch {
            contract.eventFlow.collect { event ->
                received += (event as TestEvent.Message).text.toInt()
                delay(2)
            }
        }

        try {
            delay(50)
            contract.dispatch(TestIntent.Increment)
            withTimeout(5_000) {
                contract.stateFlow.first { it.count == totalEvents }
            }
            delay(totalEvents * 2L + 500L)

            assertTrue("collector should receive at least one event", received.isNotEmpty())
            assertTrue(
                "slow event collector should miss some events when snapshot buffer drops oldest; " +
                    "received ${received.size} of $totalEvents",
                received.size < totalEvents,
            )
        } finally {
            collector.cancel()
        }
    }

    // --- StrategyReactiveContract ---

    @Test
    fun `StrategyReactiveContract dispatches correctly`() = runBlocking {
        val contract = StrategyReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueCapacity = 64,
            retryPolicy = { _, _ -> false },
            strategy = HandleStrategy.CONCURRENT,
            config = HybridConfig(),
            defaultHandler = IntentHandler<TestIntent, TestState, TestEvent> {
                Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = count + 1) } }
                    .asSingleFlow()
            },
        )

        contract.dispatch(TestIntent.Increment)
        val state = contract.stateFlow.first { it.count == 1 }
        assertEquals(1, state.count)
    }

    @Test
    fun `StrategyReactiveContract with registered handler`() = runBlocking {
        val contract = StrategyReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueCapacity = 64,
            retryPolicy = { _, _ -> false },
            strategy = HandleStrategy.CONCURRENT,
            config = HybridConfig(),
            defaultHandler = IntentHandler<TestIntent, TestState, TestEvent> { emptyFlow() },
        )

        contract.setupIntentHandlers {
            register(TestIntent.Increment::class.java, IntentHandler<TestIntent.Increment, TestState, TestEvent> {
                Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(data = "handled") } }
                    .asSingleFlow()
            })
        }

        contract.dispatch(TestIntent.Increment)
        val state = contract.stateFlow.first { it.data == "handled" }
        assertEquals("handled", state.data)
    }

    @Test
    fun `unregistered intent falls back to default handler`() = runBlocking {
        var defaultHit = false
        val contract = StrategyReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueCapacity = 64,
            retryPolicy = { _, _ -> false },
            strategy = HandleStrategy.CONCURRENT,
            config = HybridConfig(),
            defaultHandler = IntentHandler<TestIntent, TestState, TestEvent> {
                defaultHit = true
                Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(data = "default") } }
                    .asSingleFlow()
            },
        )

        contract.setupIntentHandlers {
            register(TestIntent.Increment::class.java, IntentHandler { emptyFlow() })
        }

        contract.dispatch(TestIntent.Decrement)
        val state = contract.stateFlow.first { it.data == "default" }
        assertTrue(defaultHit)
        assertEquals("default", state.data)
    }

    // --- Scope cancel ---

    @Test
    fun `dispatch after scope cancel logs warning`() = runBlocking {
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueCapacity = 64,
            retryPolicy = { _, _ -> false },
            transformer = IntentTransformer(
                strategy = HandleStrategy.CONCURRENT,
                config = HybridConfig(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> { emptyFlow() },
            ),
        )

        testScope.cancel()
        contract.dispatch(TestIntent.Increment)
        assertEquals(0, contract.stateFlow.value.count)
    }

    @Test
    fun `dispatch logs warning when dispatch queue is full`() = runBlocking {
        val messages = Collections.synchronizedList(mutableListOf<String>())
        KMvi.setup {
            copy(
                intentQueueCapacity = 0,
                logger = Logger { _, _, _, message -> messages += message() },
            )
        }
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueCapacity = 0,
            retryPolicy = { _, _ -> false },
            transformer = IntentTransformer(
                strategy = HandleStrategy.SEQUENTIAL,
                config = HybridConfig(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> {
                    flow { awaitCancellation() }
                },
            ),
        )

        repeat(200) {
            contract.dispatch(TestIntent.Increment)
        }

        assertTrue(
            "expected at least one dispatchQueue full warning, got $messages",
            messages.any { it.startsWith("Intent queue full") },
        )
    }

    @Test
    fun `scope cancel while dispatch queue has buffered intents does not process pending intents`() = runBlocking {
        val started = Collections.synchronizedList(mutableListOf<TestIntent>())
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueCapacity = kotlinx.coroutines.channels.Channel.UNLIMITED,
            retryPolicy = { _, _ -> false },
            transformer = IntentTransformer(
                strategy = HandleStrategy.SEQUENTIAL,
                config = HybridConfig(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> { intent ->
                    flow {
                        started += intent
                        awaitCancellation()
                    }
                },
            ),
        )

        repeat(200) {
            contract.dispatch(TestIntent.SetData("$it"))
        }
        withTimeout(5_000) {
            while (started.isEmpty()) {
                delay(1)
            }
        }

        testScope.cancel()
        contract.dispatch(TestIntent.Increment)

        assertEquals("only the blocking first intent should start before cancellation", 1, started.size)
        assertEquals(0, contract.stateFlow.value.count)
    }
}
