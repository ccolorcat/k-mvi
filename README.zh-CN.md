# K-MVI

[![Maven Central](https://img.shields.io/maven-central/v/cc.colorcat.mvi/core.svg)](https://search.maven.org/artifact/cc.colorcat.mvi/core)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

一个轻量、强大、类型安全的 Android MVI（Model-View-Intent）架构库，基于 Kotlin 协程与 Flow 构建。

## 特性

✅ **类型安全 MVI**：强类型的 Intent、State、Event 泛型保证编译期安全

✅ **灵活的 Intent 处理**：三种策略（CONCURRENT、SEQUENTIAL、HYBRID）适配不同场景

✅ **生命周期感知**：与 Android Architecture Components 集成，自动管理生命周期

✅ **Kotlin 协程**：基于 Flow 构建，支持响应式异步编程

✅ **易于使用**：简洁的 DSL 及扩展函数覆盖常见场景

✅ **零模板代码**：干净的 API 设计，减少重复代码

✅ **生产就绪**：内置重试策略、错误处理与日志系统

## 目录

- [安装](#安装)
- [快速开始](#快速开始)
- [核心概念](#核心概念)
- [使用指南](#使用指南)
- [配置](#配置)
- [高级特性](#高级特性)
- [最佳实践](#最佳实践)
- [示例 App](#示例-app)
- [许可协议](#许可协议)

## 安装

在模块的 `build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    implementation("cc.colorcat.mvi:core:1.2.6")
}
```

## 快速开始

### 1. 定义 MVI Contract

创建接口定义你的 Intent、State 和 Event：

```kotlin
sealed interface IMain {
    // State 表示 UI 状态
    data class State(
        val count: Int = 0,
        val loading: Boolean = false
    ) : Mvi.State

    // Event 是一次性的副作用
    sealed interface Event : Mvi.Event {
        data class ShowToast(val message: String) : Event
        data class NavigateToDetail(val id: String) : Event
    }

    // Intent 代表用户操作或系统事件
    sealed interface Intent : Mvi.Intent {
        data object Increment : Intent, Mvi.Intent.Concurrent
        data object Decrement : Intent, Mvi.Intent.Sequential
        data class LoadData(val id: String) : Intent, Mvi.Intent.Sequential
    }

    // PartialChange 更新状态并发送事件
    fun interface PartialChange : Mvi.PartialChange<State, Event>
}
```

### 2. 创建 ViewModel

```kotlin
class MainViewModel : ViewModel() {
    private val contract: ReactiveContract<IMain.Intent, IMain.State, IMain.Event> by contract(
        initState = IMain.State(),
        defaultHandler = ::handleIntent
    )

    val stateFlow: StateFlow<IMain.State> = contract.stateFlow
    val eventFlow: Flow<IMain.Event> = contract.eventFlow

    fun dispatch(intent: IMain.Intent) = contract.dispatch(intent)

    // 所有 Intent 路由到同一方法，提升可读性
    private suspend fun handleIntent(intent: IMain.Intent): Flow<IMain.PartialChange> {
        return when (intent) {
            is IMain.Intent.Increment -> handleIncrement(intent)
            is IMain.Intent.Decrement -> handleDecrement(intent)
            is IMain.Intent.LoadData -> handleLoadData(intent)
        }
    }

    private fun handleIncrement(intent: IMain.Intent.Increment): Flow<IMain.PartialChange> {
        return IMain.PartialChange { snapshot ->
            snapshot.updateState { copy(count = count + 1) }
        }.asSingleFlow()
    }

    private fun handleDecrement(intent: IMain.Intent.Decrement): Flow<IMain.PartialChange> {
        return IMain.PartialChange { snapshot ->
            if (snapshot.state.count > 0) {
                snapshot.updateState { copy(count = count - 1) }
            } else {
                snapshot.withEvent(IMain.Event.ShowToast("Already at 0"))
            }
        }.asSingleFlow()
    }

    private fun handleLoadData(intent: IMain.Intent.LoadData): Flow<IMain.PartialChange> = flow {
        emit(IMain.PartialChange { it.updateState { copy(loading = true) } })

        try {
            val data = repository.loadData(intent.id)
            emit(IMain.PartialChange { it.updateState { copy(loading = false, data = data) } })
        } catch (e: Exception) {
            emit(
                IMain.PartialChange {
                    it.updateState { copy(loading = false) }
                        .withEvent(IMain.Event.ShowToast("Load failed: ${e.message}"))
                }
            )
        }
    }
}
```

### 3. 连接 UI（Activity/Fragment）

```kotlin
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 收集状态变化
        viewModel.stateFlow.collectState(this) {
            collectProperty(IMain.State::count) { count ->
                countTextView.text = count.toString()
            }

            collectProperty(IMain.State::loading) { loading ->
                progressBar.isVisible = loading
            }
        }

        // 收集一次性事件
        viewModel.eventFlow.collectEvent(this) {
            collectTyped<IMain.Event.ShowToast> { event ->
                Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
            }

            collectTyped<IMain.Event.NavigateToDetail> { event ->
                // 导航到详情页
            }
        }

        // 派发 Intent
        incrementButton.doOnClick { IMain.Intent.Increment }
            .debounceLeading(500)
            .launchWithLifecycle(this) { viewModel.dispatch(it) }
    }
}
```

## 核心概念

### MVI 架构

K-MVI 遵循单向数据流模式：

```
┌──────────┐
│   View   │ ──── Intent ───→ ┌────────────┐
│          │                   │  ViewModel │
│ (UI Layer)│ ←─── State ───── │            │
└──────────┘                   │ (Business  │
     ↑                         │   Logic)   │
     │                         └────────────┘
     └──── Event ──────────────────┘
```

1. **用户交互**产生 **Intent**
2. **Intent** 由 **ViewModel** 处理（通过 IntentHandler）
3. **Handler** 产生 **PartialChange** 来更新 **State**
4. **State** 变化被 **View** 观察并触发 UI 更新
5. **Event** 表示一次性副作用（Toast、导航等）

### 关键组件

K-MVI 将 UI 描述为一连串的**帧（frame）**。理解以下五个核心类型就基本理解了整个"帧模型"：

- **`Snapshot`** 是 UI 描述的**一帧**：持久化的 **`State`** 加上一个可选的、一次性的 **`Event`**。
- 每一帧都由**前一帧**通过 **`PartialChange`** 推导得出——概念上即 `frame N+1 = partialChange.apply(frame N)`。帧从不从头重建，而是逐步迁移。
- **`Intent`** 驱动这些转换：用户操作到来后，其 handler 发射一个或多个 `PartialChange`，每个 `PartialChange` 产生下一帧。

```
Intent ──handler──▶ PartialChange ──apply(old)──▶ Snapshot(State + Event?) ──▶ stateFlow / eventFlow
                                         ▲                    │
                                         └──── previous frame ┘
```

#### 1. Intent

用户操作或系统事件——管线的**入口**。你派发一个 `Intent`，框架将其路由到 handler，handler 产生 `PartialChange`。在 **HYBRID** 策略下，标记子接口决定 Intent 相对于其他 Intent 的调度方式：

- `Mvi.Intent.Concurrent`：并行处理——用于独立操作（点击、刷新）。
- `Mvi.Intent.Sequential`：按顺序逐个处理——用于依赖顺序的操作（提交、然后导航）。
- 无标记：回退到**分组**调度——同组内顺序、不同组间并行（例如将数据库写入操作归为一组）。

```kotlin
sealed interface Intent : Mvi.Intent {
    data object Refresh : Intent, Mvi.Intent.Concurrent           // 并行
    data class Submit(val form: Form) : Intent, Mvi.Intent.Sequential  // 严格顺序
    data class Load(val id: String) : Intent                      // 按标签分组
}
```

#### 2. State

帧的**持久化**部分——描述那些**留在屏幕上**的元素（当前列表、加载标志、输入文本），在发生变化之前持续存在于各帧之间。State 是**不可变的** data class，通过 `copy()` 产生新副本来更新。包含两类属性：

- **源状态属性**——构造函数中的 `val` 声明。它们是**唯一事实来源**，只有 `copy()` 才能实际修改它们（例如 `count`、`loading`）。
- **计算属性**——带有自定义 getter 的 `val`，由源属性**计算**得出。它们将展示/推导逻辑从 UI 中抽离，无需手动同步，可直接接入 `collectProperty(State::derived)`（当其输入变化时精确触发更新）。

```kotlin
data class State(
    val count: Int = 0,              // 源状态属性
    val targetCount: Int = 100,      // 源状态属性
    val showLoading: Boolean = false // 源状态属性
) : Mvi.State {
    val countText: String get() = count.toString()           // 计算属性
    val isAtTarget: Boolean get() = count == targetCount      // 计算属性
}

// 在 UI 中，计算属性与其他属性一样收集：
viewModel.stateFlow.collectState(this) {
    collectProperty(State::countText) { tv.text = it }
}
```

> 参见示例 `CounterContract.State`（`app/src/main/java/cc/colorcat/mvi/sample/count/`）：
> `count` / `targetCount` 是源属性；`countText` / `alpha255` 是计算属性，在 `CounterFragment` 中直接收集。

#### 3. Event

帧的**一次性**部分——那些**不属于 State** 的一次性副作用：显示 Toast/SnackBar、导航、触发动画。Event 转瞬即逝：它只存在于**恰好一帧**中，被分发给活动的收集器（若无监听者则丢弃），并在下一帧产生时被**清除或替换**。

```kotlin
sealed interface Event : Mvi.Event {
    data class ShowToast(val message: String) : Event
    data class NavigateTo(val id: String) : Event
}
```

> 经验法则：如果一个信号应该**触发一次**，它就是 Event；如果它需要在屏幕上**持续存在**，它就是 State。不要将一次性信号（如一次性 Toast 消息）放入 State。

#### 4. Snapshot

一个不可变的**帧**：`state: S` 加上可选的 `event: E?`。你从不直接修改 snapshot——你在 `PartialChange` 内部从 `old`（前一帧）**推导出下一帧**，使用：

- `updateState { copy(...) }`——修改某些状态，**清除**任何待处理的 event。
- `withEvent(event)`——附加一个事件，保持状态不变。
- `updateWith(event) { copy(...) }`——在同一帧中修改状态**并**附加事件。

`updateState` 清除 event 是有意为之且承担着重要职责：event 必须只存在于一帧中，否则会在后续每一帧中被重复投递。

#### 5. PartialChange

执行**一帧迁移**的函数：`apply(old: Snapshot): Snapshot`。这里的"部分"是相对于**完整帧**而言的——一个 change 只接触前一帧的**一部分**（一个或几个**源状态属性**，和/或 **event**，或两者兼具），其余未触及的部分原样继承。帧是连续的：每一帧都从前一帧迁移而来，从不重建。

```kotlin
// 两步加载：每个发射的 PartialChange 产生一帧。
flow {
    emit(MyPartialChange { it.updateState { copy(loading = true) } })          // 帧 1
    val data = repository.load()
    emit(MyPartialChange { it.updateWith(Event.Loaded) { copy(loading = false, data = data) } }) // 帧 2（状态 + 事件）
}
```

> 陷阱——不要在同一个 change 中链式调用 `withEvent(...).updateState { ... }`：`updateState` 会清除你刚设置的事件。应改用 `updateWith(event) { copy(...) }` 同时设置两者。

#### 6. Contract

暴露给 UI 的只读接口：

- `stateFlow: StateFlow<S>`：状态变化的热流
- `eventFlow: Flow<E>`：一次性事件的流

#### 7. ReactiveContract

扩展 `Contract`，增加了派发 Intent 的能力：

- `dispatch(intent: I)`：发送一个 Intent 进行处理

### Intent 处理策略

K-MVI 支持三种 Intent 处理策略：

#### 1. CONCURRENT（并发）

所有 Intent 并行处理。适用于独立操作。

```kotlin
KMvi.configure {
    copy(handleStrategy = HandleStrategy.CONCURRENT)
}
```

#### 2. SEQUENTIAL（顺序）

所有 Intent 按顺序逐个处理。适用于需要严格保持顺序的操作。

```kotlin
KMvi.configure {
    copy(handleStrategy = HandleStrategy.SEQUENTIAL)
}
```

#### 3. HYBRID（混合，推荐）

结合两种方式：

- 标记为 `Mvi.Intent.Concurrent` 的 Intent 并行处理
- 标记为 `Mvi.Intent.Sequential` 的 Intent 顺序处理
- Intent 可分组（组内顺序，组间并行）

##### 全局配置（应用级）

全局配置策略，作用于所有 contract：

```kotlin
KMvi.configure {
    copy(
        handleStrategy = HandleStrategy.HYBRID,
        hybridStrategyConfig = HybridStrategyConfig(
            groupChannelCapacity = Channel.BUFFERED,
        ),
        retryPolicy = { attempt, cause ->
            attempt < 3 && cause is IOException
        },
        logger = Logger(Logger.DEBUG),
    )
}
```

##### 按 Contract 配置（更常见）

在 ViewModel 中为特定 contract 单独配置策略，这是**推荐方式**，更加灵活：

```kotlin
class MyViewModel : ViewModel() {
    private val contract by contract(
        initState = MyState(),
        handleStrategy = HandleStrategy.HYBRID,
        hybridStrategyConfig = HybridStrategyConfig(
            groupChannelCapacity = Channel.BUFFERED,
        ),
        groupTagSelector = { intent ->
            when (intent) {
                // 数据库操作——组内顺序执行
                is MyIntent.SaveUser,
                is MyIntent.UpdateUser,
                is MyIntent.DeleteUser
                    -> "database"

                // 网络操作——组内顺序执行
                is MyIntent.FetchData,
                is MyIntent.UploadData
                    -> "network"
                // 默认：返回类名，同类 Intent 顺序执行
                else -> intent::class.java.name
            }
        },
    ) {
        register(MyIntent.SaveUser::class.java, ::handleSaveUser)
        register(MyIntent.FetchData::class.java, ::handleFetchData)
        // ... 注册其他 handler
    }
}
```

**工作原理：**

- 所有 `SaveUser`、`UpdateUser`、`DeleteUser` Intent 在 "database" 组内顺序执行
- 所有 `FetchData` 和 `UploadData` Intent 在 "network" 组内顺序执行
- 其他 Intent 默认使用类名作为分组键，确保同类 Intent 顺序执行

## 使用指南

### 注册 Intent Handler

#### 使用 defaultHandler（推荐）

**推荐方式**是使用 `defaultHandler` 在一个方法中处理所有 Intent。这样所有 Intent 的处理逻辑集中在一处，更易阅读：

```kotlin
class MainViewModel : ViewModel() {
    private val contract by contract(
        initState = MyState(),
        defaultHandler = ::handleIntent
    )

    val stateFlow: StateFlow<MyState> = contract.stateFlow
    val eventFlow: Flow<MyEvent> = contract.eventFlow

    fun dispatch(intent: MyIntent) = contract.dispatch(intent)

    // 所有 Intent 处理集中在同一方法——易于阅读和维护
    private suspend fun handleIntent(intent: MyIntent): Flow<MyPartialChange> {
        return when (intent) {
            is MyIntent.Increment -> handleIncrement(intent)
            is MyIntent.Decrement -> handleDecrement(intent)
            is MyIntent.LoadData -> handleLoadData(intent)
            is MyIntent.SaveData -> handleSaveData(intent)
        }.asSingleFlow()
    }

    private fun handleIncrement(intent: MyIntent.Increment): MyPartialChange {
        return MyPartialChange { snapshot ->
            snapshot.updateState { copy(count = count + 1) }
        }
    }

    private fun handleDecrement(intent: MyIntent.Decrement): MyPartialChange {
        return MyPartialChange { snapshot ->
            if (snapshot.state.count > 0) {
                snapshot.updateState { copy(count = count - 1) }
            } else {
                snapshot.withEvent(MyEvent.ShowToast("Already at 0"))
            }
        }
    }

    private fun handleLoadData(intent: MyIntent.LoadData): Flow<MyPartialChange> = flow {
        emit(MyPartialChange { it.updateState { copy(loading = true) } })

        try {
            val data = repository.loadData(intent.id)
            emit(MyPartialChange { it.updateState { copy(loading = false, data = data) } })
        } catch (e: Exception) {
            emit(
                MyPartialChange {
                    it.updateState { copy(loading = false) }
                        .withEvent(MyEvent.ShowToast("Load failed: ${e.message}"))
                }
            )
        }
    }

    private fun handleSaveData(intent: MyIntent.SaveData): Flow<MyPartialChange> = flow {
        // 保存逻辑...
    }
}
```

**优势：**

- ✅ 所有 Intent 集中在一处可见（`handleIntent` 方法）
- ✅ 易于理解完整的 Intent 处理流程
- ✅ 更好的代码导航和维护
- ✅ 清晰分离：路由在单一方法，具体实现在各自方法

##### 进阶模式：Intent 即 PartialChange

实现更简洁的代码，可以让你的 Intent 直接实现 PartialChange：

```kotlin
sealed interface IMain {
    data class State(val count: Int = 0) : Mvi.State

    sealed interface Event : Mvi.Event {
        data class ShowToast(val message: String) : Event
    }

    sealed interface Intent : Mvi.Intent

    // 既是 PartialChange 又是 Intent
    sealed class PartialChange : Mvi.PartialChange<State, Event> {
        override fun apply(old: Mvi.Snapshot<State, Event>): Mvi.Snapshot<State, Event> {
            return when (this) {
                is Increment -> {
                    if (old.state.count >= 99) {
                        old.withEvent(Event.ShowToast("Already reached 99"))
                    } else {
                        old.updateState { copy(count = count + 1) }
                    }
                }
                is Decrement -> {
                    if (old.state.count <= 0) {
                        old.withEvent(Event.ShowToast("Already reached 0"))
                    } else {
                        old.updateState { copy(count = count - 1) }
                    }
                }
            }
        }
    }

    // 同时是 Intent 和 PartialChange 的类型
    data object Increment : PartialChange(), Intent
    data object Decrement : PartialChange(), Intent
}

class MainViewModel : ViewModel() {
    private val contract by contract(
        initState = IMain.State(),
        defaultHandler = ::handleIntent
    )

    val stateFlow: StateFlow<IMain.State> = contract.stateFlow
    val eventFlow: Flow<IMain.Event> = contract.eventFlow

    fun dispatch(intent: IMain.Intent) = contract.dispatch(intent)

    // 简单的 handler——直接转换并返回
    private suspend fun handleIntent(intent: IMain.Intent): Flow<IMain.PartialChange> {
        return when (intent) {
            is IMain.PartialChange -> intent.asSingleFlow()
        }
    }
}
```

**优势：**

- ✅ 模板代码最少化
- ✅ Intent 和状态更新逻辑合二为一
- ✅ 非常适合简单的状态更新

**何时使用：**

- 简单的 Intent，状态更新逻辑直截了当
- 无需异步操作
- 状态更新逻辑自包含

#### 使用 register() 方法

另一种方式，使用显式注册：

```kotlin
private val contract by contract(
    initState = MyState()
) {
    register(IncrementIntent::class.java, ::handleIncrement)
    register(DecrementIntent::class.java, ::handleDecrement)
}
```

Handler 可以返回：

- 单个 `PartialChange`
- `Flow<PartialChange>`（用于多次更新）

#### 带 defaultHandler 的 HYBRID 策略

对于更复杂的应用，可以在使用 `defaultHandler` 的同时，按 contract 配置 Intent 处理策略和分组：

```kotlin
class UserViewModel : ViewModel() {
    private val contract by contract(
        initState = UserState(),
        handleStrategy = HandleStrategy.HYBRID,
        hybridStrategyConfig = HybridStrategyConfig(
            groupChannelCapacity = Channel.BUFFERED,
        ),
        groupTagSelector = { intent ->
            when (intent) {
                // 将所有数据库操作归为一组
                is UserIntent.Save,
                is UserIntent.Update,
                is UserIntent.Delete
                    -> "db_operations"

                // 将所有同步操作归为一组
                is UserIntent.SyncToServer,
                is UserIntent.DownloadFromServer
                    -> "sync_operations"

                // 默认：返回类名，同类 Intent 顺序执行
                else -> intent::class.java.name
            }
        },
        defaultHandler = ::handleIntent
    )

    val stateFlow: StateFlow<UserState> = contract.stateFlow
    val eventFlow: Flow<UserEvent> = contract.eventFlow

    fun dispatch(intent: UserIntent) = contract.dispatch(intent)

    // 所有 Intent 路由集中在一处
    private suspend fun handleIntent(intent: UserIntent): Flow<UserPartialChange> {
        return when (intent) {
            is UserIntent.Save -> handleSave(intent)
            is UserIntent.Update -> handleUpdate(intent)
            is UserIntent.Delete -> handleDelete(intent)
            is UserIntent.SyncToServer -> handleSync(intent)
            is UserIntent.DownloadFromServer -> handleDownload(intent)
            is UserIntent.Load -> handleLoad(intent)
        }
    }

    // 数据库操作——在 "db_operations" 组内顺序执行
    private fun handleSave(intent: UserIntent.Save): Flow<UserPartialChange> = flow {
        emit(UserPartialChange { it.updateState { copy(saving = true) } })
        repository.save(intent.user)
        emit(UserPartialChange { it.updateState { copy(saving = false) } })
    }

    private fun handleUpdate(intent: UserIntent.Update): Flow<UserPartialChange> = flow {
        // 更新逻辑——与其他数据库操作顺序执行
    }

    private fun handleDelete(intent: UserIntent.Delete): Flow<UserPartialChange> = flow {
        // 删除逻辑——与其他数据库操作顺序执行
    }

    // 同步操作——在 "sync_operations" 组内顺序执行
    private fun handleSync(intent: UserIntent.SyncToServer): Flow<UserPartialChange> = flow {
        // 同步逻辑——与其他同步操作顺序执行
    }

    private fun handleDownload(intent: UserIntent.DownloadFromServer): Flow<UserPartialChange> = flow {
        // 下载逻辑——与其他同步操作顺序执行
    }

    // 其他操作——按类名分组
    private fun handleLoad(intent: UserIntent.Load): Flow<UserPartialChange> = flow {
        // 加载逻辑
    }
}
```

**这种方式的优势：**

- ✅ 所有 Intent 在 `handleIntent` 方法中可见
- ✅ 不同 ViewModel 可以有不同的策略
- ✅ 精细控制 Intent 处理
- ✅ 更易测试和维护
- ✅ 无需修改全局配置

#### 带 register() 的 HYBRID 策略

如果你偏好注册方式：

```kotlin
class UserViewModel : ViewModel() {
    private val contract by contract(
        initState = UserState(),
        handleStrategy = HandleStrategy.HYBRID,
        hybridStrategyConfig = HybridStrategyConfig(
            groupChannelCapacity = Channel.BUFFERED,
        ),
        groupTagSelector = { intent ->
            when (intent) {
                is UserIntent.Save,
                is UserIntent.Update,
                is UserIntent.Delete
                    -> "db_operations"
                is UserIntent.SyncToServer,
                is UserIntent.DownloadFromServer
                    -> "sync_operations"
                else -> intent::class.java.name
            }
        },
    ) {
        register(UserIntent.Save::class.java, ::handleSave)
        register(UserIntent.Update::class.java, ::handleUpdate)
        register(UserIntent.Delete::class.java, ::handleDelete)
        register(UserIntent.SyncToServer::class.java, ::handleSync)
        register(UserIntent.Load::class.java, ::handleLoad)
    }
}
```

#### Transformer 模式

更底层，更灵活的 API：

```kotlin
private val contract by contract(
    initState = MyState(),
    transformer = { intentFlow ->
        intentFlow.flatMapMerge { intent ->
            when (intent) {
                is IncrementIntent -> handleIncrement(intent)
                is DecrementIntent -> handleDecrement(intent)
            }
        }
    }
)
```

### 收集状态变化

#### 收集完整 State

```kotlin
viewModel.stateFlow.collectState(this) {
    collectWhole { state ->
        updateUI(state)
    }
}
```

#### 收集特定属性

更高效——只在特定属性变化时触发：

```kotlin
viewModel.stateFlow.collectState(this) {
    collectProperty(MyState::loading) { isLoading ->
        progressBar.isVisible = isLoading
    }

    collectProperty(MyState::title) { title ->
        titleTextView.text = title
    }
}
```

### 收集事件

#### 收集所有事件

```kotlin
viewModel.eventFlow.collectEvent(this) {
    collectAll { event ->
        when (event) {
            is MyEvent.ShowToast -> showToast(event.message)
            is MyEvent.Navigate -> navigate(event.destination)
        }
    }
}
```

#### 收集特定事件类型

```kotlin
viewModel.eventFlow.collectEvent(this) {
    collectTyped<MyEvent.ShowToast> { event ->
        Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
    }

    collectTyped<MyEvent.Navigate> { event ->
        findNavController().navigate(event.destination)
    }
}
```

### 将 UI 事件转换为 Intent

K-MVI 为常见 UI 事件提供了便捷扩展：

```kotlin
// 按钮点击
button.doOnClick { MyIntent.ButtonClicked }
    .debounceLeading(500) // 防止快速重复点击
    .launchWithLifecycle(this) { viewModel.dispatch(it) }

// 文本变化
editText.doOnAfterTextChanged(debounceMillis = 300L) { editable ->
    send(MyIntent.TextChanged(editable?.toString().orEmpty()))
}.launchWithLifecycle(this) { viewModel.dispatch(it) }

// 复选框变化
checkbox.doOnCheckedChange { isChecked ->
    send(MyIntent.CheckboxToggled(isChecked))
}.launchWithLifecycle(this) { viewModel.dispatch(it) }
```

### 防抖与节流

#### debounceLeading

响应**首次**事件，随后在时间窗口内忽略后续事件。适用于防止重复点击：

```kotlin
button.doOnClick { SubmitIntent }
    .debounceLeading(500) // 首次点击后 500ms 内忽略后续点击
    .launchWithLifecycle(this) { viewModel.dispatch(it) }
```

#### debounce（来自 Kotlin Flow）

静默一段时间后响应**最后**一次事件。适用于搜索输入框：

```kotlin
searchEditText.doOnAfterTextChanged(debounceMillis = 300L) { editable ->
    send(SearchIntent(editable?.toString().orEmpty()))
}.launchWithLifecycle(this) { viewModel.dispatch(it) }
```

## 配置

在 Application 类中全局配置 K-MVI：

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        KMvi.configure {
            copy(
                // Intent 处理策略
                handleStrategy = HandleStrategy.HYBRID,

                // Intent 处理失败的重试策略
                retryPolicy = { attempt, cause ->
                    attempt < 3 && cause is IOException // attempt 从 0 开始计数
                },

                // Hybrid 策略配置
                hybridStrategyConfig = HybridStrategyConfig(
                    groupChannelCapacity = Channel.BUFFERED
                ),

                // 日志配置：默认为 WARN；debug 版本可用 DEBUG
                logger = if (BuildConfig.DEBUG) Logger(Logger.DEBUG) else Logger()
            )
        }
    }
}
```

### 配置项

#### HandleStrategy

- `CONCURRENT`：所有 Intent 并行处理
- `SEQUENTIAL`：所有 Intent 逐个顺序处理
- `HYBRID`：基于 Intent 标记和分组，混合并发与顺序

#### IntentQueueConfig

`intentQueueConfig` 配置每个 contract 的分发入口队列。默认值为
`IntentQueueConfig(capacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)`。
`dispatch(intent)` 是非阻塞的，返回 `DispatchResult`。

允许的容量值包括 `Channel.BUFFERED`、`Channel.CONFLATED`、`Channel.RENDEZVOUS` 以及任何正数 `Int`（包括 `Channel.UNLIMITED`）。对于大多数业务 Intent，推荐使用默认的 `BufferOverflow.SUSPEND`，因为它给出最清晰的结果语义：

- 有界容量或 `Channel.BUFFERED` + `SUSPEND`：`Submitted` 表示 Intent 已进入队列或被管线接收；如果队列已满，dispatch 返回 `Full`。
- `Channel.RENDEZVOUS` + `SUSPEND`：`Submitted` 表示有接收者就绪并取走了 Intent；否则返回 `Full`。
- `Channel.UNLIMITED` + `SUSPEND`：`Submitted` 通常表示 Intent 已进入无界队列；通常不会返回 `Full`，但如果生产者超出消费者，可能内存增长。
- `Channel.CONFLATED`：`Submitted` 表示最新 Intent 已提交至 conflated 队列。旧的待处理 Intent 可能被替换，本次提交的 Intent 在处理前也可能被后续 dispatch 替换。
- `DROP_OLDEST`：`Submitted` 表示队列策略接受了本次 dispatch。如果队列已满，最旧的待处理 Intent 将被丢弃，不会被执行。
- `DROP_LATEST`：`Submitted` 表示队列策略处理了本次 dispatch。如果队列已满，最新的 Intent 可能被丢弃，永远不会被执行。

在所有模式下，`DispatchResult.Submitted` 仅描述队列提交结果。它并不意味着 handler 已启动、已完成、已改变状态或已发送事件。仅在高频 UI Intent 且可丢弃或可替换时使用 conflated/drop 策略。

#### RetryPolicy

一个函数 `(attempt: Long, cause: Throwable) -> Boolean`，决定失败后是否重试。
`attempt` 遵循 `Flow.retryWhen` 语义，**从 0 开始计数**（`0` = 第一次重试）。

默认策略：

```kotlin
{ attempt, cause ->
    attempt < 3 && cause is IOException // 对 attempt 0..2 重试瞬时 I/O 错误
}
```

> 默认策略不重试编程错误，如 `IllegalStateException`、`IllegalArgumentException` 或 `NullPointerException`。如果你的应用有额外的领域特定瞬时故障，请覆盖此策略。

#### HybridStrategyConfig

HYBRID 策略的配置：

- `groupTagSelector`：为每个回退 Intent 分配分组标签，用于组内顺序处理的函数
- `groupChannelCapacity`：分组 Intent 通道的缓冲区大小（默认：`Channel.BUFFERED` = 64）。
  允许值：`Channel.BUFFERED`、`Channel.CONFLATED`、`Channel.RENDEZVOUS` 以及任何正数 `Int`（包括 `Channel.UNLIMITED`）。

#### Logger

`Logger` 是一个 `fun interface`，包含单一的 `log(priority, tag, error, message)` 方法。
使用工厂方法 `Logger(threshold: Int)` 创建默认的基于 Android Log 的实例，它会过滤掉低于指定优先级的消息（默认：`Logger.WARN`）：

```kotlin
// 仅记录 WARN 及以上级别（生产环境默认）
Logger()

// Debug 版本记录所有 DEBUG 及以上级别
Logger(Logger.DEBUG)

// 完全自定义后端（例如 Timber）
val customLogger = Logger { priority, tag, error, message ->
    val msg = buildString {
        append(message())
        if (error != null) append("\n${error.stackTraceToString()}")
    }
    when (priority) {
        Logger.DEBUG, Logger.INFO -> Timber.tag(tag).d(msg)
        Logger.WARN -> Timber.tag(tag).w(msg)
        Logger.ERROR -> Timber.tag(tag).e(msg)
    }
}
```

## 高级特性

### 错误处理

#### 在 Intent Handler 中

```kotlin
private fun handleLoadData(intent: LoadDataIntent): Flow<IMain.PartialChange> = flow {
    emit(IMain.PartialChange { it.updateState { copy(loading = true) } })

    try {
        val data = repository.loadData()
        emit(
            IMain.PartialChange {
                it.updateState { copy(loading = false, data = data) }
            }
        )
    } catch (e: Exception) {
        emit(
            IMain.PartialChange {
                it.updateState { copy(loading = false, error = e.message) }
                    .withEvent(MyEvent.ShowError(e.message))
            }
        )
    }
}
```

#### 全局重试策略

全局重试策略会在 handler 抛出未捕获异常后自动重启管线订阅，以便后续 Intent 仍能正常处理。失败的 Intent 不会重放——handler 应使用 try-catch 进行 Intent 级别的错误恢复：

```kotlin
KMvi.configure {
    copy(
        retryPolicy = { attempt, cause ->
            when (cause) {
                is NetworkException -> attempt < 5 // 网络错误最多重试 5 次（attempt 从 0 开始）
                is TimeoutException -> attempt < 2 // 超时最多重试 2 次
                else -> false
            }
        }
    )
}
```

### 测试

K-MVI 的清晰架构使测试变得简单：

#### 测试 Intent Handler

```kotlin
@Test
fun `increment intent increases count`() = runTest {
        val viewModel = MainViewModel()
        val initialState = viewModel.stateFlow.value

        viewModel.dispatch(IMain.Intent.Increment)

        advanceUntilIdle()
        assertEquals(initialState.count + 1, viewModel.stateFlow.value.count)
    }
```

#### 测试 State Flow

```kotlin
@Test
fun `loading state changes correctly`() = runTest {
        val viewModel = MainViewModel()
        val states = mutableListOf<MyState>()

        val job = launch {
            viewModel.stateFlow.collect { states.add(it) }
        }

        viewModel.dispatch(LoadDataIntent)
        advanceUntilIdle()

        assertTrue(states.any { it.loading })
        assertFalse(states.last().loading)

        job.cancel()
    }
```

#### 测试 Event Flow

```kotlin
@Test
fun `error event is emitted on failure`() = runTest {
        val viewModel = MainViewModel()
        val events = mutableListOf<MyEvent>()

        val job = launch {
            viewModel.eventFlow.collect { events.add(it) }
        }

        viewModel.dispatch(FailingIntent)
        advanceUntilIdle()

        assertTrue(events.any { it is MyEvent.ShowError })

        job.cancel()
    }
```

### 自定义 Logger

`Logger` 是一个 `fun interface`——你可以直接实现它，或使用提供的工厂方法：

```kotlin
// 方案 1：基于阈值的默认日志器（使用 Android Log）
KMvi.configure {
    copy(logger = Logger(Logger.DEBUG))  // 记录 DEBUG 及以上级别
}

// 方案 2：完全自定义后端（例如 Timber + Crashlytics）
val customLogger = Logger { priority, tag, error, message ->
    val msg = buildString {
        append(message())
        if (error != null) append("\n${error.stackTraceToString()}")
    }
    when (priority) {
        Logger.DEBUG, Logger.INFO -> Timber.tag(tag).d(msg)
        Logger.WARN -> Timber.tag(tag).w(msg)
        Logger.ERROR -> {
            Timber.tag(tag).e(msg)
            Crashlytics.log("[$tag] $msg")
        }
    }
}

KMvi.configure {
    copy(logger = customLogger)
}
```

## 最佳实践

### 1. 保持 State 不可变

始终使用 data class 和 `copy()` 进行状态更新：

```kotlin
// ✅ 正确
data class MyState(val count: Int = 0) : Mvi.State

snapshot.updateState { copy(count = count + 1) }

// ❌ 错误
class MyState(var count: Int = 0) : Mvi.State
state.count++ // 直接修改状态
```

### 2. 使用 Event 处理一次性操作

不要将瞬时信息存储在 State 中：

```kotlin
// ✅ 正确
sealed interface Event : Mvi.Event {
    data class ShowToast(val message: String) : Event
}

// ❌ 错误
data class State(
    val toastMessage: String? = null // 这是 Event，不是 State
) : Mvi.State
```

### 3. 选择合适的 Intent 策略

- 独立操作使用 `Concurrent`
- 依赖顺序的操作使用 `Sequential`
- HYBRID 模式下将相关顺序操作分组

```kotlin
// ✅ 正确
sealed interface Intent : Mvi.Intent {
    data object Refresh : Intent, Mvi.Intent.Concurrent // 随时可发生
    data class SaveData(val data: Data) : Intent, Mvi.Intent.Sequential // 必须在下一步之前完成
}
```

### 4. 优雅地处理错误

始终在 Intent handler 中处理错误：

```kotlin
private fun handleSave(intent: SaveIntent): Flow<PartialChange> = flow {
    try {
        emit(PartialChange { it.updateState { copy(saving = true) } })
        repository.save(intent.data)
        emit(
            PartialChange {
                it.updateState { copy(saving = false) }
                    .withEvent(Event.SaveSuccess)
            }
        )
    } catch (e: Exception) {
        emit(
            PartialChange {
                it.updateState { copy(saving = false) }
                    .withEvent(Event.SaveError(e.message))
            }
        )
    }
}
```

### 5. 使用生命周期感知的收集

始终使用 `collectState` 和 `collectEvent` 扩展函数：

```kotlin
// ✅ 正确
viewModel.stateFlow.collectState(this) { /* ... */ }

// ❌ 错误 - 不尊重生命周期
lifecycleScope.launch {
    viewModel.stateFlow.collect { /* ... */ }
}
```

### 6. 优化 State 收集

只收集你需要的内容：

```kotlin
// ✅ 正确 - 仅在 count 变化时更新
collectProperty(MyState::count) { count ->
    countTextView.text = count.toString()
}

// ❌ 效率较低 - 每次状态变化都更新
collectWhole { state ->
    countTextView.text = state.count.toString()
}
```

## 示例 App

项目包含一个示例 app，演示了 K-MVI 的各种特性：

- 基本的计数器（增加/减少）
- 按钮点击的防抖处理
- 加载状态与错误处理
- 事件处理（Toast、导航）
- 不同的 Intent 处理策略

请查看 [`app`](app/) 模块获取完整示例。

## 环境要求

- Android API 24+（Android 7.0）
- Kotlin 1.9.10+
- Coroutines 1.6.0+

## 依赖

K-MVI 的依赖极少：

- `androidx.lifecycle:lifecycle-viewmodel-ktx` - ViewModel 支持
- `androidx.lifecycle:lifecycle-runtime-ktx` - 生命周期感知的收集
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` - 协程支持

## 贡献

欢迎贡献！请随时提交 Pull Request。

## 许可协议

```
Copyright 2024 ccolorcat

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## 作者

**ccolorcat**

- GitHub: [@ccolorcat](https://github.com/ccolorcat)

## 致谢

K-MVI 受以下项目概念启发而构建：
---

**如果觉得有用，请 Star ⭐ 支持！**
