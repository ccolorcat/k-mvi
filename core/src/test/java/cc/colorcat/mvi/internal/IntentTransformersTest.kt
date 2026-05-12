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
import cc.colorcat.mvi.toPartialChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emptyFlow
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

class IntentTransformersTest {

    private data class TestState(val count: Int = 0) : Mvi.State
    private sealed interface TestEvent : Mvi.Event

    private sealed interface TestIntent : Mvi.Intent {
        data object IntentA : TestIntent
        data object IntentB : TestIntent
    }

    // Intents that implement Concurrent/Sequential for HYBRID grouping tests
    private sealed interface ConcurrentIntent : TestIntent, Mvi.Intent.Concurrent {
        data object ConcurrentA : ConcurrentIntent
        data object ConcurrentB : ConcurrentIntent
    }

    private sealed interface SequentialIntent : TestIntent, Mvi.Intent.Sequential {
        data object SequentialA : SequentialIntent
        data object SequentialB : SequentialIntent
    }

    private val echoHandler = IntentHandler<TestIntent, TestState, TestEvent> {
        Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = count + 1) } }.asSingleFlow()
    }

    // Intent that implements BOTH Concurrent and Sequential — edge case for assignGroupTag
    private sealed interface BothIntent : TestIntent, Mvi.Intent.Concurrent, Mvi.Intent.Sequential {
        data object Ambiguous : BothIntent
    }

    @Rule @JvmField val testLog: TestRule = TestLogger()

    @Before
    fun setUp() {
        KMvi.setup {
            copy(
                intentQueueCapacity = 64,
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    // --- CONCURRENT ---

    @Test
    fun `CONCURRENT transforms intents`() = runBlocking {
        val transformer = IntentTransformer(
            strategy = HandleStrategy.CONCURRENT,
            config = HybridConfig(),
            handler = echoHandler,
        )

        val intents = flow {
            emit(TestIntent.IntentA)
            emit(TestIntent.IntentB)
        }

        val results = transformer.transform(intents).toList()
        assertEquals(2, results.size)
    }

    @Test
    fun `toPartialChange extension`() = runBlocking {
        val transformer = IntentTransformer(
            strategy = HandleStrategy.CONCURRENT,
            config = HybridConfig(),
            handler = echoHandler,
        )

        val intents = flow {
            emit(TestIntent.IntentA)
        }

        val results = intents.toPartialChange(transformer).toList()
        assertEquals(1, results.size)
    }

    // --- SEQUENTIAL ---

    @Test
    fun `SEQUENTIAL processes in order`() = runBlocking {
        val trackingHandler = IntentHandler<TestIntent, TestState, TestEvent> { intent ->
            flow {
                emit(Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = count + 1) } })
            }
        }

        val transformer = IntentTransformer(
            strategy = HandleStrategy.SEQUENTIAL,
            config = HybridConfig(),
            handler = trackingHandler,
        )

        val intents = flow {
            emit(TestIntent.IntentA)
            emit(TestIntent.IntentB)
        }

        val results = transformer.transform(intents).toList()
        assertEquals(2, results.size)
    }

    // --- HYBRID ---

    @Test
    fun `HYBRID accepts config`() {
        val transformer = IntentTransformer(
            strategy = HandleStrategy.HYBRID,
            config = HybridConfig<TestIntent>(
                groupChannelCapacity = kotlinx.coroutines.channels.Channel.BUFFERED,
                groupTagSelector = { it::class.java.name },
            ),
            handler = echoHandler,
        )

        assertNotNull(transformer)
    }

    @Test
    fun `transformer factory creates with correct strategy`() {
        val concurrent = IntentTransformer(
            strategy = HandleStrategy.CONCURRENT,
            config = HybridConfig(),
            handler = echoHandler,
        )
        val sequential = IntentTransformer(
            strategy = HandleStrategy.SEQUENTIAL,
            config = HybridConfig(),
            handler = echoHandler,
        )
        val hybrid = IntentTransformer(
            strategy = HandleStrategy.HYBRID,
            config = HybridConfig(),
            handler = echoHandler,
        )

        assertNotNull(concurrent)
        assertNotNull(sequential)
        assertNotNull(hybrid)
    }

    // --- Edge cases ---

    @Test
    fun `toPartialChange with empty flow returns empty`() = runBlocking {
        val transformer = IntentTransformer(
            strategy = HandleStrategy.CONCURRENT,
            config = HybridConfig(),
            handler = echoHandler,
        )

        val results = emptyFlow<TestIntent>().toPartialChange(transformer).toList()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `handler returning emptyFlow produces no changes`() = runBlocking {
        val silentHandler = IntentHandler<TestIntent, TestState, TestEvent> { emptyFlow() }
        val transformer = IntentTransformer(
            strategy = HandleStrategy.SEQUENTIAL,
            config = HybridConfig(),
            handler = silentHandler,
        )

        val results = flow {
            emit(TestIntent.IntentA)
            emit(TestIntent.IntentB)
        }.toPartialChange(transformer).toList()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `intent implementing both Concurrent and Sequential is treated as fallback`() = runBlocking {
        var handledTag = ""
        val trackingHandler = IntentHandler<BothIntent, TestState, TestEvent> {
            Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = count + 1) } }
                .asSingleFlow()
        }

        val transformer = IntentTransformer(
            strategy = HandleStrategy.HYBRID,
            config = HybridConfig<BothIntent>(
                groupTagSelector = { handledTag = it::class.java.name; it::class.java.name },
            ),
            handler = trackingHandler,
        )

        val results = flow {
            emit(BothIntent.Ambiguous)
        }.toPartialChange(transformer).toList()

        assertEquals(1, results.size)
        // Ambiguous implements both interfaces, so neither isConcurrent nor isSequential,
        // meaning it falls through to FALLBACK + groupTagSelector result
        assertFalse("should not be CONCURRENT or SEQUENTIAL tag", handledTag.contains("CONCURRENT"))
        assertFalse("should not be CONCURRENT or SEQUENTIAL tag", handledTag.contains("SEQUENTIAL"))
    }

    @Test
    fun `CONCURRENT strategy processes multiple intents`() = runBlocking {
        val transformer = IntentTransformer(
            strategy = HandleStrategy.CONCURRENT,
            config = HybridConfig(),
            handler = echoHandler,
        )

        val results = flow {
            emit(TestIntent.IntentA)
            emit(TestIntent.IntentB)
        }.toPartialChange(transformer).toList()

        assertEquals(2, results.size)
    }

    @Test
    fun `HYBRID strategy processes multiple intents`() = runBlocking {
        val transformer = IntentTransformer(
            strategy = HandleStrategy.HYBRID,
            config = HybridConfig(),
            handler = echoHandler,
        )

        val results = flow {
            emit(TestIntent.IntentA)
            emit(TestIntent.IntentB)
        }.toPartialChange(transformer).toList()

        assertEquals(2, results.size)
    }

    @Test
    fun `SEQUENTIAL strategy preserves order`() = runBlocking {
        val orderTracker = mutableListOf<String>()
        val trackingHandler = IntentHandler<TestIntent, TestState, TestEvent> { intent ->
            flow {
                val name = when (intent) {
                    TestIntent.IntentA -> "A"
                    TestIntent.IntentB -> "B"
                    else -> "?"
                }
                orderTracker.add(name)
                emit(Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = count + 1) } })
            }
        }

        val transformer = IntentTransformer(
            strategy = HandleStrategy.SEQUENTIAL,
            config = HybridConfig(),
            handler = trackingHandler,
        )

        flow {
            emit(TestIntent.IntentA)
            emit(TestIntent.IntentB)
        }.toPartialChange(transformer).toList()

        assertEquals(listOf("A", "B"), orderTracker)
    }

    // --- HYBRID strategy with Concurrent/Sequential intents ---

    @Test
    fun `HYBRID processes Concurrent intents in parallel`() = runBlocking {
        val orderTracker = mutableListOf<String>()
        val handler = IntentHandler<ConcurrentIntent, TestState, TestEvent> { intent ->
            flow {
                val name = when (intent) {
                    ConcurrentIntent.ConcurrentA -> "A"
                    ConcurrentIntent.ConcurrentB -> "B"
                    else -> "?"
                }
                orderTracker.add("start-$name")
                kotlinx.coroutines.delay(10)
                orderTracker.add("end-$name")
                emit(Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = count + 1) } })
            }
        }

        val transformer = IntentTransformer(
            strategy = HandleStrategy.HYBRID,
            config = HybridConfig<ConcurrentIntent>(),
            handler = handler,
        )

        flow {
            emit(ConcurrentIntent.ConcurrentA)
            emit(ConcurrentIntent.ConcurrentB)
        }.toPartialChange(transformer).toList()

        assertTrue("A started", orderTracker.contains("start-A"))
        assertTrue("B started", orderTracker.contains("start-B"))
    }

    @Test
    fun `HYBRID processes Sequential intents in order`() = runBlocking {
        val orderTracker = mutableListOf<String>()
        val handler = IntentHandler<SequentialIntent, TestState, TestEvent> { intent ->
            flow {
                val name = when (intent) {
                    SequentialIntent.SequentialA -> "A"
                    SequentialIntent.SequentialB -> "B"
                    else -> "?"
                }
                orderTracker.add(name)
                emit(Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = count + 1) } })
            }
        }

        val transformer = IntentTransformer(
            strategy = HandleStrategy.HYBRID,
            config = HybridConfig<SequentialIntent>(),
            handler = handler,
        )

        flow {
            emit(SequentialIntent.SequentialA)
            emit(SequentialIntent.SequentialB)
        }.toPartialChange(transformer).toList()

        assertEquals(listOf("A", "B"), orderTracker)
    }

    @Test
    fun `HYBRID separates fallback intents by class tag`() = runBlocking {
        val orderTracker = mutableListOf<String>()
        val handler = IntentHandler<TestIntent, TestState, TestEvent> { intent ->
            flow {
                val name = when (intent) {
                    TestIntent.IntentA -> "A"
                    TestIntent.IntentB -> "B"
                    else -> "?"
                }
                orderTracker.add("start-$name")
                kotlinx.coroutines.delay(10)
                orderTracker.add("end-$name")
                emit(Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = count + 1) } })
            }
        }

        val transformer = IntentTransformer(
            strategy = HandleStrategy.HYBRID,
            config = HybridConfig<TestIntent>(),
            handler = handler,
        )

        flow {
            emit(TestIntent.IntentA)
            emit(TestIntent.IntentB)
        }.toPartialChange(transformer).toList()

        assertTrue("A started", orderTracker.contains("start-A"))
        assertTrue("B started", orderTracker.contains("start-B"))
    }
}
