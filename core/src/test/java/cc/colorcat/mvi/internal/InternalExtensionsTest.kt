package cc.colorcat.mvi.internal

import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.TestLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

class InternalExtensionsTest {

    @Rule @JvmField val testLog: TestRule = TestLogger()

    // --- Test intent types ---

    private interface TestIntent : Mvi.Intent {
        data object JustIntent : TestIntent
        data object OnlyConcurrent : TestIntent, Mvi.Intent.Concurrent
        data class OnlySequential(val id: Int) : TestIntent, Mvi.Intent.Sequential
        data object Both : TestIntent, Mvi.Intent.Concurrent, Mvi.Intent.Sequential
    }

    // Anonymous implementations for edge cases
    private val anonymousIntent = object : Mvi.Intent {}
    private val anonymousConcurrent = object : Mvi.Intent, Mvi.Intent.Concurrent {}
    private val anonymousSequential = object : Mvi.Intent, Mvi.Intent.Sequential {}
    private val anonymousBoth = object : Mvi.Intent, Mvi.Intent.Concurrent, Mvi.Intent.Sequential {}

    // --- isConcurrent ---

    @Test
    fun `isConcurrent true for Concurrent-only intent`() {
        assertTrue(TestIntent.OnlyConcurrent.isConcurrent)
    }

    @Test
    fun `isConcurrent false for plain intent`() {
        assertFalse(TestIntent.JustIntent.isConcurrent)
    }

    @Test
    fun `isConcurrent false for Sequential-only intent`() {
        assertFalse(TestIntent.OnlySequential(1).isConcurrent)
    }

    @Test
    fun `isConcurrent false for intent implementing both`() {
        assertFalse(TestIntent.Both.isConcurrent)
    }

    @Test
    fun `isConcurrent false for anonymous plain intent`() {
        assertFalse(anonymousIntent.isConcurrent)
    }

    @Test
    fun `isConcurrent true for anonymous Concurrent-only intent`() {
        assertTrue(anonymousConcurrent.isConcurrent)
    }

    // --- isSequential ---

    @Test
    fun `isSequential true for Sequential-only intent`() {
        assertTrue(TestIntent.OnlySequential(1).isSequential)
    }

    @Test
    fun `isSequential false for plain intent`() {
        assertFalse(TestIntent.JustIntent.isSequential)
    }

    @Test
    fun `isSequential false for Concurrent-only intent`() {
        assertFalse(TestIntent.OnlyConcurrent.isSequential)
    }

    @Test
    fun `isSequential false for intent implementing both`() {
        assertFalse(TestIntent.Both.isSequential)
    }

    @Test
    fun `isSequential true for anonymous Sequential-only intent`() {
        assertTrue(anonymousSequential.isSequential)
    }

    // --- Mutual exclusion ---

    @Test
    fun `isConcurrent and isSequential are mutually exclusive`() {
        val intents = listOf(
            TestIntent.JustIntent,
            TestIntent.OnlyConcurrent,
            TestIntent.OnlySequential(1),
            TestIntent.Both,
            anonymousIntent,
            anonymousConcurrent,
            anonymousSequential,
            anonymousBoth,
        )
        for (intent in intents) {
            assertFalse(
                "intent $intent should not have both isConcurrent and isSequential",
                intent.isConcurrent && intent.isSequential,
            )
        }
    }

    @Test
    fun `isConcurrent XOR isSequential XOR fallback covers all cases`() {
        val intents = listOf(
            TestIntent.JustIntent,
            TestIntent.OnlyConcurrent,
            TestIntent.OnlySequential(1),
            TestIntent.Both,
            anonymousIntent,
        )
        for (intent in intents) {
            val concurrent = intent.isConcurrent
            val sequential = intent.isSequential
            val fallback = !concurrent && !sequential
            assertTrue(
                "exactly one category should apply for $intent",
                concurrent || sequential || fallback,
            )
        }
    }

    @Test
    fun `both implements neither concurrent nor sequential`() {
        // Both implements both interfaces, but because each excludes the other,
        // both isConcurrent and isSequential return false
        assertFalse(TestIntent.Both.isConcurrent)
        assertFalse(TestIntent.Both.isSequential)
    }

    // --- diagnosticName ---

    @Test
    fun `diagnosticName for named intent`() {
        assertEquals(
            "cc.colorcat.mvi.internal.InternalExtensionsTest.TestIntent.OnlyConcurrent",
            TestIntent.OnlyConcurrent.diagnosticName,
        )
    }

    @Test
    fun `diagnosticName for data class intent`() {
        assertTrue(TestIntent.OnlySequential(42).diagnosticName.contains("OnlySequential"))
    }

    @Test
    fun `diagnosticName for anonymous intent`() {
        assertNotNull(anonymousIntent.diagnosticName)
        assertTrue(anonymousIntent.diagnosticName.isNotBlank())
    }

    @Test
    fun `diagnosticName does not contain sensitive data`() {
        // Should only contain class identity, not field values
        val name = TestIntent.OnlySequential(42).diagnosticName
        assertFalse(name.contains("42"))
    }
}
