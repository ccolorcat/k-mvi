# Repository Guidelines

## Response Language

- Reply language follows the primary language of the user's current message.
- When the user mixes Chinese and English, default to Simplified Chinese.
- Code, comments, commands, config keys, API names, and error messages are kept verbatim.

## Project Overview

K-MVI is a lightweight, type-safe Android MVI (Model-View-Intent) library built on Kotlin Coroutines and Flow. The core artifact is `cc.colorcat.mvi:core` (current: `1.2.6-SNAPSHOT`), published to GitHub Packages. The repo has two modules: `core` (the library) and `app` (XML/ViewBinding sample app).

---

## Architecture & Data Flow

### Intent dispatch pipeline

```
UI (View/Fragment)
  → dispatch(intent)
  → Channel (dispatchQueue)
  → IntentTransformer (strategy routing)
  → retryWhen (RetryPolicy)
  → IntentHandler.handle(intent): Flow<PartialChange>
  → scan(PartialChange::apply)
  → Mvi.Snapshot
  → stateFlow (StateFlow) / eventFlow (SharedFlow, one-shot)
```

### Handle strategies (`HandleStrategy` enum)

| Strategy | Operator | When to use |
|---|---|---|
| `CONCURRENT` | `flatMapMerge` | Independent intents that can interleave |
| `SEQUENTIAL` | `flatMapConcat` | Ordered intents that must not interleave |
| `HYBRID` | `groupHandle` + `flattenMerge` | Mix — classify per intent type via `HybridConfig.groupTagSelector` |

Default is `HYBRID`. Intents that implement neither `Mvi.Intent.Concurrent` nor `Mvi.Intent.Sequential` fall back to `HybridConfig`'s ungrouped channel.

### Key public types (`cc.colorcat.mvi`)

| Type | Role |
|---|---|
| `Mvi.Intent` / `.Concurrent` / `.Sequential` | Marker interfaces for intents |
| `Mvi.State` | Marker for state types |
| `Mvi.Event` | Marker for one-shot side-effect events |
| `Mvi.PartialChange<S,E>` | `fun interface`; `apply(snapshot)` returns new snapshot — must be pure and non-throwing |
| `Mvi.Snapshot<S,E>` | Immutable carrier of `(state, event?)`; `updateState`, `withEvent`, `updateWith` helpers |
| `Contract<S,E>` | Read-only view exposed to UI: `stateFlow`, `eventFlow`, `dispatch` |
| `ReactiveContract<S,E>` | Mutable runtime; obtained via `ViewModel.contract(...)` |
| `IntentHandler<I,S,E>` | `fun interface`; maps one intent to `Flow<PartialChange>` |
| `IntentHandlerRegistry` | Registers per-class `IntentHandler`s via `register<MyIntent> { ... }` |
| `IntentTransformer<I,S,E>` | `fun interface`; transforms `Flow<I>` into `Flow<PartialChange>`; used for custom strategy wiring |
| `KMvi` | Global config singleton; call `KMvi.setup { copy(...) }` once (e.g., in `Application.onCreate`) |
| `HandleStrategy` / `HybridConfig` | Strategy config; set globally or per-contract |
| `Logger` | `fun interface`; `Logger(threshold)` factory backed by `android.util.Log` |

### Coroutine / Flow primitives used

`Channel`, `callbackFlow`/`awaitClose`, `scan`, `stateIn`, `shareIn`, `buffer`, `flatMapMerge`, `flatMapConcat`, `flattenMerge`, `retryWhen`, `flowOn(Dispatchers.IO/Default)`, `repeatOnLifecycle`.

---

## Key Directories

```
core/src/main/java/cc/colorcat/mvi/          # Public API surface (14 files)
core/src/main/java/cc/colorcat/mvi/internal/ # Runtime implementation; not part of public API
core/src/test/java/cc/colorcat/mvi/         # JVM unit tests (well-covered)
core/src/androidTest/java/cc/colorcat/mvi/  # Instrumented tests (only template stubs currently)
app/src/main/java/cc/colorcat/mvi/sample/   # Sample app, organized by feature package
  count/     # Counter demo — sequential intents, derived state
  login/     # Login demo — hybrid strategy, async flows, asSingleFlow
  dashboard/ # Dashboard demo — concurrent + sequential + grouped intents
  util/      # showToast, randomDelay, ViewBindingDelegate, doOnTextChanged
app/src/main/res/navigation/nav_graph.xml   # Navigation Component graph
gradle/libs.versions.toml                   # Central version catalog
```

---

## Development Commands

All commands use the Gradle wrapper from the repo root.

```bash
# Build
./gradlew :core:assemble                    # library AAR
./gradlew :app:assembleDebug                # sample debug APK

# Test
./gradlew :core:test                        # core JVM unit tests
./gradlew :app:testDebugUnitTest            # app JVM unit tests
./gradlew :core:connectedAndroidTest        # instrumented tests (needs device/emulator)

# Single test class
./gradlew :core:test --tests "cc.colorcat.mvi.internal.ReactiveContractImplTest"

# Lint
./gradlew lint

# Publish
./gradlew :core:publishToMavenLocal
./gradlew publishReleasePublicationToGitHubPackagesRepository \
    -Pgpr.personal.user=<user> -Pgpr.personal.key=<token>
```

---

## Code Conventions & Common Patterns

### Formatting (`.editorconfig`)

- 4-space indentation; continuation indent 4 spaces
- Max line length 120 characters
- LF line endings, UTF-8, insert final newline
- Trailing commas enabled at both declaration and call site (`ij_kotlin_allow_trailing_comma = true`)
- `kotlin.code.style=official`

### Naming

- Types: `PascalCase` — e.g., `DashboardViewModel`, `CounterContract`
- Functions / properties: `camelCase` — e.g., `handleLoadCategory`, `collectState`
- Constants: `UPPER_SNAKE_CASE`
- Packages: `cc.colorcat.mvi[.sample][.internal]`
- Files named after their primary class/interface

### Defining an MVI contract (consumer pattern)

```kotlin
// 1. Domain types
sealed interface CounterIntent : Mvi.Intent {
    data object Increment : CounterIntent, Mvi.Intent.Sequential
    data object Decrement : CounterIntent, Mvi.Intent.Sequential
}
data class CounterState(val count: Int = 0) : Mvi.State
sealed interface CounterEvent : Mvi.Event { data class ShowToast(val msg: String) : CounterEvent }
// PartialChange — must be pure and non-throwing
fun interface CounterChange : Mvi.PartialChange<CounterState, CounterEvent>

// 2. ViewModel — handler-based style
class CounterViewModel : ViewModel() {
    val contract: Contract<CounterState, CounterEvent> = contract(
        defaultHandler = IntentHandlerDelegate { ... }
    ) {
        register<CounterIntent.Increment> { _ -> flow { emit(CounterChange { s, _ -> s.copy(count = s.count + 1) }) } }
        register<CounterIntent.Decrement> { _ -> flow { emit(CounterChange { s, _ -> s.copy(count = s.count - 1) }) } }
    }
}

// 3. Fragment — collect state/events, dispatch intents
viewLifecycleOwner.collectState(viewModel.contract) { state -> binding.countText.text = "${state.count}" }
viewLifecycleOwner.collectEvent(viewModel.contract) { event -> if (event is CounterEvent.ShowToast) showToast(event.msg) }
binding.btnIncrement.doOnClick().dispatchWithLifecycle(viewLifecycleOwner, viewModel.contract) { CounterIntent.Increment }
```

### UI → Flow helpers (`MviExtensions.kt`)

```kotlin
view.doOnClick()                  // Flow<Unit>
view.doOnLongClick()              // Flow<Unit>
checkbox.doOnCheckedChange()      // Flow<Boolean>
editText.doOnAfterTextChanged()   // Flow<String>
someFlow.debounceLeading(300)     // debounce keeping first emission
value.asSingleFlow()              // wraps a PartialChange in Flow<PartialChange>
```

### Lifecycle-aware collection (`MviCollects.kt`)

```kotlin
collectState(contract) { state -> /* render */ }
collectEvent(contract) { event -> /* handle */ }
collectPartialState(contract, selector) { part -> /* render subset */ }
dispatchWithLifecycle(lifecycleOwner, contract) { MyIntent.Foo }
launchWithLifecycle(lifecycleOwner) { /* coroutine */ }
```

### Global configuration

```kotlin
// Application.onCreate
KMvi.setup {
    copy(
        logger = Logger(Logger.DEBUG),
        handleStrategy = HandleStrategy.HYBRID,
        intentQueueCapacity = Channel.UNLIMITED,
    )
}
```

### Error handling / retry

`RetryPolicy` is `typealias (attempt: Long, cause: Throwable) -> Boolean` — defaults to retrying `IOException` on attempts `0..2`. Set via `KMvi.Configuration.retryPolicy` or per-contract.

### No DI framework

Dependencies flow via constructor parameters, `ViewModel.contract(...)` factory extensions, and `KMvi` as a global config service locator. No Hilt, Koin, or Dagger.

### Thread safety

`dispatch()` is non-blocking but silently drops intents when the contract is inactive or the queue is full. `PartialChange.apply()` runs on the contract's internal scope — keep it pure. `eventFlow` is not replayed (one-shot `SharedFlow`).

---

## Important Files

| File | Purpose |
|---|---|
| `core/src/main/java/cc/colorcat/mvi/Mvi.kt` | Root domain types (`Intent`, `State`, `Event`, `PartialChange`, `Snapshot`) |
| `core/src/main/java/cc/colorcat/mvi/Contracts.kt` | `Contract` / `ReactiveContract` / `asContract()` |
| `core/src/main/java/cc/colorcat/mvi/KMvi.kt` | Global config singleton, `RetryPolicy`, `Configuration` |
| `core/src/main/java/cc/colorcat/mvi/HandleStrategies.kt` | `HandleStrategy` enum, `HybridConfig` |
| `core/src/main/java/cc/colorcat/mvi/MviViewModels.kt` | `ViewModel.contract(...)` factory extensions |
| `core/src/main/java/cc/colorcat/mvi/MviCollects.kt` | `collectState`, `collectEvent`, `dispatchWithLifecycle`, etc. |
| `core/src/main/java/cc/colorcat/mvi/MviExtensions.kt` | `doOnClick`, `debounceLeading`, `asSingleFlow`, etc. |
| `core/src/main/java/cc/colorcat/mvi/IntentHandlers.kt` | `IntentHandler`, `IntentHandlerRegistry` |
| `core/src/main/java/cc/colorcat/mvi/IntentTransformers.kt` | `IntentTransformer`, `toPartialChange`, strategy routing |
| `core/src/main/java/cc/colorcat/mvi/internal/ReactiveContractImpl.kt` | Runtime: channels, scan, stateFlow/eventFlow wiring |
| `gradle/libs.versions.toml` | Central version catalog (Kotlin, AGP, SDK levels, all dep versions) |
| `app/src/main/java/cc/colorcat/mvi/sample/SampleApplication.kt` | `KMvi.setup` entry point |
| `app/src/main/res/navigation/nav_graph.xml` | Navigation graph; start dest: `navigationFragment` |

---

## Runtime / Tooling Preferences

- **Build tool**: Gradle 8.13 (wrapper at `gradlew` / `gradlew.bat`)
- **Kotlin**: 1.9.10
- **AGP**: 8.13.1
- **Java**: 17 (`sourceCompatibility`, `targetCompatibility`, `jvmTarget` all set to 17)
- **Android SDK**: `compileSdk 34`, `minSdk 24`, `targetSdk 34`
- **No Compose**: sample app uses XML layouts, ViewBinding, Navigation Component
- **Publishing**: Maven Publish plugin → GitHub Packages (`https://maven.pkg.github.com/ccolorcat/k-mvi`); credentials via `-Pgpr.personal.user` / `-Pgpr.personal.key` or env vars `USERNAME` / `TOKEN`
- **Local substitution**: root `build.gradle.kts` rewrites `cc.colorcat.mvi:*` dependencies to local `project(":core")` automatically

---

## Testing & QA

### Frameworks

| Library | Version | Scope |
|---|---|---|
| JUnit 4 | 4.13.2 | `core` and `app` unit + instrumented tests |
| `kotlinx-coroutines-test` | 1.6.4 | `core` unit tests only |
| AndroidX JUnit | 1.1.5 | Both modules' instrumented tests |
| Espresso Core | 3.5.1 | Declared; no active Espresso calls yet |

No Turbine, MockK, Robolectric, Kotest, or Compose test libraries.

### Conventions

- Test class names: `<Subject>Test` (e.g., `ReactiveContractImplTest`)
- Test method names: Kotlin backticks (e.g., `` `dispatch after cancel does not crash` ``)
- Shared test utility: `core/src/test/java/cc/colorcat/mvi/TestLogger.kt` — JUnit `TestRule` that prints `[TEST START/PASS/FAIL/ERROR]`
- Local fixtures (`TestState`, `TestEvent`, `TestIntent`) are defined privately per test class, not shared

### Coroutine test patterns

```kotlin
// Most tests
@Test fun `some behavior`() = runBlocking { ... }

// Tests needing eager shareIn(WhileSubscribed) subscription
@Test fun `reactive behavior`() = runTest(UnconfinedTestDispatcher()) { ... }
```

Flow assertions use `first()`, `single()`, `toList()`, `collect {}` directly — no Turbine.

### State reset

Tests that mutate `KMvi` global config must reset it in `@Before`/`@After`:

```kotlin
@Before fun setUp() { KMvi.setup { KMvi.Configuration() } }
```

### Coverage

- **Well covered**: `Snapshot`, `PartialChange`, `asContract`, `Logger`, `KMvi` validation, intent classification, handler delegation, transformer strategy routing, lazy contract, reactive contract state/event propagation
- **Sparse / template only**: `app/src/test` (arithmetic stub), `app/src/androidTest` and `core/src/androidTest` (package-name check only)
- No UI, fragment, navigation, or Espresso tests exist yet
