# K-MVI Code Review Report

**Date**: 2026-06-25
**Scope**: `core/` (library) + `app/` (sample) — all `.kt` source files, build configs, tests, and documentation.
**Reviewer**: CodeWhale

---

## ⚠️ 严重等级说明

| 标记 | 等级 | 含义 |
|------|------|------|
| 🔴 | **HIGH** | 可能导致运行时错误、数据丢失或严重设计缺陷 |
| 🟡 | MEDIUM | 可能引起意外行为或维护困难 |
| 🟢 | LOW | 风格或轻微优化建议 |
| ℹ️ | INFO | 非问题，仅为观察或确认 |

---

## 一、Design — 设计合理性

### 🟡 Design-1: `groupHandle` 单协程路由的跨组背压

- **位置**: `InternalExtensions.kt:groupHandle()`
- **审计结论**: 属实，但不构成 🔴 HIGH。当前实现确实在一个 `flow { collect { ... channel.send(...) } }` 协程中路由所有 group；当目标 group 的 channel 已满时，`send` 会挂起并造成跨组 head-of-line blocking。
- **影响边界**: 只有在某个 group 的内部 channel 被填满且下游 handler 持续消费慢时才触发。默认 `groupChannelCapacity = Channel.BUFFERED` 会吸收普通 burst；Dashboard 示例的少量并发点击本身不足以稳定触发该瓶颈。
- **风险定级**: 结果是延迟和背压扩大，通常不是运行时错误或数据丢失。若背压继续传导到 dispatch 入口队列，`dispatch()` 会按既有队列策略返回 `DispatchResult.Full`，这是显式背压而非静默丢失。
- **设计取舍**: 当前实现优先保留背压语义、组内顺序和简单生命周期管理，避免在路由层静默丢 Intent 或为每个拥塞点引入额外发送协程。对轻量 MVI 库是可接受的保守实现。
- **建议**: 维持当前实现和文档化。高吞吐业务可调大 `groupChannelCapacity`、谨慎使用 `Channel.UNLIMITED`；若未来需要真正的跨组隔离，应先补充背压/顺序/取消语义测试和基准，再评估 `channelFlow` 或 per-group sender 方案。

### ℹ️ Design-2: `PartialChange.apply()` 纯函数契约（非运行时设计缺陷）

- **位置**: `Mvi.kt:apply()` + `ReactiveContractImpl.kt:scan{}`
- **审计结论**: 不构成 🔴 HIGH。`apply()` 是同步 reducer 回调；阻塞 I/O、协程启动或重计算放入 `apply` 属于调用方违反公共契约，不是库需要通过运行时机制兜底的设计缺陷。
- **实现事实**: 当前运行时在 `Dispatchers.Default` 上累积快照，但这是 `ReactiveContractImpl` 的线程调度实现细节；公共 API 只需要承诺“同步累积快照”，并要求 `apply` 保持纯、轻量、非抛异常。
- **不采纳 strictMode 的原因**: 调用耗时检查只能发现“慢”，不能证明“不纯”；在每个 `PartialChange` 上打点会增加热路径开销和误报（GC、调试器、设备抖动、大对象 copy）。围绕 `apply` 临时启用 Android `StrictMode` 也会修改线程策略，成本和侵入性都过高。
- **建议**: 维持文档契约，不新增 `Configuration.strictMode`。阻塞网络、数据库、文件操作应放在 `IntentHandler.handle` 产生 `Flow<PartialChange>` 的阶段，并用 `withContext(Dispatchers.IO)` 或 `flowOn(Dispatchers.IO)` 隔离。

### ✅ Design-3: `LoginContract.ClearError` 同时实现 `Intent` 和 `PartialChange`（已修复）

- **原位置**: `LoginContract.kt:294`（顶层 `object ClearError : Intent, PartialChange`）
- **原问题**: `ClearError` 同时实现 `Intent` 和 `PartialChange`，`handleIntent()` 通过 `is PartialChange -> intent.asSingleFlow()` 隐式捕获，路由意图模糊，新开发者难以理解双重角色。
- **修复**（涉及 3 个文件）:
  - **`LoginContract.kt`**: 拆分为两个独立的具名类型：
    - `Intent` 内新增 `data object ClearError : Intent`（用户动作）
    - `PartialChange` Error Management 分区新增 `data object ClearError : PartialChange`（状态变换）
    - 删除原顶层的 `object ClearError : Intent, PartialChange` 及其 KDoc
  - **`LoginViewModel.kt`**: `handleIntent` 的 `when` 改为显式分支，同时删除泛化的 catch-all：
    ```kotlin
    // Before
    is PartialChange -> intent.asSingleFlow()
    // After
    is Intent.ClearError -> PartialChange.ClearError.asSingleFlow()
    ```
  - **`LoginFragment.kt`**: 两处 `LoginContract.ClearError` → `Intent.ClearError`

### 🟢 Design-4: 冲突 Intent 类型去重集合的内存考量

- **位置**: `IntentTransformers.kt:151` — `conflictIntentTypes`
- **问题**: `StrategyIntentTransformer` 的 `conflictIntentTypes` 是一个 `ConcurrentHashMap.newKeySet()`，仅在 log warning 后 add，且永不清理。
- **运行场景**: 长时间运行的 ViewModel。
- **分析**: 该集合仅存储 `Class<*>` 对象引用。Class 对象位于 MetaSpace（不参与普通 GC），因此不存在传统意义上的"内存泄漏"。集合本身极小（~64 bytes/entry），实际影响可忽略。无操作风险。
- **建议**: 维持现状。如果希望极致整洁，可改用仅存储一次的弱引用包装，但必要性很低。

### 🟢 Design-5: `ReactiveContractLazy` 使用 `LazyThreadSafetyMode.NONE`

- **位置**: `MviViewModels.kt:236`
- **问题**: 文档说明 ViewModel 应仅在主线程访问，因此使用 `LazyThreadSafetyMode.NONE`。但此假设依赖开发者不进行跨线程访问，没有强制约束。
- **运行场景**: 正常 Android 开发。
- **建议**: 已正确文档化。对于生产库，考虑在 DEBUG 构建中增加线程检测断言。

### 🟢 Design-6: `KMvi.configure()` 非线程安全

- **位置**: `KMvi.kt:175`
- **问题**: `configure()` 使用 non-atomic read-modify-write，文档明确要求仅在主线程调用一次。这是合理的设计权衡——避免在每次 Intent 处理时加锁。
- **建议**: 已正确文档化。维持现状。

### 🟢 Design-7: 无 DI 框架依赖

- 整个库显式避免依赖 DI 框架，使用构造函数 + `ViewModel.contract()` + `KMvi` 全局配置。这是一个清晰且意图明确的设计决策。
- **建议**: 保持。

---

## 二、Bug — 代码正确性

### ✅ Bug-1: `handleDecrement` 读取 `stateFlow.value` 可能产生过期状态（已修复）

- **原位置**: `CounterViewModel.kt:handleDecrement()`
- **原代码**:
  ```kotlin
  return if (stateFlow.value.count == COUNT_MIN) {
      PartialChange { it.withEvent(Event.ShowToast("Already reached $COUNT_MIN")) }
  } else {
      PartialChange { it.updateState { copy(count = count - 1) } }
  }
  ```
- **原问题**: `stateFlow.value.count` 在 handler 调用时读取。在 CONCURRENT/HYBRID 策略下，handler 调用与 `apply()` 执行之间的时间窗口内可能有其他 `PartialChange` 被 `scan` 折叠，导致边界判断基于过期状态。
- **修复**: 将决策移入 `PartialChange` lambda，使用 `old.state.count`（`apply()` 被调用时 `scan` 传入的最新快照）：
  ```kotlin
  private fun handleDecrement(intent: Intent.Decrement) = PartialChange { old ->
      if (old.state.count == COUNT_MIN) {
          old.withEvent(Event.ShowToast("Already reached $COUNT_MIN"))
      } else {
          old.updateState { copy(count = count - 1) }
      }
  }
  ```
  同步更新了 `handleDecrement` 的 KDoc（移除 "Trade-off to be aware of" 章节，改为解释 `old.state` 相对于 `stateFlow.value` 的正确性）和类级 KDoc 的 item 2 说明。

### ✅ Bug-2: `DashboardViewModel` 中 `stamp()`/`now()` 违反 `PartialChange.apply()` 纯函数契约（已修复）

- **原位置**: `DashboardViewModel.kt:174-178`，全部 7 个 handler（Section 1–3）
- **原问题**: `stamp()` 和 `now()` 在 `PartialChange { s -> ... }` lambda 体内调用，即在 `apply()` 执行时才读取系统时钟，违反"纯函数、无可观测副作用"契约。
- **修复**: 在每次 `emit()` 之前捕获时间戳为局部 `val`，PartialChange lambda 仅关闭引用已有字符串：
  ```kotlin
  // Before — 时钟读取在 apply() 内
  emit(PartialChange { s -> s.updateState { copy(... operationLog = operationLog + stamp("START $tag")) } })

  // After — 时钟读取在 flow{} 体内
  val startStamp = stamp("START $tag")
  emit(PartialChange { s -> s.updateState { copy(... operationLog = operationLog + startStamp) } })
  ```
  `handleCheckout` 中的 `now()` 同理改为 `val completedAt = now()`，在 `randomDelay` 之后、emit 之前求值。全部 7 个 handler 均已更新。

### ℹ️ Bug-3: `Snapshot.updateWith` 调用 `state.transform()` 而非显式 receiver

- **位置**: `Mvi.kt:422`
- **代码**: `return copy(state = state.transform(), event = event)`
- **分析**: 对比 `updateState` 和 `updateWith`，两者的 `transform` 签名都是 `S.() -> S`。`state.transform()` 使用 `state` 作为 receiver，`updateState` 中的 `state.transform()` 行为完全一致。
  - **结果**: **不是 Bug。** 两者行为一致，`updateWith` 正确处理了 state 和 event 的同时更新。误检，标记为确认。

### 🟢 Bug-4: README 安装示例版本号与项目版本不一致

- **位置**: `README.md:45`
- **代码**: `implementation("cc.colorcat.mvi:core:1.2.6")`
- **问题**: 当前项目版本为 `1.4.0-SNAPSHOT`（见 `gradle/libs.versions.toml`），但 README 仍显示 `1.2.6`。
- **建议**: 更新 README 中的版本号以匹配仓库状态。

### ~~Bug-5~~: ~~`gradle.properties` 中 `android.useAndroidX=true` 未显式设置~~

- **文件**: `/gradle.properties:17`
- **裁定**: **误报。** `android.useAndroidX=true` 已在 `gradle.properties` 第 17 行显式声明。此条目不成立。

### ℹ️ Bug-6: `conflictIntentTypes` 类型安全

- `conflictIntentTypes.add(intent.javaClass)` 使用 `Class<*>` 类型的 set。虽然类型安全的泛型被擦除，但 `Class` 对象的 identity 是正确的，运行时不会出错。
- **裁定**: 安全。

---

## 三、Name — 命名准确性与简洁性

### 🟢 Name-1: `KMvi` 命名

- **位置**: `KMvi.kt`
- **观察**: 作为全局配置对象的名称，`KMvi` 是框架常见模式（如 `Koin`、`Ktor` 等），有助于建立品牌识别。但对于不熟悉此模式的新用户可能显得突兀。
- **建议**: 无需改动，这是可识别的 Kotlin 框架命名惯例。

### 🟢 Name-2: `PartialChange` 含义清晰

- `PartialChange` 很好地传达了"只变更快照（Snapshot）的一部分"的含义。文档也充分解释了"Partial"是相对于完整帧(`Snapshot`)而非仅 State。
- **建议**: 保持。

### 🟢 Name-3: `updateWith(event) { ... }` 命名准确

- 清晰地表达了"用 event 和 state transform 同时更新"的语义。对比 `withEvent` + `updateState` 链式调用可能丢失 event 的陷阱，`updateWith` 命名恰到好处。

### 🟢 Name-4: `ReactiveContractLazy` 略显冗余

- **位置**: `MviViewModels.kt:234`
- **观察**: 作为 `internal` 类，名称明确表达了用途。但 `Lazy` 后缀对 Kotlin 开发者来说是标准用法。
- **建议**: 保持。

### 🟢 Name-5: `diagnosticName` 扩展清晰

- 明确表明"仅用于日志和诊断"的意图，且文档注明不应用于持久化/序列化。命名与用途匹配良好。

### 🟢 Name-6: `GroupTagSelector` 命名准确

- 清晰表达了"为 HYBRID 策略的 fallback group 选择 tag"的职责。`byClass()` 工厂方法命名也直观。

### 🟢 Name-7: `IntentHandlerScope.register` 重载尚可

- 三个重载（`(T) -> PartialChange` / `IntentHandler<T>` / `Class<T> + IntentHandler`）都命名为 `register`，虽然参数类型不同，但开发者需要理解差异。Kotlin 重载解析规则使调用清晰。
- **建议**: 保持。

---

## 四、Style — 代码规范与 Kotlin 风格

### 🟢 Style-1: KDoc 详细程度

- **观察**: 整个库的 KDoc 非常详尽，有些方法（如 `HandleStrategy.HYBRID` 的 80 行 KDoc）包含大量内容。这有助于学习，但也使代码阅读需要滚动更多篇幅。
- **建议**: 考虑将战略性的详细文档移到 README，KDoc 保持 API 契约级别的简洁。不过当前形式对于初次接触者友好，保持现状也可接受。

### 🟢 Style-2: `Mvi.kt` 中多余空行

- **位置**: `Mvi.kt:172-173`
- **观察**: 第 172-173 行之间有一个多余空行（`Event` interface 和 `PartialChange` 的文档之间）。
  ```
  171│     interface Event
  172│ 
  173│ 
  174│     /**
  ```
- **建议**: 删除多余空行，保持代码整洁。

### 🟢 Style-3: `LoginContract.PartialChange.apply()` 中 `this@PartialChange` 的使用

- **位置**: `LoginContract.kt:136,144,152,161`
- **代码**: `this@PartialChange.username`, `this@PartialChange.message`
- **观察**: 在 `when(this)` 的 `is` 分支内，`updateState { copy(...) }` lambda 的 receiver 是 `State`。当 `State` 与 smart-cast 后的子类型有同名属性时，`this@PartialChange` 是**必需的消歧义**，而非冗余。
- **分析**:
  - `this@PartialChange.message`（lines 136, 152, 161）：`State` 无 `message` 属性，理论上可省略——Kotlin 会向外层作用域解析。但保留 `this@PartialChange` 提供了显式意图，属于风格偏好。
  - `this@PartialChange.username`（lines 144, 147）：`State` **有** `val username: String`，省略后 `username` 解析为 `State.username`（当前状态旧值）而非 `CompleteLogin.username`（intent 新值）。此处限定符**不可省略**。
- **建议**: 对 `username` 的使用保持现状（必需）。对 `message` 的使用，保留或省略皆可，统一风格即可。当前全部保留 `this@PartialChange` 是更安全的一致性选择。

### 🟢 Style-4: 良好的 Lambda 日志模式

- **观察**: 整个代码库使用 `logger.i(TAG) { "message" }` 惰性求值模式，值得表扬。避免了不必要的字符串拼接开销。

### 🟢 Style-5: `@JvmInline` value class 使用恰当

- **位置**: `IntentHandlerScope.kt:331`
- **观察**: `IntentHandlerScope` 使用 `@JvmInline` value class 包装 `IntentHandlerRegistry`，零开销抽象，优雅。

### 🟢 Style-6: `sealed interface` 使用一致

- **观察**: 库中所有 Contract（LoginContract、CounterContract、DashboardContract）一致使用 `sealed interface` 定义 Intent/Event/PartialChange，风格统一。

### 🟢 Style-7: Fragment 中 `intents` 属性的 getter 模式一致

- **观察**: 三个 Fragment 都使用 `private val intents: Flow<Intent> get() = merge(...)` 模式。`get()` 确保每次访问都使用最新的 `binding` 实例。命名 `intents`（复数）准确表达了意图为多 Intent 流的合并。

### 🟢 Style-8: `@Suppress("UNUSED_PARAMETER")` 使用恰当

- **位置**: `DashboardViewModel.kt` 多个 handler 方法
- **观察**: 对于不需要使用 `intent` 参数但签名需要的方法使用 `@Suppress` 并指定具体规则，比 `@SuppressLint` 更精确。良好的实践。

### 🟢 Style-9: 测试中的 Kotlin 反引号方法名

- **观察**: 测试方法使用 Kotlin 反引号方法名（如 `` `dispatch updates stateFlow` ``），可读性强，且库内风格一致。

### 🟢 Style-10: `DashboardViewModel` handler 代码重复

- **位置**: `DashboardViewModel.kt` — `handleLoadBanners` / `handleLoadRecommendations` / `handleLoadFlashSale`
- **观察**: 三个 handler 的代码结构完全一致——`try-catch → emit(Loading) → randomDelay() → emit(Success)`，每个约 15 行。仅数据内容不同，但基本模式完全相同。
- **建议**: 可抽取通用 `loadData` 模板函数减少重复。但作为示例代码，重复有助于独立阅读，重构与否取决于项目风格。
  ```kotlin
  private fun loadData(
      tag: String,
      setLoading: State.(Boolean) -> State,
      setData: State.(Any) -> State,
      dataProvider: suspend () -> Any,
  ): Flow<PartialChange> = flow { ... }
  ```

---

## 五、Doc — 文档准确性与完善性

### 🟡 Doc-1: README 版本号与代码不一致

- **位置**: `README.md:45` — `implementation("cc.colorcat.mvi:core:1.2.6")`
- **问题**: 应更新为当前版本 `1.4.0-SNAPSHOT` 或发布版本号。用户如果直接复制该行可能会使用过时的库。
- **建议**: 更新版本号，或在 README 中写 `1.4.0` 并确保发布时更新。

### 🟢 Doc-2: `Mvi.Snapshot` 的 KDoc 与其 `data class` 声明一致

- **位置**: `Mvi.kt:341`
- **观察**: KDoc 中注释 `@property event` 类型为 `E?`，与 `val event: E? = null` 匹配。文档与实现一致。

### 🟢 Doc-3: `Contracts.kt` 文档中示例代码使用 `defaultHandler`，但实际已改为 `register` DSL

- **位置**: `Contracts.kt:25,145`
- **观察**: 文档示例注释仍然提到 `defaultHandler = ::handleIntent` 模式（在 `ReactiveContract` 的 KDoc 中），而实际 ViewModel 代码中既有 centralized 模式也有 distributed 模式。文档覆盖了两种模式，没有遗漏。

### 🟢 Doc-4: `ReactiveContractImpl` 的 KDoc 非常详细

- **位置**: `ReactiveContractImpl.kt` — 类 KDoc、`snapshots`、`eventFlow`、`dispatch` 的 KDoc
- **观察**: 包含 pipeline 图、buffer 行为说明、背压策略、生命周期说明等。文档质量很高。

### 🟢 Doc-5: `docs/` 目录下已有高质量文档

- **已有文档**:
  - `docs/groupHandle-bottleneck-analysis.md` — 详细分析了 `groupHandle` 的瓶颈问题
  - `docs/proguard-obfuscation-audit-2026-06-19.md` — 全面的 ProGuard/R8 安全审计
- **观察**: 两份文档都非常详尽，显示项目有良好的技术债务文档化习惯。

### 🟢 Doc-6: `CoreReactiveContract` 的 `scan` 中 try-catch 行为文档化良好

- **位置**: `ReactiveContractImpl.kt:201-208` + KDoc
- **观察**: `partialChange.apply()` 的异常处理（捕获非 CancellationException 异常）以及前一个 snapshot 保留的行为都有详细说明。对于理解 MVI 管道的容错性非常有帮助。

### 🟢 Doc-7: AGENTS.md / CLAUDE.md 作为项目内开发者文档

- **观察**: 这两个文件为 CodeWhale/Nova 开发者指南，包含构建命令、架构说明、代码约定等，非常适合 LLM 辅助开发场景。
- **建议**: 保持并同步更新。

### 🟢 Doc-8: `groupHandle` KDoc 提及瓶颈但没有引用 `docs/` 下的分析文档

- **位置**: `InternalExtensions.kt`
- **观察**: KDoc 大篇幅描述了 bottleneck，但未引用 `docs/groupHandle-bottleneck-analysis.md` 中的详细分析。
- **建议**: `@see` 标签无法直接引用文件路径，改用行内文字引用：
  ```kotlin
  * See the analysis document at `docs/groupHandle-bottleneck-analysis.md` for details.
  ```

### ℹ️ Doc-9: `eventFlow` 使用 `SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)`

- **位置**: `ReactiveContractImpl.kt:264`
- **观察**: eventFlow 采用 5 秒的 stop timeout。在配置变更（如 Fragment 进入 backstack 后快速恢复）期间，上游 pipeline 会保持运行而非立刻重启。这是设计意图——避免 pipeline 重建开销。不影响正确性，Events 本身是 fire-and-forget，5 秒内无 subscriber 时 events 自然丢失。
- **建议**: 无需改动，已正确文档化。

---

## 总结

| 类别 | 🔴 HIGH | 🟡 MEDIUM | 🟢 LOW/INFO | 总计 |
|------|---------|-----------|-------------|------|
| **Design** | 0 | 2 | 6 | 8 |
| **Bug** | 0 | 2 | 1 | 3 |
| **Name** | 0 | 0 | 7 | 7 |
| **Style** | 0 | 0 | 10 | 10 |
| **Doc** | 0 | 1 | 8 | 9 |
| **总计** | **0** | **5** | **32** | **37** |

> 注：Bug-3 已确认为误检，Bug-5 经第三轮审计确认为事实性错误（`android.useAndroidX=true` 已存在），均不计入统计。Design-1 经复审由 🔴 降为 🟡，Design-2 经复审降为 ℹ️，Design-4 降为 🟢。Bug-2 由原"SimpleDateFormat 线程安全"修正为"PartialChange.apply() 纯函数契约违规"（线程安全误报已纠正）。Design-3、Bug-1、Bug-2 已修复（见下方行动项）。

### 关键行动项

1. **🟡 Design-1**: `groupHandle` 单协程跨组背压 — 已文档化，高吞吐场景需注意 `groupChannelCapacity` 配置
2. ~~**🔴 Design-2**: `PartialChange.apply()` 在 `Dispatchers.Default` 运行~~ — **复审降级**：这是调用方契约约束，不新增运行时 strictMode
3. ~~**🟡 Design-3**: `ClearError` 双重角色~~ — **已修复**：拆分为独立的 `Intent.ClearError` + `PartialChange.ClearError`，`handleIntent` 改为显式分支
4. ~~**🟡 Bug-1**: `stateFlow.value` 过期读取~~ — **已修复**：决策移入 `PartialChange` lambda，改用 `old.state`
5. ~~**🟡 Bug-2**: `stamp()`/`now()` 在 `apply()` 内调用~~ — **已修复**：全部 7 个 handler 改为在 `emit()` 前捕获时间戳为局部 `val`
6. **🟡 Doc-1 / Bug-4**: README 版本号 `1.2.6` → 更新为当前版本
7. **🟢 Style-2**: `Mvi.kt` 多余空行
8. **🟢 Doc-8**: KDoc 中 `groupHandle` 瓶颈分析改用行内文字引用

### 项目优点

- 架构清晰：MVI 数据流（Intent → PartialChange → State/Event）干净
- 文档完善：大量 KDoc + 独立 docs/ 文档 + README
- 命名一致：整个库命名风格统一，可读性强
- 测试覆盖好：`ReactiveContractImplTest` (759行)、`MviTest`、`IntentHandlersTest` 等综合测试
- 编码规范：Kotlin 惯用法（sealed interface、fun interface、reified inline、@JvmInline）使用恰当
- 设计说明清晰：已知权衡（如 groupHandle bottleneck）都有文档说明
- 健康的质量意识：`docs/proguard-obfuscation-audit-2026-06-19.md` 表明项目有安全意识

### 审查过程记录

本报告经过三轮审查 + 一次代码修复：
1. **第一轮**（初始审查）：逐文件阅读全部源代码、测试和文档后撰写
2. **第二轮**（Meta-Review）：对初稿进行二次审查，发现并修正了 3 项遗漏（Dashboard handler 重复、eventFlow WhileSubscribed）、1 个评级偏差（Design-4 降级）和 1 个格式建议（Doc-8 KDoc 引用语法）
3. **第三轮**（源码验证审计）：逐项对照源码验证，修正了 3 处错误：
   - Bug-2 由"SimpleDateFormat 线程不安全"修正为"PartialChange.apply() 纯函数违规"（`stamp()` 在 `scan` 内串行调用，无竞争）
   - Bug-5 移除（`android.useAndroidX=true` 已在 `gradle.properties:17` 显式声明）
   - Style-3 修正（`this@PartialChange.username` 是必需的消歧义，因 `State` 有同名属性）
4. **代码修复**（Design-3 + Bug-1 + Bug-2）：`ClearError` 拆分（`LoginContract.kt`、`LoginViewModel.kt`、`LoginFragment.kt`）；`handleDecrement` 改用 `old.state`（`CounterViewModel.kt`）；DashboardViewModel 全部 7 个 handler 的 `stamp()`/`now()` 移至 `flow {}` 体内
