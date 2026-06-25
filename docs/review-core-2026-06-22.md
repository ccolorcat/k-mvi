# K-MVI Core Module Review

> Review date: 2026-06-22
> Scope: `core/src/main/java/cc/colorcat/mvi/` (13 files) + `internal/` (4 files)
> Mode: Read-only review, no modifications

### 严重等级图例

| 标记 | 等级 | 含义 |
|------|------|------|
| 🔴 | Critical | 真实 Bug / 可能导致运行时错误 |
| 🟠 | Medium | 设计 trade-off / 有切实风险但当前可控 |
| 🟡 | Minor | 风格、命名、文档改进建议 |
| 🟢 | Good / Resolved | 正面评价 / 问题已修复 |

---

## Design

- 🟢 **Design-1**: `Mvi` 作为所有领域类型（`Intent`/`State`/`Event`/`PartialChange`/`Snapshot`）的命名空间容器，设计意图清晰。除避免类型命名冲突外，它还能改善 IDE 补全体验：用户输入 `Mvi.` 后即可看到核心领域类型与嵌套 marker interface 的候选项，降低 API 发现成本。虽然会带来少量 `Mvi.Xxx` 前缀冗余，但这是为可发现性和补全引导做出的合理取舍。

- 🟢 **Design-2**: `Snapshot` 的 frame 模型设计精良——`updateState`（清 event）、`withEvent`（仅设置 event）、`updateWith`（同时更新 state + event）三者职责明确且互斥，KDoc 中清楚说明了 `withEvent` + `updateState` 联用会丢 event。这个小 API 表面避免了用户大量踩坑。

- 🟢 **Design-3**: `HandleStrategy` (CONCURRENT / SEQUENTIAL / HYBRID) 的划分合理。HYBRID 策略通过 `groupHandle` + `flattenMerge(MAX_VALUE)` 实现并发组、串行组和 Fallback 组的三类处理，覆盖了绝大多数实际场景。

- 🟠 **Design-4**: `groupHandle` 的单协程 collect 瓶颈已在 KDoc 中明确记录，但这是设计层面一个不可忽视的 trade-off。所有分组的 channel.send 共享一个 collect 协程，当某个组回压时，所有组都会被阻塞。虽然有 mitigation（增 `groupChannelCapacity` 或使用 `UNLIMITED`），但遇到高并发多分组场景时可能成为性能瓶颈。

- 🟢 **Design-5**: `CoreReactiveContract` 的 pipeline 设计清晰：`intentsChannel → receiveAsFlow → toPartialChange → retryWhen → scan → flowOn(Default) → buffer(DROP_OLDEST) → shareIn → [stateFlow / eventFlow]`。每个环节职责明确，尤其是 `retryWhen` 放置在 `toPartialChange` 之后、`scan` 之前，确保了 handler 异常时才能重试而 `PartialChange.apply` 的异常不影响 pipeline。

- 🟢 **Design-6**: `eventFlow` 使用 `WhileSubscribed(5000)` + 不 replay 的 `shareIn`，配合 5s 的 stop timeout 优雅处理配置变更场景。这比传统的 `Channel` + `consumeAsFlow` 更灵活。

- 🟢 **Design-7**: `stateFlow.stateIn()` 和 `eventFlow.shareIn()` 分别对 `snapshots` 发起了两次下游订阅。`snapshots` 本身是 `Eagerly` 启动的 `SharedFlow`（上游 pipeline 由 `shareIn` 机制订阅，不计为 `snapshots` 的下游订阅者），两次订阅（`stateIn` + `shareIn`）导致些许冗余但无害。

- 🟢 **Design-8**: `asContract()` 返回 `ReadOnlyContract` 通过 `by delegate` 实现了真正的只读封装，无法通过类型转换绕过 dispatch 方法，是一个安全且简洁的设计。

- 🟢 **Design-9**: `IntentHandlerScope` 设计为 `value class` 并利用 `@PublishedApi internal` + `reified` 类型参数，使用户只需写 `register<MyIntent> { ... }` 而不必重复声明 `S`/`E`。这是解决 Kotlin 泛型类型推断限制的巧妙模式。

- 🟢 **Design-10**（6月25日已调整）: `ReactiveContractLazy` 原本是 custom `Lazy` 实现，使用 `@Volatile cached` + `cached ?: create().also { cached = it }`。由于 `ViewModel` 与 delegated property 的访问约定明确为主线程，不需要为并发首次访问提供线程安全保证；真正的问题是 `@Volatile` 容易制造“线程安全”的误读。现已改为 `by lazy(LazyThreadSafetyMode.NONE, create)` 一行委托，保留主线程 / 非线程安全语义，同时消除自定义实现与 `@Volatile` 注解带来的认知负担。

---

## Bug

- 🟢 **Bug-1**（6月25日重新归类）: `ReactiveContractLazy.value` 的 `cached ?: create().also { cached = it }` 非线程安全。复查后确认这不是运行时 bug：该 lazy 只用于 `ViewModel.contract(...)` delegate，调用约定明确为主线程访问，因此不需要支持并发首次初始化。已将实现改为 `by lazy(LazyThreadSafetyMode.NONE, create)`，让“非线程安全 / 无同步开销”的设计选择显式依赖 stdlib 语义，并删除容易误导读者的 `@Volatile` 注解。

- 🟢 **Bug-2**（边缘情况，6月23日已修复）: `dispatch()` 中有 TOCTOU 竞争：先检查 `scopeJob.isActive` 再调用 `channel.trySend`，两者之间 scope 可能被取消。但结果只是将 `Unavailable` 误报告为 `Full`，或者将 `Full` 误报告为 `Submitted`。系统行为正确（不重复处理、不丢失 intent），只是返回结果不精确。这在实践中影响极小，因为 `dispatch` 返回值仅用于监控，不用于业务逻辑。**修复方式**：保留前置 `isActive` 检查以保证 scope 取消时正确返回 `Unavailable`，在 `trySend` 失败后补充二次 `isActive` 检查以缩窄 TOCTOU 窗口——将因竞争导致的误报 `Full` 修正为 `Unavailable`。

---

## Trade-offs & Notes

> 以下条目并非 Bug，但属于设计层面的 trade-off 或容易误读的模式，值得记录。

- 🟢 **Note-1**（6月25日已修复）: `isConcurrent`/`isSequential` 曾从“互斥过滤”改为“纯接口检查”，以便把双标记冲突检测集中到 `assignGroupTag`。后续复查确认这两个 internal property 只是 `intent is Mvi.Intent.Concurrent/Sequential` 的薄包装，生产代码也只有 `assignGroupTag` 一个调用点；现已删除，`assignGroupTag` 内直接做 marker interface 判断，冲突检测和路由逻辑继续集中在同一处。

- 🟢 **Note-2**: `eventFlow` 使用 `WhileSubscribed(5000)` + `shareIn(replay = 0)` 是有意设计：5 秒 stop timeout 只是在短暂 collector 缺席时保持 `eventFlow` 对 `snapshots` 的订阅，避免配置变更 / Fragment back-stack 过渡期间频繁重启；它不会缓存事件。没有活跃 collector 时产生的事件会被丢弃，这是 one-shot event 的语义。`ReactiveContractImpl` KDoc 已明确要求先订阅 `eventFlow`，再 dispatch 可能产生事件的 intent。

- 🟢 **Note-3**: `groupHandle` 中 `activeChannels` 使用 `linkedMapOf`，并在 `openChannel` 内先写入 map 再 `emit`。这个顺序是有意设计：如果 `emit` 因下游回压挂起，外层 `collect` 同样挂起，不会继续处理后续 intent，也就不会向尚未被下游订阅的 channel 发送数据；若挂起期间取消，`finally` 会关闭已登记的 channel。代码注释已明确说明该顺序，当前实现正确。

- 🟢 **Note-4**: `KMvi.configure` 使用非原子的 `config = config.transform()`，但这是符合设计约定的：该方法只应在 `Application.onCreate()` 等初始化阶段从主线程调用。`config` 使用 `@Volatile` 仅用于让后台 Flow pipeline 读取到最新发布的配置，不用于支持并发写入。KDoc 已明确说明 `configure()` 非线程安全且并发调用可能丢失更新，因此无需引入 `AtomicReference` 扩大 API 契约。

---

## Name

- 🟢 **Name-1**: `groupHandle` 单独看不如 `groupAndHandle` 自然，但它是 internal 函数，唯一生产调用点位于 `StrategyIntentTransformer.hybrid()`，上下文中已有 `assignGroupTag` 与 `handleByTag`，语义足够清晰。KDoc 首句也明确说明其职责是按 tag 分组并独立处理每个 group。当前命名与项目里的 `HandleStrategy` / `IntentHandler` / `handleByTag` 术语保持一致，暂不为了轻微信息增益重命名 internal 函数、测试名、日志文本和文档引用。

- 🟢 **Name-2**: `Mvi.PartialChange` — `PartialChange` 含义明确表示"部分变更"，但在某些场景（如拦截所有变更做 logging/debugging）用户需要理解它代表"一个 frame 到下个 frame 的迁移"。名称本身可以传达更清晰的 frame 视角，例如 `FrameMigration` 或 `SnapshotTransform`。不过当前命名在 MVI 社区已被广泛接受，无需改变。

- 🟢 **Name-3**: `getStackTraceString()` 是 internal helper，只有内部 logger 使用。虽然 `get` 前缀偏 Java 风格，但它与 Android `Log.getStackTraceString(Throwable)` 的命名一致，而默认 logger 本身也基于 Android `Log`。Kotlin stdlib 对应 API 是 `Throwable.stackTraceToString()` 函数而非属性，因此改成 `val Throwable.stackTraceString` 并不会更一致。当前命名可保留。

- 🟢 **Name-4**: `Mvi.Intent.Concurrent` 和 `Mvi.Intent.Sequential` 作为嵌套接口的命名准确——明确表示"并发意图"和"顺序意图"。

- 🟢 **Name-5**: `DispatchResult.Submitted` / `Unavailable` / `Full` 三个子类的命名非常清晰准确，比常见的 `Success`/`Failure` 更能传达语义。

- 🟢 **Name-6**: `StrategyIntentTransformer` 名称略长但准确描述了职责。`StrategyReactiveContract` 同理。

- 🟢 **Name-7**: `IntentHandlerDelegate` 中的 `Delegate` 命名合理——它同时实现了 `IntentHandlerRegistry` 和 `IntentHandler`，扮演双重委托角色。

- 🟢 **Name-8**: `ReactiveContractLazy` 是 internal `Lazy<ReactiveContract<I, S, E>>` delegate，名称准确描述了职责。它只在两个 `ViewModel.contract(...)` factory 和对应测试中使用，不是 public API；与 stdlib `lazy()` 的大小写和符号形态不同，混淆风险很低。`ContractLazy` 会降低类型精度，`LazyReactiveContract` 反而更容易被误读为 contract 本体，当前命名可保留。

---

## Style

- 🟡 **Style-1**: `.editorconfig` 中 `max_line_length=120`，当前 core main 仍有少量超过 120 列的代码行，集中在长日志 / 错误消息字符串和 `KMvi.Configuration.toString()`。这不影响行为，但与项目格式约定不完全一致；建议后续将这些长字符串拆行，保持源码宽度一致。

- 🟡 **Style-2**（6月25日已部分优化）: KDoc 整体仍偏重，部分文件文档篇幅明显高于代码本身；其中 `HandleStrategies.kt` 作为 strategy 权威说明保留完整文档。已将 `IntentTransformers.kt` 中重复展开三类 strategy 的 KDoc 改为简述职责并通过 `HandleStrategy` 交叉引用权威文档，避免 strategy 语义变动时多处同步。后续仍可继续减少局部重复，优先保留核心入口的完整说明，其他位置用 `@see` / 交叉引用承接。

- 🟢 **Style-3**（6月25日已修复）: `@author ccolorcat` 标签出现在 16 个文件中。Kotlin 社区一般不再使用 `@author` 标签，改为通过 Git 历史追踪作者。已从全部 16 个文件中移除。

- 🟢 **Style-4**: `internal val logger: Logger get() = KMvi.logger` 每次访问都从 `KMvi` 重新读取配置，而不是缓存引用。由于 `KMvi.config` 是 `@Volatile` 的，这是正确的行为（确保最新的 logger 配置被使用），但每次日志调用都有一次 volatile read 开销。这点开销微不足道，无需改进。

- 🟢 **Style-5**: 代码中的 lambda 日志模式（`logger.i(TAG) { ... }`）使用得很好，充分利用了 Kotlin 的 lambda 求值——只有在满足日志级别时才计算消息字符串。

- 🟢 **Style-6**: 使用 `buildString { appendLine(...) }` 来构造包含 exception 的日志消息，避免了字符串拼接，样式优秀。

- 🟢 **Style-7**: `HandleStrategies.kt` 中 `strategyTransformer` 是 `internal fun` 而非 `internal val StrategyIntentTransformer(...) : IntentTransformer` 的工厂形式，很好地隔离了构造细节。

- 🟢 **Style-8**: `MviCollects.kt` 中自由函数 `collectTyped` 与 `EventCollector.collectTyped` 看似重复，但职责分层清晰：自由函数服务“只收一个 event type”的简单场景，DSL 成员服务 `collectEvent { ... }` 中多个 collector 共享同一个 `SupervisorJob` 的场景。`collectAll` 并没有自由函数版本，只存在于 DSL 中。KDoc 已明确建议单一事件类型用自由函数，多事件类型用 `collectEvent` DSL，当前 API 表面积是有意取舍。

- 🟢 **Style-9**: `IntentHandlerDelegate.handle()` 中的 `@Suppress("UNCHECKED_CAST")` 是类型擦除下的必要实现细节。`handlers` 的唯一写入点是 `register(intentType: Class<T>, handler: IntentHandler<T, S, E>)`，同一个泛型参数 `T` 同时绑定 key 和 handler；读取时又使用 `intent.javaClass` 做 exact class lookup，因此正常 API 使用下取出的 handler 与 intent 类型一致。额外 `isInstance` 检查基本冗余，运行时也无法验证 handler 的泛型参数；DEBUG 守卫只能覆盖 raw type 滥用，不值得增加复杂度。

---

## Doc

- 🟢 **Doc-1**: 顶层 KDoc 覆盖非常完善——每个 public 类型、方法、属性都有 KDoc，且包含了使用示例、注意事项和架构图。这是非常好的习惯。

- 🟡 **Doc-2**: `HandleStrategies.kt` 中 `strategyTransformer` 函数和 `StrategyIntentTransformer` 类的 KDoc 内容有大量重叠，后者几乎完整复述了前三类 strategy 的行为。建议后者简要标注 "See HandleStrategy for strategy details。" 以避免重复。

- 🟢 **Doc-3**（6月25日已修复）: `Mvi.kt` 的 `PartialChange` KDoc 中给出了完整 async 操作示例（`handleLoadData`）。示例中的 `repository.loadData()` 已改为使用 `withContext(Dispatchers.IO)` 包裹可能阻塞的网络 / 数据库 / 文件工作，避免误导用户把阻塞 I/O 放进默认的 `Dispatchers.Default` handler pipeline。`IntentHandlers.kt`、`MviViewModels.kt` 和 README 中同类 `loadData` 示例也已同步更新。

- 🟡 **Doc-4**: `MviCollects.kt` 的 `dispatchWithLifecycle` KDoc 中注明事件的行为：
  > Collection starts when the lifecycle reaches [state] and **stops** when the lifecycle drops below that state.
  这是正确的，但注意 `repeatOnLifecycle` 在 lifecycle 低于指定状态时会取消协程并重新启动，因此 `dispatch(this)` 会丢弃其间到达的 flow 元素——这在 dispatcher 中通常是可以接受的（因为 dispatch 是非幂等敏感的）。

- 🟢 **Doc-5**（已修复）: `ReactiveContractLazy` 的 KDoc 此前未充分说明 `@Volatile` 与非线程安全的关系。当前 KDoc 已改为说明该 lazy 是主线程访问约定下的非线程安全实现，委托给 `lazy(LazyThreadSafetyMode.NONE)` 是为了显式表达“不做同步、不保证并发初始化安全”的设计选择。

- 🟢 **Doc-6**: `DispatchResult` 的 KDoc 非常详尽，对各种 Channel 配置下的语义都有说明，这对于用户理解 dispatch 的返回行为至关重要。

- 🟢 **Doc-7**: `groupHandle` 的 KDoc 详细记录了单协程瓶颈问题和 mitigation 方案，也记录了 tag 的稳定性要求。这种"诚实文档"值得保留和表扬。

- 🟡 **Doc-8**: `InternalUtils.kt` 中 `tagLabel` 的 KDoc 良好，但 `requireSupportedChannelConfig` 的 KDoc 中没有提及 `capacity` 各特殊值的数字含义。注意 `Channel.BUFFERED` 常量值为 `-2`（运行时解析为默认 buffer size 64，来自系统属性 `kotlinx.coroutines.flow.buffer.size`）；`Channel.RENDEZVOUS = 0`；`Channel.CONFLATED = -1`；`Channel.UNLIMITED = Int.MAX_VALUE`。如果 KDoc 列出这些常量与其运行时行为的映射，代码理解会更直观。

- 🟡 **Doc-9**: `README.md` 中多处指向过时的版本号（如 `1.2.6`），需要随着版本更新同步。

---

## 总结

整体来看，core 模块的代码质量很高。设计上有清晰的架构层次和优雅的 API 抽象，类型安全得到充分保障。

### 严重等级分布

| 等级 | 数量 | 条目 |
|------|------|------|
| 🔴 Critical | 0 | — |
| 🟠 Medium | 1 | Design-4 |
| 🟡 Minor | 5 | Style-1/2, Doc-2/4/8/9 |
| 🟢 Good / Resolved | 32 | Design-1/2/3/5/6/7/8/9/10, Bug-1/2, Note-1/2/3/4, Name-1/2/3/4/5/6/7/8, Style-3/4/5/6/7/8/9, Doc-1/3/5/6/7 |

**仍可改进的领域**：

- KDoc 的冗余减少——遵循 DRY，交叉引用代替重复（Style-2, Doc-2）
- `groupHandle` 单协程瓶颈的长期方案（Design-4）

**已解决的问题**：

- `dispatch()` TOCTOU 竞争（Bug-2，6月23日修复）
- `ReactiveContractLazy` KDoc 对主线程访问约定和非线程安全语义的说明（Doc-5）
- `isConcurrent`/`isSequential` 冗余 internal property 已删除，冲突检测仍集中在 `assignGroupTag`（Note-1，6月25日修复）
- `ReactiveContractLazy` 改为 `by lazy(LazyThreadSafetyMode.NONE)` 委托，删除自定义 lazy 与误导性的 `@Volatile` 注解；并将原并发风险重新归类为主线程访问约定下的非线程安全设计（Bug-1 + Design-10，6月25日调整）
- `@author ccolorcat` 从全部 16 个核心模块源码文件中移除（Style-3，6月25日修复）
- 阻塞型 `repository.loadData()` 示例已用 `withContext(Dispatchers.IO)` 隔离，避免误导用户在 Default pipeline 中执行 I/O（Doc-3，6月25日修复）
