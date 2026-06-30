# K-MVI Core 模块审计报告

- **审计日期**: 2026-06-30
- **审计版本**: 1.4.4-SNAPSHOT (cc.colorcat.mvi:core)
- **范围**: `core/src/main/java/cc/colorcat/mvi/`（14 个源文件 + 4 个内部文件）
- **测试范围**: `core/src/test/java/cc/colorcat/mvi/`（5 个测试文件）
- **方法**: 通读全部源码和测试的静态分析，未进行运行时验证。

---

## 总体摘要

K-MVI 核心模块是一个精心构建的、达到生产质量的 MVI 库。架构清晰，API 表面经过精心设计，同时提供低层级和高层级的入口点，测试覆盖也相当扎实。以下是按类别划分的详细分析。

---

## Design（设计）

架构决策、管道设计、并发模型及 API 表面评估。

- **Design-1**: 架构扎实。单向数据流 `Intent → Channel → Transformer → PartialChange → scan → Snapshot → StateFlow/EventFlow` 清晰、封装良好且忠实地遵循了 MVI 模式。

- **Design-2**: 提供两套 API（基于 Transformer 的低层级和基于 Handler 的高层级）是好的设计。用户可以从简单的 DSL（`register<MyIntent> { ... }`）入手，在需要时再过渡到完整的 `IntentTransformer` 控制。

- **Design-3**: Snapshot 帧模型（`state + 可选 event`）配合 `updateState`/`withEvent`/`updateWith` 设计良好。文档明确警告了在单个 `PartialChange` 中先 `withEvent` 再 `updateState` 的陷阱。

- **Design-4**: `FatalErrorHandler.handle(error): Nothing` 是一个有力的设计选择——它强制实现不得正常返回，从而在类型层面将不可恢复的错误路径显式化。

- **Design-5**: 三种 `HandleStrategy`（CONCURRENT, SEQUENTIAL, HYBRID）覆盖了实际的并发需求。HYBRID 的分组模型（并发标记、顺序标记、基于标签的回退）考虑周全。

- **Design-6**: **[局限] `groupHandle` 单协程瓶颈。** `groupHandle` 中的外层 `collect { }` 循环只在**一个**协程中运行。当 `channel.send(intent)` 因为某个组的 channel 满了（由于该组的 handler 较慢）而挂起时，**所有**组的都会被阻塞——即使其他组的 channel 还有空闲容量。这一点有充分的文档说明，但作为设计局限在高吞吐或混合延迟场景下仍然存在。缓解措施：选择适当的 `groupChannelCapacity` 或使用 `Channel.UNLIMITED`（代价是内存无界增长）。

- **Design-7**: **[局限] Snapshot 缓冲区的 DROP_OLDEST 可能静默丢失事件。** 融合后的 `flowOn + buffer(64, DROP_OLDEST)` 边界意味着，如果 `eventFlow` 的收集器比状态管道慢，最旧的 snapshot（携带其 event）会在收集器读取之前被丢弃。这点有良好的文档说明，属于设计权衡而非缺陷——在一次性事件模型中，过时/迟到的事件比丢失事件更糟糕。

- **Design-8**: **[局限] KMvi 全局可变单例。** `KMvi.configure()` 执行的是非原子的读-改-写操作，并明确声明非线程安全。后备配置字段仅用 `@Volatile` 保证读取可见性。这在单线程的 `Application.onCreate()` 使用场景中没问题，但如果配置需要从多个点或测试中更改（测试必须在 `@Before` 中重置），全局单例模式本质上是脆弱的。

- **Design-9**: 将 `IntentHandlerScope` 设计为一个包装 `IntentHandlerRegistry` 的 `@JvmInline value class`，是解决 Kotlin 类型推断与 reified 泛型问题的优雅方案。将 S/E 绑定到 Scope 上避免了"全有或全无"的类型参数问题。

- **Design-10**: `eventFlow` 使用 `SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)`。5 秒的超时时间是一个好的权衡：它能承受配置变更和 Fragment 返回栈切换而不重启上游订阅，避免在收集器短暂缺席期间丢失事件。

---

## Bug（缺陷）

通过静态分析发现的潜在缺陷或正确性问题。

- **Bug-1**: **未发现可确认的运行时缺陷。** 代码库经过充分测试，`ReactiveContractImplTest` 共 974 行，覆盖了 dispatch → state 更新、多 intent 累积、`PartialChange.apply` 异常处理、重试恢复、取消语义、事件发射、`DispatchResult` 变体以及作用域生命周期。所有审查逻辑均正确。

- **Bug-2**: **[边界情况，非缺陷]** Handler 查询使用精确类匹配（见 AGENTS.md 和 IntentHandlers.kt）。为父类注册 handler 不会拦截子类的 dispatch。这是有意为之且有文档说明的，但对于习惯多态分发的用户来说是一个常见的困惑点。用户必须按具体类型注册，或使用 `defaultHandler` 配合 `when` 表达式。

- **Bug-3**: **[边界情况，非缺陷]** 在 `groupHandle` 中，当 `flattenMerge` 取消了一个内部流（例如由于作用域取消），对应的 channel 会被关闭。过时 channel 检测（`isClosedForSend` → 重新打开）正确处理了这种情况，防止 `ClosedSendChannelException` 导致整个管道崩溃。

- **Bug-4**: **[潜在关注点]** `IntentQueueConfig.onBufferOverflow` 默认值为 `SUSPEND`，但 `dispatch()` 使用的是非阻塞的 `Channel.trySend`。当队列在 `SUSPEND` 模式下已满时，`trySend()` 返回一个失败的 `ChannelResult`（failure 而非 closed），这正确地映射到了 `DispatchResult.Full`。不是缺陷——语义是正确的——但配置中的 `SUSPEND` 名称可能误导用户认为 `dispatch` 可能挂起。KDoc 已阐明这一点。

---

## Name（命名）

类型、函数和参数的命名清晰度、一致性和简洁性。

- **Name-1**: `Mvi` 对象作为 `Intent` / `State` / `Event` / `PartialChange` / `Snapshot` 的命名空间——作为作用域命名空间是合理的，尽管略显不常见。它保持了顶层命名空间的清洁，但增加了嵌套开销（每个文件中都要写 `Mvi.Intent`、`Mvi.PartialChange`）。

- **Name-2**: `PartialChange` — 好的命名。"Partial"准确传达了只有帧的一部分被更改。相关术语（帧、snapshot、迁移）一致性好。

- **Name-3**: `DispatchResult` 及其 `Submitted` / `Unavailable` / `Full` — 清晰无歧义。每个变体的 KDoc 都详尽说明了其含义（和不含义）。

- **Name-4**: `StrategyReactiveContract` vs `CoreReactiveContract` — 命名没有清晰地传达继承关系。`CoreReactiveContract` 是使用 `IntentTransformer` 的基类；`StrategyReactiveContract` 通过 `HandleStrategy` + handler 注册扩展了它。使用 `Base`/`Strategy` 或 `Transformer`/`Handler` 这样的前缀会更一目了然。不过两者都是 `internal` 的，属于次要问题。

- **Name-5**: `IntentHandlerDelegate` — 好名字。清晰地传达了它是一个包装器，委托给已注册的 handler 并带有回退机制。

- **Name-6**: `IntentHandlerScope` — 用于 DSL 作用域的好名字。

- **Name-7**: `debounceLeading` — 非常优秀的命名。清晰地与标准 `debounce`（尾沿）形成对比，并立即传达出"首沿"行为。

- **Name-8**: `asSingleFlow()` — 可接受但存在争议。`asFlow()` 或 `toFlow()` 会更简洁。"Single"限定词正确暗示了单次发射。

- **Name-9**: `groupHandle` — 尚可但略有歧义：是"处理分组"还是"分组再处理"？在内部上下文看来是清晰的。

- **Name-10**: `groupCountWarningThreshold` — 清晰但冗长。`groupCountThreshold` 或 `groupWarningThreshold` 同样清晰且更短。

- **Name-11**: `ReactiveContractLazy` — 清晰。名称直接表明它是 `ReactiveContract` 的 `Lazy`。

- **Name-12**: `setupIntentHandlers` — 清晰。`setup` 前缀与 DSL 构建器模式一致。

---

## Style（风格）

Kotlin 代码风格、惯用用法、格式约定和代码一致性。

- **Style-1**: 代码统一遵循 Kotlin `official` 风格：4 空格缩进、类型使用 `PascalCase`、函数/属性使用 `camelCase`、常量使用 `UPPER_SNAKE_CASE`。使用尾随逗号。LF 换行符。

- **Style-2**: 所有内部日志统一使用延迟求值的日志写法（`logger.i(TAG) { "message" }`）——良好的性能实践。

- **Style-3**: 没有多余的冒号或单表达式函数中的显式 `return`。Kotlin 惯用法使用得当。

- **Style-4**: `@Suppress("UNCHECKED_CAST")` 作用域控制得当（仅在单个表达式上，而不是整个函数/文件）。

- **Style-5**: **[次要] Contract 中代码块顺序不够一致。** 文件中 `ReactiveContract` 定义在 `Contract` 之后，这符合逻辑（只读 → 完整功能）。但 `DispatchResult` 放在单独的文件中，而 `asContract()` 扩展在 `Contracts.kt` 中。这种分离是合理的，尽管 `DispatchResult.kt` 有理由合并到 `Contracts.kt` 中。

- **Style-6**: 对 SAM 类型（`IntentHandler`、`PartialChange`、`FatalErrorHandler`、`Logger`、`IntentTransformer`、`GroupTagSelector`）使用 `fun interface` 是好的实践。这种做法允许在调用点进行 lambda 到 SAM 的转换。

- **Style-7**: `StrategyIntentTransformer.assignGroupTag` 方法代码整洁、注释充分。早检测冲突的 `when` 表达式组织良好。

- **Style-8**: **[次要] `doOnAfterTextChanged` 中空的 `TextWatcher` 方法体**——`beforeTextChanged` 和 `onTextChanged` 有空的括号 `{}`。这在 Kotlin 中对于 Java 监听器接口是惯用的，但用 `object : TextWatcher` 配合两个空方法略显冗长。鉴于这是标准的 Android 模式，可以接受。

- **Style-9**: KDoc 正确使用 `[]` 表示 Kotlin 引用（`[Mvi.PartialChange]`、`[Snapshot.updateState]`）。代码块统一使用 ``` 包裹。

- **Style-10**: **[次要] 测试代码混合使用 `runBlocking` 和 `runTest(UnconfinedTestDispatcher())`** 而非统一采用一种方式。`eventFlow` 测试文件中的注释很好地解释了选择 `UnconfinedTestDispatcher` 的原因，但 `runBlocking` 与 `runTest` 的混合使用缺少统一模式，略显不一致。

---

## Doc（文档）

KDoc 注释、README 和内联注释的完整性、正确性和清晰度。

- **Doc-1**: 整体文档质量优秀。每个公开的类、接口、函数和属性都有 KDoc。许多内部类也有详尽的文档。

- **Doc-2**: `Mvi.kt` 的顶层 KDoc 提供了包含数据流图的完整架构概述——对新用户非常友好。

- **Doc-3**: `HandleStrategies.kt` 有大量的 KDoc，包含策略对比、示例和可视化图示。但**过于冗长**。仅 `HYBRID` 一节就占了约 80 行，包含 ASCII 图、多个示例和配置片段。部分内容在 `HandleStrategy.SEQUENTIAL` 和 `.CONCURRENT` 中重复出现。更简洁的版本会更易于维护和阅读。

- **Doc-4**: `DispatchResult` 的 KDoc 对队列策略语义进行了详尽说明。关于每种 `Channel` 模式（RENDEZVOUS、CONFLATED、BUFFERED、UNLIMITED）如何与 `DispatchResult` 交互的解释很有价值。

- **Doc-5**: `groupHandle` 的 KDoc 诚实地说明了单协程瓶颈（使用 ⚠️ 标记）并提供了缓解指导。这种内部文档的透明度值得称赞。

- **Doc-6**: `eventFlow` 的 KDoc 清楚地警告了事件丢失场景（无订阅者、管道拥塞）并提供了展示正确的"先订阅后 dispatch"模式的代码示例。好。

- **Doc-7**: `Snapshot.updateState` 的 KDoc 明确警告了在单个 `PartialChange` 中 `withEvent().updateState()` 的陷阱，并附带了"❌"和"✅"的代码示例。非常有用。

- **Doc-8**: **[缺失] `MviExtensions.kt` 和 `IntentTransformers.kt` 中 `@file:OptIn(FlowPreview::class)` 注解缺少 KDoc。** 引入这些 API 的用户需要知道他们应该 opt-in。添加简要的文件级 KDoc 注释会有所帮助。

- **Doc-9**: **[缺失] 缺少外部包级别文档**（没有 `package-info.kt` 或 `package.html`）。公共 API 表面有良好的独立 KDoc，但没有一个文档描述包结构供用户浏览 API。

- **Doc-10**: **[微小不一致]** 部分 KDoc 代码示例使用 ```（三重反引号），而另一些使用 `（行内反引号风格）。格式不统一。

- **Doc-11**: `IntentHandlerRegistry.register` 的 KDoc 正确记录了精确类匹配的限制，包括关于子类 dispatch 回退到 `defaultHandler` 的说明。好。

- **Doc-12**: core 的 `build.gradle.kts` 没有依赖版本注释，但版本目录（`libs.versions.toml`）组织良好。可接受。

- **Doc-13**: 测试文件名清晰（`MviTest`、`ReactiveContractImplTest`、`MviExtensionsTest`、`LoggerTest`、`FatalErrorHandlerTest`）。测试方法名使用 Kotlin 反引号格式配合描述性的自然语言。

- **Doc-14**: **[缺失]** `ReactiveContractLazy` 类的 KDoc 缺少关于 `LazyThreadSafetyMode.NONE` 意味着初始化器**在并发访问下可能被多次调用**的说明。KDoc 提到"可能被调用多次"，但没有明确警告后果（重复创建 contract）。由于 ViewModel 访问预期是单线程的，这可以接受，但更清晰的警告会更稳妥。

---

## 分类统计

| 类别 | 正向评价数 | 关注/建议数 |
|----------|----------|---------------------|
| **Design** | 10 项优点 | 3 项已知局限（groupHandle 瓶颈、DROP_OLDEST 事件丢失、全局单例） |
| **Bug** | 无确认缺陷 | 2 项边界情况（精确类查找、过时 channel 恢复） |
| **Name** | 8 项好的命名 | 2 项建议（StrategyReactiveContract/CoreReactiveContract 命名、asSingleFlow 简洁性） |
| **Style** | 9 项好的风格 | 3 项建议（测试框架一致性、空 TextWatcher 方法体、测试协程混用） |
| **Doc** | 12 项好的文档 | 4 项不足（OptIn 注解说明、包文档、代码块一致性、LazyThreadSafetyMode 清晰度） |

## 优化建议（可选）

下列建议不属于问题，仅供参考：

1. **中等**: 考虑为 `CoreReactiveContract` 添加一个 `dispatcher` 参数，允许注入自定义的 `CoroutineDispatcher`，而不是硬编码 `Dispatchers.Default`。这将提高可测试性并允许平台特定的调优。

2. **低**: `groupHandle` 的单协程瓶颈可以通过为每个组标签派生一个独立的 `produce` 协程来缓解，而不是通过单个 `collect` 循环路由所有 intent。这将消除队头阻塞，代价是增加协程开销。

3. **低**: 考虑将 `KMvi.Configuration` 的属性提取为显式传递给 contract 的独立配置对象，减少倾向于依赖注入的库使用者对全局可变单例的依赖。

4. **低**: 在 `MviExtensions.kt` 和 `IntentTransformers.kt` 中添加 `@file:OptIn` 说明，引导用户了解 `FlowPreview` 注解的要求。
