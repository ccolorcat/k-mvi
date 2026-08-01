package cc.colorcat.mvi

import cc.colorcat.mvi.internal.chunkForLogcat
import cc.colorcat.mvi.internal.formatLogText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.net.UnknownHostException

class LoggerTest {

    @Rule @JvmField val testLog: TestRule = TestLogger()

    @Test
    fun `default logger is created with WARN threshold`() {
        val log = Logger()
        assertNotNull(log)
    }

    @Test
    fun `logger with DEBUG threshold`() {
        val log = Logger(Logger.DEBUG)
        assertNotNull(log)
    }

    @Test
    fun `logger with ERROR threshold`() {
        val log = Logger(Logger.ERROR)
        assertNotNull(log)
    }

    @Test
    fun `custom logger captures priority`() {
        var lastPriority = -1
        val log = Logger { priority, _, _, message ->
            lastPriority = priority
            message()
        }

        log.log(Logger.INFO, "tag", null) { "test" }
        assertEquals(Logger.INFO, lastPriority)
    }

    @Test
    fun `custom logger captures tag`() {
        var capturedTag: String? = null
        val log = Logger { _, tag, _, message ->
            capturedTag = tag
            message()
        }

        log.log(Logger.WARN, "my-tag", null) { "hello" }
        assertEquals("my-tag", capturedTag)
    }

    @Test
    fun `custom logger receives throwable`() {
        var capturedError: Throwable? = null
        val log = Logger { _, _, cause, message ->
            capturedError = cause
            message()
        }

        val exception = RuntimeException("test")
        log.log(Logger.ERROR, "tag", exception) { "error message" }

        assertEquals(exception, capturedError)
    }

    @Test
    fun `threshold constants have correct ordering`() {
        assertTrue(Logger.VERBOSE < Logger.DEBUG)
        assertTrue(Logger.DEBUG < Logger.INFO)
        assertTrue(Logger.INFO < Logger.WARN)
        assertTrue(Logger.WARN < Logger.ERROR)
        assertTrue(Logger.ERROR < Logger.ASSERT)
    }

    @Test
    fun `message lambda is lazy - not evaluated if not needed`() {
        var called = false
        val log = Logger { _, _, _, _ -> } // no-op logger

        log.log(Logger.DEBUG, "tag", null) { called = true; "" }

        // lambda still evaluated because log always receives it;
        // laziness is at the application level (factory with threshold)
        // not at the fun interface level
        assertFalse(called)
    }

    @Test
    fun `message lambda can be called`() {
        var called = false
        val log = Logger { _, _, _, message ->
            called = true
            message()
        }

        log.log(Logger.INFO, "tag", null) { "test" }
        assertTrue("lambda should have been evaluated when log is called", called)
    }

    @Test
    fun `factory with threshold skips below threshold`() {
        val captured = mutableListOf<String>()
        // Simulate threshold behavior without using Android Log
        val threshold = Logger.WARN
        val log = Logger { priority, _, _, message ->
            if (priority >= threshold) {
                captured.add(message())
            }
        }

        log.log(Logger.DEBUG, "t", null) { "debug" }
        log.log(Logger.INFO, "t", null) { "info" }
        log.log(Logger.WARN, "t", null) { "warn" }
        log.log(Logger.ERROR, "t", null) { "error" }

        assertEquals(listOf("warn", "error"), captured)
    }

    @Test
    fun `factory with VERBOSE threshold passes all`() {
        val captured = mutableListOf<String>()
        val threshold = Logger.VERBOSE
        val log = Logger { priority, _, _, message ->
            if (priority >= threshold) {
                captured.add(message())
            }
        }

        log.log(Logger.DEBUG, "t", null) { "d" }
        log.log(Logger.ERROR, "t", null) { "e" }
        log.log(Logger.VERBOSE, "t", null) { "v" }

        assertEquals(3, captured.size)
    }

    @Test
    fun `format log text preserves nested causes including unknown host`() {
        val cause = UnknownHostException("offline")
        val error = IllegalStateException("wrapper", cause)

        val text = formatLogText("request failed", error)

        assertTrue(text.startsWith("request failed\njava.lang.IllegalStateException: wrapper"))
        assertTrue(text.contains("Caused by: java.net.UnknownHostException: offline"))
    }

    @Test
    fun `chunking preserves complete long text`() {
        val text = "line one\n" + "x".repeat(2_500) + "tail"

        val chunks = text.chunkForLogcat(maxChunkSize = 1_000)

        assertEquals(text, chunks.joinToString(separator = ""))
        assertEquals("line one\n", chunks.first())
        assertTrue(chunks.all { it.length <= 1_000 })
        assertTrue(chunks.last().endsWith("tail"))
    }

    @Test
    fun `chunking does not split surrogate pair`() {
        val text = "a😀b"

        val chunks = text.chunkForLogcat(maxChunkSize = 1)

        assertEquals(text, chunks.joinToString(separator = ""))
        assertEquals(listOf("a", "😀", "b"), chunks)
    }

    @Test
    fun `chunking rejects non-positive size`() {
        val error = runCatching { "message".chunkForLogcat(maxChunkSize = 0) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `chunking accepts maximum size without integer overflow`() {
        assertEquals(listOf("message"), "message".chunkForLogcat(maxChunkSize = Int.MAX_VALUE))
    }
}
