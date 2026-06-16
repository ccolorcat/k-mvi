# K-MVI 代码评审

评审范围：`core/src/main/java/cc/colorcat/mvi/**`（含 `internal/`），并参照 `app/` 样例验证运行场景。

评审基准：库面向 Android `ViewModel` + `LifecycleOwner` 使用，`dispatch` 由主线程调用，`IntentHandler.handle` 与 `PartialChange.apply` 默认在 `Dispatchers.Default` 上执行，真正阻塞的 I/O 由业务 handler 显式切到 `Dispatchers.IO`。所以"线程安全"只在跨主线程边界的位置才计入问题，纯主线程路径不在意争用。

---

## Design（架构 / 设计）

- ✅ **Design 1（已修复）**. `Contract<I, S, E>` 的 `I` 类型参数没有被消费——`stateFlow`、`eventFlow` 都只依赖 `S` 和 `E`，只有 `ReactiveContract.dispatch(intent: I)` 需要 `I`。结果是所有暴露给 UI 的只读 API 都被迫多带一个无用的泛型，调用方写 `Contract<MyIntent, MyState, MyEvent>` 没有任何收益。建议拆为 `Contract<S, E>`，让 `ReactiveContract<I, S, E> : Contract<S, E>` 单独引入 `I`。
- ✅ **Design 2（已修复）**. `IntentHandlerDelegate.handle`（`IntentHandlers.kt:247`）只要找不到针对 `intent.javaClass` 的精确 handler，就以 WARN 级别打 `"No handler registered for ..., fallback to defaultHandler"`。但 `LoginViewModel` 这种"集中式 `defaultHandler`"是被官方样例明确推荐的写法（见样例文档"centralized handling"），按现在的实现，登录这条业务路径上每一个 Intent 都会刷一条 WARN。fallback 在"defaultHandler 是空流"时该 WARN，但在"defaultHandler 是用户显式提供"时应该是 INFO/DEBUG 甚至静默。
- ✅ **Design 3（已修复）**. `StrategyIntentTransformer.assignGroupTag`（`IntentTransformers.kt:328`）对"同时实现 `Concurrent` 和 `Sequential`"的冲突 Intent 按 class 去重记录 WARN，并继续路由到 fallback group。冲突是 *类型级* 的，不是 *实例级* 的，重复分发同一冲突类型不会继续污染日志。
- ✅ **Design 4（已修复）**. `HybridConfig` 已去泛型，只保留业务无关的 HYBRID 运行参数；业务分组逻辑拆到 `GroupTagSelector<I>`，默认实现为 `GroupTagSelector.byClass()`。全局配置直接持有 `hybridConfig: HybridConfig`，不再混入 erased `Mvi.Intent` selector。
- ✅ **Design 5（已通过文档和诊断日志处理）**. `groupHandle`（`InternalExtensions.kt:127`）会缓存每个 group tag 的 channel，直到 upstream 完成或 channel 被检测为 stale/closed 后替换。主动 LRU 驱逐会破坏"同 tag 顺序处理"语义，因此不引入 `maxActiveGroups`。`GroupTagSelector` 与 `groupHandle` KDoc 已明确：高基数 data-tied tag（资源 id、用户 id、查询串等）会让 active group 常驻增长；除非确实需要 per-value ordering，否则应使用 bucketed tag（如 `"user"` 而不是 `"user-${userId}"`）。同时新增 `groupCountWarningThreshold` 稀疏告警：active group channel 数量达到阈值时输出 WARN，随后阈值翻倍，避免频繁刷日志。
- ✅ **Design 6（已修复）**. 管线已去掉 `flowOn(Dispatchers.IO)`，默认整条 `toPartialChange` / `retryWhen` / `scan` 处理链路在 `Dispatchers.Default` 上执行，只保留 `Default` 到 `shareIn` 的一个处理边界。真正阻塞的网络、数据库、文件 I/O 由业务 handler 自行 `withContext(Dispatchers.IO)` 或对阻塞源 Flow 使用 `flowOn(Dispatchers.IO)`。
- ✅ **Design 7（已重新评估，建议保留）**. `Mvi` 用一个 `object` 聚合 `Intent` / `State` / `Event` / `PartialChange` / `Snapshot` 并非单纯多一层命名空间。它给高频核心类型提供了稳定的 IDE 补全入口：用户只要记住 `Mvi.`，就能枚举全部 MVI 域类型；这比分别记 `PartialChange` / `Snapshot` / `State` 等通用词更容易。放到包顶级还会让 `Intent` 在 Android 项目中更容易与 `android.content.Intent` 和业务 `Contract.Intent` 撞名。因此不建议按“全部提升到包顶级”执行；若要降低书写成本，应优先在业务 contract 内封装短名，例如 `fun interface PartialChange : Mvi.PartialChange<State, Event>`，样例已经采用这种模式。
- ✅ **Design 8（已重新评估，建议保留）**. `ReadOnlyContract`（`Contracts.kt:292`）并不是无效包装。`asContract()` 返回私有 `ReadOnlyContract` 实例，而不是把原始 `ReactiveContract` 直接向上转型为 `Contract`；该包装只实现 `Contract<S, E>`，不实现 `ReactiveContract<I, S, E>`，因此调用方拿到它后无法通过普通 `as? ReactiveContract` 取回 `dispatch`。需要补充说明的是，防穿透成立的前提是 ViewModel 暴露 `reactiveContract.asContract()`；如果只是写 `val contract: Contract<S, E> = reactiveContract`，那仍然只是类型窄化，调用方可以强转回 `ReactiveContract`。
- ✅ **Design 9（已修复）**. `IntentHandler.handle` 已从 `suspend fun handle(intent): Flow<...>` 改为同步返回 `Flow<PartialChange>`。`Flow` 本身就是承载异步、多次 emission、取消和顺序语义的边界；外层 `suspend` 会让用户把耗时工作放在"返回 Flow 之前"，导致这部分工作不属于 `flatMapMerge` / `flatMapConcat` 管理的 inner Flow 生命周期，CONCURRENT / SEQUENTIAL 策略的执行时机更难解释。当前契约是：`handle(intent)` 只同步、轻量地构造 Flow，网络、数据库、delay、复杂业务等都放进返回的 `flow { ... }` 内。配套地，`register { PartialChange }` 便利重载也已从 `suspend (I) -> PartialChange` 改成普通 `(I) -> PartialChange`，并包装为 `IntentHandler { intent -> flow { emit(handler(intent)) } }`，避免 `handler(intent).asSingleFlow()` 在 Flow 创建前先执行 handler。

---

## Bug（实现正确性）

- ✅ **Bug 1（已修复）**. `ReactiveContractImpl.kt` 中 `SNAPSHOT_BUFFER_CAPACITY` 已移到 `CoreReactiveContract` 长 KDoc 之前，类 KDoc 现在正确挂在 `CoreReactiveContract` 上，不再误挂到常量声明。
- ✅ **Bug 2（已修复）**. `EventCollector.collectTyped(KClass, ...)` 已去掉误导性的 `requireNotNull(clazz.java.cast(it))`。上游 `flow: Flow<E>` 非 null，且前置 `clazz.isInstance(it)` 已完成类型过滤，动态 `KClass` 重载保留 `clazz.isInstance + clazz.java.cast` 即可。
- ✅ **Bug 3（已重新评估，通过文档契约处理）**. `MviExtensions` 里的 `doOnClick` / `doOnLongClick` / `doOnCheckedChange` / `doOnAfterTextChanged` 会在 `callbackFlow { ... }` 内注册 / 移除 View listener，理论上要求主线程；但库的常规生命周期 helper（`launchCollect` / `dispatchWithLifecycle`）都通过 `LifecycleOwner.lifecycleScope` 收集，正常 Fragment/View 用法已经满足。默认加 `.flowOn(Dispatchers.Main.immediate)` 会改变上游上下文并可能引入额外 Flow 边界，属于过度防御；当前选择是在 KDoc 明确这些 View Flow 必须在主线程收集，并推荐通过库提供的 lifecycle helper 使用。
- ✅ **Bug 4（已修复）**. `CoreReactiveContract` 已把 scope 必须包含 [Job] 变成显式不变量：构造时通过 `requireNotNull(scope.coroutineContext[Job])` 获取 `scopeJob`，并用它注册 `invokeOnCompletion { channel.close() }`。这让 channel 关闭和 `dispatch` 的活跃性判断共享同一个生命周期前提；无 Job scope 会在构造阶段失败，而不是留下永远不会随 scope 完成而关闭的 intent channel。公开 `ViewModel.contract(...)` 传入的 `viewModelScope` 本身满足该条件。
- Bug 5. `Mvi.Snapshot.updateState { ... }`（`Mvi.kt:336`）会无条件清掉 `event`：
  ```kotlin
  fun updateState(transform: S.() -> S): Snapshot<S, E> =
      this.copy(state = newState, event = null)
  ```
  当 handler 先 emit 一个带 event 的 PartialChange，紧接着另一个 PartialChange 调 `updateState { copy(loading=false) }` 收尾——后者就会把前者的 event 吞掉。KDoc 写了，但用 `updateState` 是默认手势，出错概率很高。要么把 `updateState` 改成"保留 event"语义、用 `updateWith(null, transform)` 显式清，要么至少在样例里展示一次踩坑场景。
- ✅ **Bug 6（已修复）**. `IntentHandlerDelegate.handlers` 与默认 `GroupTagSelector.byClass()` 均以运行时 `Class` 对象作为 key/tag，不再受 R8 / ProGuard 类名混淆影响。自定义 `GroupTagSelector` 仍应返回稳定、低基数、`equals/hashCode` 行为可靠的 tag。
- ✅ **Bug 7（已重新评估，降级为文档约束）**. `KMvi.setup`（`KMvi.kt:172`）确实是 `config = config.transform()` 这种非原子的读-改-写；并发调用时可能丢失其中一次基于旧快照生成的配置。但 KDoc 已明确 `setup` 非线程安全、只应在应用初始化主线程调用，因此这不构成当前公开契约下的实现正确性 Bug。保留 `@Volatile` 更合适：后台 Flow 管线可能读取 `KMvi.logger` / `retryPolicy` / `handleStrategy` 等全局配置，volatile 能保证读者看到已发布配置；代码和 KDoc 已补充说明它只保证可见性，不让并发 `setup` 变成原子操作。

---

## Name（命名）

- Name 1. `Contract` 名字太通用，与 Kotlin `kotlin.contracts.contract` DSL 撞概念；从外部 import 时常常需要全限定。`MviContract` 或 `MviStore` 更易检索。
- Name 2. `HybridConfig` 没有体现归属——它是 "HYBRID 策略的分组配置"。`HybridStrategyConfig` 或 `HybridGroupingConfig` 更清晰，对应 `IntentQueueConfig` 这种命名风格。
- Name 3. `KMvi.setup` 与 `StrategyReactiveContract.setupIntentHandlers` 用同一个动词 `setup` 表达两件事（全局配置 vs 注册 handler）。Kotlin 习惯 `configure` 表"全局/一次性设置"，`register` 表"加东西到集合"，可以拉齐。
- Name 4. `asSingleFlow()` 定义在 `T`（泛型顶层）上（`MviExtensions.kt:44`），等同于 `Any?.asSingleFlow()`，会污染整个项目的自动补全。建议改为 `internal` 或限定 receiver 为 `Mvi.PartialChange<*, *>`。
- Name 5. `launchCollect`（两个重载）和 `launchWithLifecycle` 语义高度重叠：前者 KDoc 说自己是"low-level"，后者 KDoc 又说自己是"基础工具"。两者实现也几乎一致，建议保留一个公开名（例如 `launchWithLifecycle`），另一个降为 `internal`。
- Name 6. `dispatchWithLifecycle` 的签名 `fun <I, R> Flow<I>.dispatchWithLifecycle(..., dispatch: (I) -> R)` 用 `R` 仅仅是为了"接受 `contract::dispatch` 而不强制 Unit 返回值"。这是工程权宜之计，应在 KDoc 上挑明，或者改用 `(I) -> Any?`。
- Name 7. `Mvi.PartialChange` 名字里只说"state"——但实现里它既能改 state 又能附带 event，apply 后还可能只动 event。`SnapshotUpdate` 或 `StateMutation` 更贴近实际语义。
- Name 8. `StateCollector.collectPartial(KProperty1<S, A>, ...)`：这里 "partial" 指的是"一个 property"，但库里 `Mvi.PartialChange` 的 "partial" 含义完全不同（更新 *部分字段*）。同一个词意义混用，读者得在两套语境间切换。`collectProperty` 更直白。
- Name 9. `DispatchResult.Inactive` 与 `DispatchResult.Closed` 对调用方来说没有可操作区别——都是"我没排进队"。强行二分让 `when` 多写一支。合并为单个 `Rejected(reason)` 或 `NotAccepted` 更轻量。
- ✅ **Name 10（已修复）**. `IntentHandler.handle` 已去掉 `suspend`，签名现在更准确地表达契约：handler 同步构造 Flow，异步处理发生在返回的 Flow 内（参见 Design 9）。

---

## Style（Kotlin 风格 / 简洁性）

- Style 1. 保留 `Mvi.` 作为核心类型聚合入口，但文档和样例应更明确推荐“业务侧短名封装”来减少重复书写：例如在每个 feature contract 中定义 `sealed interface Intent : Mvi.Intent`、`data class State(...) : Mvi.State`、`sealed interface Event : Mvi.Event`、`fun interface PartialChange : Mvi.PartialChange<State, Event>`。这样既保留 `Mvi.` 的 IDE 可发现性和 Android 命名避让，又让 ViewModel 里的签名保持短。
- Style 2. KDoc 之间存在大量重复——"Intent → PartialChange → State → View" 流程图、HandleStrategy 对比表、HYBRID 三类 Intent 的解释，在 `Mvi.kt`、`Contracts.kt`、`HandleStrategies.kt`、`IntentHandlers.kt`、`IntentTransformers.kt`、`ReactiveContractImpl.kt` 各写了一遍。一次写在 `Mvi.kt` 或 README，其他位置用 `@see` 即可，避免说法漂移。
- Style 3. `ReadOnlyContract`（`Contracts.kt:292`）写成：
  ```kotlin
  override val stateFlow: StateFlow<S> get() = source.stateFlow
  override val eventFlow: Flow<E> get() = source.eventFlow
  ```
  既然 `source` 是不可变 `val`，对应 flow 也是不可变属性，去掉 `get()`、直接 `= source.stateFlow` 就行；最短写法是 `class ReadOnlyContract<...>(source: ReactiveContract<...>) : Contract<...> by source` 委托。
- Style 4. `IntentTransformer.Companion.invoke(...)` 已经是 SAM-fun-interface，可以直接 `IntentTransformer { ... }` 构造；再写一个 `internal operator fun invoke(handleStrategy, config, handler)` 等于把 framework 内部工厂塞进公开 companion，污染对外类型。改成包内 `internal fun strategyTransformer(...)` 函数更合 Kotlin 习惯。
- Style 5. `StrategyReactiveContract` 提供两个构造器，只为切换"传 `IntentHandlerDelegate`"还是"传 `IntentHandler` 后内部包一层 delegate"。可以删私构造，用 `init { ... }` 初始化 delegate；或者直接给公共构造，private 那条根本没人需要。
- Style 6. `MviExtensions.doOnClick` 里 `this.block()`（`MviExtensions.kt:176, 207, 237`）的显式 `this.` 是冗余的，`block()` 即可。
- Style 7. `Mvi.Snapshot.updateState` 和 `updateWith` 都先存中间局部变量再 `this.copy(...)`。两个 `this.` 都可去；`updateState` 一行：`copy(state = state.transform(), event = null)`。
- Style 8. `Logger`（`Logger.kt:90-99`）默认实现用 `buildString { appendLine(message()); appendLine(error.getStackTraceString()) }`，两次 `appendLine` 留下一个尾随换行，再交给 `Log.println`——`Log.println` 自己也会换行，结果日志多一空行。换成 `appendLine(message()).append(error.getStackTraceString())` 或直接 `"${message()}\n${error.getStackTraceString()}"`。
- Style 9. `IntentQueueConfig` 用具名实参调用 `requireSupportedChannelConfig(name = ..., capacity = ..., onBufferOverflow = ...)`，`HybridConfig` 却只 positional 两参。统一一种风格便于阅读。
- Style 10. `@OptIn(FlowPreview::class)` 在 `StrategyIntentTransformer.transform`、`StrategyIntentTransformer.handleByTag`、`MviExtensions.doOnAfterTextChanged` 各写一次。这两个文件里其他位置也都用了 `flatMapMerge` / `debounce`，统一在文件头 `@file:OptIn(FlowPreview::class)` 一次说明更整齐，也防止后续新增方法漏写。
- Style 11. `Snapshot.withEvent(event: E)` 和 `Snapshot.updateWith(event: E, transform)` 都强制 `event` 非 null。要想"清掉 event"只能调 `updateState`（连带强制改 state）。补一个 `Snapshot.clearEvent(): Snapshot<S, E> = copy(event = null)` 或者把 `withEvent` 参数改成 `E?`，对称性更好。
- Style 12. `MviCollects.collectTypedEvent`（`MviCollects.kt:457`）是 `Flow<Mvi.Event>.collectTypedEvent`，但 `EventCollector.collectTyped` 已经覆盖同样场景且能复用 supervisor job。两个 API 做同一件事，至少在 KDoc 给个倾向说明，或将其中一个降为 `internal`。

---

## Doc（文档准确性 / 完整性）

- Doc 1. `Contracts.kt` 第 23-24 行示例：
  ```kotlin
  private val contract: Contract<MyIntent, MyState, MyEvent> by viewModels()
  ```
  `by viewModels()` 返回 `ViewModel`，不可能赋给 `Contract`。这段代码不可编译。
- Doc 2. `ReactiveContract` KDoc 第 140-144 行示例调 `mviViewModel(scope = viewModelScope, initState = MyState(), defaultHandler = ::handleIntent)`——仓库里没有 `mviViewModel` 函数，工厂是 `ViewModel.contract(...)`。
- Doc 3. `Contract.stateFlow` KDoc 说 "Conflated: Only the latest state is kept, intermediate states may be skipped"。`StateFlow` 的 conflation 行为是 *按 `equals` 去重*，"可能跳过中间值"成立但缺了 `equals` 这层关键约束；用户照字面读会误以为是按时间 conflate。
- ✅ **Doc 4（已修复）**. `ReactiveContractImpl.kt` 的 `CoreReactiveContract` 长 KDoc 已紧贴 class 声明，Dokka / IDE 会把它归到类上。
- Doc 5. `Mvi.Snapshot.updateState` KDoc 提到"会清空 event，需要保留请用 `updateWith`"，但示例里没有出现"先 event 再 updateState 导致 event 丢失"的反面案例。这种 API 的"反例样板"对避免 Bug 5 更有效。
- ✅ **Doc 6（已修复）**. 业务分组入口已改为 `groupTagSelector = GroupTagSelector<MyIntent> { ... }`，`HybridConfig` 只保留业务无关运行参数；相关 KDoc 明确默认 `byClass()` 返回运行时 `Class`，自定义 tag 需要稳定且低基数。
- ✅ **Doc 7（已修复）**. `MviExtensions.doOnClick` / `doOnLongClick` / `doOnCheckedChange` / `doOnAfterTextChanged` 的 KDoc 已补充主线程收集约束，并说明 `launchCollect` / `dispatchWithLifecycle` 的常规 lifecycle 用法满足该要求；样例 `doOnTextChanged` 也同步补充说明。
- Doc 8. `Contract.eventFlow` KDoc 第 262 行示例：
  ```kotlin
  viewModel.eventFlow.collectEvent(viewLifecycleOwner) { ... }
  ```
  里面的 lambda 是 `EventCollector<E>.() -> Unit`，必须用 `collectAll { ... }` 或 `collectTyped<...> { ... }` 才能消费事件——直接 `it -> ...` 是无法编译的。示例需要补全 DSL 用法。
- Doc 9. `MviViewModels.kt` 第 47-67 行示例：
  ```kotlin
  transformer = { intent ->
      when (intent) {
          is MyIntent.Load -> flow { ... }
          ...
      }
  }
  ```
  但 `IntentTransformer.transform(intentFlow: Flow<I>): Flow<...>` 接受的是 *Flow* 而不是单个 intent。要么签名错，要么 lambda 错；现状无法编译。建议示例改为 `IntentTransformer { intentFlow -> intentFlow.flatMapConcat { ... } }`。
- Doc 10. `IntentHandlerDelegate` KDoc 大段解释"为何对每个 intent 都打 INFO 日志"，并未提到"fallback to defaultHandler 那条 WARN 在集中式 handler 模式下会刷屏"的副作用——而集中式 handler 正是另一个样例 `LoginViewModel` 推荐的写法。两个文档加起来形成自相矛盾的指导。
- ✅ **Doc 11（已修复）**. `KMvi.setup` KDoc 写"NOT thread-safe，主线程调用"，但同一个文件里 `private @Volatile var config` 给读者错觉——以为安全只差临界区。KDoc 和字段注释已补充：`@Volatile` 仅保证后台读取者能看到最新发布的配置，不保证 `setup` 的读-改-写原子性，多线程并发调用仍可能丢更新。
- Doc 12. `Mvi.Snapshot` 是 `public data class`，构造器对外可见但 KDoc 没说明"用户是否应该手动构造 Snapshot"。从用法上 Snapshot 只在 `apply` 内被读、在测试里被构造，建议在 KDoc 标明"主要用于框架内部 / 测试；业务代码应通过 `updateState` / `withEvent` / `updateWith` 派生"。
- Doc 13. `Mvi` KDoc 顶部用 `Author / Date / GitHub:` 三行而非 KDoc 标准的 `@author`，全仓统一这种风格没问题，但 IDE / Dokka 不会把它识别为元数据；如果项目希望 Dokka 渲染出作者信息，改用 `@author ccolorcat` 才能生效。

---

## 总评

总体架构清晰、层次合理：`Mvi` 域类型 → `PartialChange` 累加 → `Snapshot` → `StateFlow` / `SharedFlow` 的拆分恰当；HYBRID 策略的"组内串行、组间并行"是这类库里少见但非常实用的设计；`IntentQueueConfig` / `DispatchResult` 把 Channel 语义体面地包装出来，没让 Channel 概念漏到 UI 层。

主要改进方向集中在三处：
1. **削减"为细节而细节"的日志与文档冗余**——`IntentHandlerDelegate` 的 fallback WARN、`assignGroupTag` 的冲突 WARN、各文件重复粘贴的 MVI 概述都该精简。
2. **真正不变的契约写到 KDoc，可变 / 易踩的契约配示例反面教材**——`Snapshot.updateState` 会清 event、`groupHandle` 会常驻 Channel、`do*` 系列要求主线程，这些都该有"踩坑→怎么避免"的样例段。
3. **保留 `Mvi.` 作为核心类型补全入口，同时推广业务侧短名封装**——`Mvi.` 对新用户更容易记忆和补全，也能避免顶级 `Intent` 与 Android / 业务类型撞名；日常签名的简化应交给 feature contract 内部的 `Intent` / `State` / `Event` / `PartialChange` 短名。

Bug 级别的问题数量不多，且大多数是文档型（示例错、KDoc 挂错位置）。Bug 3 已重新评估为 View Flow 的主线程收集契约问题，并通过 KDoc 处理；Bug 4 已通过显式要求 scope 包含 `Job` 修复。当前剩余问题大多是 API 语义、文档准确性和样例指导层面的改进，不阻塞当前 release。
