package cc.colorcat.mvi

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testFlow() = runBlocking {
        println("▶ Start Flow without buffer/conflate")
        testBackpressure(flowType = "default")

        println("\n▶ Start Flow with buffer()")
        testBackpressure(flowType = "buffer")

        println("\n▶ Start Flow with conflate()")
        testBackpressure(flowType = "conflate")
    }

    suspend fun testBackpressure(flowType: String) {
        flow {
            for (i in 1..10) {
                emit(i)
                println("🔵 Emitting $i at ${System.currentTimeMillis() % 100000}")
                delay(10) // 生产者每 10ms 发送一个数据
            }
        }
            .let { flow ->
                when (flowType) {
                    "buffer" -> flow.buffer()  // 启用缓冲区，避免 emit() 阻塞
                    "conflate" -> flow.conflate()  // 只保留最新数据，丢弃旧数据
                    else -> flow // 默认情况，emit() 可能会被 collect() 阻塞
                }
            }
            .collect { value ->
                delay(100) // 模拟耗时处理（消费者速度远慢于生产者）
                println("🟢 Collected $value at ${System.currentTimeMillis() % 100000}")
            }
    }
}
