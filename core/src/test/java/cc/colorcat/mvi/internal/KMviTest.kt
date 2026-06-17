package cc.colorcat.mvi.internal

import cc.colorcat.mvi.HandleStrategy
import cc.colorcat.mvi.GroupTagSelector
import cc.colorcat.mvi.HybridStrategyConfig
import cc.colorcat.mvi.IntentQueueConfig
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.TestLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

class KMviTest {
    private sealed interface TestIntent : Mvi.Intent {
        data object LoadUser : TestIntent
        data object LoadPost : TestIntent
    }


    @Rule @JvmField val testLog: TestRule = TestLogger()

    @Before
    fun setUp() {
        // Full reset to defaults each test; logger replaced with a no-op to avoid Android Log.
        // Ignoring the receiver (the previous Configuration) is intentional — we want a clean slate
        // so capacity / strategy / retry changes from earlier tests cannot leak into later ones.
        KMvi.configure { KMvi.Configuration(logger = Logger { _, _, _, _ -> }) }
    }

    @Test
    fun `default configuration is accessible`() {
        val config = KMvi.Configuration(logger = Logger { _, _, _, _ -> })

        assertEquals(IntentQueueConfig.DEFAULT_CAPACITY, config.intentQueueConfig.capacity)
        assertEquals(BufferOverflow.SUSPEND, config.intentQueueConfig.onBufferOverflow)
        assertEquals(HybridStrategyConfig.DEFAULT_GROUP_COUNT_WARNING_THRESHOLD, config.hybridStrategyConfig.groupCountWarningThreshold)
        assertEquals(256, HybridStrategyConfig.DEFAULT_GROUP_COUNT_WARNING_THRESHOLD)
    }

    @Test
    fun `configure with custom values`() {
        val customLogger = Logger { _, _, _, _ -> }

        KMvi.configure {
            copy(
                handleStrategy = HandleStrategy.SEQUENTIAL,
                logger = customLogger,
            )
        }
    }

    @Test
    fun `configure allows zero intentQueueConfig capacity`() {
        KMvi.configure {
            copy(
                intentQueueConfig = IntentQueueConfig(capacity = 0),
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `configure allows CONFLATED intentQueueConfig capacity`() {
        KMvi.configure {
            copy(
                intentQueueConfig = IntentQueueConfig(capacity = Channel.CONFLATED),
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `configure rejects invalid negative intentQueueConfig capacity`() {
        assertThrows(IllegalArgumentException::class.java) {
            KMvi.configure {
                copy(
                    intentQueueConfig = IntentQueueConfig(capacity = -3),
                    logger = Logger { _, _, _, _ -> },
                )
            }
        }
    }

    @Test
    fun `configure allows UNLIMITED intentQueueConfig capacity`() {
        KMvi.configure {
            copy(
                intentQueueConfig = IntentQueueConfig(capacity = Channel.UNLIMITED),
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `configure allows positive intentQueueConfig capacity`() {
        KMvi.configure {
            copy(
                intentQueueConfig = IntentQueueConfig(capacity = 128),
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `configure rejects CONFLATED intentQueueConfig with drop overflow`() {
        assertThrows(IllegalArgumentException::class.java) {
            KMvi.configure {
                copy(
                    intentQueueConfig = IntentQueueConfig(
                        capacity = Channel.CONFLATED,
                        onBufferOverflow = BufferOverflow.DROP_OLDEST,
                    ),
                    logger = Logger { _, _, _, _ -> },
                )
            }
        }
    }

    @Test
    fun `configure allows Channel_BUFFERED hybrid groupChannelCapacity`() {
        KMvi.configure {
            copy(
                logger = Logger { _, _, _, _ -> },
                hybridStrategyConfig = HybridStrategyConfig(groupChannelCapacity = Channel.BUFFERED),
            )
        }
    }

    @Test
    fun `configure allows positive hybrid groupChannelCapacity`() {
        KMvi.configure {
            copy(
                logger = Logger { _, _, _, _ -> },
                hybridStrategyConfig = HybridStrategyConfig(groupChannelCapacity = 32),
            )
        }
    }

    @Test
    fun `configure allows positive hybrid groupCountWarningThreshold`() {
        KMvi.configure {
            copy(
                logger = Logger { _, _, _, _ -> },
                hybridStrategyConfig = HybridStrategyConfig(groupCountWarningThreshold = 32),
            )
        }
    }

    @Test
    fun `configure allows Int_MAX_VALUE hybrid groupCountWarningThreshold`() {
        KMvi.configure {
            copy(
                logger = Logger { _, _, _, _ -> },
                hybridStrategyConfig = HybridStrategyConfig(groupCountWarningThreshold = Int.MAX_VALUE),
            )
        }
    }

    @Test
    fun `configure rejects zero hybrid groupCountWarningThreshold`() {
        assertThrows(IllegalArgumentException::class.java) {
            KMvi.configure {
                copy(
                    logger = Logger { _, _, _, _ -> },
                    hybridStrategyConfig = HybridStrategyConfig(groupCountWarningThreshold = 0),
                )
            }
        }
    }

    @Test
    fun `configure rejects negative hybrid groupCountWarningThreshold`() {
        assertThrows(IllegalArgumentException::class.java) {
            KMvi.configure {
                copy(
                    logger = Logger { _, _, _, _ -> },
                    hybridStrategyConfig = HybridStrategyConfig(groupCountWarningThreshold = -1),
                )
            }
        }
    }

    @Test
    fun `configure allows CONFLATED hybrid groupChannelCapacity`() {
        KMvi.configure {
            copy(
                logger = Logger { _, _, _, _ -> },
                hybridStrategyConfig = HybridStrategyConfig(groupChannelCapacity = Channel.CONFLATED),
            )
        }
    }

    @Test
    fun `configure rejects invalid negative hybrid groupChannelCapacity`() {
        assertThrows(IllegalArgumentException::class.java) {
            KMvi.configure {
                copy(
                    logger = Logger { _, _, _, _ -> },
                    hybridStrategyConfig = HybridStrategyConfig(groupChannelCapacity = -3),
                )
            }
        }
    }

    @Test
    fun `configuration accepts custom hybridStrategyConfig`() {
        val hybridStrategyConfig = HybridStrategyConfig(
            groupChannelCapacity = 32,
            groupCountWarningThreshold = 128,
        )

        KMvi.configure {
            copy(
                logger = Logger { _, _, _, _ -> },
                hybridStrategyConfig = hybridStrategyConfig,
            )
        }

        assertEquals(32, KMvi.hybridStrategyConfig.groupChannelCapacity)
        assertEquals(128, KMvi.hybridStrategyConfig.groupCountWarningThreshold)
    }

    @Test
    fun `HybridStrategyConfig rejects zero groupCountWarningThreshold`() {
        assertThrows(IllegalArgumentException::class.java) {
            HybridStrategyConfig(groupCountWarningThreshold = 0)
        }
    }

    @Test
    fun `HybridStrategyConfig rejects negative groupCountWarningThreshold`() {
        assertThrows(IllegalArgumentException::class.java) {
            HybridStrategyConfig(groupCountWarningThreshold = -1)
        }
    }

    @Test
    fun `GroupTagSelector creates typed selector`() {
        val selector = GroupTagSelector<TestIntent> { intent ->
            when (intent) {
                TestIntent.LoadUser -> "user"
                TestIntent.LoadPost -> "post"
            }
        }

        assertEquals("user", selector.selectTag(TestIntent.LoadUser))
        assertEquals("post", selector.selectTag(TestIntent.LoadPost))
    }

    @Test
    fun `GroupTagSelector byClass uses runtime class`() {
        val selector = GroupTagSelector.byClass<TestIntent>()

        assertSame(TestIntent.LoadUser.javaClass, selector.selectTag(TestIntent.LoadUser))
    }

    @Test
    fun `configure can be called multiple times`() {
        KMvi.configure {
            copy(
                handleStrategy = HandleStrategy.CONCURRENT,
                logger = Logger { _, _, _, _ -> },
            )
        }
        KMvi.configure {
            copy(
                handleStrategy = HandleStrategy.SEQUENTIAL,
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `retryPolicy default`() {
        // Already set up with no-op logger in @Before
        val policy = KMvi.retryPolicy

        // attempt is 0-based from Flow.retryWhen
        assertTrue("IOException attempt 0 should retry", policy(0, IOException("network")))
        assertTrue("IOException attempt 1 should retry", policy(1, IOException("network")))
        assertTrue("IOException attempt 2 should retry", policy(2, IOException("network")))
        assertFalse("IOException attempt 3 should stop", policy(3, IOException("network")))

        // cause is IOException, attempt > 2 — stop
        assertFalse("IOException attempt 4 should stop", policy(4, IOException("network")))

        // programming/runtime exceptions are not considered transient by default
        assertFalse("RuntimeException should not retry", policy(0, RuntimeException("bug")))
        assertFalse("IllegalArgumentException should not retry", policy(0, IllegalArgumentException("bad input")))

        // cause is Error — don't retry
        assertFalse("Error should not retry", policy(1, Error("fatal")))
    }
}
