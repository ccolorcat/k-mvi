package cc.colorcat.mvi

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A JUnit [TestRule] that logs test lifecycle events (start, success, failure, skipped)
 * to standard output. Provides clear visibility into which tests are running and their results.
 *
 * Usage:
 * ```kotlin
 * class MyTest {
 *     @Rule @JvmField val testLog = TestLogger()
 *
 *     @Test
 *     fun `my test case`() {
 *         // test body
 *     }
 * }
 * ```
 */
class TestLogger : TestRule {

    /**
     * Formats the test description into a human-readable name.
     * Uses the method name directly (supports backtick names in Kotlin).
     */
    private fun displayName(description: Description): String =
        description.methodName

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val name = displayName(description)
                println("[TEST START] $name")
                try {
                    base.evaluate()
                    println("[TEST PASS ] $name")
                } catch (e: AssertionError) {
                    println("[TEST FAIL ] $name — ${e.message}")
                    throw e
                } catch (e: Exception) {
                    println("[TEST ERROR] $name — ${e::class.simpleName}: ${e.message}")
                    throw e
                }
            }
        }
    }
}
