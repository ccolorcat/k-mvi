# K-MVI 代码评审

评审范围：`core/src/main/java/cc/colorcat/mvi/**`（含 `internal/`），并参照 `app/` 样例验证运行场景。

评审基准：库面向 Android `ViewModel` + `LifecycleOwner` 使用，`dispatch` 由主线程调用，`PartialChange.apply` 在 `Dispatchers.Default` 上执行，`IntentHandler.handle` 在 `Dispatchers.IO` 上执行。所以"线程安全"只在跨主线程边界的位置才计入问题，纯主线程路径不在意争用。

---

## Design（架构 / 设计）

- ✅ **Design 1（已修复）**. `Contract<I, S, E>` 的 `I` 类型参数没有被消费——`stateFlow`、`eventFlow` 都只依赖 `S` 和 `E`，只有 `ReactiveContract.dispatch(intent: I)` 需要 `I`。结果是所有暴露给 UI 的只读 API 都被迫多带一个无用的泛型，调用方写 `Contract<MyIntent, MyState, MyEvent>` 没有任何收益。建议拆为 `Contract<S, E>`，让 `ReactiveContract<I, S, E> : Contract<S, E>` 单独引入 `I`。
- ✅ **Design 2（已修复）**. `IntentHandlerDelegate.handle`（`IntentHandlers.kt:247`）只要找不到针对 `intent.javaClass` 的精确 handler，就以 WARN 级别打 `"No handler registered for ..., fallback to defaultHandler"`。但 `LoginViewModel` 这种"集中式 `defaultHandler`"是被官方样例明确推荐的写法（见样例文档"centralized handling"），按现在的实现，登录这条业务路径上每一个 Intent 都会刷一条 WARN。fallback 在"defaultHandler 是空流"时该 WARN，但在"defaultHandler 是用户显式提供"时应该是 INFO/DEBUG 甚至静默。
- Design 3. `StrategyIntentTransformer.assignGroupTag`（`IntentTransformers.kt:328`）对"同时实现 `Concurrent` 和 `Sequential`"的冲突 Intent，每分发一次都打一条 WARN。冲突是 *类型级* 的，不是 *实例级* 的——日志应当按 class 去重，或者用 `assert` 在 debug 构建中报错；当前实现会污染生产日志。
- Design 4. `KMvi.Configuration.hybridConfig: HybridConfig<Mvi.Intent>` 的根类型是 `Mvi.Intent`。`HybridConfig` 自身是 `<in I>`，能逆变地用到任何子类型，但用户没法把"按业务语义分组"的全局策略写在 `KMvi.setup` 里——只能在每个 `contract(...)` 里单独传 `config: HybridConfig<MyIntent>`。这一点和其他配置项"`KMvi.setup` 提供默认值即可"的设计不一致。
- Design 5. `groupHandle`（`InternalExtensions.kt:127`）用 `linkedMapOf<String, Channel<I>>()` 缓存每个 group tag 的 channel，*直到 upstream 完成才关闭*。若 `groupTagSelector` 返回的 tag 跟 Intent 数据相关（资源 id、用户 id、查询串……），map 就会随 Intent 种类数无界增长，单个 ViewModel 生命期内可能积累大量未关闭 Channel。`HybridConfig` 的 KDoc 只口头提醒"不要建太多 group"，没有说出"每个 group 都常驻"。
- Design 6. 管线连续 `flowOn(Dispatchers.IO)` → `scan` → `flowOn(Dispatchers.Default)`（`ReactiveContractImpl.kt:199-219`）。对真正做 I/O 的 Intent 是合理的，但对"按钮点击 → 仅做 `copy()`"这类纯 CPU Intent，等于每个 Intent 强制两次线程切换。在 Android 上，UI Intent 远多于 I/O Intent，默认就背了这份开销。更朴素的选择：默认整条管线在 `Default`，让真正阻塞的 handler 自行 `withContext(IO)`。
- Design 7. `Mvi` 用一个 `object` 把 `Intent` / `State` / `Event` / `PartialChange` / `Snapshot` 全部嵌套进去。带来的副作用是用户代码到处写 `Mvi.PartialChange<S, E>` / `Mvi.Snapshot<S, E>`，import 一次也省不掉嵌套类的全名。这是包名职责，不是对象职责——把它们放成 `cc.colorcat.mvi` 包顶级类更顺手，命名空间靠 package 即可。
- Design 8. `ReadOnlyContract`（`Contracts.kt:292`）是"防穿透"包装，但用户取到 `Contract` 之后仍可以 `import cc.colorcat.mvi.ReactiveContract` 然后做 `as? ReactiveContract` 强转——能拦的只是不知情的强转。真正想"UI 拿不到 dispatch"，靠的是 ViewModel 不把 `ReactiveContract` 暴露出去而不是这层包装。包装存在，但作用被它自身 KDoc 夸大了。
- Design 9. `IntentHandler.handle` 是 `suspend fun handle(intent): Flow<...>`。同步返回 Flow 本身就支持惰性构造，几乎不需要外面再加一层 suspend。当前用法（`IntentHandlerDelegate.handle` 里直接 `return handler.handle(intent)`）也从不利用这层 suspend。多余的 suspend 让 SAM 调用方多一道思考成本。

---

## Bug（实现正确性）

- Bug 1. `ReactiveContractImpl.kt` 第 36-129 行那块长 KDoc 描述的是 `CoreReactiveContract` 类，但它后面紧跟的是 `private const val SNAPSHOT_BUFFER_CAPACITY = 64`（第 130 行），类本身从第 132 行开始。KDoc 在 Kotlin 里挂给紧随其后的声明——所以这段长说明实际挂在常量上，`CoreReactiveContract` 这个类对 Dokka / IDE 来说是 *零 KDoc*。需要把 `SNAPSHOT_BUFFER_CAPACITY` 移到 KDoc 之前，或把 KDoc 紧挨到 class 头之上。
- Bug 2. `EventCollector.collectTyped(KClass, ...)`（`MviCollects.kt:400`）写成：
  ```kotlin
  flow.filter { clazz.isInstance(it) }
      .map { requireNotNull(clazz.java.cast(it)) }
  ```
  `Class.cast` 在传入非 null 时永远不会返回 null（不匹配会抛 `ClassCastException`），上游 `flow: Flow<E>` 又是非 null，`requireNotNull` 永不触发，纯属误导后续维护者以为这里能出 null。直接 `flow.filterIsInstance(clazz.java)` 或 `flow.mapNotNull { clazz.safeCast(it) }` 一行就够。
- Bug 3. `MviExtensions` 里的 `doOnClick` / `doOnLongClick` / `doOnCheckedChange` / `doOnAfterTextChanged` 都在 `callbackFlow { ... }` 内调 `setOnClickListener(...)`，在 `awaitClose { ... }` 内反向移除。这些 View / TextWatcher 操作必须在主线程；当前实现没有 `.flowOn(Dispatchers.Main.immediate)`、也没有运行时校验。如果调用方在 `viewModelScope`（默认 `Dispatchers.Main.immediate`，恰好就在主线程，所以暂时没炸）以外的 scope 上 collect，就会抛 `CalledFromWrongThreadException`。要么加 `.flowOn(Dispatchers.Main.immediate)`，要么在 KDoc 里明确声明"必须 main 线程 collect"。
- Bug 4. `CoreReactiveContract` 里 `scope.coroutineContext[Job]?.invokeOnCompletion { channel.close() }`（`ReactiveContractImpl.kt:162`）使用 `?.`：如果 scope 的 context 里没有 `Job`，channel 永远不会关闭，`dispatch` 也就永远不会返回 `Closed`，scope 已经"等价于失活"但 `trySend` 仍然成功。对 `viewModelScope` 这是无关紧要的边角，但用户拿任意 `CoroutineScope` 构造时（库公开支持，因为 `CoreReactiveContract` 接受 `scope` 参数）有真实风险。建议改为 `requireNotNull(scope.coroutineContext[Job]) { ... }`。
- Bug 5. `Mvi.Snapshot.updateState { ... }`（`Mvi.kt:336`）会无条件清掉 `event`：
  ```kotlin
  fun updateState(transform: S.() -> S): Snapshot<S, E> =
      this.copy(state = newState, event = null)
  ```
  当 handler 先 emit 一个带 event 的 PartialChange，紧接着另一个 PartialChange 调 `updateState { copy(loading=false) }` 收尾——后者就会把前者的 event 吞掉。KDoc 写了，但用 `updateState` 是默认手势，出错概率很高。要么把 `updateState` 改成"保留 event"语义、用 `updateWith(null, transform)` 显式清，要么至少在样例里展示一次踩坑场景。
- Bug 6. `IntentHandlerDelegate.handlers: ConcurrentHashMap<Class<*>, IntentHandler<*, S, E>>` 以 `intent.javaClass` 作为 key。如果应用启用 R8 / ProGuard 对 Intent 类做了名字混淆但 *仍然区分* class 对象，运行时没问题；但同时 `HybridConfig` 默认 `groupTagSelector = { it.javaClass.name }` 在混淆后会把多种 Intent 折叠到同一个 tag，把"并行"悄悄变"串行"。`HybridConfig.groupTagSelector` 的 KDoc 已警告，但 `KMvi.Configuration` 和 `IntentHandlerRegistry.register` 这边没引用这条警告，新手按文档配出来的就是踩坑。
- Bug 7. `KMvi.setup`（`KMvi.kt:176`）写法是 `config = config.transform()`：典型的读-改-写，配合 `@Volatile` 只能保证可见性，不保证原子性。KDoc 写了"非线程安全，主线程调用"，但 `@Volatile` 的存在反而让人误以为线程安全。两条路二选一：要么 `compareAndSet` 循环并去掉 NOT-thread-safe 声明，要么干脆去掉 `@Volatile`、单纯依赖"只在 `Application.onCreate` 调一次"的约定。

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
- Name 10. `IntentHandler.handle` 的 `suspend` 修饰让方法签名读起来像"会暂停"，实际上 99% 的实现立刻返回 `flow { ... }`。考虑去掉 `suspend`（参见 Design 9）。

---

## Style（Kotlin 风格 / 简洁性）

- Style 1. 嵌套在 `object Mvi { ... }` 里的接口/数据类（`Intent`、`State`、`Event`、`PartialChange`、`Snapshot`）让用户和库内部到处写 `Mvi.PartialChange<S, E>`、`Mvi.Snapshot<S, E>`。Kotlin 习惯靠 package 做命名空间，把这五个类型上提到 `cc.colorcat.mvi` 顶级即可，签名瞬间清爽：`PartialChange<S, E>`、`Snapshot<S, E>`。
- Style 2. KDoc 之间存在大量重复——"Intent → PartialChange → State → View" 流程图、HandleStrategy 对比表、HYBRID 三类 Intent 的解释，在 `Mvi.kt`、`Contracts.kt`、`HandleStrategies.kt`、`IntentHandlers.kt`、`IntentTransformers.kt`、`ReactiveContractImpl.kt` 各写了一遍。一次写在 `Mvi.kt` 或 README，其他位置用 `@see` 即可，避免说法漂移。
- Style 3. `ReadOnlyContract`（`Contracts.kt:292`）写成：
  ```kotlin
  override val stateFlow: StateFlow<S> get() = source.stateFlow
  override val eventFlow: Flow<E> get() = source.eventFlow
  ```
  既然 `source` 是不可变 `val`，对应 flow 也是不可变属性，去掉 `get()`、直接 `= source.stateFlow` 就行；最短写法是 `class ReadOnlyContract<...>(source: ReactiveContract<...>) : Contract<...> by source` 委托。
- Style 4. `IntentTransformer.Companion.invoke(...)` 已经是 SAM-fun-interface，可以直接 `IntentTransformer { ... }` 构造；再写一个 `internal operator fun invoke(strategy, config, handler)` 等于把 framework 内部工厂塞进公开 companion，污染对外类型。改成包内 `internal fun strategyTransformer(...)` 函数更合 Kotlin 习惯。
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
- Doc 4. `ReactiveContractImpl.kt` 36-129 行的长 KDoc 实际挂在 `SNAPSHOT_BUFFER_CAPACITY` 上（见 Bug 1）。Dokka 渲染时 `CoreReactiveContract` 是空文档。
- Doc 5. `Mvi.Snapshot.updateState` KDoc 提到"会清空 event，需要保留请用 `updateWith`"，但示例里没有出现"先 event 再 updateState 导致 event 丢失"的反面案例。这种 API 的"反例样板"对避免 Bug 5 更有效。
- Doc 6. `KMvi.Configuration.hybridConfig` 的 KDoc 写"Default: class-name based grouping"，但 `HybridConfig.groupTagSelector` 的 KDoc 大篇幅警告 "R8 obfuscation 会把不同类塌成同一 tag"。两段文档应当互相 `@see`，否则用户从 `KMvi.Configuration` 入口看不到这条关键警告。
- Doc 7. `MviExtensions.doOnClick` / `doOnLongClick` / `doOnCheckedChange` / `doOnAfterTextChanged` 的 KDoc 完全没说"必须在主线程 collect"。`setOnClickListener` 和 `TextWatcher` 都是 View 操作，需要 Main。配合 Bug 3，这条文档遗漏是必须补的。
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
- Doc 11. `KMvi.setup` KDoc 写"NOT thread-safe，主线程调用"，但同一个文件里 `private @Volatile var config` 给读者错觉——以为安全只差临界区。补一句"`@Volatile` 仅为可见性，多线程并发 setup 会丢更新"，或参考 Bug 7 修代码。
- Doc 12. `Mvi.Snapshot` 是 `public data class`，构造器对外可见但 KDoc 没说明"用户是否应该手动构造 Snapshot"。从用法上 Snapshot 只在 `apply` 内被读、在测试里被构造，建议在 KDoc 标明"主要用于框架内部 / 测试；业务代码应通过 `updateState` / `withEvent` / `updateWith` 派生"。
- Doc 13. `Mvi` KDoc 顶部用 `Author / Date / GitHub:` 三行而非 KDoc 标准的 `@author`，全仓统一这种风格没问题，但 IDE / Dokka 不会把它识别为元数据；如果项目希望 Dokka 渲染出作者信息，改用 `@author ccolorcat` 才能生效。

---

## 总评

总体架构清晰、层次合理：`Mvi` 域类型 → `PartialChange` 累加 → `Snapshot` → `StateFlow` / `SharedFlow` 的拆分恰当；HYBRID 策略的"组内串行、组间并行"是这类库里少见但非常实用的设计；`IntentQueueConfig` / `DispatchResult` 把 Channel 语义体面地包装出来，没让 Channel 概念漏到 UI 层。

主要改进方向集中在三处：
1. **削减"为细节而细节"的日志与文档冗余**——`IntentHandlerDelegate` 的 fallback WARN、`assignGroupTag` 的冲突 WARN、各文件重复粘贴的 MVI 概述都该精简。
2. **真正不变的契约写到 KDoc，可变 / 易踩的契约配示例反面教材**——`Snapshot.updateState` 会清 event、`groupHandle` 会常驻 Channel、`do*` 系列要求主线程，这些都该有"踩坑→怎么避免"的样例段。
3. **削减 `Mvi.` 这层不必要的命名嵌套**，让用户日常签名从 `Mvi.PartialChange<MyState, MyEvent>` 退化为 `PartialChange<MyState, MyEvent>`，可读性收益巨大且零运行时成本。

Bug 级别的问题数量不多，且大多数是文档型（示例错、KDoc 挂错位置），真正运行时风险只有 Bug 3（`do*` 主线程未声明也未限制）和 Bug 4（`Job` 缺失时 channel 不关）——前者用户在样例约定的"viewLifecycleOwner + viewModelScope"组合里被遮盖、暂时不会爆，后者只有当 `CoreReactiveContract` 被自定义 scope 调用时才暴露。值得修，但不阻塞当前 release。
