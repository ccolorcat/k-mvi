package cc.colorcat.mvi.internal

import cc.colorcat.mvi.GroupTagSelector
import cc.colorcat.mvi.HandleStrategy
import cc.colorcat.mvi.HybridConfig
import cc.colorcat.mvi.IntentHandler
import cc.colorcat.mvi.IntentQueueConfig
import cc.colorcat.mvi.IntentTransformer
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.TestLogger
import cc.colorcat.mvi.asSingleFlow
import cc.colorcat.mvi.toPartialChange
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.util.Collections

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
        data object Other : BothIntent
    }

    @Rule
    @JvmField
    val testLog: TestRule = TestLogger()

    @Before
    fun setUp() {
        KMvi.setup {
            copy(
                intentQueueConfig = IntentQueueConfig(capacity = 64),
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    // --- CONCURRENT ---

    @Test
    fun `CONCURRENT transforms intents`() = runBlocking {
        val transformer = IntentTransformer(
            handleStrategy = HandleStrategy.CONCURRENT,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector.byClass(),
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
            handleStrategy = HandleStrategy.CONCURRENT,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector.byClass(),
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
            handleStrategy = HandleStrategy.SEQUENTIAL,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector.byClass(),
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
            handleStrategy = HandleStrategy.HYBRID,
            hybridConfig = HybridConfig(
                groupChannelCapacity = kotlinx.coroutines.channels.Channel.BUFFERED,
            ),
            groupTagSelector = GroupTagSelector { it::class.java.name },
            handler = echoHandler,
        )

        assertNotNull(transformer)
    }

    @Test
    fun `transformer factory creates with correct strategy`() {
        val concurrent = IntentTransformer(
            handleStrategy = HandleStrategy.CONCURRENT,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector.byClass(),
            handler = echoHandler,
        )
        val sequential = IntentTransformer(
            handleStrategy = HandleStrategy.SEQUENTIAL,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector.byClass(),
            handler = echoHandler,
        )
        val hybrid = IntentTransformer(
            handleStrategy = HandleStrategy.HYBRID,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector.byClass(),
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
            handleStrategy = HandleStrategy.CONCURRENT,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector.byClass(),
            handler = echoHandler,
        )

        val results = emptyFlow<TestIntent>().toPartialChange(transformer).toList()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `handler returning emptyFlow produces no changes`() = runBlocking {
        val silentHandler = IntentHandler<TestIntent, TestState, TestEvent> { emptyFlow() }
        val transformer = IntentTransformer(
            handleStrategy = HandleStrategy.SEQUENTIAL,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector.byClass(),
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
        var handledTag: Any? = null
        val trackingHandler = IntentHandler<BothIntent, TestState, TestEvent> {
            Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = count + 1) } }
                .asSingleFlow()
        }

        val transformer = IntentTransformer(
            handleStrategy = HandleStrategy.HYBRID,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector { handledTag = it::class.java; it::class.java },
            handler = trackingHandler,
        )

        val results = flow {
            emit(BothIntent.Ambiguous)
        }.toPartialChange(transformer).toList()

        assertEquals(1, results.size)
        // Ambiguous implements both interfaces, so neither isConcurrent nor isSequential,
        // meaning it falls through to the custom groupTagSelector result.
        assertSame(BothIntent.Ambiguous.javaClass, handledTag)
    }

    @Test
    fun `conflict intent emits WARN once per class`() = runBlocking {
        val warns = mutableListOf<String>()
        KMvi.setup {
            copy(
                logger = Logger { priority, _, _, message ->
                    if (priority == Logger.WARN) warns.add(message())
                },
            )
        }

        val transformer = IntentTransformer(
            handleStrategy = HandleStrategy.HYBRID,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector { it::class.java.name },
            handler = IntentHandler<BothIntent, TestState, TestEvent> {
                Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = count + 1) } }
                    .asSingleFlow()
            },
        )

        val intents = flow {
            emit(BothIntent.Ambiguous)
            emit(BothIntent.Ambiguous)
            emit(BothIntent.Other)
            emit(BothIntent.Other)
            emit(BothIntent.Ambiguous)
        }
        val results = intents.toPartialChange(transformer).toList()

        assertEquals(5, results.size)
        assertEquals("WARN must fire once per conflicting class", 2, warns.size)
        assertTrue(warns.any { it.contains("Ambiguous") })
        assertTrue(warns.any { it.contains("Other") })
        assertTrue(warns.all { it.contains("both Concurrent and Sequential") })
        assertTrue(warns.all { it.contains("falling back to hybrid group selection") })
    }

    @Test
    fun `CONCURRENT strategy processes multiple intents`() = runBlocking {
        val transformer = IntentTransformer(
            handleStrategy = HandleStrategy.CONCURRENT,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector.byClass(),
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
            handleStrategy = HandleStrategy.HYBRID,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector.byClass(),
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
            handleStrategy = HandleStrategy.SEQUENTIAL,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector.byClass(),
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
            handleStrategy = HandleStrategy.HYBRID,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector.byClass(),
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
            handleStrategy = HandleStrategy.HYBRID,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector.byClass(),
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
            handleStrategy = HandleStrategy.HYBRID,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector.byClass(),
            handler = handler,
        )

        flow {
            emit(TestIntent.IntentA)
            emit(TestIntent.IntentB)
        }.toPartialChange(transformer).toList()

        assertTrue("A started", orderTracker.contains("start-A"))
        assertTrue("B started", orderTracker.contains("start-B"))
    }

    @Test
    fun `HYBRID runs concurrent sequential and fallback groups together`() = runBlocking {
        val orderTracker = Collections.synchronizedList(mutableListOf<String>())
        val handler = IntentHandler<TestIntent, TestState, TestEvent> { intent ->
            flow {
                val name = when (intent) {
                    ConcurrentIntent.ConcurrentA -> "concurrent-A"
                    ConcurrentIntent.ConcurrentB -> "concurrent-B"
                    SequentialIntent.SequentialA -> "sequential-A"
                    SequentialIntent.SequentialB -> "sequential-B"
                    TestIntent.IntentA -> "fallback-A"
                    TestIntent.IntentB -> "fallback-B"
                    else -> "?"
                }
                orderTracker.add("start-$name")
                if (name.endsWith("-A")) {
                    delay(50)
                }
                orderTracker.add("end-$name")
                emit(Mvi.PartialChange<TestState, TestEvent> { it.updateState { copy(count = count + 1) } })
            }
        }

        val transformer = IntentTransformer(
            handleStrategy = HandleStrategy.HYBRID,
            hybridConfig = HybridConfig(),
            groupTagSelector = GroupTagSelector { "fallback" },
            handler = handler,
        )

        val results = flow {
            emit(SequentialIntent.SequentialA)
            emit(ConcurrentIntent.ConcurrentA)
            emit(TestIntent.IntentA)
            emit(SequentialIntent.SequentialB)
            emit(ConcurrentIntent.ConcurrentB)
            emit(TestIntent.IntentB)
        }.toPartialChange(transformer).toList()

        val order = orderTracker.toList()
        assertEquals(6, results.size)
        assertTrue(
            "concurrent group should start before sequential-A completes: $order",
            order.indexOf("start-concurrent-A") in 0 until order.indexOf("end-sequential-A"),
        )
        assertTrue(
            "fallback group should start before sequential-A completes: $order",
            order.indexOf("start-fallback-A") in 0 until order.indexOf("end-sequential-A"),
        )
        assertTrue(
            "sequential group should preserve order: $order",
            order.indexOf("end-sequential-A") < order.indexOf("start-sequential-B"),
        )
        assertTrue(
            "fallback group should preserve order within its configured tag: $order",
            order.indexOf("end-fallback-A") < order.indexOf("start-fallback-B"),
        )
        assertTrue(
            "concurrent group should process members in parallel: $order",
            order.indexOf("start-concurrent-B") in 0 until order.indexOf("end-concurrent-A"),
        )
    }
}
