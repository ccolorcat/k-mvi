package cc.colorcat.mvi.internal

import cc.colorcat.mvi.Logger
import cc.colorcat.mvi.TestLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.net.UnknownHostException

class LoggerExtensionsTest {

    @Rule @JvmField val testLog: TestRule = TestLogger()

    @Test
    fun `v logs with VERBOSE priority`() {
        var capturedPriority = -1
        val log = Logger { priority, _, _, _ -> capturedPriority = priority }
        log.v("tag") { "msg" }
        assertEquals(Logger.VERBOSE, capturedPriority)
    }

    @Test
    fun `d logs with DEBUG priority`() {
        var capturedPriority = -1
        val log = Logger { priority, _, _, _ -> capturedPriority = priority }
        log.d("tag") { "msg" }
        assertEquals(Logger.DEBUG, capturedPriority)
    }

    @Test
    fun `i logs with INFO priority`() {
        var capturedPriority = -1
        val log = Logger { priority, _, _, _ -> capturedPriority = priority }
        log.i("tag") { "msg" }
        assertEquals(Logger.INFO, capturedPriority)
    }

    @Test
    fun `w logs with WARN priority`() {
        var capturedPriority = -1
        val log = Logger { priority, _, _, _ -> capturedPriority = priority }
        log.w("tag") { "msg" }
        assertEquals(Logger.WARN, capturedPriority)
    }

    @Test
    fun `e logs with ERROR priority`() {
        var capturedPriority = -1
        val log = Logger { priority, _, _, _ -> capturedPriority = priority }
        log.e("tag") { "msg" }
        assertEquals(Logger.ERROR, capturedPriority)
    }

    @Test
    fun `e with throwable logs ERROR priority and captures error`() {
        var capturedPriority = -1
        var capturedError: Throwable? = null
        val log = Logger { priority, _, cause, _ ->
            capturedPriority = priority
            capturedError = cause
        }
        val exception = RuntimeException("test")
        log.e("tag", cause = exception) { "msg" }
        assertEquals(Logger.ERROR, capturedPriority)
        assertSame(exception, capturedError)
    }

    @Test
    fun `assert logs with ASSERT priority`() {
        var capturedPriority = -1
        val log = Logger { priority, _, _, _ -> capturedPriority = priority }
        log.assert("tag") { "msg" }
        assertEquals(Logger.ASSERT, capturedPriority)
    }

    @Test
    fun `extension functions pass tag correctly`() {
        var capturedTag: String? = null
        val log = Logger { _, tag, _, _ -> capturedTag = tag }

        log.v("v-tag") { "" }
        assertEquals("v-tag", capturedTag)

        log.d("d-tag") { "" }
        assertEquals("d-tag", capturedTag)

        log.i("i-tag") { "" }
        assertEquals("i-tag", capturedTag)

        log.w("w-tag") { "" }
        assertEquals("w-tag", capturedTag)

        log.e("e-tag") { "" }
        assertEquals("e-tag", capturedTag)

        log.assert("assert-tag") { "" }
        assertEquals("assert-tag", capturedTag)
    }

    @Test
    fun `message lambda is not evaluated when logger ignores it`() {
        val log = Logger { _, _, _, _ -> }

        var called = false
        log.v("tag") { called = true; "msg" }

        assertFalse(called)
    }

    @Test
    fun `e without throwable passes null error`() {
        var capturedError: Throwable? = RuntimeException("not null")
        val log = Logger { _, _, cause, _ -> capturedError = cause }
        log.e("tag") { "msg" }
        assertNull(capturedError)
    }

    @Test
    fun `assert passes null error`() {
        var capturedError: Throwable? = RuntimeException("not null")
        val log = Logger { _, _, cause, _ -> capturedError = cause }
        log.assert("tag") { "msg" }
        assertNull(capturedError)
    }

    @Test
    fun `stack trace string includes unknown host cause`() {
        val cause = UnknownHostException("offline")
        val exception = RuntimeException("wrapper", cause)

        val stackTrace = exception.getStackTraceString()

        assertTrue(stackTrace.contains("java.lang.RuntimeException: wrapper"))
        assertTrue(stackTrace.contains("Caused by: java.net.UnknownHostException: offline"))
    }
}
