package cc.colorcat.mvi.internal

import cc.colorcat.mvi.HandleStrategy
import cc.colorcat.mvi.HybridConfig
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.TestLogger
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

class KMviTest {

    @Rule @JvmField val testLog: TestRule = TestLogger()

    @Before
    fun setUp() {
        // Reset to a valid config with a no-op logger to avoid Android Log dependency
        KMvi.setup {
            copy(
                intentQueueCapacity = 256,
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `default configuration is accessible`() {
        KMvi.setup {
            copy(
                intentQueueCapacity = 256,
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `setup with custom values`() {
        val customLogger = Logger { _, _, _, _ -> }

        KMvi.setup {
            copy(
                handleStrategy = HandleStrategy.SEQUENTIAL,
                logger = customLogger,
                intentQueueCapacity = 256,
            )
        }
    }

    @Test
    fun `setup allows zero intentQueueCapacity`() {
        KMvi.setup {
            copy(
                intentQueueCapacity = 0,
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `setup allows CONFLATED intentQueueCapacity`() {
        KMvi.setup {
            copy(
                intentQueueCapacity = Channel.CONFLATED,
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `setup rejects invalid negative intentQueueCapacity`() {
        assertThrows(IllegalArgumentException::class.java) {
            KMvi.setup {
                copy(
                    intentQueueCapacity = -3,
                    logger = Logger { _, _, _, _ -> },
                )
            }
        }
    }

    @Test
    fun `setup allows UNLIMITED intentQueueCapacity`() {
        KMvi.setup {
            copy(
                intentQueueCapacity = Channel.UNLIMITED,
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `setup allows positive intentQueueCapacity`() {
        KMvi.setup {
            copy(
                intentQueueCapacity = 128,
                logger = Logger { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun `setup allows Channel_BUFFERED groupChannelCapacity`() {
        KMvi.setup {
            copy(
                intentQueueCapacity = 256,
                logger = Logger { _, _, _, _ -> },
                hybridConfig = HybridConfig<Mvi.Intent>(groupChannelCapacity = Channel.BUFFERED),
            )
        }
    }

    @Test
    fun `setup allows positive groupChannelCapacity`() {
        KMvi.setup {
            copy(
                intentQueueCapacity = 256,
                logger = Logger { _, _, _, _ -> },
                hybridConfig = HybridConfig<Mvi.Intent>(groupChannelCapacity = 32),
            )
        }
    }

    @Test
    fun `setup allows CONFLATED groupChannelCapacity`() {
        KMvi.setup {
            copy(
                intentQueueCapacity = 256,
                logger = Logger { _, _, _, _ -> },
                hybridConfig = HybridConfig<Mvi.Intent>(groupChannelCapacity = Channel.CONFLATED),
            )
        }
    }

    @Test
    fun `setup can be called multiple times`() {
        KMvi.setup {
            copy(
                intentQueueCapacity = 256,
                handleStrategy = HandleStrategy.CONCURRENT,
                logger = Logger { _, _, _, _ -> },
            )
        }
        KMvi.setup {
            copy(
                intentQueueCapacity = 256,
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
