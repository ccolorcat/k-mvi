# K-MVI

[![Maven Central](https://img.shields.io/maven-central/v/cc.colorcat.mvi/core.svg)](https://search.maven.org/artifact/cc.colorcat.mvi/core)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A lightweight, powerful, and type-safe MVI (Model-View-Intent) architecture library for Android, written in Kotlin with Coroutines and Flow.

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
            emit(IMain.PartialChange { 
                it.updateState { copy(loading = false) }
                  .withEvent(IMain.Event.ShowToast("Load failed: ${e.message}"))
            })
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
            collectPartial(IMain.State::count) { count ->
                countTextView.text = count.toString()
            }
            
            collectPartial(IMain.State::loading) { loading ->
                progressBar.isVisible = loading
            }
        }
        
        // Collect one-time events
        viewModel.eventFlow.collectEvent(this) {
            collectParticular<IMain.Event.ShowToast> { event ->
                Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
            }
            
            collectParticular<IMain.Event.NavigateToDetail> { event ->
                // Navigate to detail screen
            }
        }
        
        // Dispatch intents
        incrementButton.doOnClick { IMain.Intent.Increment }
            .debounceFirst(500)
            .launchCollect(this) { viewModel.dispatch(it) }
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

#### 1. Intent

Represents user actions or system events. Can be marked as:
- `Mvi.Intent.Concurrent`: Processed in parallel
- `Mvi.Intent.Sequential`: Processed sequentially (one at a time)
- Neither: Uses the default strategy from configuration

#### 2. State

Represents the complete UI state at any point in time. Should be:
- **Immutable**: Use data classes with `copy()`
- **Serializable**: For process death handling
- **Complete**: Contains all information needed to render UI

#### 3. Event

Represents one-time side effects that don't belong in state:
- Showing toasts or snackbars
- Navigation actions
- Triggering animations
- Any action that should happen once

#### 4. PartialChange

A function that takes the current `Snapshot` (state + event queue) and returns an updated `Snapshot`. It can:
- Update state: `snapshot.updateState { copy(field = newValue) }`
- Emit events: `snapshot.withEvent(MyEvent.SomeEvent)`
- Chain updates: `snapshot.updateState { ... }.withEvent(...)`

#### 5. Contract

The read-only interface that exposes:
- `stateFlow: StateFlow<S>`: Hot flow of state changes
- `eventFlow: Flow<E>`: Cold flow of one-time events

#### 6. ReactiveContract

Extends Contract with the ability to dispatch intents:
- `dispatch(intent: I)`: Send an intent for processing

### Intent Handling Strategies

K-MVI supports three strategies for processing intents:

#### 1. CONCURRENT
All intents are processed in parallel. Best for independent operations.

```kotlin
KMvi.setup {
    copy(handleStrategy = HandleStrategy.CONCURRENT)
}
```

#### 2. SEQUENTIAL
All intents are processed one-by-one in order. Best for operations that must maintain strict ordering.

```kotlin
KMvi.setup {
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
KMvi.setup {
    copy(
        handleStrategy = HandleStrategy.HYBRID,
        hybridConfig = HybridConfig(
            groupSelector = { intent ->
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

Configure the strategy for a specific contract in your ViewModel. This is the **recommended approach** as it provides more flexibility:

```kotlin
class MyViewModel : ViewModel() {
    private val contract by contract(
        initState = MyState(),
        strategy = HandleStrategy.HYBRID,
        config = HybridConfig(
            groupSelector = { intent ->
                when (intent) {
                    // Database operations - process sequentially within this group
                    is MyIntent.SaveUser,
                    is MyIntent.UpdateUser,
                    is MyIntent.DeleteUser -> "database"
                    
                    // Network operations - process sequentially within this group
                    is MyIntent.FetchData,
                    is MyIntent.UploadData -> "network"
                    // Default: return class name so same type intents execute sequentially
                    else -> intent::class.java.name
                    else -> null
                }
            },
            bufferCapacity = Channel.BUFFERED
        )
    ) {
        register(MyIntent.SaveUser::class.java, ::handleSaveUser)
        register(MyIntent.FetchData::class.java, ::handleFetchData)
        // ... register other handlers
    }
}
```

**How it works:**
- All `SaveUser`, `UpdateUser`, and `DeleteUser` intents will execute sequentially (one after another) within the "database" group
- All `FetchData` and `UploadData` intents will execute sequentially within the "network" group
- Other intents use their class name as group key by default, ensuring same-type intents execute sequentially
- Intents returning `null` from `groupSelector` will use their `Concurrent`/`Sequential` marker

## Usage Guide

### Registering Intent Handlers

#### Using defaultHandler (Recommended)

The **recommended approach** is to use `defaultHandler` to handle all intents in a single method. This makes the code more readable as you can see all intent handling logic in one place:

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
            emit(MyPartialChange { 
                it.updateState { copy(loading = false) }
                  .withEvent(MyEvent.ShowToast("Load failed: ${e.message}"))
            })
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

For more complex applications, you can configure the intent handling strategy and grouping per-contract using `defaultHandler`:

```kotlin
class UserViewModel : ViewModel() {
    private val contract by contract(
        initState = UserState(),
        strategy = HandleStrategy.HYBRID,
        config = HybridConfig(
            groupSelector = { intent ->
                when (intent) {
                    // Group all database operations together
                    is UserIntent.Save,
                    is UserIntent.Update,
                    is UserIntent.Delete -> "db_operations"
                    
                    // Group all sync operations together
                    is UserIntent.SyncToServer,
                    is UserIntent.DownloadFromServer -> "sync_operations"
                    
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
        strategy = HandleStrategy.HYBRID,
        config = HybridConfig(
            groupSelector = { intent ->
                when (intent) {
                    is UserIntent.Save,
                    is UserIntent.Update,
                    is UserIntent.Delete -> "db_operations"
                    is UserIntent.SyncToServer,
                    is UserIntent.DownloadFromServer -> "sync_operations"
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
    collectPartial(MyState::loading) { isLoading ->
        progressBar.isVisible = isLoading
    }
    
    collectPartial(MyState::title) { title ->
        titleTextView.text = title
    }
}
```

### Collecting Events

#### Collect All Events

```kotlin
viewModel.eventFlow.collectEvent(this) {
    collectWhole { event ->
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
    collectParticular<MyEvent.ShowToast> { event ->
        Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
    }
    
    collectParticular<MyEvent.Navigate> { event ->
        findNavController().navigate(event.destination)
    }
}
```

### Converting UI Events to Intents

K-MVI provides convenient extensions for common UI events:

```kotlin
// Button clicks
button.doOnClick { MyIntent.ButtonClicked }
    .debounceFirst(500) // Prevent rapid clicks
    .launchCollect(this) { viewModel.dispatch(it) }

// Text changes
editText.doOnTextChanged { text -> MyIntent.TextChanged(text) }
    .debounce(300) // Wait for user to stop typing
    .launchCollect(this) { viewModel.dispatch(it) }

// Checkbox changes
checkbox.doOnCheckedChanged { checked -> MyIntent.CheckboxToggled(checked) }
    .launchCollect(this) { viewModel.dispatch(it) }
```

### Debouncing and Throttling

#### debounceFirst
Responds to the **first** event, then ignores subsequent events for a time window. Perfect for preventing accidental double-clicks:

```kotlin
button.doOnClick { SubmitIntent }
    .debounceFirst(500) // Ignore clicks within 500ms of the first click
    .launchCollect(this) { viewModel.dispatch(it) }
```

#### debounce (from Kotlin Flow)
Responds to the **last** event after a period of silence. Perfect for search as user types:

```kotlin
searchEditText.doOnTextChanged { SearchIntent(it) }
    .debounce(300) // Wait 300ms after user stops typing
    .launchCollect(this) { viewModel.dispatch(it) }
```

## Configuration

Configure K-MVI globally in your Application class:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        KMvi.setup {
            copy(
                // Intent handling strategy
                handleStrategy = HandleStrategy.HYBRID,
                
                // Retry policy for failed intent processing
                retryPolicy = { attempt, cause ->
                    attempt <= 3 && cause !is CancellationException
                },
                
                // Hybrid strategy configuration
                hybridConfig = HybridConfig(
                    groupSelector = { intent ->
                        // Return a group key for intents that should be grouped
                        when (intent) {
                            is DatabaseIntent -> "database"
                            is NetworkIntent -> "network"
                            // Default: return class name so same type intents execute sequentially
                            else -> intent::class.java.name
                        }
                    },
                    bufferCapacity = Channel.BUFFERED
                ),
                
                // Logger configuration
                logger = Logger(
                    minLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO,
                    output = { level, tag, message ->
                        Log.println(level.priority, tag, message)
                    }
                )
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

#### RetryPolicy
A function `(attempt: Long, cause: Throwable) -> Boolean` that determines whether to retry after a failure.

Default policy:
```kotlin
{ attempt, cause -> 
    attempt <= 3 && cause !is Error // Retry up to 3 times, but not for Errors
}
```

#### HybridConfig
Configuration for HYBRID strategy:
- `groupSelector`: Function to assign intents to groups for sequential processing
- `bufferCapacity`: Buffer size for grouped intent channels

#### Logger
- `minLevel`: Minimum log level to output
- `output`: Custom log output function (defaults to Android Log)

## Advanced Features

### Error Handling

#### In Intent Handlers

```kotlin
private fun handleLoadData(intent: LoadDataIntent): Flow<IMain.PartialChange> = flow {
    emit(IMain.PartialChange { it.updateState { copy(loading = true) } })
    
    try {
        val data = repository.loadData()
        emit(IMain.PartialChange { 
            it.updateState { copy(loading = false, data = data) }
        })
    } catch (e: Exception) {
        emit(IMain.PartialChange { 
            it.updateState { copy(loading = false, error = e.message) }
              .withEvent(MyEvent.ShowError(e.message))
        })
    }
}
```

#### Global Retry Policy

The global retry policy will automatically retry failed intent processing:

```kotlin
KMvi.setup {
    copy(
        retryPolicy = { attempt, cause ->
            when (cause) {
                is NetworkException -> attempt <= 5 // Retry network errors
                is TimeoutException -> attempt <= 2 // Retry timeouts
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

Implement a custom logger for advanced logging needs:

```kotlin
class CustomLogger : Logger(
    minLevel = LogLevel.DEBUG,
    output = { level, tag, message ->
        when (level) {
            LogLevel.DEBUG, LogLevel.INFO -> Timber.tag(tag).d(message)
            LogLevel.WARN -> Timber.tag(tag).w(message)
            LogLevel.ERROR -> {
                Timber.tag(tag).e(message)
                // Send to crash reporting
                Crashlytics.log("[$tag] $message")
            }
        }
    }
)

KMvi.setup {
    copy(logger = CustomLogger())
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
        emit(PartialChange { 
            it.updateState { copy(saving = false) }
              .withEvent(Event.SaveSuccess)
        })
    } catch (e: Exception) {
        emit(PartialChange { 
            it.updateState { copy(saving = false) }
              .withEvent(Event.SaveError(e.message))
        })
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
collectPartial(MyState::count) { count ->
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

