# MVI Core Review 

严重（正确性 / 语义缺陷）

 ### 1. 任一 handler 抛异常会重启整条管线，静默丢弃其它 in-flight / 已入组的 intent

 证据 internal/ReactiveContractImpl.kt:209-214：

 ```kotlin
   intentsChannel.receiveAsFlow()
       .toPartialChange(transformer)      // flatMapMerge / flatMapConcat / groupHandle 都在这里面
       .retryWhen { cause, attempt -> retryPolicy(attempt, cause) }  // ← 位于 transformer 之上
       .scan(...) { ... }
 ```

 retryWhen 放在 transformer 上游。当某个 handler.handle(it) 的 Flow 抛出未捕获异常时：
 - flatMapMerge（CONCURRENT / HYBRID 并发组）会取消所有兄弟 inner flow，正在处理的其它 intent 全部中断；
 - HYBRID 下 groupHandle 的 finally 关闭所有分组 channel（InternalExtensions.kt:187-192），这些 channel 里已从 intentsChannel 取出、缓冲但尚未处理的
 intent（每组最多 groupChannelCapacity=64）被直接丢弃；
 - retryWhen 重新订阅整个上游，groupHandle/transformer 全部重建。

 结论：HYBRID 是默认策略，而它恰恰是丢失最严重的（in-flight + 各组缓冲）。CONCURRENT 丢失 ≤16 个在途 intent；SEQUENTIAL 丢 1 个。触发异常的那个 intent
 也不会重放（receiveAsFlow 已消费）。这属于跨 intent 干扰 + 静默数据丢失，违背 MVI 期望的 intent 隔离性。

 框架给出的“缓解”是文档要求“handler 内部自己 try-catch”——但这等于让 RetryPolicy 事实上不可用：一旦真的触发 retry 就意味着丢数据。

 建议：把错误隔离下沉到单个 intent 的处理内部，例如在 StrategyIntentTransformer 中对每个 handler.handle(it) 单独包
 retryWhen{}.catch{}（IntentTransformers.kt:170-172, 206-212），使一个 intent 的失败既不影响并发兄弟，也不清空分组缓冲。顶层 retryWhen 应仅用于 transformer
 自身骨架异常。

 ### 2. retryWhen 的 attempt 是 contract 生命周期全局计数，并非“每 intent”

 证据 ReactiveContractImpl.kt:211 + KMvi.kt:214-221（默认 attempt < 3）。

 retryWhen 的 attempt 只增不减、跨成功发射不重置（kotlinx 语义）。因此默认策略下，整个 contract 生命周期内累计第 4 次逃逸异常（无论来自哪个
 intent、间隔多久）就会 retryPolicy 返回 false → 进入 fatal。对一个网络时好时坏的会话，异常会随时间累积，最终必然越过阈值。

 AGENTS.md 声称“attempt is scoped per intent”与实现不符（按要求忽略文档，但此处影响的是运行语义）。RetryPolicy 签名 (attempt, cause)（KMvi.kt:51）也拿不到
 intent，无法做按类型的重试决策。

 建议：明确重试是“管线级”而非“intent 级”；若想要 per-intent 语义，须配合第 1 条把 retry 下沉到 handler 调用处，attempt 自然按 intent 重置，并把 intent 加入
 RetryPolicy 签名。

 ### 3. fatal 路径在 SupervisorJob 作用域内必然崩溃，"之后返回 Unavailable" 实际不可达

 证据 ReactiveContractImpl.kt:227-236 + FatalErrorHandler.kt:9-19：

 ```kotlin
   .catch { cause -> ...; intentsChannel.cancel(); fatalErrorHandler.handle(cause) }  // handle: Nothing，必抛
 ```

 snapshots 由 shareIn(scope, Eagerly) 在 viewModelScope（SupervisorJob + Main.immediate）里 launch 收集。catch 里重新抛出的异常 → 该 sharing 协程异常结束 → 在
 SupervisorJob 下不会取消 scope，但该异常无人接管，转交线程 uncaughtExceptionHandler → Android 崩溃。

 由于 FatalErrorHandler.handle 的返回类型是 Nothing（契约上禁止恢复），任何实现都会抛出，所以 fatal = 崩溃是必然的。文档中“fatal 之后 dispatch 返回
 Unavailable”的场景在 Android 上进程已崩溃、无从谈起。

 结合第 1、2 条：一次 PartialChange.apply 的开发期错误，或第 4 次逃逸 IOException，都会崩溃整个
 App。这是很强的默认行为，需要明确是否是刻意的“fail-loud”。若希望默认降级而非崩溃，FatalErrorHandler 不应约束为 Nothing。

 ───────────────────────────────────────────

 中等（设计权衡，多为已知/已文档化但值得复核）

 ### 4. 一次性 Event 搭载在可丢弃的 snapshot 缓冲上

 证据 ReactiveContractImpl.kt:235：buffer(64, DROP_OLDEST)，而 eventFlow = snapshots.mapNotNull { it.event }（:287）。

 State 用 StateFlow（取最新、可跳过中间值，合理）；但 Event 与 State 同处一个 Snapshot，在拥塞时携带 event 的旧 snapshot 会被 DROP_OLDEST
 丢掉，事件永久丢失。一次性事件容忍丢失可理解，但把“不可重放的关键副作用”耦合到一个会丢帧的缓冲上，是结构性风险。类 KDoc 已警示，但仍建议：让 event
 走独立的、不与 state 帧共享丢弃策略的通道。

 ### 5. HYBRID 单路由协程瓶颈

 证据 InternalExtensions.kt:158-179：collect { channel.send(intent) } 是单协程，任一组 channel 满则 send 挂起 → 阻塞所有组。已在 KDoc 详述并给了
 mitigation（调大容量 / UNLIMITED）。属可接受的设计取舍，但默认策略带此瓶颈，需知悉。

 ### 6. handler 查找为精确类匹配，无多态

 证据 IntentHandlers.kt:230-233：handlers[intent.javaClass]。父类/密封基类注册不覆盖子类。已文档化，是刻意取舍；对 sealed + data class
 的常见写法意味着每个具体类都要单独 register，否则落到 defaultHandler。可接受但属易踩点。

 ───────────────────────────────────────────

 轻微

 ### 7. debounceLeading 首帧哨兵依赖 System.nanoTime() >= 0

 MviExtensions.kt:141-145：var time = -windowNanos，首个事件 time - prev = now + windowNanos。Android 上 nanoTime() 为正、可用；但 Java 契约允许 nanoTime()
 返回负值，理论上首帧可能被吞或发生减法溢出。跨平台/极端下脆弱，建议用独立布尔 first 标志表达“首帧必发”。

 ### 8. 启动期订阅顺序依赖 Main.immediate 的同步启动

 snapshots(shareIn Eagerly, replay=0) 与 stateFlow(stateIn Eagerly)、eventFlow 均在构造期 launch。因 replay=0，若 snapshots 先于 stateFlow 收到快照会丢给
 stateFlow。实际在 viewModelScope（Main.immediate + DEFAULT start）下三者构造期同步订阅完成、且 intent 只能在构造后 dispatch，故安全；但这依赖具体
 dispatcher。若换用被 dispatched 的 scope（如某些测试/自定义 scope），存在丢首个状态的竞态。建议不要依赖此隐式时序，或对 snapshots 的启动/replay
 语义做显式保证。

 ### 9. configure 非原子 read-modify-write（KMvi.kt:181-184）——已文档化，单次主线程初始化的约束合理。

 ───────────────────────────────────────────

 结论

 - 类型体系（Mvi.Intent/State/Event/PartialChange/Snapshot）、Contract/ReactiveContract 读写分离、asContract 封装、DispatchResult
 语义、IntentQueueConfig/requireSupportedChannelConfig 校验、lifecycle 采集 DSL —— 这些设计清晰、实现正确。
 - receiveAsFlow（非 consumeAsFlow）以支持 retry 重订阅、scan 在 retryWhen 下游以跨重试保留累积状态、operator fusion 的 flowOn+buffer、onCompletion
 对“transformer 提前完成 = 僵尸 contract”的防御 —— 这些细节处理得当且有意为之。
 - 真正需要修的是错误处理管线的层级（第 1–3 条）：retryWhen 位于 transformer 上游导致“一个 handler 失败即重启全局、丢弃他组在途/缓冲 intent、attempt
 全局累计、最终必崩”。这是与“MVI 单向、intent 隔离”定义最相冲突的地方，且默认 HYBRID 策略受影响最重。建议将 retry/catch 下沉到每个 handler.handle(intent)
  调用处，实现真正的 per-intent 隔离与重置。
