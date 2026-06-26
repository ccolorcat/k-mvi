# groupHandle 跨组背压分析

## 审计结论

`InternalExtensions.kt` 中的 `groupHandle` 确实存在**单协程路由导致的跨组背压**：所有 group 的 intent 都由同一个 `collect` 协程分发，某个 group 的 channel 满时，`channel.send(intent)` 会挂起该路由协程，进而暂时阻塞其他 group 的路由。

这属于**延迟 / 背压扩大风险**，不是直接运行时错误，也不是静默数据丢失。当前实现保留了背压语义、同组顺序和简单生命周期管理；对轻量 MVI 库是合理的保守实现。除非有明确的高吞吐瓶颈证据，否则不建议立即重构。

本文件早期版本给出的“方案 B：`channelFlow` + 按组发件协程”不能作为推荐实现使用：示例代码存在语义漏洞，会在拥塞期间静默丢 intent，且未覆盖取消、顺序和错误传播边界。

---

## 当前实现事实

`groupHandle` 当前形态：

```kotlin
internal fun <I : Mvi.Intent, R> Flow<I>.groupHandle(...): Flow<Flow<R>> = flow {
    val activeChannels = linkedMapOf<Any, Channel<I>>()

    collect { intent ->
        val tag = tagSelector(intent)
        val channel = activeChannels[tag] ?: openChannel(tag)
        channel.send(intent)
    }
}
```

关键点：

- `collect { ... }` 顺序执行，只有一个路由协程。
- 每个 tag 对应一个 `Channel<I>`，同 tag intent 进入同一个 group pipeline。
- `channel.send(intent)` 保留背压：目标 channel 满时挂起，而不是丢弃。
- `activeChannels` 只在单协程中访问，避免并发 map 维护成本。
- 上游结束或失败时，剩余 group channel 会被关闭并传播失败原因。

---

## 触发条件

跨组背压需要同时满足：

1. 某个 group 的内部 channel 已满。
2. 该 group 的下游 handler 持续慢于上游 intent 到达速度。
3. 路由协程下一次正好要向这个满 channel `send`。

触发后链路：

```text
Group A handler 慢
    -> Channel A 满
        -> router 调用 channelA.send(intent) 挂起
            -> router 暂停处理后续 upstream intent
                -> Group B/C 即使 channel 有空位，也暂时收不到新 intent
```

这是 head-of-line blocking。它扩大了慢 group 的背压影响范围，但不会凭空丢 intent。

---

## 影响边界

### 默认配置会吸收普通 burst

`HybridStrategyConfig.groupChannelCapacity` 默认是 `Channel.BUFFERED`。普通 UI 点击、少量并发刷新、Dashboard 示例级别的 burst 通常会被 group channel 缓冲吸收，不会稳定触发该瓶颈。

### 背压可能继续传导到 dispatch 入口

如果慢 group 长时间阻塞路由，入口 intent queue 可能继续堆积。达到入口容量后，`dispatch()` 会返回 `DispatchResult.Full`。这是显式背压，不是内部静默丢弃。

### `Channel.UNLIMITED` 不是免费修复

`Channel.UNLIMITED` 可以避免 group channel 满导致的 router 挂起，但会把确定性背压改成无界内存增长。只应在 intent 速率有外部上界、handler 延迟可控、且业务明确接受内存风险时使用。

### `Channel.RENDEZVOUS` 会放大该问题

`Channel.RENDEZVOUS` 没有缓冲。只要对应 group 当前没有 ready receiver，`send` 就会挂起，更容易把单 group 背压传导到所有 group。

### 高基数 tag 是独立风险

每个不同 tag 会保留一个 active channel，直到 upstream 完成、失败，或检测到旧 channel stale 后替换。高基数 tag（用户 ID、资源 ID、搜索词、时间戳）会增加活跃 group 数；这是资源占用风险，不等同于单协程路由瓶颈。

---

## 不建议直接采用的方案

### 方案 A：`trySend` + 内部丢弃

把：

```kotlin
channel.send(intent)
```

改成：

```kotlin
channel.trySend(intent)
```

可以避免 router 挂起，但会改变语义：intent 已经在更早的 `dispatch()` 返回 `Submitted`，之后却在内部 group channel 满时被静默丢弃。对表单提交、支付、写操作、导航等不可丢 intent，这是错误语义。

如果未来要支持 drop，应通过显式配置暴露，例如 `onGroupOverflow = DROP_LATEST / DROP_OLDEST / SUSPEND`，并在文档中说明 `DispatchResult.Submitted` 只代表进入入口队列，不代表最终进入 group。

### 方案 B：`channelFlow` + 单个 pending sender

旧文档推荐过如下思路：router 先 `trySend`，失败时为该 group 启动一个 `launch { channel.send(intent) }`。

该示例不能直接使用，原因：

1. 示例代码使用 `upstream.collect`，但函数签名中没有 `upstream` 变量；若作为实现，需要显式保存外层 receiver 或改写为可编译形式。
2. `pendingSenders[tag]` 只允许每个 tag 同时存在一个 sender。若 channel 仍然满，第二个失败的 `trySend` 会发现 sender 未完成，然后什么也不做，导致 intent 静默丢失。
3. 若改成“每次失败都 launch 一个 sender”来避免丢失，需要重新证明同组顺序、取消传播、异常传播、stale channel reopening、upstream 完成时 pending sender drain 等语义。
4. `channelFlow` 本身仍然是 cold flow；真正改变的是内部并发模型和 channel 边界，不是“变成 eager”。因此不能把语义差异简化为 eager/lazy。

结论：该方案最多是探索方向，不是可落地推荐。

### 方案 C：每组独立消费协程

每个 group 一个协程可以做到物理隔离，但它不是当前 `Flow<Flow<R>>` 形态的等价小改：

- 如果每组协程直接向下游 `send(change)`，函数签名会从 `Flow<Flow<R>>` 变成接近 `Flow<R>`，需要重构调用方。
- 如果仍要返回 `Flow<Flow<R>>`，就要保留外层 group flow 语义，同时额外管理每组协程生命周期。
- 使用 `trySend` 分发仍会遇到 overflow 语义：丢弃、挂起、无限排队三者必须显式选择。
- 每组一个协程 + 一个 channel 对高基数 tag 有更高内存和调度成本。

该方案只有在库明确要优化高吞吐 HYBRID 场景时才值得推进。

---

## 推荐处理

### 当前版本

保持当前实现，继续文档化该设计取舍：

- 保留背压，不在内部静默丢 intent。
- 保留同组顺序。
- 保持 `activeChannels` 单协程维护，生命周期简单。
- 通过 `groupChannelCapacity` 给高吞吐用户调参空间。
- 通过 `groupCountWarningThreshold` 诊断高基数 tag。

### 按 tag 配置容量的裁定

当前不新增 per-tag capacity API。`InternalExtensions.kt` 的 `openChannel(tag)` 继续使用 `Channel(config.groupChannelCapacity)`，`HybridStrategyConfig` 继续只暴露全局 `groupChannelCapacity`；目前没有已确认的生产瓶颈证明统一 `groupChannelCapacity` 不够用。

Per-tag capacity 只能推迟某个热门 group 的 channel 填满，不能消除 `groupHandle` 的单路由协程 head-of-line blocking；它是调参能力，不是隔离修复。

如果未来有证据需要该能力，选择“按 tag 返回 capacity”的受限接口，而不是“按 tag 创建 Channel”的工厂。推荐未来接口形态如下（仅示例，不在当前版本实现）：

```kotlin
fun interface GroupChannelCapacitySelector {
    fun selectCapacity(tag: Any, defaultCapacity: Int): Int
}
```

未来实现该 selector 时，必须复用 `core/src/main/java/cc/colorcat/mvi/internal/InternalUtils.kt` 的 `requireSupportedChannelConfig` 对 selector 返回值做运行时校验；默认实现必须返回当前 `HybridStrategyConfig.groupChannelCapacity`。

不选择 channel factory 的原因：它暴露 `Channel<I>` 所有权，允许用户返回已关闭 / 共享 / 生命周期不受 `groupHandle` 管理的 channel，绕过现有 capacity 校验，并把 close/error propagation 责任泄漏给用户；这超过“不同 tag 需要不同容量”的需求。

### 业务侧调优

按场景选择：

| 场景 | 建议 |
|------|------|
| 普通 UI intent | 使用默认 `Channel.BUFFERED` |
| 同组短时 burst 明显 | 增大 `groupChannelCapacity` |
| 不同 tag 的流量差异明显，但没有生产瓶颈证据 | 先调整全局 `groupChannelCapacity` 或重新设计 `GroupTagSelector`；不新增 API |
| 必须尽早暴露慢 handler | 使用较小 buffer 或 `Channel.RENDEZVOUS` |
| intent 速率有严格外部上界 | 可评估 `Channel.UNLIMITED` |
| 高基数 tag 但不需要逐值顺序 | 使用 bucketed tag，例如 `"user"` / `"search"` |

### 未来若要修复

先补测试和基准，再改实现。最低测试集：

1. 一个 group channel 满时，其他 group 是否按新设计继续处理。
2. 同一 tag 的 intent 在背压下仍严格按输入顺序处理。
3. 拥塞期间不丢 intent，或按显式 overflow 策略可观测地丢弃。
4. upstream 失败时，所有 active channel 和 pending sender 都收到取消 / close cause。
5. downstream 取消某个 inner flow 后，stale channel 能正确重开。
6. 高基数 tag 的 warning 行为不退化。
7. 与 `flattenMerge(Int.MAX_VALUE)` 的交互保持现有 HYBRID 语义。

可行演进路径：

1. 先增加覆盖当前行为的回归测试，锁定背压、顺序、取消语义。
2. 如确有高吞吐需求，再设计显式 `onGroupOverflow` 策略。
3. 最后评估 `channelFlow` / per-group sender / per-group pipeline 的实现成本和性能收益。

---

## 结论

`groupHandle` 的单协程路由是一个真实的跨组背压限制，但当前最安全的处理不是套用旧文档中的“方案 B”。在没有生产证据和语义测试前，维持现状更稳妥；如果未来要优化，必须把 overflow、顺序、取消、错误传播作为一组设计一起处理。

---

*文档更新日期: 2026-06-26*
