# groupHandle 单协程瓶颈分析与解决方案

## 问题描述

`InternalExtensions.kt` 中的 `groupHandle` 函数存在一个架构性瓶颈：**单协程路由阻塞**。

### 根因

`groupHandle` 内部使用 `flow {}` builder 构建一个冷流，在这个 builder 内部只有一个协程运行 `collect` 循环：

```kotlin
internal fun <I : Mvi.Intent, R> Flow<I>.groupHandle(...): Flow<Flow<R>> = flow {
    val activeChannels = linkedMapOf<Any, Channel<I>>()
    // ...
    try {
        collect { intent ->              // ← 只有 1 个协程
            val tag = tagSelector(intent)
            // ... 获取或创建 channel ...
            channel.send(intent)          // ← 如果这个 channel 满了，send 挂起
        }                                 // ← 整个路由协程阻塞，所有组停摆
    } catch (t: Throwable) { ... }
}
```

`Channel.send()` 是一个挂起函数：当目标 Channel 的缓冲区满（即下游 handler 消费速度跟不上），它会 **suspend 当前协程**。这个当前协程恰恰是所有组共用的"路由器"——它阻塞了，所有组的 Intent 路由都阻塞，无论其他组的 Channel 是否空闲。

### 影响链路

```
下游 handler A 慢 (Group A)
    → Channel A 满
        → channelA.send(intent) 挂起
            → collect 协程阻塞
                → Channel B/C/D 虽有空位也无法接收 Intent
                    → 组 B/C/D 被迫饿死
```

### 现有缓解措施（不治本）

| 措施 | 效果 | 代价 |
|------|------|------|
| 调大 `groupChannelCapacity`（默认 64） | 延后碰壁点 | 不解决根本问题 |
| 使用 `Channel.UNLIMITED` | 永不阻塞 | 无界内存 → OOM 风险 |
| 使用 `Channel.RENDEZVOUS` | 零缓冲 | 稍有慢 handler 就阻塞 |

---

## 方案对比总览

| 方案 | 实现成本 | 丢 Intent | 背压语义 | 组间隔离 | 额外运行时开销 |
|------|----------|-----------|----------|----------|---------------|
| A: `trySend` + 丢帧 | 1 行 | ✅ 丢 | 无 | ✅ 完全 | 零 |
| **B: `channelFlow` + 按组发件协程** | ~20 行 | ❌ 不丢 | ✅ 保留 | ✅ 完全 | 仅在背压时 |
| C: 按组独立协程流水线 | ~50 行 + 测试 | 可配置 | 可配置 | ✅ 完全 | 每组一个协程 |

---

## 方案 A：`trySend` + 丢帧回退

### 思路

将阻塞的 `channel.send(intent)` 替换为非阻塞的 `channel.trySend(intent)`。当 Channel 满时直接丢弃 Intent 并记日志。

### 改动

```kotlin
// line 205 修改前
channel.send(intent)

// line 205 修改后
val result = channel.trySend(intent)
if (result.isFailure) {
    logger.w(TAG) {
        "Intent dropped for group ${tag.tagLabel}: channel full (capacity=${channel.capacity})"
    }
}
```

### 原理

`trySend` 在 Channel 满时立即返回 `ChannelResult.failure`（原 `isFailure`），不会 suspend 当前协程。路由协程始终可以继续处理下一个 Intent。

```
路由协程: trySend(A) → trySend(B) → trySend(A) → ...
                                  ║
                    A满 → 记日志 → 继续下一帧
```

### 优缺点

**优点：**
- 改动极小：一行代码
- 零运行时开销：`trySend` 与 `send` 在不满时开销相同
- 组间完全隔离：路由协程永不阻塞

**缺点：**
- 丢 Intent：当前 Channel 满时，新到达的 Intent 被丢弃且不会重试
- 调用方无感知：`dispatch()` 之前已经返回 `Submitted`，丢弃发生在管道内部

### 适用场景

Intent 可丢弃的场景：
- 分析/埋点事件
- 冗余刷新请求（UI 已有最新数据时）
- 实时性要求高的轮询数据

不适用：
- 表单提交、支付、写操作等不可丢的操作

---

## 方案 B：`channelFlow` + 按组发件协程（推荐）

### 思路

将 `flow {}` 改为 `channelFlow {}`，获得可启动协程的 `CoroutineScope`。路由协程使用 `trySend` 非阻塞分发，当某个组 Channel 满时（`trySend` 失败），为该组**按需启动一个专用发件协程**处理阻塞 `send`。

### 完整实现

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <I : Mvi.Intent, R> Flow<I>.groupHandle(
    config: HybridStrategyConfig,
    tagSelector: (I) -> Any,
    handler: Flow<I>.(tag: Any) -> Flow<R>,
): Flow<Flow<R>> = channelFlow {
    val activeChannels = linkedMapOf<Any, Channel<I>>()
    // 跟踪每个组是否有正在排队的发件协程
    val pendingSenders = mutableMapOf<Any, Job>()
    var cause: Throwable? = null
    var nextWarningThreshold = config.groupCountWarningThreshold

    fun warnIfGroupCountHigh(tag: Any) {
        if (nextWarningThreshold == Int.MAX_VALUE) return
        val count = activeChannels.size
        if (count < nextWarningThreshold) return
        logger.w(TAG) {
            "groupHandle active groups reached $count " +
                "(threshold=$nextWarningThreshold, openedTag=${tag.tagLabel})."
        }
        nextWarningThreshold = if (nextWarningThreshold <= Int.MAX_VALUE / 2) {
            nextWarningThreshold * 2
        } else {
            Int.MAX_VALUE
        }
    }

    suspend fun openChannel(tag: Any): Channel<I> {
        val channel = Channel<I>(config.groupChannelCapacity)
        activeChannels[tag] = channel
        emit(channel.consumeAsFlow().handler(tag))
        warnIfGroupCountHigh(tag)
        return channel
    }

    try {
        upstream.collect { intent ->
            val tag = tagSelector(intent)
            val existingChannel = activeChannels[tag]
            val channel = if (existingChannel == null || existingChannel.isClosedForSend) {
                if (existingChannel != null) {
                    activeChannels.remove(tag)
                    logger.w(TAG) { "Stale channel detected for group ${tag.tagLabel}, reopening." }
                }
                openChannel(tag)
            } else {
                existingChannel
            }

            val result = channel.trySend(intent)
            if (result.isFailure) {
                // Channel 满 → 启动专用发件协程
                val sender = pendingSenders[tag]
                if (sender == null || sender.isCompleted) {
                    pendingSenders[tag] = launch {
                        channel.send(intent)
                    }
                }
            }
        }
    } catch (t: Throwable) {
        logger.e(TAG, t) { "groupHandle failed, upstream will be cancelled" }
        cause = t
        throw t
    } finally {
        pendingSenders.values.forEach { it.cancel() }
        val channels = activeChannels.values.toList()
        activeChannels.clear()
        channels.forEach { it.close(cause) }
    }
}
```

### 原理

```
正常负载 (trySend 成功):
  路由协程: trySend(A) → trySend(B) → trySend(A) → ...
                                          ║
                                    A 有空位，秒回
           ──────────────────────────────────────────

背压场景 (trySend 失败):
  路由协程: trySend(A) → trySend(B) → trySend(A) → ...
                              ║
                        A 满 → launch { channel.send(intent) }
                                           ║
                                     发件协程 A 挂起等待
                                     A 恢复后自动结束
           ──────────────────────────────────────────
           组 B 全程不受影响
```

### 关键性质

| 性质 | 说明 |
|------|------|
| **正常负载零开销** | `trySend` 不满时不创建协程，性能等同于原 `send` |
| **组内顺序保持** | Channel 的 FIFO 等待队列保证同一组的 `send` 顺序 |
| **协程数量有上界** | 最多 = 单个组 Channel 满期间到达的 Intent 数，channel 恢复后即消除 |
| **结构化并发** | `channelFlow` 的 scope 在完成时自动取消所有 pending sender |
| **零公共 API 变更** | 只改 `groupHandle` 内部实现，签名不变 |

### 优缺点

**优点：**
- 保留背压语义：不丢 Intent，Channel 满时仍会反压，只是反压效果被隔离到单组
- 组间完全隔离：一个组的慢 handler 不影响其他组
- 自适应开销：无背压时零额外开销，有背压时才产生协程

**缺点：**
- `flow {}` → `channelFlow {}` 改变冷流语义（`channelFlow` 是 eager 的，emit 会等待下游就绪）
- `upstream.collect` 需要显式引用上游 Flow（不能直接调用 `collect`）
- `pendingSenders` 的 `Job` 检查需要确保正确

---

## 方案 C：按组独立协程流水线

### 思路

彻底重构 `groupHandle`：每个组的 Channel 由一个**独立的消费协程**管理，路由协程只做 `trySend` 分发。消费者之间完全物理隔离。

### 实现

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <I : Mvi.Intent, R> Flow<I>.groupHandle(
    config: HybridStrategyConfig,
    tagSelector: (I) -> Any,
    handler: Flow<I>.(tag: Any) -> Flow<R>,
): Flow<Flow<R>> = channelFlow {
    data class Group(
        val channel: Channel<I>,
        val job: Job,
    )

    val groups = mutableMapOf<Any, Group>()
    var cause: Throwable? = null

    // 路由协程：只做 tag 分配和非阻塞分发
    val router = launch {
        upstream.collect { intent ->
            val tag = tagSelector(intent)
            val group = groups.getOrPut(tag) {
                val channel = Channel<I>(config.groupChannelCapacity)
                // 每组独立消费协程
                val job = launch {
                    channel.consumeAsFlow()
                        .handler(tag)
                        .collect { change ->
                            send(change)
                        }
                }
                Group(channel, job)
            }
            group.channel.trySend(intent)
                .onFailure {
                    logger.w(TAG) {
                        "Group ${tag.tagLabel} channel full " +
                            "(capacity=${group.channel.capacity}), intent dropped"
                    }
                }
        }
    }

    // 等待路由协程完成
    try {
        router.join()
    } catch (t: Throwable) {
        cause = t
        throw t
    } finally {
        groups.values.forEach { group ->
            group.job.cancel()
            group.channel.close(cause)
        }
    }
}
```

### 数据流

```
        ┌─ → [组 A Channel] → [组 A 消费协程] → send(change) ─┐
上游 Flow ┼─ → [组 B Channel] → [组 B 消费协程] → send(change) ─┼─ → 下游
        └─ → [组 C Channel] → [组 C 消费协程] → send(change) ─┘
             ║
        组间完全独立

路由协程: trySend → 永不阻塞
消费协程: 每组独立 → 背压只影响本组
```

### 优缺点

**优点：**
- 完全物理隔离：各组有独立的协程、独立的 Channel、独立的调度
- 路由极轻：路由协程只做 tag 分配和 `trySend`，不做任何挂起操作
- 结构清晰：代码组织直观，每组的生命周期独立管理

**缺点：**
- 改动较大：重构整个 `groupHandle`，需要重写现有测试
- fan-out 开销：每组一个协程 + 一个 Channel，组数多时有额外内存开销
- `trySend` 失败即丢弃：不保留背压到上游的语义；可以通过增加各组独立的缓冲来缓解

### 可配置丢帧策略

方案 C 可以在 `HybridStrategyConfig` 中扩展一个 `onOverflow` 策略：

```kotlin
class HybridStrategyConfig(
    val groupChannelCapacity: Int = Channel.BUFFERED,
    val groupCountWarningThreshold: Int = 256,
    val onGroupOverflow: OverflowStrategy = OverflowStrategy.DROP,
) {
    enum class OverflowStrategy {
        DROP,      // 丢弃新 Intent（默认）
        BLOCK,     // 阻塞路由协程（等同于原始行为）
        ENQUEUE,   // 入队到无界队列（不丢但可能 OOM）
    }
}
```

---

## 总结与建议

### 推荐路径

```
方案 B ──── 当前最实用的修复

├── 改动量：~20 行（只改 InternalExtensions.kt）
├── 语义：保留背压、不丢 Intent
├── 性能：无背压时零额外开销
└── 风险：低 —— channelFlow 语义差异可通过测试覆盖
```

### 不推荐的方案

- **单纯加大 buffer**（现有缓解措施）：延后问题而非解决问题
- **`Channel.UNLIMITED`**：把确定性背压问题变成非确定性 OOM 问题
- **方案 A 用于不可丢操作**：Intent 丢失在用户不可察觉的地方造成逻辑错误

### 升级路径

如果未来需要更多灵活性，可以在方案 B 的基础上：

1. 稳定运行一段时间，验证 `channelFlow` 语义兼容性
2. 考虑将方案 C 的 `OverflowStrategy` 引入 `HybridStrategyConfig`
3. 为高级用户提供 `GroupTagSelector` 级别的 buffer 配置

---

*文档生成时间: 2026-06-22*
*基于 commit: d853dbb (release/1.4.1)*
