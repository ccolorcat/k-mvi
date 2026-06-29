package cc.colorcat.mvi

import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

class FatalErrorHandlerTest {
    @Rule
    @JvmField
    val testLog: TestRule = TestLogger()

    @Test
    fun `Rethrow throws original error`() {
        val error = IllegalStateException("fatal")

        val thrown = assertThrows(IllegalStateException::class.java) {
            FatalErrorHandler.Rethrow.handle(error)
        }

        assertSame(error, thrown)
    }

    @Test
    fun `custom fatal handler can record before throwing`() {
        var recorded: Throwable? = null
        val error = IllegalStateException("fatal")
        val handler = FatalErrorHandler { throwable ->
            recorded = throwable
            throw throwable
        }

        val thrown = assertThrows(IllegalStateException::class.java) {
            handler.handle(error)
        }

        assertSame(error, recorded)
        assertSame(error, thrown)
    }
}
