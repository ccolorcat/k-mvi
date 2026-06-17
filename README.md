# K-MVI

[![Maven Central](https://img.shields.io/maven-central/v/cc.colorcat.mvi/core.svg)](https://search.maven.org/artifact/cc.colorcat.mvi/core)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A lightweight, powerful, and type-safe MVI (Model-View-Intent) architecture library for Android, written in Kotlin with
Coroutines and Flow.

## Features

✅ **Type-Safe MVI**: Strongly typed Intent, State, and Event generics ensure compile-time safety

✅ **Flexible Intent Handling**: Three strategies (CONCURRENT, SEQUENTIAL, HYBRID) for different use cases

✅ **Lifecycle-Aware**: Automatic lifecycle management with Android Architecture Components

✅ **Kotlin Coroutines**: Built on top of Flow for reactive, asynchronous programming

✅ **Easy to Use**: Simple DSL and extension functions for common scenarios

✅ **Minimal Boilerplate**: Clean API design reduces repetitive code

✅ **Testable**: Clear separation of concerns makes unit testing straightforward

✅ **Production Ready**: Includes retry policies, error handling, and logging

## Table of Contents

- [Installation](#installation)
- [Quick Start](#quick-start)
- [Core Concepts](#core-concepts)
- [Usage Guide](#usage-guide)
- [Configuration](#configuration)
- [Advanced Features](#advanced-features)
- [Best Practices](#best-practices)
- [Sample App](#sample-app)
- [License](#license)

## Installation

Add the dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("cc.colorcat.mvi:core:1.2.6")
}
```

## Quick Start

### 1. Define Your MVI Contract

Create an interface that defines your Intent, State, and Event:

```kotlin
sealed interface IMain {
    // State represents the UI state
    data class State(
        val count: Int = 0,
        val loading: Boolean = false
    ) : Mvi.State

    // Events are one-time side effects
    sealed interface Event : Mvi.Event {
        data class ShowToast(val message: String) : Event
        data class NavigateToDetail(val id: String) : Event
    }

    // Intents represent user actions or system events
    sealed interface Intent : Mvi.Intent {
        data object Increment : Intent, Mvi.Intent.Concurrent
        data object Decrement : Intent, Mvi.Intent.Sequential
        data class LoadData(val id: String) : Intent, Mvi.Intent.Sequential
    }

    // PartialChange updates state and emits events
    fun interface PartialChange : Mvi.PartialChange<State, Event>
}
```

### 2. Create a ViewModel

```kotlin
class MainViewModel : ViewModel() {
    private val contract: ReactiveContract<IMain.Intent, IMain.State, IMain.Event> by contract(
        initState = IMain.State(),
        defaultHandler = ::handleIntent
    )

    val stateFlow: StateFlow<IMain.State> = contract.stateFlow
    val eventFlow: Flow<IMain.Event> = contract.eventFlow

    fun dispatch(intent: IMain.Intent) = contract.dispatch(intent)

    // Handle all intents in one method for better readability
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

### 3. Connect to UI (Activity/Fragment)

```kotlin
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Collect state changes
        viewModel.stateFlow.collectState(this) {
            collectProperty(IMain.State::count) { count ->
                countTextView.text = count.toString()
            }

            collectProperty(IMain.State::loading) { loading ->
                progressBar.isVisible = loading
            }
        }

        // Collect one-time events
        viewModel.eventFlow.collectEvent(this) {
            collectTyped<IMain.Event.ShowToast> { event ->
                Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
            }

            collectTyped<IMain.Event.NavigateToDetail> { event ->
                // Navigate to detail screen
            }
        }

        // Dispatch intents
        incrementButton.doOnClick { IMain.Intent.Increment }
            .debounceLeading(500)
            .launchWithLifecycle(this) { viewModel.dispatch(it) }
    }
}
```

## Core Concepts

### MVI Architecture

K-MVI follows the unidirectional data flow pattern:

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

1. **User interactions** generate **Intents**
2. **Intents** are processed by **ViewModel** (via IntentHandlers)
3. **Handlers** produce **PartialChanges** that update **State**
4. **State** changes are observed by **View** and trigger UI updates
5. **Events** represent one-time side effects (toasts, navigation, etc.)

### Key Components

K-MVI describes the UI as a sequence of **frames**. Understanding the five core types below is mostly
understanding this "frame model":

- A **`Snapshot`** is one **frame** of the UI description: a persistent **`State`** plus an optional,
  transient **`Event`**.
- Each new frame is **derived from the previous one** by a **`PartialChange`** — conceptually
  `frame N+1 = partialChange.apply(frame N)`. A frame is never rebuilt from scratch; it is migrated.
- **`Intent`s** are what drive these transitions: a user action arrives, its handler emits one or more
  `PartialChange`s, and each produces the next frame.

```
Intent ──handler──▶ PartialChange ──apply(old)──▶ Snapshot(State + Event?) ──▶ stateFlow / eventFlow
                                         ▲                    │
                                         └──── previous frame ┘
```

#### 1. Intent

A user action or system event — the **entry point** to the pipeline. You dispatch an `Intent`, the
framework routes it to a handler, and the handler produces `PartialChange`s. Under the **HYBRID**
strategy, marker sub-interfaces decide how an intent is scheduled relative to others:

- `Mvi.Intent.Concurrent`: processed in parallel — for independent actions (a click, a refresh).
- `Mvi.Intent.Sequential`: processed one-at-a-time in a single FIFO queue — for order-dependent
  actions (submit, then navigate).
- Neither marker: falls back to **group** scheduling — sequential within the same
  `GroupTagSelector` tag, parallel across different tags (e.g. group all DB writes together).

```kotlin
sealed interface Intent : Mvi.Intent {
    data object Refresh : Intent, Mvi.Intent.Concurrent          // parallel
    data class Submit(val form: Form) : Intent, Mvi.Intent.Sequential  // strict order
    data class Load(val id: String) : Intent                     // grouped by tag
}
```

#### 2. State

The **persistent** part of a frame — it describes the elements that *stay on screen* (the current
list, a loading flag, the input text) and carries across frames until something changes it. State is an
**immutable** data class, updated only by producing a new copy via `copy()`. It has two kinds of
property:

- **Source state properties** — the stored constructor `val`s. They are the **single source of
  truth** and the only things a `copy()` actually changes (e.g. `count`, `loading`).
- **Computed properties** — derived `val`s with a custom getter, computed *from* the source
  properties. They keep presentation/derivation logic out of the UI, are never stored or manually kept
  in sync, and plug straight into `collectProperty(State::derived)` (they change exactly when their
  inputs change).

```kotlin
data class State(
    val count: Int = 0,              // source state property
    val targetCount: Int = 100,      // source state property
    val showLoading: Boolean = false // source state property
) : Mvi.State {
    val countText: String get() = count.toString()           // computed
    val isAtTarget: Boolean get() = count == targetCount      // computed
}

// In the UI, computed properties are collected like any other:
viewModel.stateFlow.collectState(this) {
    collectProperty(State::countText) { tv.text = it }
}
```

> See the sample `CounterContract.State` (`app/src/main/java/cc/colorcat/mvi/sample/count/`):
> `count` / `targetCount` are source properties; `countText` / `alpha255` are computed and collected
> directly in `CounterFragment`.

#### 3. Event

The **transient** part of a frame — a one-time side effect that does **not** belong in State: showing
a toast/snackbar, navigation, triggering an animation. An event is fleeting: it lives in **exactly one
frame**, is delivered to an active collector (or dropped if none is listening), and is **cleared or
replaced** the moment the next frame is produced.

```kotlin
sealed interface Event : Mvi.Event {
    data class ShowToast(val message: String) : Event
    data class NavigateTo(val id: String) : Event
}
```

> Rule of thumb: if a signal should fire *once*, it's an Event; if it should *persist* on screen, it's
> State. Don't put transient signals (like a one-shot toast message) into State.

#### 4. Snapshot

One immutable **frame**: `state: S` plus an optional `event: E?`. You never mutate a snapshot — you
**derive the next frame** from the `old` one inside a `PartialChange`, using:

- `updateState { copy(...) }` — change some state, **clears** any pending event.
- `withEvent(event)` — attach an event, keep the state.
- `updateWith(event) { copy(...) }` — change state **and** attach an event in the same frame.

`updateState` clearing the event is intentional and load-bearing: an event must live in only one
frame, otherwise it would be re-delivered on every later frame.

#### 5. PartialChange

The function that performs **one frame migration**: `apply(old: Snapshot): Snapshot`. The word
**"partial"** is relative to the *whole frame* — a change touches only **part** of the previous frame
(one or a few **source state properties**, and/or the **event**, or both), and everything it doesn't
touch carries over unchanged. Frames are continuous: each is migrated from the previous, never rebuilt.

```kotlin
// A two-step load: each emitted PartialChange produces one frame.
flow {
    emit(MyPartialChange { it.updateState { copy(loading = true) } })          // frame 1
    val data = repository.load()
    emit(MyPartialChange { it.updateWith(Event.Loaded) { copy(loading = false, data = data) } }) // frame 2 (state + event)
}
```

> Pitfall — don't chain `withEvent(...).updateState { ... }` inside a single change: `updateState`
> clears the event you just set. Set both together with `updateWith(event) { copy(...) }` instead.

#### 6. Contract

The read-only interface exposed to the UI:

- `stateFlow: StateFlow<S>`: Hot flow of state changes
- `eventFlow: Flow<E>`: Flow of one-time events

#### 7. ReactiveContract

Extends `Contract` with the ability to dispatch intents:

- `dispatch(intent: I)`: Send an intent for processing

### Intent Handling Strategies

K-MVI supports three strategies for processing intents:

#### 1. CONCURRENT

All intents are processed in parallel. Best for independent operations.

```kotlin
KMvi.configure {
    copy(handleStrategy = HandleStrategy.CONCURRENT)
}
```

#### 2. SEQUENTIAL

All intents are processed one-by-one in order. Best for operations that must maintain strict ordering.

```kotlin
KMvi.configure {
    copy(handleStrategy = HandleStrategy.SEQUENTIAL)
}
```

#### 3. HYBRID (Recommended)

Combines both approaches:

- Intents marked with `Mvi.Intent.Concurrent` are processed in parallel
- Intents marked with `Mvi.Intent.Sequential` are processed sequentially
- Intents can be grouped (group members process sequentially, groups process in parallel)

##### Global Configuration (Application-wide)

Configure the strategy globally for all contracts:

```kotlin
KMvi.configure {
    copy(
        handleStrategy = HandleStrategy.HYBRID,
        hybridStrategyConfig = HybridStrategyConfig(
            groupTagSelector = { intent ->
                when (intent) {
                    is LoadIntent -> "load_group"
                    is SaveIntent -> "save_group"
                    // Default: return class name so same type intents execute sequentially
                    else -> intent::class.java.name
                }
            }
        )
    )
}
```

##### Per-Contract Configuration (More Common)

Configure the strategy for a specific contract in your ViewModel. This is the **recommended approach** as it provides
more flexibility:

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
                // Database operations - process sequentially within this group
                is MyIntent.SaveUser,
                is MyIntent.UpdateUser,
                is MyIntent.DeleteUser
                    -> "database"

                // Network operations - process sequentially within this group
                is MyIntent.FetchData,
                is MyIntent.UploadData
                    -> "network"
                // Default: return class name so same type intents execute sequentially
                else -> intent::class.java.name
            }
        },
    ) {
        register(MyIntent.SaveUser::class.java, ::handleSaveUser)
        register(MyIntent.FetchData::class.java, ::handleFetchData)
        // ... register other handlers
    }
}
```

**How it works:**

- All `SaveUser`, `UpdateUser`, and `DeleteUser` intents will execute sequentially (one after another) within the "
  database" group
- All `FetchData` and `UploadData` intents will execute sequentially within the "network" group
- Other intents use their class name as group key by default, ensuring same-type intents execute sequentially

## Usage Guide

### Registering Intent Handlers

#### Using defaultHandler (Recommended)

The **recommended approach** is to use `defaultHandler` to handle all intents in a single method. This makes the code
more readable as you can see all intent handling logic in one place:

```kotlin
class MainViewModel : ViewModel() {
    private val contract by contract(
        initState = MyState(),
        defaultHandler = ::handleIntent
    )

    val stateFlow: StateFlow<MyState> = contract.stateFlow
    val eventFlow: Flow<MyEvent> = contract.eventFlow

    fun dispatch(intent: MyIntent) = contract.dispatch(intent)

    // All intent handling in one method - easy to read and maintain
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
        // Save logic...
    }
}
```

**Benefits:**

- ✅ All intents visible in one place (`handleIntent` method)
- ✅ Easy to understand the complete intent flow
- ✅ Better code navigation and maintenance
- ✅ Clear separation: routing in one method, handling in individual methods

##### Advanced Pattern: Intent as PartialChange

For even simpler code, you can make your Intent implement PartialChange directly:

```kotlin
sealed interface IMain {
    data class State(val count: Int = 0) : Mvi.State

    sealed interface Event : Mvi.Event {
        data class ShowToast(val message: String) : Event
    }

    sealed interface Intent : Mvi.Intent

    // PartialChange that can also be an Intent
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

    // Intents that are also PartialChanges
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

    // Simple handler - just cast and return
    private suspend fun handleIntent(intent: IMain.Intent): Flow<IMain.PartialChange> {
        return when (intent) {
            is IMain.PartialChange -> intent.asSingleFlow()
        }
    }
}
```

**Benefits:**

- ✅ Minimal boilerplate code
- ✅ Intent and state update logic in one place
- ✅ Perfect for simple state updates

**When to use:**

- Simple intents with straightforward state updates
- No async operations needed
- State update logic is self-contained

#### Using register() Method

Alternative approach using explicit registration:

```kotlin
private val contract by contract(
    initState = MyState()
) {
    register(IncrementIntent::class.java, ::handleIncrement)
    register(DecrementIntent::class.java, ::handleDecrement)
}
```

Handlers can return:

- Single `PartialChange`
- `Flow<PartialChange>` for multiple updates

#### HYBRID Strategy with defaultHandler

For more complex applications, you can configure the intent handling strategy and grouping per-contract using
`defaultHandler`:

```kotlin
class UserViewModel : ViewModel() {
    private val contract by contract(
        initState = UserState(),
        handleStrategy = HandleStrategy.HYBRID,
        config = HybridStrategyConfig(
            groupTagSelector = { intent ->
                when (intent) {
                    // Group all database operations together
                    is UserIntent.Save,
                    is UserIntent.Update,
                    is UserIntent.Delete
                        -> "db_operations"

                    // Group all sync operations together
                    is UserIntent.SyncToServer,
                    is UserIntent.DownloadFromServer
                        -> "sync_operations"

                    // Default: return class name so same type intents execute sequentially
                    else -> intent::class.java.name
                }
            }
        ),
        defaultHandler = ::handleIntent
    )

    val stateFlow: StateFlow<UserState> = contract.stateFlow
    val eventFlow: Flow<UserEvent> = contract.eventFlow

    fun dispatch(intent: UserIntent) = contract.dispatch(intent)

    // All intent routing in one place
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

    // Database operations - will execute sequentially within "db_operations" group
    private fun handleSave(intent: UserIntent.Save): Flow<UserPartialChange> = flow {
        emit(UserPartialChange { it.updateState { copy(saving = true) } })
        repository.save(intent.user)
        emit(UserPartialChange { it.updateState { copy(saving = false) } })
    }

    private fun handleUpdate(intent: UserIntent.Update): Flow<UserPartialChange> = flow {
        // Update logic - sequential with other db operations
    }

    private fun handleDelete(intent: UserIntent.Delete): Flow<UserPartialChange> = flow {
        // Delete logic - sequential with other db operations
    }

    // Sync operations - will execute sequentially within "sync_operations" group
    private fun handleSync(intent: UserIntent.SyncToServer): Flow<UserPartialChange> = flow {
        // Sync logic - sequential with other sync operations
    }

    private fun handleDownload(intent: UserIntent.DownloadFromServer): Flow<UserPartialChange> = flow {
        // Download logic - sequential with other sync operations
    }

    // Other operations - grouped by class name
    private fun handleLoad(intent: UserIntent.Load): Flow<UserPartialChange> = flow {
        // Load logic
    }
}
```

**Benefits of this approach:**

- ✅ All intents visible in `handleIntent` method
- ✅ Different ViewModels can have different strategies
- ✅ Fine-grained control over intent processing
- ✅ Easier to test and maintain
- ✅ No need to modify global configuration

#### HYBRID Strategy with register()

If you prefer the registration approach:

```kotlin
class UserViewModel : ViewModel() {
    private val contract by contract(
        initState = UserState(),
        handleStrategy = HandleStrategy.HYBRID,
        config = HybridStrategyConfig(
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
            }
        )
    ) {
        register(UserIntent.Save::class.java, ::handleSave)
        register(UserIntent.Update::class.java, ::handleUpdate)
        register(UserIntent.Delete::class.java, ::handleDelete)
        register(UserIntent.SyncToServer::class.java, ::handleSync)
        register(UserIntent.Load::class.java, ::handleLoad)
    }
}
```

#### Transformer-based API

More control, for advanced use cases:

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

### Collecting State Changes

#### Collect Entire State

```kotlin
viewModel.stateFlow.collectState(this) {
    collectWhole { state ->
        updateUI(state)
    }
}
```

#### Collect Specific Properties

More efficient - only triggers when the specific property changes:

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

### Collecting Events

#### Collect All Events

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

#### Collect Specific Event Types

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

### Converting UI Events to Intents

K-MVI provides convenient extensions for common UI events:

```kotlin
// Button clicks
button.doOnClick { MyIntent.ButtonClicked }
    .debounceLeading(500) // Prevent rapid clicks
    .launchWithLifecycle(this) { viewModel.dispatch(it) }

// Text changes
editText.doOnAfterTextChanged(debounceMillis = 300L) { editable ->
    send(MyIntent.TextChanged(editable?.toString().orEmpty()))
}.launchWithLifecycle(this) { viewModel.dispatch(it) }

// Checkbox changes
checkbox.doOnCheckedChange { isChecked ->
    send(MyIntent.CheckboxToggled(isChecked))
}.launchWithLifecycle(this) { viewModel.dispatch(it) }
```

### Debouncing and Throttling

#### debounceLeading

Responds to the **first** event, then ignores subsequent events for a time window. Perfect for preventing accidental
double-clicks:

```kotlin
button.doOnClick { SubmitIntent }
    .debounceLeading(500) // Ignore clicks within 500ms of the first click
    .launchWithLifecycle(this) { viewModel.dispatch(it) }
```

#### debounce (from Kotlin Flow)

Responds to the **last** event after a period of silence. Perfect for search as user types:

```kotlin
searchEditText.doOnAfterTextChanged(debounceMillis = 300L) { editable ->
    send(SearchIntent(editable?.toString().orEmpty()))
}.launchWithLifecycle(this) { viewModel.dispatch(it) }
```

## Configuration

Configure K-MVI globally in your Application class:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        KMvi.configure {
            copy(
                // Intent handling strategy
                handleStrategy = HandleStrategy.HYBRID,

                // Retry policy for failed intent processing
                retryPolicy = { attempt, cause ->
                    attempt < 3 && cause is IOException // attempt is 0-based
                },

                // Hybrid strategy configuration
                hybridStrategyConfig = HybridStrategyConfig(
                    groupTagSelector = { intent ->
                        // Return a group key for intents that should be grouped
                        when (intent) {
                            is DatabaseIntent -> "database"
                            is NetworkIntent -> "network"
                            // Default: return class name so same type intents execute sequentially
                            else -> intent::class.java.name
                        }
                    },
                    groupChannelCapacity = Channel.BUFFERED
                ),

                // Logger configuration: WARN by default; use DEBUG in debug builds
                logger = if (BuildConfig.DEBUG) Logger(Logger.DEBUG) else Logger()
            )
        }
    }
}
```

### Configuration Options

#### HandleStrategy

- `CONCURRENT`: All intents process in parallel
- `SEQUENTIAL`: All intents process one-by-one
- `HYBRID`: Mix of concurrent and sequential based on intent markers and grouping

#### IntentQueueConfig

`intentQueueConfig` configures the dispatch entry queue for each contract. The default is
`IntentQueueConfig(capacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)`.
`dispatch(intent)` is non-blocking and returns a `DispatchResult`.

Allowed capacities are `Channel.BUFFERED`, `Channel.CONFLATED`, `Channel.RENDEZVOUS`, and any
positive `Int` (including `Channel.UNLIMITED`). The default `BufferOverflow.SUSPEND` is recommended
for most business intents because it gives the clearest result semantics:

- Bounded capacity or `Channel.BUFFERED` + `SUSPEND`: `Submitted` means the intent entered the queue
  or was received by the pipeline; if the queue is full, dispatch returns `Full`.
- `Channel.RENDEZVOUS` + `SUSPEND`: `Submitted` means a receiver was ready and took the intent;
  otherwise dispatch returns `Full`.
- `Channel.UNLIMITED` + `SUSPEND`: `Submitted` usually means the intent entered an unbounded queue;
  it normally does not return `Full`, but can grow memory if producers outrun consumers.
- `Channel.CONFLATED`: `Submitted` means the latest intent was submitted to the conflated queue.
  Older pending intents may be replaced, and this submitted intent may also be replaced by a later
  dispatch before it is processed.
- `DROP_OLDEST`: `Submitted` means the queue policy accepted this dispatch. If the queue was full,
  the oldest pending intent was dropped and will not be processed.
- `DROP_LATEST`: `Submitted` means the queue policy handled this dispatch. If the queue was full,
  this latest intent may have been dropped and may never be processed.

In every mode, `DispatchResult.Submitted` only describes queue submission. It does not mean the
handler has started, completed, changed state, or emitted an event. Use conflated/drop policies only
for replaceable or discardable high-frequency UI intents.

#### RetryPolicy

A function `(attempt: Long, cause: Throwable) -> Boolean` that determines whether to retry after a failure.
`attempt` follows `Flow.retryWhen` semantics and is **0-based** (`0` = first retry).

Default policy:

```kotlin
{ attempt, cause ->
    attempt < 3 && cause is IOException // Retries transient I/O failures on attempt 0..2
}
```

> The default policy does not retry programming errors such as `IllegalStateException`,
> `IllegalArgumentException`, or `NullPointerException`. Override it if your app has
> additional domain-specific transient failures.

#### HybridStrategyConfig

Configuration for HYBRID strategy:

- `groupTagSelector`: Function to assign a group tag to each fallback intent for sequential processing
- `groupChannelCapacity`: Buffer size for grouped intent channels (default: `Channel.BUFFERED` = 64).
  Allowed values are `Channel.BUFFERED`, `Channel.CONFLATED`, `Channel.RENDEZVOUS`, and any positive `Int` (including `Channel.UNLIMITED`).

#### Logger

`Logger` is a `fun interface` with a single `log(priority, tag, error, message)` method.
Use the factory `Logger(threshold: Int)` to create a default Android-Log-backed instance
that filters messages below the given priority level (default: `Logger.WARN`):

```kotlin
// Only log WARN and above (production default)
Logger()

// Log everything from DEBUG in debug builds
Logger(Logger.DEBUG)

// Fully custom backend (e.g., Timber)
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

## Advanced Features

### Error Handling

#### In Intent Handlers

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

#### Global Retry Policy

The global retry policy will automatically restart the pipeline subscription after an unhandled handler exception, so
subsequent intents can still be processed. The failing intent is not replayed — handlers should use try-catch for
intent-level error recovery:

```kotlin
KMvi.configure {
    copy(
        retryPolicy = { attempt, cause ->
            when (cause) {
                is NetworkException -> attempt < 5 // Retry network errors (0-based attempt)
                is TimeoutException -> attempt < 2 // Retry timeouts (0-based attempt)
                else -> false
            }
        }
    )
}
```

### Testing

K-MVI's clean architecture makes testing straightforward:

#### Testing Intent Handlers

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

#### Testing State Flow

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

#### Testing Event Flow

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

### Custom Logger

`Logger` is a `fun interface` — implement it directly or use the provided factory:

```kotlin
// Option 1: threshold-based default logger (uses Android Log)
KMvi.configure {
    copy(logger = Logger(Logger.DEBUG))  // Log DEBUG and above
}

// Option 2: fully custom backend (e.g., Timber + Crashlytics)
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

## Best Practices

### 1. Keep State Immutable

Always use data classes and `copy()` for state updates:

```kotlin
// ✅ Good
data class MyState(val count: Int = 0) : Mvi.State

snapshot.updateState { copy(count = count + 1) }

// ❌ Bad
class MyState(var count: Int = 0) : Mvi.State
state.count++ // Mutating state directly
```

### 2. Use Events for One-Time Actions

Don't store transient information in state:

```kotlin
// ✅ Good
sealed interface Event : Mvi.Event {
    data class ShowToast(val message: String) : Event
}

// ❌ Bad
data class State(
    val toastMessage: String? = null // This is an event, not state
) : Mvi.State
```

### 3. Choose Appropriate Intent Strategy

- Use `Concurrent` for independent operations
- Use `Sequential` for order-dependent operations
- Group related sequential operations in HYBRID mode

```kotlin
// ✅ Good
sealed interface Intent : Mvi.Intent {
    data object Refresh : Intent, Mvi.Intent.Concurrent // Can happen anytime
    data class SaveData(val data: Data) : Intent, Mvi.Intent.Sequential // Must complete before next
}
```

### 4. Handle Errors Gracefully

Always handle errors in intent handlers:

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

### 5. Use Lifecycle-Aware Collection

Always use `collectState` and `collectEvent` extensions:

```kotlin
// ✅ Good
viewModel.stateFlow.collectState(this) { /* ... */ }

// ❌ Bad - doesn't respect lifecycle
lifecycleScope.launch {
    viewModel.stateFlow.collect { /* ... */ }
}
```

### 6. Optimize State Collection

Collect only what you need:

```kotlin
// ✅ Good - only updates when count changes
collectProperty(MyState::count) { count ->
    countTextView.text = count.toString()
}

// ❌ Less efficient - updates on every state change
collectWhole { state ->
    countTextView.text = state.count.toString()
}
```

## Sample App

The project includes a sample app demonstrating various K-MVI features:

- Basic counter with increment/decrement
- Debouncing button clicks
- Loading states and error handling
- Event handling (toasts, navigation)
- Different intent handling strategies

Check the [`app`](app/) module for complete examples.

## Requirements

- Android API 24+ (Android 7.0)
- Kotlin 1.9.10+
- Coroutines 1.6.0+

## Dependencies

K-MVI has minimal dependencies:

- `androidx.lifecycle:lifecycle-viewmodel-ktx` - For ViewModel support
- `androidx.lifecycle:lifecycle-runtime-ktx` - For lifecycle-aware collection
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` - For coroutines support

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

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

## Author

**ccolorcat**

- GitHub: [@ccolorcat](https://github.com/ccolorcat)

## Acknowledgments

K-MVI is inspired by and builds upon concepts from:
---

**Star this repo** ⭐ if you find it useful!
