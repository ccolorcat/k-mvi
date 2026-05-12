package cc.colorcat.mvi.internal

import cc.colorcat.mvi.HandleStrategy
import cc.colorcat.mvi.HybridConfig
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.TestLogger
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `setup allows negative intentQueueCapacity`() {
        KMvi.setup {
            copy(
                intentQueueCapacity = -1,
                logger = Logger { _, _, _, _ -> },
            )
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

        // cause is Exception, attempt <= 3 — retry
        assertTrue("attempt 1 should retry", policy(1, RuntimeException("test")))
        assertTrue("attempt 2 should retry", policy(2, RuntimeException("test")))
        assertTrue("attempt 3 should retry", policy(3, RuntimeException("test")))

        // cause is Exception, attempt > 3 — stop
        assertFalse("attempt 4 should stop", policy(4, RuntimeException("test")))

        // cause is Error — don't retry
        assertFalse("Error should not retry", policy(1, Error("fatal")))
    }
}
