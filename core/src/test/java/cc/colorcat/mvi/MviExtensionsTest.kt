package cc.colorcat.mvi

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

class MviExtensionsTest {

    @Rule @JvmField val testLog: TestRule = TestLogger()

    @Test
    fun `asSingleFlow emits the value`() = runBlocking {
        val result = "hello".asSingleFlow().single()
        assertEquals("hello", result)
    }

    @Test
    fun `asSingleFlow for integer`() = runBlocking {
        val result = 42.asSingleFlow().first()
        assertEquals(42, result)
    }

    @Test
    fun `asSingleFlow emits exactly one item`() = runBlocking {
        val results = "single".asSingleFlow().toList()
        assertEquals(1, results.size)
    }

    @Test
    fun `asSingleFlow for Mvi Intent`() = runBlocking {
        val intent = object : Mvi.Intent {}
        val result = intent.asSingleFlow().single()
        assertTrue(result === intent)
    }

    @Test
    fun `asSingleFlow for null value`() = runBlocking {
        val result = null.asSingleFlow().single()
        assertEquals(null, result)
    }

    @Test
    fun `asSingleFlow for data class`() = runBlocking {
        data class TestData(val x: Int, val y: String)
        val data = TestData(1, "test")
        val result = data.asSingleFlow().single()
        assertEquals(TestData(1, "test"), result)
    }

    @Test
    fun `asSingleFlow for list`() = runBlocking {
        val list = listOf(1, 2, 3)
        val result = list.asSingleFlow().single()
        assertEquals(listOf(1, 2, 3), result)
    }

    @Test
    fun `asSingleFlow flow completes after emission`() = runBlocking {
        val results = mutableListOf<Int>()
        99.asSingleFlow().collect { results.add(it) }
        assertEquals(listOf(99), results)
    }
}
