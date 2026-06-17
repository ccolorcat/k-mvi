# K-MVI 代码评审

评审范围：`core/src/main/java/cc/colorcat/mvi/**`（含 `internal/`），并参照 `app/` 样例验证运行场景。

评审基准：库面向 Android `ViewModel` + `LifecycleOwner` 使用，`dispatch` 由主线程调用，`IntentHandler.handle` 与 `PartialChange.apply` 默认在 `Dispatchers.Default` 上执行，真正阻塞的 I/O 由业务 handler 显式切到 `Dispatchers.IO`。所以"线程安全"只在跨主线程边界的位置才计入问题，纯主线程路径不在意争用。

---

## Design（架构 / 设计）

- ✅ **Design 1（已修复）**. `Contract<I, S, E>` 的 `I` 类型参数没有被消费——`stateFlow`、`eventFlow` 都只依赖 `S` 和 `E`，只有 `ReactiveContract.dispatch(intent: I)` 需要 `I`。结果是所有暴露给 UI 的只读 API 都被迫多带一个无用的泛型，调用方写 `Contract<MyIntent, MyState, MyEvent>` 没有任何收益。建议拆为 `Contract<S, E>`，让 `ReactiveContract<I, S, E> : Contract<S, E>` 单独引入 `I`。
- ✅ **Design 2（已修复）**. `IntentHandlerDelegate.handle`（`IntentHandlers.kt:247`）只要找不到针对 `intent.javaClass` 的精确 handler，就以 WARN 级别打 `"No handler registered for ..., fallback to defaultHandler"`。但 `LoginViewModel` 这种"集中式 `defaultHandler`"是被官方样例明确推荐的写法（见样例文档"centralized handling"），按现在的实现，登录这条业务路径上每一个 Intent 都会刷一条 WARN。fallback 在"defaultHandler 是空流"时该 WARN，但在"defaultHandler 是用户显式提供"时应该是 INFO/DEBUG 甚至静默。
- ✅ **Design 3（已修复）**. `StrategyIntentTransformer.assignGroupTag`（`IntentTransformers.kt:328`）对"同时实现 `Concurrent` 和 `Sequential`"的冲突 Intent 按 class 去重记录 WARN，并继续路由到 fallback group。冲突是 *类型级* 的，不是 *实例级* 的，重复分发同一冲突类型不会继续污染日志。
- ✅ **Design 4（已修复）**. `HybridStrategyConfig` 已去泛型，只保留业务无关的 HYBRID 运行参数；业务分组逻辑拆到 `GroupTagSelector<I>`，默认实现为 `GroupTagSelector.byClass()`。全局配置直接持有 `hybridStrategyConfig: HybridStrategyConfig`，不再混入 erased `Mvi.Intent` selector。
- ✅ **Design 5（已通过文档和诊断日志处理）**. `groupHandle`（`InternalExtensions.kt:127`）会缓存每个 group tag 的 channel，直到 upstream 完成或 channel 被检测为 stale/closed 后替换。主动 LRU 驱逐会破坏"同 tag 顺序处理"语义，因此不引入 `maxActiveGroups`。`GroupTagSelector` 与 `groupHandle` KDoc 已明确：高基数 data-tied tag（资源 id、用户 id、查询串等）会让 active group 常驻增长；除非确实需要 per-value ordering，否则应使用 bucketed tag（如 `"user"` 而不是 `"user-${userId}"`）。同时新增 `groupCountWarningThreshold` 稀疏告警：active group channel 数量达到阈值时输出 WARN，随后阈值翻倍，避免频繁刷日志。
- ✅ **Design 6（已修复）**. 管线已去掉 `flowOn(Dispatchers.IO)`，默认整条 `toPartialChange` / `retryWhen` / `scan` 处理链路在 `Dispatchers.Default` 上执行，只保留 `Default` 到 `shareIn` 的一个处理边界。真正阻塞的网络、数据库、文件 I/O 由业务 handler 自行 `withContext(Dispatchers.IO)` 或对阻塞源 Flow 使用 `flowOn(Dispatchers.IO)`。
- ✅ **Design 7（已重新评估，建议保留）**. `Mvi` 用一个 `object` 聚合 `Intent` / `State` / `Event` / `PartialChange` / `Snapshot` 并非单纯多一层命名空间。它给高频核心类型提供了稳定的 IDE 补全入口：用户只要记住 `Mvi.`，就能枚举全部 MVI 域类型；这比分别记 `PartialChange` / `Snapshot` / `State` 等通用词更容易。放到包顶级还会让 `Intent` 在 Android 项目中更容易与 `android.content.Intent` 和业务 `Contract.Intent` 撞名。因此不建议按“全部提升到包顶级”执行；若要降低书写成本，应优先在业务 contract 内封装短名，例如 `fun interface PartialChange : Mvi.PartialChange<State, Event>`，样例已经采用这种模式。
- ✅ **Design 8（已重新评估，建议保留）**. `ReadOnlyContract`（`Contracts.kt:292`）并不是无效包装。`asContract()` 返回私有 `ReadOnlyContract` 实例，而不是把原始 `ReactiveContract` 直接向上转型为 `Contract`；该包装只实现 `Contract<S, E>`，不实现 `ReactiveContract<I, S, E>`，因此调用方拿到它后无法通过普通 `as? ReactiveContract` 取回 `dispatch`。需要补充说明的是，防穿透成立的前提是 ViewModel 暴露 `reactiveContract.asContract()`；如果只是写 `val contract: Contract<S, E> = reactiveContract`，那仍然只是类型窄化，调用方可以强转回 `ReactiveContract`。
- ✅ **Design 9（已修复）**. `IntentHandler.handle` 已从 `suspend fun handle(intent): Flow<...>` 改为同步返回 `Flow<PartialChange>`。`Flow` 本身就是承载异步、多次 emission、取消和顺序语义的边界；外层 `suspend` 会让用户把耗时工作放在"返回 Flow 之前"，导致这部分工作不属于 `flatMapMerge` / `flatMapConcat` 管理的 inner Flow 生命周期，CONCURRENT / SEQUENTIAL 策略的执行时机更难解释。当前契约是：`handle(intent)` 只同步、轻量地构造 Flow，网络、数据库、delay、复杂业务等都放进返回的 `flow { ... }` 内。配套地，`register { PartialChange }` 便利重载也已从 `suspend (I) -> PartialChange` 改成普通 `(I) -> PartialChange`，并包装为 `IntentHandler { intent -> flow { emit(handler(intent)) } }`，避免 `handler(intent).asSingleFlow()` 在 Flow 创建前先执行 handler。

---

## Bug（实现正确性）

- ✅ **Bug 1（已修复）**. `ReactiveContractImpl.kt` 中 `SNAPSHOT_BUFFER_CAPACITY` 已移到 `CoreReactiveContract` 长 KDoc 之前，类 KDoc 现在正确挂在 `CoreReactiveContract` 上，不再误挂到常量声明。
- ✅ **Bug 2（已修复）**. `EventCollector.collectTyped(KClass, ...)` 已去掉误导性的 `requireNotNull(clazz.java.cast(it))`。上游 `flow: Flow<E>` 非 null，且前置 `clazz.isInstance(it)` 已完成类型过滤，动态 `KClass` 重载保留 `clazz.isInstance + clazz.java.cast` 即可。
- ✅ **Bug 3（已重新评估，通过文档契约处理）**. `MviExtensions` 里的 `doOnClick` / `doOnLongClick` / `doOnCheckedChange` / `doOnAfterTextChanged` 会在 `callbackFlow { ... }` 内注册 / 移除 View listener，理论上要求主线程；但库的常规生命周期 helper（`launchWithLifecycle` / `dispatchWithLifecycle`）都通过 `LifecycleOwner.lifecycleScope` 收集，正常 Fragment/View 用法已经满足。默认加 `.flowOn(Dispatchers.Main.immediate)` 会改变上游上下文并可能引入额外 Flow 边界，属于过度防御；当前选择是在 KDoc 明确这些 View Flow 必须在主线程收集，并推荐通过库提供的 lifecycle helper 使用。
- ✅ **Bug 4（已修复）**. `CoreReactiveContract` 已把 scope 必须包含 [Job] 变成显式不变量：构造时通过 `requireNotNull(scope.coroutineContext[Job])` 获取 `scopeJob`，并用它注册 `invokeOnCompletion { channel.close() }`。这让 channel 关闭和 `dispatch` 的活跃性判断共享同一个生命周期前提；无 Job scope 会在构造阶段失败，而不是留下永远不会随 scope 完成而关闭的 intent channel。公开 `ViewModel.contract(...)` 传入的 `viewModelScope` 本身满足该条件。
- ✅ **Bug 5（已重新评估，误诊不修复）**. 原报告称"handler 先 emit 一个带 event 的 PartialChange，紧接着另一个 PartialChange 调 `updateState` 收尾，后者吞掉前者的 event"——这是误诊。关键在 `eventFlow = snapshots.mapNotNull { it.event }`（`ReactiveContractImpl.kt:265`），而 `scan`（`:202`）**对每个 PartialChange 都会发出一个独立的中间 snapshot**。两段式 emit 会依次产出 `(s1, event)` 和 `(s2, null)` 两帧，`eventFlow` 在第一帧就已投递事件；第二个 `updateState` 清的是它自己那帧的 event，吞不掉已投递的事件。唯一真实丢事件路径是 buffer `DROP_OLDEST` 拥塞，已由 `ReactiveContractImplTest.kt:322` 测试和 `eventFlow` KDoc 单独覆盖，与 `updateState` 无关。
  更重要的是，`updateState` 清 event 是**承重设计、不能反转**：因为任何仍带非空 event 的 snapshot 都会被 `mapNotNull` 再投递一次，事件必须严格"只活一帧"。若按原建议把 `updateState` 改成"保留 event"，则一次事件之后的**每一次普通状态更新都会重复投递该事件**（重复 toast / 重复导航），直到用户显式清除——这是把一个不存在的问题换成一个高频、难察觉的真实 Bug。`updateState` 是 MVI 最高频手势，"清 event"必须是其默认语义。
  真实存在但很窄的隐患是**单个 apply 内**链式 `withEvent(...).updateState { ... }`（清掉刚设的 event），正解为 `updateWith(...)`；该反例样板已补入 `updateState` KDoc（并见 Doc 5）。代码语义保持不动。
- ✅ **Bug 6（已修复）**. `IntentHandlerDelegate.handlers` 与默认 `GroupTagSelector.byClass()` 均以运行时 `Class` 对象作为 key/tag，不再受 R8 / ProGuard 类名混淆影响。自定义 `GroupTagSelector` 仍应返回稳定、低基数、`equals/hashCode` 行为可靠的 tag。
- ✅ **Bug 7（已重新评估，降级为文档约束）**. `KMvi.configure`（`KMvi.kt:178`）确实是 `config = config.transform()` 这种非原子的读-改-写；并发调用时可能丢失其中一次基于旧快照生成的配置。但 KDoc 已明确 `configure` 非线程安全、只应在应用初始化主线程调用，因此这不构成当前公开契约下的实现正确性 Bug。保留 `@Volatile` 更合适：后台 Flow 管线可能读取 `KMvi.logger` / `retryPolicy` / `handleStrategy` 等全局配置，volatile 能保证读者看到已发布配置；代码和 KDoc 已补充说明它只保证可见性，不让并发 `configure` 变成原子操作。

---

## Name（命名）

- Name 1（暂不处理）. `Contract` 名字太通用，与 Kotlin `kotlin.contracts.contract` DSL 撞概念；从外部 import 时常常需要全限定。已决定当前保留 `Contract`。
- ✅ **Name 2（已修复）**. `HybridConfig` 已改为 `HybridStrategyConfig`，公开参数 / 全局配置属性同步从 `hybridConfig` 改为 `hybridStrategyConfig`，明确它归属于 [HandleStrategy.HYBRID]。
- ✅ **Name 3（已修复）**. 全局配置入口已从 `KMvi.setup` 改为 `KMvi.configure`，表达“配置全局框架设置”；`StrategyReactiveContract.setupIntentHandlers` 暂按当前决策保留不动。
- ✅ **Name 4（已修复）**. `asSingleFlow()` 已从任意 `T` receiver 收窄为 `Mvi.PartialChange<S, E>` receiver，不再污染项目里所有类型的自动补全。
- ✅ **Name 5（已修复）**. 两个公开 `launchCollect` 重载已删除，生命周期感知收集统一保留 `launchWithLifecycle`；MVI state / event 仍优先使用 `collectState` / `collectEvent` DSL。
- ✅ **Name 6（已修复）**. `dispatchWithLifecycle` 已去掉额外的 `R` 泛型，签名改为 `dispatch: (I) -> Unit`，函数自身仍返回可取消的 `Job`。
- ✅ **Name 7（已重新评估，建议保留 `PartialChange`）**. 评审最初认为名字偏向 "state"、建议改为 `SnapshotUpdate` / `StateMutation`，这是误读，被旧 KDoc 强化了。正确模型是更高一层的"帧迁移"：一个 `Snapshot` 是一帧完整的画面描述（state + 可选 event），新一帧永远从上一帧迁移而来，而 `PartialChange` 就是这次迁移——只更新上一帧描述里的*一部分*（某些 state 字段、和/或那条 event），其余从上一帧顺延。`apply(old: Snapshot): Snapshot` 这个签名本身已表达"拿 old 派生 new"的增量语义。因此 "partial" 的参照系是*整帧 Snapshot*，名字选得恰当；改为 `SnapshotUpdate` 会丢失 "partial（只改部分）" 与 "从上一帧迁移" 两层含义，`StateMutation` 还额外把范围窄回 state 并用 "mutation" 错误暗示可变。真正的缺陷在 KDoc：`Mvi.kt` "What is Partial" 段把 "partial" 锚成了"state 的部分字段"，已重写为以整帧（Snapshot）为参照系。
- ✅ **Name 8（已修复）**. `collectPartial` 的 "partial" 与 `Mvi.PartialChange` 的 "partial" 含义不同（前者指"恰好一个 property"，后者指"对一帧 Snapshot 的部分迁移"），既不精确又造成术语重载。已将 DSL 成员 `StateCollector.collectPartial` 和独立扩展 `Flow<S>.collectPartialState` **统一重命名为 `collectProperty`**：① 名字与入参 `KProperty1`（Kotlin 称 property）精确对应；② 成员与扩展 receiver 不同（`StateCollector` 不是 `Flow`），永不冲突，是地道 Kotlin 写法；③ 扩展的 receiver 本身即 state flow，域已由 receiver 表达，故不加 `State` 后缀（避免 `stateFlow.collectStateProperty` 叠词）。`collectWhole` 不变。KDoc 链接已消歧（成员引用写 `[StateCollector.collectProperty]`，独立函数补 `@see` 互链）。事件侧 `collectTypedEvent` 已按同理简化为 `collectTyped`（见 Style 12）。
- ✅ **Name 9（已修复）**. `DispatchResult` 已收敛为三态 `Submitted` / `Full` / `Unavailable`。`Inactive` 与 `Closed` 本是同一根因：入口 channel 只由 `scopeJob.invokeOnCompletion { channel.close() }` 关闭，故 channel-closed ⟺ scope 完成 ⟺ `!scope.isActive`；`dispatch()` 先查 `isActive` 返回，`Closed` 仅在极窄竞态下可达且含义相同，对调用方无可操作差异。二者合并为单个 **`Unavailable`**（面向调用方、与成因无关的终态伞形词，不泄漏 `scope.isActive` 实现细节）。`Full` 保留独立——它是活契约上的瞬时背压（可重试），KDoc 已明确 `Unavailable`（终态）与 `Full`（瞬时）的对比。`Unavailable` 保持无 payload 的 `data object`：不携带异常信息（合并的前提即“无可操作差异”，且 `close()` 无 cause、scope-inactive 路径根本无异常，诊断由内部两条 `logger.w` 文案承担）。否决了 `Rejected(reason)`（重新引入非可操作区分）与 `NotAccepted`（与 `Full` 概念打架）。
- ✅ **Name 10（已修复）**. `IntentHandler.handle` 已去掉 `suspend`，签名现在更准确地表达契约：handler 同步构造 Flow，异步处理发生在返回的 Flow 内（参见 Design 9）。

---

## Style（Kotlin 风格 / 简洁性）

- ⏸️ **Style 1（已基本满足，文档项待办）**. 样例已全面采用“业务侧短名封装”模式（见 `CounterContract.kt`：`data class State(...) : Mvi.State`、`sealed interface Event : Mvi.Event`、`sealed interface Intent : Mvi.Intent.Sequential`、`fun interface PartialChange : Mvi.PartialChange<State, Event>`）。剩余唯一动作是在 README 显式把它写成推荐范式，属独立文档任务，不并入本批代码清理。
- ⏸️ **Style 2（暂缓，需保守处理）**. KDoc 跨文件重复确实存在，但不宜一刀切用 `@see` 替换：间接化会牺牲 IDE 悬浮 / Dokka 页面的本地自洽性。结论：以 `Mvi.kt` 为规范源，仅删除最逐字的重复段、其余留摘要+`@see`。属较大文档工程且易引入“说法漂移”，作为独立任务处理，不并入本批。
- ✅ **Style 3（已修复）**. `ReadOnlyContract`（`Contracts.kt`）原先手写 `stateFlow`/`eventFlow` 两个 `get()` 转发，已改为 `: Contract<S, E> by source` 接口委托。`Contract` 只有两个只读属性，委托完全等价；且委托对象只实现 `Contract`，仍无法通过 cast 触达 `dispatch`，防穿透语义不变。
- ✅ **Style 4（已修复）**. 删除了 `IntentTransformer` companion 里的 `internal operator fun invoke(...)`（它把框架内部工厂塞进公开类型的补全入口），改为包内顶层 `internal fun strategyTransformer(...)`。唯一调用点（`ReactiveContractImpl.kt`）及两个测试文件已同步更新。
- ❌ **Style 5（不修改，已否决）**. `StrategyReactiveContract` 的双构造不是冗余：私有主构造接收 `delegate`，把同一实例**既传给 super 的 `transformer = strategyTransformer(..., delegate)`、又作为属性持有**供后续 `setupIntentHandlers` 变更。建议的 `init { ... }` 行不通——`init` 在 super 构造**之后**才运行，无法在 super 构造期提供 delegate。这是 Kotlin 共享“喂 super + 持有”实例的地道写法，保持现状。
- ✅ **Style 6（已修复）**. 删除 `doOnClick` / `doOnLongClick` / `doOnCheckedChange` 中冗余的 `this.block(...)`。**保留** `doOnAfterTextChanged` 中的 `this@callbackFlow.block(s)`——它在匿名 `TextWatcher` 内部，`this` 指向 watcher，不限定 receiver 就会编译错误（原条目漏看了这点）。
- ✅ **Style 7（已修复）**. `Snapshot.updateState` / `updateWith` 去掉中间局部变量与冗余 `this.`，压成一行 `copy(state = state.transform(), event = ...)`；`withEvent` 的 `this.copy` 同样去掉。
- ✅ **Style 8（已修复）**. `Logger` 默认实现的第二个 `appendLine` 改为 `append`，消除尾随换行与 `Log.println` 自带换行叠加产生的空行。
- ✅ **Style 9（已满足）**. 复核发现 `HybridStrategyConfig.init` 现已与 `IntentQueueConfig` 一致，均用具名实参调用 `requireSupportedChannelConfig(name = ..., capacity = ...)`。此条目过期，无需改动。
- ✅ **Style 10（已修复）**. `IntentTransformers.kt`、`MviExtensions.kt` 各自的散落 `@OptIn(FlowPreview::class)`（共 3 处）已提升为文件头 `@file:OptIn(FlowPreview::class)`，避免后续新增方法漏写。
- ❌ **Style 11（不修改，已否决）**. 帧模型下 event 仅存活一帧、随下一帧 `updateState` 自动清除，不存在“需单独清 event”的常见场景，`clearEvent()` 属 YAGNI；把 `withEvent` 改成 `E?` 则语义混乱（“with event null”读不通）。确需“保 state、丢 pending event”，`Snapshot` 是公开 data class，`it.copy(event = null)` 已是现成逃生口，不增设命名 API。
- ✅ **Style 12（已修复）**. 独立函数 `collectTypedEvent` 已重命名为 `collectTyped`，与 `EventCollector.collectTyped` 成员同名（receiver 不同，永不冲突：`collectEvent { }` 块内解析到成员、裸 `eventFlow` 上解析到扩展）。这与状态侧 `collectProperty`（成员 supervised / 独立 one-off）是同一套模式：成员在 DSL 内共享 supervisor job，独立函数用于一次性收集。两者关系通过 KDoc 互链（`@see EventCollector.collectTyped` / `@see collectEvent`）说明，无需把任何一个降为 `internal`。同时去掉了 `eventFlow.collectTypedEvent`（event…Event）的叠词。

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
- ✅ **Doc 5（已修复）**. `Mvi.Snapshot.updateState` KDoc 已补"反例样板"：明确单个 apply 内 `withEvent(...).updateState { ... }` 会清掉刚设的 event（❌），正解为 `updateWith(...) { ... }`（✅），并说明这只发生在*单次 change 内*——跨 PartialChange 的 emit 是安全的（带 event 的帧会先投递再被下一帧清除）。同时 `State` / `Event` / `Snapshot` 的 KDoc 已用"帧模型"统一表述：State 跨帧持续，Event 只活一帧、有消费者则投递否则丢弃。参见 Bug 5。
- ✅ **Doc 6（已修复）**. 业务分组入口已改为 `groupTagSelector = GroupTagSelector<MyIntent> { ... }`，`HybridStrategyConfig` 只保留业务无关运行参数；相关 KDoc 明确默认 `byClass()` 返回运行时 `Class`，自定义 tag 需要稳定且低基数。
- ✅ **Doc 7（已修复）**. `MviExtensions.doOnClick` / `doOnLongClick` / `doOnCheckedChange` / `doOnAfterTextChanged` 的 KDoc 已补充主线程收集约束，并说明 `launchWithLifecycle` / `dispatchWithLifecycle` 的常规 lifecycle 用法满足该要求；样例 `doOnTextChanged` 也同步补充说明。
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
- ✅ **Doc 11（已修复）**. `KMvi.configure` KDoc 写"NOT thread-safe，主线程调用"，但同一个文件里 `private @Volatile var config` 给读者错觉——以为安全只差临界区。KDoc 和字段注释已补充：`@Volatile` 仅保证后台读取者能看到最新发布的配置，不保证 `configure` 的读-改-写原子性，多线程并发调用仍可能丢更新。
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
