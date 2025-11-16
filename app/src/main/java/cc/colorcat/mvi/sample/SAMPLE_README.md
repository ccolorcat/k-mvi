# Counter Sample - MVI Pattern Usage Guide

This sample demonstrates how to implement the MVI (Model-View-Intent) pattern using the k-mvi library.

## Files Overview

### Contract Layer
- **`CounterContract.kt`**: Defines the MVI contract with State, Event, Intent, and PartialChange

### ViewModel Layer
- **`CounterViewModel.kt`**: Demonstrates three different approaches to return PartialChange:
  1. **Inline conditional**: Simple synchronous logic with internal branching
  2. **Early return branching**: Multiple return paths for complex conditional logic
  3. **Flow-based async**: For async operations and complex state transformations

### UI Layer
- **`CounterFragment.kt`**: Fragment that observes state/events and dispatches intents
- **`fragment_counter.xml`**: UI layout for the counter screen

## How to Use

### 1. Add CounterFragment to Your Activity

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

### 2. Understanding the MVI Flow

```
User Action → Intent → ViewModel → PartialChange → State → UI Update
                                 ↓
                               Event → UI Side Effect
```

1. **User clicks button** → Fragment dispatches Intent
2. **Intent is processed** → ViewModel handler returns PartialChange (or Flow)
3. **State is updated** → StateFlow emits new state
4. **UI observes state** → Fragment renders the new state
5. **Events are emitted** → Fragment handles one-time side effects (toast, navigation, etc.)

### 3. Key MVI Concepts

#### State
- Represents the current UI state
- Immutable data class
- Observed via `StateFlow`
- UI is a pure function of state

#### Event
- One-time side effects (toasts, navigation, dialogs)
- Not part of the state
- Should be consumed only once
- Emitted via `Flow`

#### Intent
- User actions/intentions
- Sealed interface for type safety
- Sequential processing (in this example)
- Dispatched to ViewModel

#### PartialChange
- State transformation function
- Takes current snapshot, returns new snapshot
- Can emit events alongside state changes
- Can be synchronous (PartialChange) or async (Flow<PartialChange>)

### 4. Three Patterns for PartialChange

#### Pattern 1: Inline Conditional (handleIncrement)
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
**Use when**: Simple synchronous logic with minimal branching

#### Pattern 2: Early Return Branching (handleDecrement)
```kotlin
private fun handleDecrement(intent: Intent.Decrement): PartialChange {
    return if (stateFlow.value.count == COUNT_MIN) {
        PartialChange { it.withEvent(Event.ShowToast("Already reached $COUNT_MIN")) }
    } else {
        PartialChange { it.updateState { copy(count = count - 1) } }
    }
}
```
**Use when**: Complex conditional logic with distinct execution paths

#### Pattern 3: Flow-Based Async (handleReset)
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
**Use when**: Async operations, multiple emissions, error handling, or complex transformations

### 5. Best Practices

1. **Keep PartialChange Simple**: Focus on updating state/events, not complex business logic
2. **Use Lifecycle-Aware Collection**: Always use `repeatOnLifecycle` to prevent leaks
3. **Immutable State**: Use data classes with `copy()` for state updates
4. **Events are One-Time**: Don't store events in state; emit them separately
5. **Type Safety**: Use sealed interfaces for type-safe Intent/Event handling
6. **Test Handlers Independently**: Each handler can be unit tested in isolation

## Dependencies

The sample requires:
- `cc.colorcat.mvi:core` - The k-mvi library
- `androidx.lifecycle:lifecycle-runtime-ktx` - For lifecycle-aware coroutines
- `androidx.activity:activity-ktx` - For `viewModels()` delegate
- `androidx.constraintlayout:constraintlayout` - For layout
- `com.google.android.material:material` - For Material buttons

## Running the Sample

1. Sync Gradle dependencies
2. Run the app module
3. Interact with the counter:
   - **+ button**: Increment counter (max 100)
   - **- button**: Decrement counter (min 0)
   - **↻ button**: Reset with async simulation (shows loading, generates random count/target, 10% failure rate)
4. Observe toast messages when limits are reached or reset completes/fails
5. Watch the background alpha change as count approaches the target number
6. See the loading indicator at top during reset operation

## Learn More

- See inline documentation in each file for detailed explanations
- Check the three handler patterns in `CounterViewModel.kt`
- Understand lifecycle-aware collection in `CounterFragment.kt`

