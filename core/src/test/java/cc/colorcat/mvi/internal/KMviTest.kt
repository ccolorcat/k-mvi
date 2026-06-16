package cc.colorcat.mvi.internal

import cc.colorcat.mvi.HandleStrategy
import cc.colorcat.mvi.HybridConfig
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
        KMvi.setup { KMvi.Configuration(logger = Logger { _, _, _, _ -> }) }
    }

    @Test
    fun `default configuration is accessible`() {
        val config = KMvi.Configuration(logger = Logger { _, _, _, _ -> })

        assertEquals(IntentQueueConfig.DEFAULT_CAPACITY, config.intentQueueConfig.capacity)
        assertEquals(BufferOverflow.SUSPEND, config.intentQueueConfig.onBufferOverflow)
        assertEquals(HybridConfig.DEFAULT_GROUP_COUNT_WARNING_THRESHOLD, config.groupCountWarningThreshold)
        assertEquals(256, HybridConfig.DEFAULT_GROUP_COUNT_WARNING_THRESHOLD)
    }

    @Test
    fun `setup with custom values`() {
        val customLogger = Logger { _, _, _, _ -> }

        KMvi.setup {
            copy(
                handleStrategy = HandleStrategy.SEQUENTIAL,
                logger = customLogger,
            )
        }
    }

    @Test
    fun `setup allows zero intentQueueConfig capacity`() {
        KMvi.setup {
            copy(
                intentQueueConfig = IntentQueueConfig(capacity = 0),
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `setup allows CONFLATED intentQueueConfig capacity`() {
        KMvi.setup {
            copy(
                intentQueueConfig = IntentQueueConfig(capacity = Channel.CONFLATED),
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `setup rejects invalid negative intentQueueConfig capacity`() {
        assertThrows(IllegalArgumentException::class.java) {
            KMvi.setup {
                copy(
                    intentQueueConfig = IntentQueueConfig(capacity = -3),
                    logger = Logger { _, _, _, _ -> },
                )
            }
        }
    }

    @Test
    fun `setup allows UNLIMITED intentQueueConfig capacity`() {
        KMvi.setup {
            copy(
                intentQueueConfig = IntentQueueConfig(capacity = Channel.UNLIMITED),
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `setup allows positive intentQueueConfig capacity`() {
        KMvi.setup {
            copy(
                intentQueueConfig = IntentQueueConfig(capacity = 128),
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `setup rejects CONFLATED intentQueueConfig with drop overflow`() {
        assertThrows(IllegalArgumentException::class.java) {
            KMvi.setup {
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
    fun `setup allows Channel_BUFFERED groupChannelCapacity`() {
        KMvi.setup {
            copy(
                logger = Logger { _, _, _, _ -> },
                groupChannelCapacity = Channel.BUFFERED,
            )
        }
    }

    @Test
    fun `setup allows positive groupChannelCapacity`() {
        KMvi.setup {
            copy(
                logger = Logger { _, _, _, _ -> },
                groupChannelCapacity = 32,
            )
        }
    }

    @Test
    fun `setup allows positive groupCountWarningThreshold`() {
        KMvi.setup {
            copy(
                logger = Logger { _, _, _, _ -> },
                groupCountWarningThreshold = 32,
            )
        }
    }

    @Test
    fun `setup allows Int_MAX_VALUE groupCountWarningThreshold`() {
        KMvi.setup {
            copy(
                logger = Logger { _, _, _, _ -> },
                groupCountWarningThreshold = Int.MAX_VALUE,
            )
        }
    }

    @Test
    fun `setup rejects zero groupCountWarningThreshold`() {
        assertThrows(IllegalArgumentException::class.java) {
            KMvi.setup {
                copy(
                    logger = Logger { _, _, _, _ -> },
                    groupCountWarningThreshold = 0,
                )
            }
        }
    }

    @Test
    fun `setup rejects negative groupCountWarningThreshold`() {
        assertThrows(IllegalArgumentException::class.java) {
            KMvi.setup {
                copy(
                    logger = Logger { _, _, _, _ -> },
                    groupCountWarningThreshold = -1,
                )
            }
        }
    }

    @Test
    fun `setup allows CONFLATED groupChannelCapacity`() {
        KMvi.setup {
            copy(
                logger = Logger { _, _, _, _ -> },
                groupChannelCapacity = Channel.CONFLATED,
            )
        }
    }

    @Test
    fun `setup rejects invalid negative groupChannelCapacity`() {
        assertThrows(IllegalArgumentException::class.java) {
            KMvi.setup {
                copy(
                    logger = Logger { _, _, _, _ -> },
                    groupChannelCapacity = -3,
                )
            }
        }
    }

    @Test
    fun `hybridConfig factory uses global groupChannelCapacity`() {
        KMvi.setup {
            copy(
                logger = Logger { _, _, _, _ -> },
                groupChannelCapacity = 32,
            )
        }

        val config = KMvi.hybridConfig<TestIntent>()

        assertEquals(32, config.groupChannelCapacity)
    }

    @Test
    fun `hybridConfig factory uses global groupCountWarningThreshold`() {
        KMvi.setup {
            copy(
                logger = Logger { _, _, _, _ -> },
                groupCountWarningThreshold = 32,
            )
        }

        val config = KMvi.hybridConfig<TestIntent>()

        assertEquals(32, config.groupCountWarningThreshold)
    }

    @Test
    fun `hybridConfig factory accepts groupChannelCapacity override`() {
        KMvi.setup {
            copy(
                logger = Logger { _, _, _, _ -> },
                groupChannelCapacity = 32,
            )
        }

        val config = KMvi.hybridConfig<TestIntent>(groupChannelCapacity = 64)

        assertEquals(64, config.groupChannelCapacity)
    }

    @Test
    fun `hybridConfig factory accepts groupCountWarningThreshold override`() {
        KMvi.setup {
            copy(
                logger = Logger { _, _, _, _ -> },
                groupCountWarningThreshold = 32,
            )
        }

        val config = KMvi.hybridConfig<TestIntent>(groupCountWarningThreshold = 128)

        assertEquals(128, config.groupCountWarningThreshold)
    }

    @Test
    fun `HybridConfig rejects zero groupCountWarningThreshold`() {
        assertThrows(IllegalArgumentException::class.java) {
            HybridConfig<TestIntent>(groupCountWarningThreshold = 0)
        }
    }

    @Test
    fun `HybridConfig rejects negative groupCountWarningThreshold`() {
        assertThrows(IllegalArgumentException::class.java) {
            HybridConfig<TestIntent>(groupCountWarningThreshold = -1)
        }
    }

    @Test
    fun `hybridConfig factory creates typed groupTagSelector`() {
        val config = KMvi.hybridConfig<TestIntent> { intent ->
            when (intent) {
                TestIntent.LoadUser -> "user"
                TestIntent.LoadPost -> "post"
            }
        }

        assertEquals("user", config.groupTagSelector(TestIntent.LoadUser))
        assertEquals("post", config.groupTagSelector(TestIntent.LoadPost))
    }

    @Test
    fun `setup can be called multiple times`() {
        KMvi.setup {
            copy(
                handleStrategy = HandleStrategy.CONCURRENT,
                logger = Logger { _, _, _, _ -> },
            )
        }
        KMvi.setup {
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
