# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

K-MVI is a lightweight MVI (Model-View-Intent) architecture library for Android, written in Kotlin with Coroutines and
Flow. Published on Maven Central as `cc.colorcat.mvi:core`.

## Build Commands

```bash
# Build all modules (library + sample app)
./gradlew assemble

# Run all unit tests
./gradlew test

# Run a single test class
./gradlew :core:test --tests "cc.colorcat.mvi.ExampleUnitTest"

# Run lint checks
./gradlew lint

# Publish to local Maven for local development
./gradlew :core:publishToMavenLocal
```

## Project Structure

**Two modules:**

- `core/` — The library (published artifact `cc.colorcat.mvi:core`). All MVI framework code lives here.
- `app/` — Sample app demonstrating library usage (counter, dashboard, login examples).

## Architecture

### Core MVI Flow

```
dispatch(intent) → Channel → IntentTransformer → retryWhen → scan(PartialChange) → Snapshot → stateFlow / eventFlow
```

The processing pipeline:

1. `dispatch()` enqueues intents into a Channel (capacity 64, suspend on overflow)
2. `IntentTransformer` converts `Flow<Intent>` → `Flow<PartialChange>` using the configured strategy
3. `retryWhen` applies the configurable `RetryPolicy` for failures
4. `scan` accumulates `PartialChange` into `Snapshot` via `Dispatchers.Default`
5. `snapshots` SharedFlow is split into `stateFlow` (StateFlow, eager) and `eventFlow` (SharedFlow, WhileSubscribed)

### Key Files

| File                               | Role                                                                                         |
|------------------------------------|----------------------------------------------------------------------------------------------|
| `Mvi.kt`                           | Core types: `Intent`, `State`, `Event`, `PartialChange`, `Snapshot`                          |
| `Contracts.kt`                     | `Contract` (read-only) and `ReactiveContract` (read-write) interfaces                        |
| `KMvi.kt`                          | Global config singleton: handleStrategy, retryPolicy, logger, hybridConfig                   |
| `HandleStrategies.kt`              | `HandleStrategy` enum (CONCURRENT/SEQUENTIAL/HYBRID) + `HybridConfig`                        |
| `IntentHandlers.kt`                | `IntentHandler`, `IntentHandlerRegistry`, `IntentHandlerDelegate` (ConcurrentHashMap-backed) |
| `IntentTransformers.kt`            | `IntentTransformer` + `StrategyIntentTransformer` (per-strategy implementation)              |
| `internal/ReactiveContractImpl.kt` | `CoreReactiveContract` (pipeline) + `StrategyReactiveContract` (handler registration)        |
| `MviViewModels.kt`                 | ViewModel `by contract(...)` extensions (two APIs: transformer-based and handler-based)      |
| `MviExtensions.kt`                 | View click/text → Flow extensions + `debounceLeading` time-based deduplication               |
| `MviCollects.kt`                   | Lifecycle-aware `collectState`/`collectEvent` DSL builders                                   |
| `internal/InternalExtensions.kt`   | `groupHandle` flow operator for HYBRID strategy grouping                                     |

### Intent Handling Strategies

Three strategies, configured globally via `KMvi.setup {}` or per-contract:

- **CONCURRENT** (`flatMapMerge`): All intents in parallel. Max throughput, no ordering.
- **SEQUENTIAL** (`flatMapConcat`): All intents one-by-one. Strict FIFO, can block.
- **HYBRID** (default): Concurrent intents → parallel; Sequential intents → single queue; fallback intents → grouped by
  tag (sequential within group, parallel between groups).

### Two Ways to Register Intent Handlers

1. **`defaultHandler` (centralized, recommended)**: Single `when` expression routing all intents:
   ```kotlin
   private val contract by contract(initState = State(), defaultHandler = ::handleIntent)
   private fun handleIntent(intent: Intent): Flow<PartialChange> = when (intent) { ... }
   ```

2. **`register()` (distributed)**: Per-type handler registration in the contract builder block:
   ```kotlin
   private val contract by contract(initState = State()) {
       register(Intent.Increment::class.java, ::handleIncrement)
       register(Intent.Decrement::class.java, ::handleDecrement)
   }
   ```

### Two Contract Creation APIs

- **Transformer-based** (`by contract(initState = ..., transformer = ...)`): Low-level, full control over Flow
  transformation.
- **Handler-based** (`by contract(initState = ..., defaultHandler = ...)`): High-level, with strategy, config, and
  handler registry support.

### Lifecycle-Aware Collection

UI layer uses `collectState` and `collectEvent` DSL builders:

```kotlin
viewModel.stateFlow.collectState(this) {
    collectProperty(State::loading) { /* triggered only when loading changes */ }
    collectWhole { state -> /* triggered on any state change */ }
}
viewModel.eventFlow.collectEvent(this) {
    collectTyped<Event.ShowToast> { /* type-safe event handling */ }
}
```

### Logging

Framework uses `Logger` (fun interface wrapping Android Log). Default threshold is WARN. Configure via
`KMvi.setup { copy(logger = Logger(Logger.DEBUG)) }`. All framework logs use tag `"k-mvi"`.
