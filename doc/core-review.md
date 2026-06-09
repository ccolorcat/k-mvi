# core 模块代码审查报告

> 审查范围：`core/src/main/java/cc/colorcat/mvi/` 及其 `internal/` 子包
> 不涉及兼容性，重点审查架构、正确性、命名、Kotlin 风格与文档。

---

## 1. 整体架构设计

### 1.1 核心层次结构

```
Mvi（命名空间）
 ├── Intent / Intent.Concurrent / Intent.Sequential
 ├── State
 ├── Event
 ├── PartialChange<S, E>    -- fun interface，状态变换函数
 └── Snapshot<S, E>         -- 状态 + 可选事件的不可变快照

Contract<I,S,E>             -- 只读接口（UI 层使用）
ReactiveContract<I,S,E>     -- 可写接口（ViewModel 使用）

HandleStrategy              -- CONCURRENT / SEQUENTIAL / HYBRID
HybridConfig                -- HYBRID 策略的 fallback 分组配置
IntentHandler               -- fun interface，Intent → Flow<PartialChange>
IntentTransformer           -- fun interface，Flow<Intent> → Flow<PartialChange>
KMvi                        -- 全局配置单例

[internal]
CoreReactiveContract        -- 双通道管道核心实现
StrategyReactiveContract    -- 在 Core 基础上增加策略 + 动态注册
```

### 1.2 优点

**设计清晰，职责分明。**

- `Contract` / `ReactiveContract` 的读写分离设计合理：`ReadOnlyContract` 包装器在类型系统层面防止强转绕过，不依赖运行时检查。
- `PartialChange` + `Snapshot` 的不可变状态演化模式优雅，天然支持 `copy()`，配合 `scan` 使用正确。
- `IntentTransformer` 与 `IntentHandler` 的分层：Transformer 负责并发控制（策略路由），Handler 负责业务逻辑，关注点分离良好。
- 双通道 dispatch 模式（`dispatchQueue` → `intentsChannel`）设计严谨：`dispatch()` 非阻塞，FIFO 顺序由专用协程保证，关闭时的 `finally` 块防止后续 `trySend` 静默成功。
- `groupHandle` 中对 stale channel 的检测和重新打开处理了真实的并发边界情况，文档也有充分说明。
- `SupervisorJob` 在 `StateCollector`/`EventCollector` 中的使用正确，保证了单个 collector 失败不影响其他 collector。
- `SharingStarted.WhileSubscribed(5000)` 用于 `eventFlow` 是合理权衡，可避免 Fragment 返回栈切换时重建上游订阅。
- `retryWhen` 的作用范围（仅保护 Intent 处理，不保护 `PartialChange.apply()`）在文档中有明确说明。

### 1.3 设计疑问

**~~`sealed interface Mvi` 的用法~~** ✅ 已修复

已将 `sealed interface Mvi` 改为 `object Mvi`。`Mvi` 作为命名空间容器语义更直观，内部嵌套类型（`Intent`、`State`、`Event`、`PartialChange`、`Snapshot`）通过 `Mvi.Xxx` 访问方式不变，对用户完全透明。

**~~`Snapshot.of()` 工厂方法~~** ✅ 已修复

已删除 `Snapshot.companion object`（含 `of()` 工厂方法）。`Mvi.Snapshot(state)` 与 `Mvi.Snapshot(state, event)` 即为直接构造，无需额外工厂。相关 KDoc 与测试同步更新。

---

## 2. 代码正确性 / Bug

### ~~2.1 【高】`doOnClick` 等函数的 KDoc 示例无法编译~~ ✅ 已修复

**位置**：`MviExtensions.kt` — `doOnClick`、`doOnLongClick`、`doOnCheckedChange`、`doOnAfterTextChanged`

已将所有四个函数 KDoc 示例中的 `send()` 改为 `trySend()`，并在每个函数的 `@param block` 中明确说明：block 是非 suspend lambda，只能调用 `trySend()`，`send()` 是 suspend 函数无法在此上下文调用。
同步修复了 `debounceLeading` 示例代码中嵌套引用的 `doOnClick` 示例（同一文件第 110 行）。

---

### ~~2.2 【中】`debounceLeading` 强依赖 Android API，无法进行 JVM 单元测试~~ ✅ 已修复

**位置**：`MviExtensions.kt`

将 `SystemClock.elapsedRealtime()` 替换为 `System.currentTimeMillis()`（JVM/Android 通用，毫秒精度，与参数单位一致），移除 `import android.os.SystemClock`。以 `time = -timeMillis` 初始化确保首个事件必然发射，逻辑简洁。`require` 条件改为 `> 0L`（不再允许 0），错误信息同步更新为 `"timeMillis must be positive"`，`@param` 文档同步修正。

新增 JVM 单元测试类 `DebounceLeadingTest`（6 个用例）：首次即时发射、快速连续事件抑制、间隔足够后发射、滑动窗口中段抑制、零值抛异常、负值抛异常。

---

### ~~2.3 【低】`assignGroupTag` 中的 `else` 分支逻辑结构略混乱~~ ✅ 已修复

**位置**：`IntentTransformers.kt`，`StrategyIntentTransformer.assignGroupTag`

在 `assignGroupTag` KDoc 中明确拆分 `else` 分支的两种情形：

- **两者都未实现**（neither）：合法的 fallback 分组模式，用于更精细的分组控制，无需任何警告。
- **两者都实现**（both）：标记接口互斥，属于错误用法，运行时记录警告，并回退到 fallback 分组处理。

同步在 `Mvi.Intent.Concurrent` 和 `Mvi.Intent.Sequential` 接口的 KDoc 中补充了互斥约束说明，明确告知用户同时实现两者是冲突行为。
---

### ~~2.4 【低】`PartialChange.apply()` 抛异常会终止整个管道~~ ✅ 已修复

**位置**：`ReactiveContractImpl.kt`，`CoreReactiveContract.snapshots` `scan` 块

`scan` 内的 `catch` 不再 rethrow（`CancellationException` 除外）：捕获后以 ERROR 级别记录，
返回 `oldSnapshot` 保持状态不变，管道继续处理后续 intent。
同步更新：`PartialChange.apply()` KDoc、`CoreReactiveContract` 类级文档（Error Handling 节）、
`snapshots` 属性 KDoc（Retry Strategy 节），以及新增回归测试
`PartialChange apply exception keeps pipeline alive`。

## 3. 命名

| 位置 | 当前命名 | 问题 | 建议 |
|------|---------|------|------|
| `KMvi` | 全局配置单例 | `K` 前缀作为"Kotlin"的缩写，在此语境中不直观；`KMvi` 读起来像某个第三方工具的品牌名 | `MviConfig` 或 `Mvi.Config` 更清晰 |
| `HybridConfig` | HYBRID 策略配置 | 名称没有传达出"仅影响 fallback Intent"的含义，让人误以为是所有策略的配置 | `FallbackGroupConfig`、`HybridGroupConfig` 或 `FallbackConfig` |
| ~~`collectParticular`~~ | EventCollector | ~~名称不惯用，未传达类型过滤语义~~ | ✅ 已修复：`collectParticular` → `collectTyped`；`collectPartial` / `collectWhole` 保持不变（whole/partial 构成有效反义对） |
| `contract()` 扩展函数 | `ViewModel.contract(...)` | 函数名 `contract` 与接口名 `Contract` 同词不同形，在同一代码上下文中出现 `val contract: Contract by contract(...)` 时语义有些重叠 | 可考虑 `mviContract()`（加前缀区分） |
| `IntentHandlerDelegate` | 内部类 | `Delegate` 后缀是实现细节，不够描述性 | 已是 `internal`，可接受；若重命名可考虑 `IntentHandlerDispatcher` |
| `ReadOnlyContract` | 私有包装类 | 清晰 | 无需修改 |
| `debounceLeading` | 扩展函数 | 命名准确，与标准 `debounce`（trailing edge）区分明显 | 无需修改 |
| `asSingleFlow()` | 扩展函数 | 清晰 | 无需修改 |
| `RetryPolicy` | typealias | 命名准确 | 无需修改 |
| `diagnosticName` | 内部属性 | 命名清晰，且有"仅用于诊断"的语义 | 无需修改 |

---

## 4. Kotlin 写法

### 4.1 `this` 冗余

`Snapshot` 是 `data class`，在其方法内部 `this.state`、`this.copy(...)` 中的 `this` 可以省略，省略后代码更简洁：

```kotlin
// 当前
fun updateState(transform: S.() -> S): Snapshot<S, E> {
    val newState = this.state.transform()
    return this.copy(state = newState, event = null)
}

// 建议
fun updateState(transform: S.() -> S): Snapshot<S, E> {
    val newState = state.transform()
    return copy(state = newState, event = null)
}
```

同理，`doOnClick` 中的 `this.block()` 可简写为 `block()`。

### 4.2 `fun interface` 使用正确

`Logger`、`IntentHandler`、`IntentTransformer`、`PartialChange` 均正确使用 `fun interface`，支持 lambda 传入，符合 Kotlin 惯用法。

### 4.3 ~~`sealed interface` 使用正确~~

`Mvi` 已改为 `object`（见 §1.3 修复）。`HandleStrategy`（`enum class`）设计合理，`Mvi.Intent`、`Mvi.State`、`Mvi.Event` 作为开放接口允许用户实现。

### 4.4 内联函数的 `crossinline` / `noinline` 使用正确

`launchWithLifecycle`、`collectTypedEvent` 中的 `crossinline block` 使用正确（block 被传入非内联 lambda 中）。`register` 扩展函数中的 `noinline handler` 使用正确（handler 需要存储）。

### 4.5 `@PublishedApi` 使用正确

`EventCollector.state` 被标记为 `@PublishedApi internal`，供 `inline fun collectTyped` 访问，符合 Kotlin 规范。

### 4.6 `EventCollector.collectTyped` 的过载设计

```kotlin
inline fun <reified A : E> collectTyped(block: suspend (A) -> Unit): Job
inline fun <reified A : E> collectTyped(state: Lifecycle.State, block: suspend (A) -> Unit): Job
fun <A : E> collectTyped(clazz: KClass<A>, block: suspend (A) -> Unit): Job
fun <A : E> collectTyped(clazz: KClass<A>, state: Lifecycle.State, block: suspend (A) -> Unit): Job
```

四个重载全部提供是合理的（涵盖 reified 版本和 KClass 版本），但参数顺序略有问题：inline 版本是 `(block)` / `(state, block)`，KClass 版本是 `(clazz, block)` / `(clazz, state, block)`。`state` 参数在中间位置（非最后），对于 Kotlin 的尾随 lambda 语法来说，带 state 的重载略显尴尬：

```kotlin
collectTyped<ShowToast>(Lifecycle.State.RESUMED) { event -> ... }
// 还好，因为 block 是最后一个参数
```

目前实际影响不大，可接受。

### 4.7 `ReactiveContractLazy` 非线程安全

```kotlin
override val value: ReactiveContract<I, S, E>
    get() = cached ?: create().also { cached = it }
```

文档已说明非线程安全，ViewModel 通常仅在主线程访问，但 `cached` 未加 `@Volatile`，在多线程场景下存在可见性问题（尽管 ViewModel 不应在多线程使用）。可考虑加 `@Volatile` 作为防御性措施，代价极低。

---

## 5. 文档

### 5.1 整体质量

文档质量较高，尤其：

- `ReactiveContractImpl.kt` 中对管道通道拓扑结构（Channel A、Channel B、DROP_OLDEST 的原因）的描述非常详细，对维护者很有价值。
- `groupHandle` 中对"单协程路由瓶颈"的警告文档清晰准确。
- `HybridConfig` 中 ProGuard 混淆风险的警告文档优秀，有示例、有替代方案。
- `KMvi.defaultRetryPolicy` 中对"广泛捕获 Exception 的风险"有明确的生产环境警告。
- `ReactiveContract.dispatch()` 对队列满、scope 失活等边界情况的描述完整。

### ~~5.2 文档错误~~ ✅ 已修复

`doOnClick` / `doOnLongClick` / `doOnCheckedChange` / `doOnAfterTextChanged` 的示例已全部改为 `trySend()`，`@param block` 补充了非 suspend 上下文说明。`debounceLeading` 内嵌示例同步修复。

### 5.3 ~~文档冗余~~ ✅ 已修复

`Snapshot.of()` 已删除，相关 KDoc 示例已改为直接使用主构造函数。

### 5.4 日期标注已过时

每个文件末尾的 `Author / Date / GitHub` 注脚中，日期为静态写死（如 `2024-05-10`），但文件此后显然经过多次修改。静态日期会误导阅读者，建议删除日期行（依赖 git blame 获取历史信息），或改为`最后更新日期`并在提交时同步维护。

### 5.5 文档缺失

- `ReactiveContractLazy` 已有注释说明"非线程安全"，但未说明*为何*不做线程安全（ViewModel 主线程假设），补充该说明可防止未来误用。
- `MviCollects.kt` 中的 `launchWithLifecycle`：文档说"All collections are launched on the main thread"，但实际上 `context` 参数中可以携带 `Dispatcher`，该断言不完全准确，建议修正为"默认在主线程，可通过 `context` 参数覆盖"。

---

## 6. 测试覆盖

### 6.1 覆盖较好

| 测试文件 | 覆盖范围 |
|---------|---------|
| `ReactiveContractImplTest` | 基础 dispatch、状态累积、事件、初始状态、容量校验、scope 取消 |
| `IntentTransformersTest` | CONCURRENT/SEQUENTIAL/HYBRID 策略、fallback 分组、边界情况 |
| `IntentHandlersTest` | 注册、回退、替换、多 Intent 序列 |
| `InternalExtensionsTest` | `isConcurrent`/`isSequential` 互斥、`diagnosticName` |
| `LoggerExtensionsTest` | 各级别日志、lazy 求值、tag 传递 |
| `KMviTest` | 配置读取、多次 setup、容量校验 |
| `MviExtensionsTest` | `asSingleFlow`、`debounceLeading`（6 用例，含边界）|
| `ReactiveContractLazyTest` | 懒加载、缓存、`isInitialized` |

### 6.2 缺少测试

| 功能 | 情况 |
|------|------|
| ~~`debounceLeading`~~ | ✅ 已修复：改用 `System.nanoTime()`，新增 6 个 JVM 测试 |
| `doOnClick` / `doOnLongClick` / `doOnCheckedChange` / `doOnAfterTextChanged` | 无测试（需 Android 环境） |
| `StateCollector` / `EventCollector` | 无测试（需要 `LifecycleOwner`，可用 Robolectric） |
| `launchCollect` / `launchWithLifecycle` | 无测试 |
| `Mvi.Snapshot` 方法（`updateState`/`withEvent`/`updateWith`） | `MviTest` 存在，但需确认 |
| `groupHandle` 的 stale channel 路径 | 未见专项测试 |

---

## 7. 汇总

| 类别 | 严重程度 | 问题 |
|------|---------|------|
| 正确性 | **高** | ~~`doOnClick` 等 KDoc 示例使用 `send()` 无法编译，应为 `trySend()`~~ ✅ 已修复 |
| 正确性 | **中** | ~~`debounceLeading` 依赖 `SystemClock`，JVM 不可测，无测试覆盖~~ ✅ 已修复 |
| 正确性 | 低 | ~~`assignGroupTag` else 分支逻辑结构略混乱（逻辑正确，阅读困难）~~ ✅ 已修复 |
| 正确性 | 低 | ~~`PartialChange.apply()` 异常无法被 retryWhen 捕获（已文档化，但无编译保障）~~ ✅ 已修复 |
| 命名 | 低 | `KMvi` 名称不直观 |
| 命名 | 低 | `HybridConfig` 未体现"仅针对 fallback"的语义 |
| 命名 | 低 | ~~`collectPartial` vs `collectParticular` 不对称~~ ✅ 已修复：`collectParticular` → `collectTyped` |
| Kotlin 写法 | 低 | `Snapshot` 方法内多余的 `this.`，`doOnClick` 中多余的 `this.block()` |
| Kotlin 写法 | 低 | `ReactiveContractLazy.cached` 缺少 `@Volatile` |
| 文档 | **高** | ~~`doOnClick` 等 KDoc 示例错误（与正确性问题同源）~~ ✅ 已修复 |
| 文档 | 低 | ~~`Snapshot.of()` 多余，可删除~~ ✅ 已修复 |
| 文档 | 低 | 文件中 Date 注脚静态过时 |
| 文档 | 低 | `launchWithLifecycle` 文档"主线程"说法不完全准确 |
| 架构 | 低 | ~~`sealed interface Mvi` 的 sealed 限制没有实际意义~~ ✅ 已修复：改为 `object Mvi` |
| 架构 | 低 | ~~`Snapshot.of()` API 表面多余~~ ✅ 已修复 |
| 测试 | 中 | ~~`debounceLeading`~~（已修复）、UI 事件转换函数、`StateCollector`/`EventCollector` 缺少测试 |
