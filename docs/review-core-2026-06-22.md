# K-MVI Core Module Review

> Review date: 2026-06-22
> Scope: `core/src/main/java/cc/colorcat/mvi/` (13 files) + `internal/` (4 files)
> Mode: Read-only review, no modifications

---

## Design

- **Design-1**: `Mvi` 作为所有领域类型（`Intent`/`State`/`Event`/`PartialChange`/`Snapshot`）的命名空间容器，设计意图清晰——保证类型命名空间不冲突。但 Kotlin 已提供顶级类型 + 包级导入，额外套一层 `Mvi.Xxx` 增加了冗余。`Mvi.Intent`、`Mvi.State` 等前缀在多处重复出现，且用户定义的类型也必须实现 `Mvi.State` 等具名接口，这使得 `Mvi` 对象的包裹价值有限，建议考虑将核心类型提升为顶级。

- **Design-2**: `Snapshot` 的 frame 模型设计精良——`updateState`（清 event）、`withEvent`（仅设置 event）、`updateWith`（同时更新 state + event）三者职责明确且互斥，KDoc 中清楚说明了 `withEvent` + `updateState` 联用会丢 event。这个小 API 表面避免了用户大量踩坑。

- **Design-3**: `HandleStrategy` (CONCURRENT / SEQUENTIAL / HYBRID) 的划分合理。HYBRID 策略通过 `groupHandle` + `flattenMerge(MAX_VALUE)` 实现并发组、串行组和 Fallback 组的三类处理，覆盖了绝大多数实际场景。

- **Design-4**: `groupHandle` 的单协程 collect 瓶颈已在 KDoc 中明确记录，但这是设计层面一个不可忽视的 trade-off。所有分组的 channel.send 共享一个 collect 协程，当某个组回压时，所有组都会被阻塞。虽然有 mitigation（增 `groupChannelCapacity` 或使用 `UNLIMITED`），但遇到高并发多分组场景时可能成为性能瓶颈。

- **Design-5**: `CoreReactiveContract` 的 pipeline 设计清晰：`intentsChannel → receiveAsFlow → toPartialChange → retryWhen → scan → flowOn(Default) → buffer(DROP_OLDEST) → shareIn → [stateFlow / eventFlow]`。每个环节职责明确，尤其是 `retryWhen` 放置在 `toPartialChange` 之后、`scan` 之前，确保了 handler 异常时才能重试而 `PartialChange.apply` 的异常不影响 pipeline。

- **Design-6**: `eventFlow` 使用 `WhileSubscribed(5000)` + 不 replay 的 `shareIn`，配合 5s 的 stop timeout 优雅处理配置变更场景。这比传统的 `Channel` + `consumeAsFlow` 更灵活。

- **Design-7**: `stateFlow.stateIn()` 和 `eventFlow.shareIn()` 分别对 `snapshots` 发起了两次额外订阅。`snapshots` 本身是 `Eagerly` 启动的 `SharedFlow`，三次订阅（pipeline 自身 + `stateIn` + `shareIn`）导致些许冗余但无害。

- **Design-8**: `asContract()` 返回 `ReadOnlyContract` 通过 `by delegate` 实现了真正的只读封装，无法通过类型转换绕过 dispatch 方法，是一个安全且简洁的设计。

- **Design-9**: `IntentHandlerScope` 设计为 `value class` 并利用 `@PublishedApi internal` + `reified` 类型参数，使用户只需写 `register<MyIntent> { ... }` 而不必重复声明 `S`/`E`。这是解决 Kotlin 泛型类型推断限制的巧妙模式。

- **Design-10**: `ReactiveContractLazy` 是 custom `Lazy` 实现，但 `@Volatile cached` + `cached ?: create().also { cached = it }` 存在非原子 check-then-act 问题。虽然 ViewModel 的主线程访问约定使其在多数情况下安全，但理论上如果某天多线程访问，可能会创建多次实例。使用 `SynchronizedLazyImpl` 或 `kotlin.lazy` 会更安全。

---

## Bug

- **Bug-1**: `ReactiveContractLazy.value` 的 `cached ?: create().also { cached = it }` 非线程安全。即使 KDoc 声明 "not thread-safe"，`@Volatile` 仅保证可见性而不保证原子性。两个线程同时首次访问时将各自创建一个实例，后写入的会覆盖前一个。实际风险低（ViewModel 默认单线程使用），但标记 `@Volatile` 容易让读者误以为它是线程安全的。

- **Bug-2**: `isConcurrent` 扩展属性实现为 `this is Mvi.Intent.Concurrent && this !is Mvi.Intent.Sequential`，`isSequential` 类似。当某个 Intent 同时实现两个标记接口时（conflict 场景），`isConcurrent` 和 `isSequential` 都返回 `false`，然后 `assignGroupTag` 的 `else` 分支检测到冲突并记录警告。这个逻辑是正确的，但不够直观——属于"设计正确但容易让人误解"的模式。建议考虑将冲突检测统一放到 `assignGroupTag` 中，而 `isConcurrent`/`isSequential` 仅做纯粹的接口检查（不互斥过滤），让分类逻辑完全集中在 transformer 中。

- **Bug-3**: `eventFlow` 使用 `WhileSubscribed(5000)` + `shareIn`，5 秒的 stop timeout 意味着 Fragment 进入后台后最多 5 秒内 event 仍可见，然后才停掉上游。这可能导致短暂的重复订阅和少量丢 event。设计上接受这个 trade-off，但在高实时性场景需注意。

- **Bug-4**（边缘情况）: `dispatch()` 中有 TOCTOU 竞争：先检查 `scopeJob.isActive` 再调用 `channel.trySend`，两者之间 scope 可能被取消。但结果只是将 `Unavailable` 误报告为 `Full`，或者将 `Full` 误报告为 `Submitted`。系统行为正确（不重复处理、不丢失 intent），只是返回结果不精确。这在实践中影响极小，因为 `dispatch` 返回值仅用于监控，不用于业务逻辑。

- **Bug-5**（非 Bug 但需注意）: `groupHandle` 中 `activeChannels` 使用 `linkedMapOf`，但在 `openChannel` 内先写入 map 再 `emit`。如果 `emit` 因下游 `flattenMerge` 的回压而挂起，map 中已有一个未消费的 channel。`emit` 恢复后下游订阅 channel，在此之前不会有数据到达。这个顺序设计正确，但如果 review 不够仔细可能会被误解为竞态。

- **Bug-6**（非代码问题）: `KMvi.configure` 使用非原子的 `config = config.transform()`。虽然 KDoc 明确声明显式是非线程安全的，但如果用户不慎在多线程配置，可能会丢失更新。建议在文档上加强提示（已在 KDoc 中说明），或考虑在 v2 中使用 `AtomicReference`。

---

## Name

- **Name-1**: `groupHandle` 命名不够自描述。从名称无法直接理解是"按组处理"还是"处理组"。对比 `groupBy` + `handleByTag` 的组合，`groupHandle` 作为函数名稍显模糊。建议改为 `fanOutByTag` 或 `groupAndHandle`。

- **Name-2**: `Mvi.PartialChange` — `PartialChange` 含义明确表示"部分变更"，但在某些场景（如拦截所有变更做 logging/debugging）用户需要理解它代表"一个 frame 到下个 frame 的迁移"。名称本身可以传达更清晰的 frame 视角，例如 `FrameMigration` 或 `SnapshotTransform`。不过当前命名在 MVI 社区已被广泛接受，无需改变。

- **Name-3**: `getStackTraceString()` 扩展函数命名风格不符合 Kotlin 属性惯例。通常 Kotlin 中应使用 `stackTraceString`（属性风格）。`getStackTraceString` 是 Java 风格。

- **Name-4**: `Mvi.Intent.Concurrent` 和 `Mvi.Intent.Sequential` 作为嵌套接口的命名准确——明确表示"并发意图"和"顺序意图"。

- **Name-5**: `DispatchResult.Submitted` / `Unavailable` / `Full` 三个子类的命名非常清晰准确，比常见的 `Success`/`Failure` 更能传达语义。

- **Name-6**: `StrategyIntentTransformer` 名称略长但准确描述了职责。`StrategyReactiveContract` 同理。

- **Name-7**: `IntentHandlerDelegate` 中的 `Delegate` 命名合理——它同时实现了 `IntentHandlerRegistry` 和 `IntentHandler`，扮演双重委托角色。

- **Name-8**: `ReactiveContractLazy` — `Lazy` 后缀暗示它是一个 `Lazy<ReactiveContract>`，这确实符合其类型，但命名与 Kotlin stdlib 的 `lazy()` 函数容易混淆。建议改为 `ContractLazy` 或 `LazyReactiveContract`。

---

## Style

- **Style-1**: KDoc 中存在较多超过 120 列的行（`.editorconfig` 中 `max_line_length=120`）。例如 `MviCollects.kt` 的多个 KDoc 参数描述和 `IntentHandlers.kt` 的详细文档超出了列限制。建议格式化 KDoc 使其符合项目约定。

- **Style-2**: KDoc 整体过于冗长，部分文件的文档篇幅远超代码本身（如 `HandleStrategies.kt` 316 行中约 70% 是 KDoc）。文档中有大量重复的内容——`IntentTransformer` 中重复解释了三类 strategy 的行为，而这些在 `HandleStrategy` 中已有完整描述。建议遵循 DRY 原则，核心概念集中描述，其他文件交叉引用。

- **Style-3**: `@author ccolorcat` 标签出现在多个文件中。Kotlin 社区一般不再使用 `@author` 标签，改为通过 Git 历史追踪作者。建议移除。

- **Style-4**: `getStackTraceString()` 扩展函数的命名是 JavaBean 风格——扩展函数中应使用 `stackTraceString()` 属性风格，保持 Kotlin 的一致性。

- **Style-5**: `internal val logger: Logger get() = KMvi.logger` 每次访问都从 `KMvi` 重新读取配置，而不是缓存引用。由于 `KMvi.config` 是 `@Volatile` 的，这是正确的行为（确保最新的 logger 配置被使用），但每次日志调用都有一次 volatile read 开销。这点开销微不足道，无需改进。

- **Style-6**: 代码中的 lambda 日志模式（`logger.i(TAG) { ... }`）使用得很好，充分利用了 Kotlin 的 lambda 求值——只有在满足日志级别时才计算消息字符串。

- **Style-7**: 使用 `buildString { appendLine(...) }` 来构造包含 exception 的日志消息，避免了字符串拼接，样式优秀。

- **Style-8**: `HandleStrategies.kt` 中 `strategyTransformer` 是 `internal fun` 而非 `internal val StrategyIntentTransformer(...) : IntentTransformer` 的工厂形式，很好地隔离了构造细节。

- **Style-9**: `MviCollects.kt` 中 `collectAll` 和 `collectTyped` 作为自由函数和 `EventCollector` 方法存在重复——职责相同但入口不同。虽然提供了灵活的使用方式，但 API 表面积增大，用户需要区分何时用 DSL，何时用自由函数。

- **Style-10**: `IntentHandlerDelegate.handle()` 中 `@Suppress("UNCHECKED_CAST")` 是安全的（因为 handler 注册时保证了类型匹配），但 `as IntentHandler<I, S, E>` 的转换发生在运行时，如果某天引入类型不匹配的 handler，此处的异常会比较难调试。可以考虑在 register 时做更严格的类型检查，或者增加一条守卫日志（DEBUG 级别）。

---

## Doc

- **Doc-1**: 顶层 KDoc 覆盖非常完善——每个 public 类型、方法、属性都有 KDoc，且包含了使用示例、注意事项和架构图。这是非常好的习惯。

- **Doc-2**: `HandleStrategies.kt` 中 `strategyTransformer` 函数和 `StrategyIntentTransformer` 类的 KDoc 内容有大量重叠，后者几乎完整复述了前三类 strategy 的行为。建议后者简要标注 "See HandleStrategy for strategy details。" 以避免重复。

- **Doc-3**: `Mvi.kt` 的 `PartialChange` KDoc 中给出了一个完整的 async 操作示例（`handleLoadData`），但 `flow { }` 示例中 `repository.loadData()` 是 suspend 调用，没有使用 `withContext(Dispatchers.IO)` 或 `flowOn(IO)`。这在 KDoc 示例中可能误导用户认为可以直接在 `flow { }` 中执行 IO 操作而不需切换调度器（实际 handler 运行在 `Dispatchers.Default` 上）。建议在示例中添加 `flowOn(Dispatchers.IO)` 或注解说明。

- **Doc-4**: `MviCollects.kt` 的 `dispatchWithLifecycle` KDoc 中注明事件的行为：
  > Collection starts when the lifecycle reaches [state] and **stops** when the lifecycle drops below that state.
  这是正确的，但注意 `repeatOnLifecycle` 在 lifecycle 低于指定状态时会取消协程并重新启动，因此 `dispatch(this)` 会丢弃其间到达的 flow 元素——这在 dispatcher 中通常是可以接受的（因为 dispatch 是非幂等敏感的）。

- **Doc-5**: `ReactiveContractLazy` 的 KDoc 声明 "This method is NOT thread-safe"，但同时注明了使用 `@Volatile`。这两个说法看似矛盾——建议更明确地说明：`@Volatile` 仅保证可见性，不保证原子性；check-then-act 的竞态条件仍然存在。

- **Doc-6**: `DispatchResult` 的 KDoc 非常详尽，对各种 Channel 配置下的语义都有说明，这对于用户理解 dispatch 的返回行为至关重要。

- **Doc-7**: `groupHandle` 的 KDoc 详细记录了单协程瓶颈问题和 mitigation 方案，也记录了 tag 的稳定性要求。这种"诚实文档"值得保留和表扬。

- **Doc-8**: `InternalUtils.kt` 中 `tagLabel` 的 KDoc 良好，但 `requireSupportedChannelConfig` 的 KDoc 中没有提及 `capacity` 各特殊值的数字含义（`Channel.BUFFERED = 64`，`Channel.RENDEZVOUS = 0`，`Channel.CONFLATED = -1`，`Channel.UNLIMITED = Int.MAX_VALUE`）。如果 KDoc 列出这些常量映射，代码理解会更直观。

- **Doc-9**: `README.md` 中多处指向过时的版本号（如 `1.2.6`），需要随着版本更新同步。

---

## 总结

整体来看，core 模块的代码质量很高。设计上有清晰的架构层次和优雅的 API 抽象，类型安全得到充分保障。主要改进空间集中在：`Mvi` 外层容器的必要性、KDoc 的冗余减少、`groupHandle` 的命名和单协程瓶颈、以及 `ReactiveContractLazy` 的线程安全边界。
