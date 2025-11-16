# Counter Sample - K-MVI Pattern Usage Guide

This sample demonstrates how to implement the MVI (Model-View-Intent) pattern using the **k-mvi** library with a reactive/declarative approach.

## Overview

The Counter sample showcases:
- **Reactive Intent Collection**: Using `doOnClick` to create intent flows
- **Efficient State Observation**: Using `collectState` with partial updates
- **Type-Safe Event Handling**: Using `collectEvent` with particular event types
- **Three PartialChange Patterns**: Different approaches for state transformations

## Architecture Components

### 1. Contract Layer (`CounterContract.kt`)

Defines all MVI components in a sealed interface:

```kotlin
sealed interface CounterContract {
    data class State(
        val count: Int = 0,
        val targetNumber: Int = Random.nextInt(COUNT_MIN, COUNT_MAX),
        val showLoading: Boolean = false,
    ) : Mvi.State {
        val alpha: Float  // Computed: proximity to targetNumber (1.0 = match, 0.0 = max distance)
        val countText: String  // Computed: count as String for UI
        val targetText: String  // Computed: targetNumber as String for UI
    }
    
    sealed interface Event : Mvi.Event {
        data class ShowToast(val message: CharSequence) : Event
    }
    
    sealed interface Intent : Mvi.Intent.Sequential {
        object Increment : Intent
        object Decrement : Intent
        object Reset : Intent
    }
    
    fun interface PartialChange : Mvi.PartialChange<State, Event>
}
```

**Key Points:**
- `State`: Immutable data class representing UI state
  - `count`: Current counter value (0-100)
  - `targetNumber`: Random target the user tries to reach
  - `showLoading`: Loading indicator state (shown during reset)
  - Computed properties (`alpha`, `countText`, `targetText`) separate presentation logic from business logic
- `Event`: One-time side effects (toasts, navigation, etc.)
- `Intent`: User actions marked as Sequential for ordered processing
- `PartialChange`: Functional interface for state transformations

### 2. ViewModel Layer (`CounterViewModel.kt`)

Demonstrates **three different patterns** for returning `PartialChange`:

#### Pattern 1: Inline Conditional (`handleIncrement`)
```kotlin
private fun handleIncrement(intent: Intent.Increment): PartialChange {
    return PartialChange {
        if (it.state.count == COUNT_MAX) {
            it.withEvent(Event.ShowToast("Already reached $COUNT_MAX"))
        } else {
            it.updateState { copy(count = count + 1) }
        }
    }
}
```
- **Use when**: Simple synchronous logic with minimal branching
- **Pros**: Compact, readable, thread-safe (uses snapshot)
- **Cons**: Not recommended for complex logic (see handler documentation)

#### Pattern 2: Early Return Branching (`handleDecrement`)
```kotlin
private fun handleDecrement(intent: Intent.Decrement): PartialChange {
    return if (stateFlow.value.count == COUNT_MIN) {
        PartialChange { it.withEvent(Event.ShowToast("Already reached $COUNT_MIN")) }
    } else {
        PartialChange { it.updateState { copy(count = count - 1) } }
    }
}
```
- **Use when**: Complex conditional logic with distinct execution paths
- **Pros**: Clear separation, easier testing, better for guard clauses
- **Cons**: Slightly more verbose
- **Recommended**: This is the preferred pattern for most cases

#### Pattern 3: Flow-Based Async (`handleReset`)
```kotlin
private fun handleReset(intent: Intent.Reset): Flow<PartialChange> = flow {
    try {
        emit(PartialChange { it.updateState { copy(showLoading = true) } })
        
        randomDelay()
        
        if (Random.nextInt(0, 101) > 90) {
            throw RuntimeException("test exception")
        }
        
        val count = Random.nextInt(COUNT_MIN, COUNT_MAX)
        val target = Random.nextInt(COUNT_MIN, COUNT_MAX)
        emit(PartialChange {
            it.updateWith(Event.ShowToast("Reset Successfully")) {
                copy(count = count, targetNumber = target)
            }
        })
    } catch (e: Exception) {
        emit(PartialChange { it.withEvent(Event.ShowToast("Reset Failure")) })
    } finally {
        emit(PartialChange { it.updateState { copy(showLoading = false) } })
    }
}
```
- **Use when**: Async operations, multiple emissions, complex transformations
- **Pros**: Supports reactive operations, cancellation, backpressure, error handling
- **Examples**: API calls, database queries, multi-step flows
- **Key Features**:
  - **Multiple emissions**: Loading → Success/Error → Complete
  - **Error handling**: try-catch-finally pattern for robust error management
  - **State transitions**: Shows loading state, performs async work, handles completion
  - **Random simulation**: Demonstrates async delay and occasional failures (10% chance)

### 3. UI Layer (`CounterFragment.kt`)

Demonstrates **reactive/declarative** MVI implementation:

#### Reactive Intent Collection
```kotlin
private val intents: Flow<Intent>
    get() = merge(
        incrementDecrementIntents().debounceFirst(600L),
        binding.reset.doOnClick { trySend(Intent.Reset) }.debounceFirst(600L),
    )

private fun incrementDecrementIntents(): Flow<Intent> = merge(
    binding.increment.doOnClick { trySend(Intent.Increment) },
    binding.decrement.doOnClick { trySend(Intent.Decrement) },
)

private fun setupViewModel() {
    intents
        .onEach { intent -> viewModel.dispatch(intent) }
        .launchIn(lifecycleScope)
}
```

**Benefits:**
- Separates intent creation from processing
- Makes all user actions explicit and discoverable
- Enables easy testing and composition
- Creates unidirectional data flow: UI → Intent → ViewModel → State → UI
- **Debouncing**: Uses `debounceFirst(600ms)` to prevent rapid clicks and improve UX
- **Grouped intents**: Increment/Decrement are grouped separately for flexible rate limiting

#### Efficient State Observation
```kotlin
viewModel.stateFlow.collectState(viewLifecycleOwner) {
    collectPartial(State::countText, binding.count::setText)
    collectPartial(State::targetText, binding.targetNumber::setText)
    collectPartial(State::alpha, binding.root::setAlpha)
}
```

**Benefits:**
- `collectState`: Automatic lifecycle management
- `collectPartial`: Only updates when specific property changes (e.g., only when `countText` changes)
- Efficient: Avoids unnecessary UI updates
- Type-safe: Compile-time checking with method references
- Clean: Direct method reference syntax (`binding.count::setText`) for concise UI binding

#### Type-Safe Event Handling
```kotlin
viewModel.eventFlow.collectEvent(this) {
    collectParticular<Event.ShowToast> { event ->
        context?.showToast(event.message)
    }
}
```

**Benefits:**
- `collectEvent`: Lifecycle-aware, one-time consumption
- `collectParticular`: Type-safe event filtering
- Exhaustive: Compiler helps ensure all events are handled
- Clean: No manual event consumption logic needed

### 4. Layout (`fragment_counter.xml`)

Material Design layout with:
- **Indeterminate loading bar** (4dp) at top - shows during reset operation
- **Target number display** - shows the random target to reach
- **Large counter display** (64sp) - current count value
- **Three circular buttons**: Decrement (−), Reset (↻), Increment (+)
- **Range info text** - displays valid range (0-100)
- **Dynamic alpha** - background opacity changes based on proximity to target
- ConstraintLayout for responsive design

## MVI Data Flow

```
┌─────────────────────────────────────────────────────────────┐
│                     Unidirectional Flow                      │
└─────────────────────────────────────────────────────────────┘

User Action
    │
    ▼
doOnClick creates Flow<Intent>
    │
    ▼
Merge all intent flows
    │
    ▼
Dispatch to ViewModel
    │
    ▼
Handler processes Intent
    │
    ▼
Returns PartialChange (or Flow<PartialChange>)
    │
    ▼
PartialChange transforms State/Event
    │
    ├─────────────────┬─────────────────┐
    ▼                 ▼                 ▼
StateFlow emits   EventFlow emits   (if event)
    │                 │
    ▼                 ▼
collectState      collectEvent
    │                 │
    ▼                 ▼
Render UI         Handle side effect
```

## Key K-MVI Extensions

### State Collection
- `StateFlow.collectState(lifecycleOwner)`: Lifecycle-aware state collection
- `collectPartial(selector, handler)`: Observe specific properties efficiently
- `collectPartial(selector) { }`: Lambda-based partial collection

### Event Collection
- `Flow.collectEvent(lifecycleOwner)`: Lifecycle-aware event collection
- `collectParticular<EventType> { }`: Type-safe event handling
- Automatically handles one-time consumption

### Intent Creation
- `View.doOnClick { }`: Convert clicks to intent flows
- Returns `Flow<Intent>` that can be merged
- Uses `callbackFlow` for lifecycle-safe emissions

### State Transformation
- `Snapshot.updateState { }`: Update state immutably
- `Snapshot.withEvent(event)`: Emit event without state change
- `Snapshot.updateWith(event) { }`: Update state and emit event
- `PartialChange.asSingleFlow()`: Convert to Flow<PartialChange>

## Best Practices

### 1. Keep PartialChange Simple
❌ **Don't** put complex business logic inside PartialChange:
```kotlin
PartialChange {
    // Complex validation, calculations, etc.
    val result = complexBusinessLogic(it.state)
    it.updateState { copy(value = result) }
}
```

✅ **Do** perform logic outside, PartialChange only updates:
```kotlin
if (stateFlow.value.count == MAX) {
    PartialChange { it.withEvent(Event.ShowToast("Max reached")) }
} else {
    PartialChange { it.updateState { copy(count = count + 1) } }
}
```

### 2. Use Lifecycle-Aware Collection
Always use k-mvi's lifecycle extensions:
```kotlin
// ✅ Automatic lifecycle management
stateFlow.collectState(viewLifecycleOwner) { }
eventFlow.collectEvent(this) { }

// ❌ Manual lifecycle management (more boilerplate)
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        stateFlow.collect { }
    }
}
```

### 3. Prefer Early Return for Conditional Logic
For handlers with conditions, prefer Pattern 2 (Early Return):
```kotlin
// ✅ Clear separation
if (condition) {
    return PartialChange { /* handle edge case */ }
}
return PartialChange { /* normal case */ }

// ❌ Nested logic in PartialChange
return PartialChange {
    if (condition) { /* ... */ } else { /* ... */ }
}
```

### 4. Use Flow for Async Operations
```kotlin
private fun handleLoadData(intent: Intent.Load): Flow<PartialChange> = flow {
    emit(PartialChange { it.updateState { copy(loading = true) } })
    
    try {
        val data = repository.loadData()
        emit(PartialChange { 
            it.updateState { copy(loading = false, data = data) }
        })
    } catch (e: Exception) {
        emit(PartialChange {
            it.updateWith(Event.ShowError(e.message)) {
                copy(loading = false)
            }
        })
    }
}
```

### 5. Immutable State Updates
Always use `copy()` for state updates:
```kotlin
// ✅ Immutable update
it.updateState { copy(count = count + 1) }

// ❌ Don't mutate
state.count++ // Won't work with data class
```

## Running the Sample

1. **Build the project**:
   ```bash
   ./gradlew :app:assembleDebug
   ```

2. **Add CounterFragment to Activity**:
   ```kotlin
   class MainActivity : AppCompatActivity() {
       override fun onCreate(savedInstanceState: Bundle?) {
           super.onCreate(savedInstanceState)
           setContentView(R.layout.activity_main)
           
           if (savedInstanceState == null) {
               supportFragmentManager.commit {
                   replace(R.id.fragment_container, CounterFragment())
               }
           }
       }
   }
   ```

3. **Interact with counter**:
   - Click **+** to increment (max: 100)
   - Click **−** to decrement (min: 0)
   - Click **↻** to reset (async operation with loading, random count/target, 10% failure)
   - See toast messages when limits are reached or reset completes/fails
   - **Game mechanic**: Try to reach the target number - background alpha increases as you get closer
   - Watch the loading indicator during reset operation

## Dependencies

Required dependencies in `app/build.gradle.kts`:

```kotlin
dependencies {
    // K-MVI core library
    implementation("cc.colorcat.mvi:core:1.2.6-SNAPSHOT")
    
    // AndroidX libraries
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Material Design
    implementation("com.google.android.material:material:1.9.0")
}
```

Enable ViewBinding in `android` block:
```kotlin
buildFeatures {
    viewBinding = true
}
```

## Testing

### Testing Handlers
```kotlin
@Test
fun `increment should increase count by 1`() = runTest {
    val viewModel = CounterViewModel()
    
    viewModel.dispatch(Intent.Increment)
    
    assertEquals(1, viewModel.stateFlow.value.count)
}

@Test
fun `increment at max should emit toast event`() = runTest {
    val viewModel = CounterViewModel()
    val events = mutableListOf<Event>()
    
    // Set count to max
    repeat(100) { viewModel.dispatch(Intent.Increment) }
    
    // Collect events
    val job = launch {
        viewModel.eventFlow.collect { events.add(it) }
    }
    
    // Try to increment beyond max
    viewModel.dispatch(Intent.Increment)
    
    assertTrue(events.any { it is Event.ShowToast })
    job.cancel()
}
```

### Testing Intent Flows
```kotlin
@Test
fun `incrementDecrementIntents should emit Increment when button clicked`() = runTest {
    val fragment = CounterFragment()
    // ... setup fragment
    
    fragment.binding.increment.performClick()
    
    // Verify intent was dispatched (note: debouncing may affect timing)
}
```

## Learn More

### File-by-File Documentation
- **CounterContract.kt**: Detailed explanation of each MVI component
- **CounterViewModel.kt**: Three PartialChange patterns with comprehensive notes
- **CounterFragment.kt**: Reactive UI patterns with k-mvi extensions

### Key Concepts
- **Unidirectional Data Flow**: Data flows in one direction (Intent → State → UI)
- **Immutability**: State is never mutated, always copied
- **Separation of Concerns**: Contract, ViewModel, and UI are clearly separated
- **Type Safety**: Sealed interfaces ensure exhaustive handling
- **Lifecycle Awareness**: Automatic collection management

### K-MVI Library Features
- Contract-based architecture
- Lifecycle-aware extensions
- Efficient partial state updates
- Type-safe event handling
- Reactive intent collection
- Support for both sync and async operations

## Comparison: Traditional vs K-MVI Approach

### Traditional Manual Approach
```kotlin
// Manual lifecycle management
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.stateFlow.collect { state ->
            binding.count.text = state.count.toString()
        }
    }
}

// Manual click listeners
binding.increment.setOnClickListener {
    viewModel.dispatch(Intent.Increment)
}
```

### K-MVI Declarative Approach
```kotlin
// Automatic lifecycle + efficient updates + computed property
viewModel.stateFlow.collectState(viewLifecycleOwner) {
    collectPartial(State::countText, binding.count::setText)
}

// Reactive intent flows
merge(
    binding.increment.doOnClick { trySend(Intent.Increment) },
    // ... more intents
).onEach { viewModel.dispatch(it) }.launchIn(lifecycleScope)
```

**Benefits of K-MVI approach:**
- Less boilerplate
- Automatic lifecycle management
- Efficient partial updates
- Computed properties separate presentation from business logic
- Method references for concise, type-safe binding
- Declarative and composable
- Better separation of concerns

---

**Author**: ccolorcat  
**Date**: 2025-11-14  
**GitHub**: https://github.com/ccolorcat  
**License**: See LICENSE file

