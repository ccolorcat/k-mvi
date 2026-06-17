package cc.colorcat.mvi.internal

import cc.colorcat.mvi.DispatchResult
import cc.colorcat.mvi.GroupTagSelector
import cc.colorcat.mvi.HandleStrategy
import cc.colorcat.mvi.HybridStrategyConfig
import cc.colorcat.mvi.IntentHandler
import cc.colorcat.mvi.IntentQueueConfig
import cc.colorcat.mvi.strategyTransformer
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.TestLogger
import cc.colorcat.mvi.asSingleFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveContractImplTest {

    @Rule
    @JvmField
    val testLog: TestRule = TestLogger()

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
        KMvi.configure {
            copy(
                intentQueueConfig = IntentQueueConfig(capacity = 64),
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @After
    fun tearDown() {
        KMvi.configure { this }
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
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            retryPolicy = { _, _ -> false },
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.CONCURRENT,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> {
                    Mvi.PartialChange<TestState, TestEvent> {
                        it.updateState {
                            if (this is TestState) copy(count = count + 1)
                            else this
                        }
                    }.asSingleFlow()
                },
            ),
        )

        assertEquals(0, contract.stateFlow.value.count)

        assertEquals(DispatchResult.Submitted, contract.dispatch(TestIntent.Increment))
        val state = contract.stateFlow.first { it.count == 1 }
        assertEquals(1, state.count)
    }

    @Test
    fun `dispatch multiple intents accumulate state`() = runBlocking {
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            retryPolicy = { _, _ -> false },
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.SEQUENTIAL,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
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
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            retryPolicy = { _, _ -> false },
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.SEQUENTIAL,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
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
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            retryPolicy = { _, _ -> false },
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.CONCURRENT,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> { emptyFlow() },
            ),
        )

        assertEquals(99, contract.stateFlow.value.count)
        assertEquals("init", contract.stateFlow.value.data)
    }

    @Test
    fun `invalid intentQueueConfig capacity throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreReactiveContract(
                scope = testScope,
                initState = TestState(),
                intentQueueConfig = IntentQueueConfig(capacity = -3),
                retryPolicy = { _, _ -> false },
                transformer = strategyTransformer(
                    handleStrategy = HandleStrategy.CONCURRENT,
                    hybridStrategyConfig = HybridStrategyConfig(),
                    groupTagSelector = GroupTagSelector.byClass(),
                    handler = IntentHandler<TestIntent, TestState, TestEvent> { emptyFlow() },
                ),
            )
        }
    }

    @Test
    fun `scope without Job throws`() {
        val scopeWithoutJob = object : CoroutineScope {
            override val coroutineContext = EmptyCoroutineContext
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            CoreReactiveContract(
                scope = scopeWithoutJob,
                initState = TestState(),
                intentQueueConfig = IntentQueueConfig(capacity = 64),
                retryPolicy = { _, _ -> false },
                transformer = strategyTransformer(
                    handleStrategy = HandleStrategy.CONCURRENT,
                    hybridStrategyConfig = HybridStrategyConfig(),
                    groupTagSelector = GroupTagSelector.byClass(),
                    handler = IntentHandler<TestIntent, TestState, TestEvent> { emptyFlow() },
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("scope must contain a Job"))
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
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            retryPolicy = { _, _ -> false },
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.CONCURRENT,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
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
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            retryPolicy = { _, _ -> false },
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.CONCURRENT,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
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
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            retryPolicy = { _, _ -> false },
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.CONCURRENT,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
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
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            retryPolicy = { _, _ -> false },
            handleStrategy = HandleStrategy.CONCURRENT,
            hybridStrategyConfig = HybridStrategyConfig(),
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
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            retryPolicy = { _, _ -> false },
            handleStrategy = HandleStrategy.CONCURRENT,
            hybridStrategyConfig = HybridStrategyConfig(),
            defaultHandler = IntentHandler<TestIntent, TestState, TestEvent> { emptyFlow() },
        )

        contract.setupIntentHandlers {
            register(
                TestIntent.Increment::class.java,
                IntentHandler<TestIntent.Increment, TestState, TestEvent> {
                    Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(data = "handled") } }
                        .asSingleFlow()
                },
            )
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
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            retryPolicy = { _, _ -> false },
            handleStrategy = HandleStrategy.CONCURRENT,
            hybridStrategyConfig = HybridStrategyConfig(),
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
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            retryPolicy = { _, _ -> false },
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.CONCURRENT,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> { emptyFlow() },
            ),
        )

        testScope.cancel()
        assertEquals(DispatchResult.Unavailable, contract.dispatch(TestIntent.Increment))
        assertEquals(0, contract.stateFlow.value.count)
    }

    @Test
    fun `dispatch logs warning when intent queue is full`() = runBlocking {
        val messages = Collections.synchronizedList(mutableListOf<String>())
        KMvi.configure {
            copy(
                intentQueueConfig = IntentQueueConfig(capacity = 0),
                logger = Logger { _, _, _, message -> messages += message() },
            )
        }
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueConfig = IntentQueueConfig(capacity = 0),
            retryPolicy = { _, _ -> false },
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.SEQUENTIAL,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> {
                    flow { awaitCancellation() }
                },
            ),
        )

        val results = List(200) {
            contract.dispatch(TestIntent.Increment)
        }

        assertTrue(
            "expected at least one intent queue full warning, got $messages",
            messages.any { it.startsWith("Intent queue full") },
        )
        assertTrue(
            "expected at least one Full result, got $results",
            results.any { it == DispatchResult.Full },
        )
    }

    @Test
    fun `RENDEZVOUS intentQueueConfig does not buffer pending intents`() = runBlocking {
        val messages = Collections.synchronizedList(mutableListOf<String>())
        KMvi.configure {
            copy(
                intentQueueConfig = IntentQueueConfig(capacity = Channel.RENDEZVOUS),
                logger = Logger { _, _, _, message -> messages += message() },
            )
        }
        val started = Collections.synchronizedList(mutableListOf<TestIntent>())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueConfig = IntentQueueConfig(capacity = Channel.RENDEZVOUS),
            retryPolicy = { _, _ -> false },
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.SEQUENTIAL,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> { intent ->
                    flow {
                        started += intent
                        if (intent == TestIntent.SetData("0")) {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        }
                        emit(
                            Mvi.PartialChange<TestState, TestEvent> { snapshot ->
                                snapshot.updateState { copy(data = (intent as TestIntent.SetData).value) }
                            },
                        )
                    }
                },
            ),
        )

        withTimeout(5_000) {
            while (!firstStarted.isCompleted) {
                contract.dispatch(TestIntent.SetData("0"))
                delay(1)
            }
        }

        messages.clear()
        val blockedResults = listOf(
            contract.dispatch(TestIntent.SetData("1")),
            contract.dispatch(TestIntent.SetData("2")),
            contract.dispatch(TestIntent.SetData("3")),
        )

        releaseFirst.complete(Unit)
        contract.stateFlow.first { it.data == "0" }
        delay(100)

        assertEquals(listOf(TestIntent.SetData("0")), started.toList())
        assertEquals(listOf(DispatchResult.Full, DispatchResult.Full, DispatchResult.Full), blockedResults)
        assertTrue(
            "expected full warnings for unbuffered rendezvous queue, got $messages",
            messages.any { it.startsWith("Intent queue full") },
        )
    }

    @Test
    fun `CONFLATED intentQueueConfig keeps latest pending intent`() = runBlocking {
        val started = Collections.synchronizedList(mutableListOf<TestIntent>())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueConfig = IntentQueueConfig(capacity = Channel.CONFLATED),
            retryPolicy = { _, _ -> false },
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.SEQUENTIAL,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> { intent ->
                    flow {
                        started += intent
                        if (intent == TestIntent.SetData("0")) {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        }
                        emit(
                            Mvi.PartialChange<TestState, TestEvent> { snapshot ->
                                snapshot.updateState { copy(data = (intent as TestIntent.SetData).value) }
                            },
                        )
                    }
                },
            ),
        )

        contract.dispatch(TestIntent.SetData("0"))
        withTimeout(5_000) {
            firstStarted.await()
        }

        assertEquals(DispatchResult.Submitted, contract.dispatch(TestIntent.SetData("1")))
        assertEquals(DispatchResult.Submitted, contract.dispatch(TestIntent.SetData("2")))
        assertEquals(DispatchResult.Submitted, contract.dispatch(TestIntent.SetData("3")))

        releaseFirst.complete(Unit)

        val state = contract.stateFlow.first { it.data == "3" }
        assertEquals("3", state.data)
        assertEquals(listOf(TestIntent.SetData("0"), TestIntent.SetData("3")), started.toList())
    }

    @Test
    fun `DROP_LATEST intentQueueConfig returns Submitted even when latest intent is dropped`() = runBlocking {
        val started = Collections.synchronizedList(mutableListOf<TestIntent>())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueConfig = IntentQueueConfig(
                capacity = 1,
                onBufferOverflow = BufferOverflow.DROP_LATEST,
            ),
            retryPolicy = { _, _ -> false },
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.SEQUENTIAL,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> { intent ->
                    flow {
                        started += intent
                        if (intent == TestIntent.SetData("0")) {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        }
                        emit(
                            Mvi.PartialChange<TestState, TestEvent> { snapshot ->
                                snapshot.updateState { copy(data = (intent as TestIntent.SetData).value) }
                            },
                        )
                    }
                },
            ),
        )

        assertEquals(DispatchResult.Submitted, contract.dispatch(TestIntent.SetData("0")))
        withTimeout(5_000) {
            firstStarted.await()
        }

        assertEquals(DispatchResult.Submitted, contract.dispatch(TestIntent.SetData("1")))
        assertEquals(DispatchResult.Submitted, contract.dispatch(TestIntent.SetData("2")))

        releaseFirst.complete(Unit)
        val state = contract.stateFlow.first { it.data == "1" }
        delay(100)

        assertEquals("1", state.data)
        assertEquals(listOf(TestIntent.SetData("0"), TestIntent.SetData("1")), started.toList())
    }

    @Test
    fun `DROP_OLDEST intentQueueConfig returns Submitted even when older intent is dropped`() = runBlocking {
        val started = Collections.synchronizedList(mutableListOf<TestIntent>())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueConfig = IntentQueueConfig(
                capacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            ),
            retryPolicy = { _, _ -> false },
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.SEQUENTIAL,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
                handler = IntentHandler<TestIntent, TestState, TestEvent> { intent ->
                    flow {
                        started += intent
                        if (intent == TestIntent.SetData("0")) {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        }
                        emit(
                            Mvi.PartialChange<TestState, TestEvent> { snapshot ->
                                snapshot.updateState { copy(data = (intent as TestIntent.SetData).value) }
                            },
                        )
                    }
                },
            ),
        )

        assertEquals(DispatchResult.Submitted, contract.dispatch(TestIntent.SetData("0")))
        withTimeout(5_000) {
            firstStarted.await()
        }

        assertEquals(DispatchResult.Submitted, contract.dispatch(TestIntent.SetData("1")))
        assertEquals(DispatchResult.Submitted, contract.dispatch(TestIntent.SetData("2")))

        releaseFirst.complete(Unit)
        val state = contract.stateFlow.first { it.data == "2" }
        delay(100)

        assertEquals("2", state.data)
        assertEquals(listOf(TestIntent.SetData("0"), TestIntent.SetData("2")), started.toList())
    }

    @Test
    fun `scope cancel while intent queue has buffered intents does not process pending intents`() = runBlocking {
        val started = Collections.synchronizedList(mutableListOf<TestIntent>())
        val contract = CoreReactiveContract(
            scope = testScope,
            initState = TestState(),
            intentQueueConfig = IntentQueueConfig(capacity = Channel.UNLIMITED),
            retryPolicy = { _, _ -> false },
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.SEQUENTIAL,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
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
        assertEquals(DispatchResult.Unavailable, contract.dispatch(TestIntent.Increment))

        assertEquals("only the blocking first intent should start before cancellation", 1, started.size)
        assertEquals(0, contract.stateFlow.value.count)
    }
}
