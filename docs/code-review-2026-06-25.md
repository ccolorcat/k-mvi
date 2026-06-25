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

### 🔴 Design-1: `groupHandle` 单协程路由瓶颈

- **位置**: `InternalExtensions.kt:groupHandle()`
- **问题**: `groupHandle` 的 `collect { }` 是单协程循环。当某个 group 的 channel 因为下游 handler 慢而 `send` 挂起时，**所有 group 都被阻塞**（即使其他 group channel 有空闲容量）。
- **运行场景**: 高频并发 Intent 场景，如 Dashboard 示例中同时触发 3 个 CONCURRENT Intent + 多个 SEQUENTIAL Intent。
- **建议**: 方法已在 KDoc 和 `docs/groupHandle-bottleneck-analysis.md` 中充分文档化，属于已知设计取舍。对于生产高吞吐场景，建议增大 `groupChannelCapacity` 或使用 `Channel.UNLIMITED`。

### 🔴 Design-2: `PartialChange.apply()` 在 `Dispatchers.Default` 上运行的限制

- **位置**: `Mvi.kt:apply()` + `ReactiveContractImpl.kt:scan{}`
- **问题**: `PartialChange.apply()` 在 `Dispatchers.Default` 上被 `scan` 同步调用。文档要求其必须为纯函数（无 I/O、无协程启动），但如果开发者误将阻塞 I/O 放入 apply，将导致 Default 线程池阻塞。
- **运行场景**: 生产环境，依赖 Default 线程池做其他计算任务的场景。
- **建议**: 考虑在运行时增加校验（如在开发模式检查调用耗时）捕获非纯函数用法；或者在 `Configuration` 中增加 `strictMode` 开关以在 debug 构建中启用断言。

### 🟡 Design-3: `LoginContract.ClearError` 同时实现 `Intent` 和 `PartialChange`

- **位置**: `LoginContract.kt:294`
- **问题**: `ClearError` 同时实现 `Intent` 和 `PartialChange`。在 `handleIntent()` 中，`ClearError` 可以匹配 `is PartialChange` 分支并直接 `asSingleFlow()`，但其通过 `Intent` -> `handler` -> `when` 的路由链中转，当 Intent 本身也是 PartialChange 时概念上存在混淆可能性。
- **运行场景**: 团队协作多人维护时，新开发者可能不理解此双重角色设计意图。
- **建议**: 虽然是有效（且已被文档说明）的设计模式，但建议将 `ClearError` 改为普通 Intent + 显式 handler 处理，以提高可读性和可预测性。

### 🟡 Design-4: 冲突 Intent 类型集合的泄漏

- **位置**: `IntentTransformers.kt:151` — `conflictIntentTypes`
- **问题**: `StrategyIntentTransformer` 的 `conflictIntentTypes` 是一个 `ConcurrentHashMap.newKeySet()`，仅在 log warning 后 add。这个集合**永不清理**，在长时间运行的 contract 中，如果存在大量不同的冲突 Intent 类型，会积累内存。
- **运行场景**: 长时间运行的 ViewModel，存在多种冲突 Intent 类型。
- **建议**: 这是无害的（仅存储 `Class` 对象引用），但可以考虑添加 `WeakReference` 包装或限制存储数量。

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

### 🟡 Bug-1: `handleDecrement` 读取 `stateFlow.value` 可能产生过期状态

- **位置**: `CounterViewModel.kt:handleDecrement()`
- **代码**:
  ```kotlin
  return if (stateFlow.value.count == COUNT_MIN) {
      PartialChange { it.withEvent(Event.ShowToast("Already reached $COUNT_MIN")) }
  } else {
      PartialChange { it.updateState { copy(count = count - 1) } }
  }
  ```
- **问题**: `stateFlow.value.count` 在 handler 调用时读取，但 Handler 和 `PartialChange.apply()` 之间的时间窗口内状态可能已被其他并发 Intent 改变。在用户标记 `Sequential` 的 Counter 中没有问题，但如果开发者将此模式复制到 CONCURRENT/HYBRID 策略中就会触发此 Bug。
- **运行场景**: CONCURRENT 或 HYBRID 策略下，多个 Intent 并发处理且依赖最新状态做决策。
- **建议**: 文档虽已提及此 trade-off（见 CounterViewModel KDoc），但仍建议在框架层面或代码注释中更显著地标注此陷阱。考虑添加 lint check 规则。

### 🟡 Bug-2: `Snapshot.updateWith` 调用 `state.transform()` 而非 `state.transform(state)` 的隐式 receiver

- **位置**: `Mvi.kt:422`
- **代码**: `return copy(state = state.transform(), event = event)`
- **问题**: 对比 `updateState` 和 `updateWith`，两者的 `transform` 签名都是 `S.() -> S`。此处 `state.transform()` 和 `updateState` 中的 `state.transform()` 行为一致——都使用 `state` 作为 receiver。
  - **结果**: **不是 Bug。** 两者行为一致，`updateWith` 正确处理了 state 和 event 的同时更新。误检，标记为确认。

### 🟢 Bug-3: README 安装示例版本号与项目版本不一致

- **位置**: `README.md:45`
- **代码**: `implementation("cc.colorcat.mvi:core:1.2.6")`
- **问题**: 当前项目版本为 `1.4.0-SNAPSHOT`（见 `gradle/libs.versions.toml`），但 README 仍显示 `1.2.6`。
- **建议**: 更新 README 中的版本号以匹配仓库状态。

### 🟢 Bug-4: `gradle.properties` 中 `android.useAndroidX=true` 未显式设置

- **文件**: `/gradle.properties`
- **问题**: 虽然没有显式设置 `android.useAndroidX=true`，但 AGP 8.x 默认开启。不是真正的 Bug，但为了可读性和向后兼容，建议显式声明。

### ℹ️ Bug-5: `conflictIntentTypes` 类型安全

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

### 🟢 Style-3: `LoginContract.PartialChange.apply()` 中冗余的 `this@PartialChange`

- **位置**: `LoginContract.kt:136,144,152,161`
- **代码**: `this@PartialChange.username`, `this@PartialChange.message`
- **观察**: 在 `when` 分支中的 `is` 分支里使用 `this@PartialChange` 而不是直接访问属性。虽然在 `data class` 的 `apply` 内部需要消除歧义，但考虑到 `when` 分支已经 `is SetErrorMessage` 等，使用 receiver 内的属性更简洁。
- **建议**: 可以简化为直接访问属性，因为 Kotlin 的智能类型转换已经确保了 scope。如：
  ```kotlin
  is SetErrorMessage -> old.updateState { copy(errorMessage = message) }
  ```

### 🟢 Style-4: 良好的 Lambda 日志模式

- **观察**: 整个代码库使用 `logger.i(TAG) { "message" }` 惰性求值模式，值得表扬。避免了不必要的字符串拼接开销。

### 🟢 Style-5: `@JvmInline` value class 使用恰当

- **位置**: `IntentHandlerScope.kt:331`
- **观察**: `IntentHandlerScope` 使用 `@JvmInline` value class 包装 `IntentHandlerRegistry`，零开销抽象，优雅。

### 🟢 Style-6: `sealed interface` 使用一致

- **观察**: 库中所有 Contract（LoginContract、CounterContract、DashboardContract）一致使用 `sealed interface` 定义 Intent/Event/PartialChange，风格统一。

### 🟢 Style-7: DashboardFragment 中 `intents` 属性的 getter 模式一致

- **观察**: 三个 Fragment 都使用 `private val intents: Flow<Intent> get() = merge(...)` 模式。`get()` 确保每次访问都使用最新的 `binding` 实例。命名 `intents`（复数）准确表达了意图为多 Intent 流的合并。

### 🟢 Style-8: `@Suppress("UNUSED_PARAMETER")` 使用恰当

- **位置**: `DashboardViewModel.kt` 多个 handler 方法
- **观察**: 对于不需要使用 `intent` 参数但签名需要的方法使用 `@Suppress` 并指定具体规则，比 `@SuppressLint` 更精确。良好的实践。

### 🟢 Style-9: 测试中的 Kotlin 反引号方法名

- **观察**: 测试方法使用 Kotlin 反引号方法名（如 `` `dispatch updates stateFlow` ``），可读性强，且库内风格一致。

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
- **观察**: KDoc 大篇幅描述了 bottleneck，但未引用 `docs/groupHandle-bottleneck-analysis.md`。建议在 KDoc 末尾添加交叉引用链接：
  ```kotlin
  * @see [docs/groupHandle-bottleneck-analysis.md]
  ```

---

## 总结

| 类别 | 🔴 HIGH | 🟡 MEDIUM | 🟢 LOW/INFO | 总计 |
|------|---------|-----------|-------------|------|
| **Design** | 2 | 2 | 3 | 7 |
| **Bug** | 0 | 2 | 2 | 4 |
| **Name** | 0 | 0 | 7 | 7 |
| **Style** | 0 | 0 | 9 | 9 |
| **Doc** | 0 | 1 | 7 | 8 |
| **总计** | **2** | **5** | **28** | **35** |

### 关键行动项

1. **🔴 Design-1**: `groupHandle` 单协程瓶颈 — 已文档化，高吞吐场景需注意 `groupChannelCapacity` 配置
2. **🔴 Design-2**: `PartialChange.apply()` 在 `Dispatchers.Default` 运行 — 考虑增加开发模式检测
3. **🟡 Bug-1**: `stateFlow.value` 过期读取 — 文档已提及，但需更突出的显式标注
4. **🟡 Bug-3 / Doc-1**: README 版本号 `1.2.6` → 更新为当前版本
5. **🟢 Style-2**: `Mvi.kt` 多余空行

### 项目优点

- 架构清晰：MVI 数据流（Intent → PartialChange → State/Event）干净
- 文档完善：大量 KDoc + 独立 docs/ 文档 + README
- 命名一致：整个库命名风格统一，可读性强
- 测试覆盖好：`ReactiveContractImplTest` (759行)、`MviTest`、`IntentHandlersTest` 等综合测试
- 编码规范：Kotlin 惯用法（sealed interface、fun interface、reified inline、@JvmInline）使用恰当
- 设计说明清晰：已知权衡（如 groupHandle bottleneck、stateFlow.value 过期）都有文档说明
- 健康的质量意识：`docs/proguard-obfuscation-audit-2026-06-19.md` 表明项目有安全意识
