# K-MVI Agent Guide

## Response Language

- Reply language follows the primary language of the user's current message.
- When the user mixes Chinese and English, default to Simplified Chinese.
- Code, comments, commands, config keys, API names, and error messages kept verbatim.

## Project Overview

K-MVI is a lightweight, type-safe Android MVI library built on Kotlin Coroutines and Flow.
Artifact: `cc.colorcat.mvi:core` (current: `1.4.1`), published to GitHub Packages and Maven Central.
Two modules: `core` (library) and `app` (XML/ViewBinding sample app with Navigation Component).

---

## Build / Test / Lint

All commands from repo root using Gradle wrapper.

```bash
# Build
./gradlew :core:assemble                              # library AAR
./gradlew :app:assembleDebug                          # sample debug APK

# Run core JVM unit tests (primary test suite)
./gradlew :core:test
# Single test class
./gradlew :core:test --tests "cc.colorcat.mvi.internal.ReactiveContractImplTest"

# App unit tests (stub only)
./gradlew :app:testDebugUnitTest

# Instrumented tests (needs device/emulator, template stubs only)
./gradlew :core:connectedAndroidTest

# Lint
./gradlew lint

# Publish
./gradlew :core:publishToMavenLocal
./gradlew publishReleasePublicationToGitHubPackagesRepository \
    -Pgpr.personal.user=<user> -Pgpr.personal.key=<token>
```

---

## Architecture & Data Flow

### Pipeline

```
UI → dispatch(intent) → Channel (intentsChannel)
  → retryWhen (RetryPolicy)
  → IntentTransformer (strategy routing)
  → IntentHandler.handle(intent): Flow<PartialChange>
  → scan(PartialChange::apply) → Mvi.Snapshot
  → flowOn(Default) + buffer(64, DROP_OLDEST)  ┄ fused into one channel
  → shareIn (Eagerly) → stateFlow / eventFlow
```

- **intentsChannel**: dispatch entry queue, configurable via `IntentQueueConfig` (default: capacity 256, BufferOverflow.SUSPEND)
- **snapshot buffer**: capacity 64, DROP_OLDEST — stale snapshots (including events) discarded if downstream is slow
- `PartialChange.apply()` runs inside `scan` on `Dispatchers.Default` — **must be pure**, non-throwing, no I/O

### DispatchResult

`ReactiveContract.dispatch()` returns a sealed class:
- `Submitted` — accepted by entry queue (not a guarantee of processing)
- `Unavailable` — contract scope is done (terminal, do not retry)
- `Full` — queue at capacity (transient, retry later)

### Handle Strategy

| Strategy | Operator | Tag mapping |
|---|---|---|
| `CONCURRENT` | `flatMapMerge` | All intents parallel |
| `SEQUENTIAL` | `flatMapConcat` | All intents serial |
| `HYBRID` (default) | `groupHandle` + `flattenMerge(MAX_VALUE)` | Concurrent → parallel, Sequential → serial, Fallback → grouped by `GroupTagSelector` |

### Key Considerations

- Intents implementing **both** `Concurrent` and `Sequential` are treated as conflict → logged once, routed to fallback group
- `groupHandle` runs a **single coroutine** for routing to groups — a blocked group channel blocks all groups
- `eventFlow` is a `SharedFlow` with **no replay** — start collecting before dispatching intents that may produce events
- `PartialChange.apply` exceptions inside `scan`: previous snapshot retained, pipeline continues; `CancellationException` re-thrown
- `dispatch()` uses `Channel.trySend` — non-blocking; with `SUSPEND` overflow, a full queue returns `Full` instead of suspending

---

## Key Public Types (`cc.colorcat.mvi`)

| Type | Role |
|---|---|
| `Mvi.Intent` / `.Concurrent` / `.Sequential` | Marker interfaces; mutually exclusive |
| `Mvi.State` | Marker for persistent UI state |
| `Mvi.Event` | Marker for one-shot side effects (no replay) |
| `Mvi.PartialChange<S,E>` | `fun interface`; `apply(oldSnapshot)` → new snapshot; must be pure |
| `Mvi.Snapshot<S,E>` | Immutable `(state, event?)`; helpers: `updateState`, `withEvent`, `updateWith` |
| `Contract<S,E>` | Read-only view: `stateFlow`, `eventFlow` |
| `ReactiveContract<I,S,E>` | Mutable runtime: adds `dispatch(intent): DispatchResult` |
| `DispatchResult` | Sealed: `Submitted`, `Unavailable`, `Full` |
| `IntentQueueConfig` | Dispatch queue `capacity` + `onBufferOverflow` (default: 256, SUSPEND) |
| `IntentHandler<I,S,E>` | `fun interface`; `handle(intent): Flow<PartialChange>` |
| `IntentHandlerRegistry` | Register/unregister handlers per class |
| `IntentHandlerScope` | DSL for `register<MyIntent> { ... }` in contract builder |
| `IntentTransformer<I,S,E>` | `fun interface`; transforms `Flow<I>` → `Flow<PartialChange>` |
| `KMvi` | Global config singleton; call `KMvi.configure { copy(...) }` once |
| `HandleStrategy` | Enum: `CONCURRENT`, `SEQUENTIAL`, `HYBRID` |
| `HybridStrategyConfig` | Config: `groupChannelCapacity`, `groupCountWarningThreshold` |
| `GroupTagSelector<I>` | `fun interface`; `selectTag(intent): Any`; default `byClass()` |
| `RetryPolicy` | Typealias: `(attempt: Long, cause: Throwable) -> Boolean` |
| `Logger` | `fun interface`; `Logger(threshold)` factory backed by `android.util.Log` |

---

## Key Directories

```
core/src/main/java/cc/colorcat/mvi/          # Public API surface (13 files)
core/src/main/java/cc/colorcat/mvi/internal/ # Runtime implementation (4 files: ReactiveContractImpl, InternalExtensions, InternalUtils, LoggerExtensions)
core/src/test/java/cc/colorcat/mvi/          # JVM unit tests
core/src/test/java/cc/colorcat/mvi/internal/ # Internal implementation tests
app/src/main/java/cc/colorcat/mvi/sample/    # Sample app
  count/       # Counter — sequential intents, derived state
  login/       # Login — hybrid strategy, async flows, centralized dispatch
  dashboard/   # Dashboard — concurrent + sequential + grouped intents
  util/        # showToast, randomDelay, ViewBindingDelegate, doOnTextChanged
app/src/main/res/navigation/nav_graph.xml   # Navigation Component graph
gradle/libs.versions.toml                   # Central version catalog
```

---

## Important Files

| File | Purpose |
|---|---|
| `Mvi.kt` | Root domain types: `Intent`, `State`, `Event`, `PartialChange`, `Snapshot` |
| `Contracts.kt` | `Contract`, `ReactiveContract`, `asContract()`, `DispatchResult` |
| `KMvi.kt` | Global config singleton, `RetryPolicy`, `Configuration` |
| `HandleStrategies.kt` | `HandleStrategy` enum, `HybridStrategyConfig`, `GroupTagSelector` |
| `IntentQueueConfig.kt` | Dispatch queue `capacity` + `onBufferOverflow` |
| `IntentHandlers.kt` | `IntentHandler`, `IntentHandlerRegistry`, `IntentHandlerScope`, `IntentHandlerDelegate` |
| `IntentTransformers.kt` | `IntentTransformer`, `strategyTransformer`, `StrategyIntentTransformer` |
| `MviViewModels.kt` | `ViewModel.contract(...)` factory extensions (transformer + handler APIs) |
| `MviCollects.kt` | `collectState`, `collectEvent`, `dispatchWithLifecycle`, `launchWithLifecycle` |
| `MviExtensions.kt` | `doOnClick`, `debounceLeading`, `asSingleFlow`, UI→Flow helpers |
| `Logger.kt` | `Logger` fun interface with Android `Log` backend |
| `internal/ReactiveContractImpl.kt` | Runtime: channels, scan, stateIn/shareIn wiring |
| `internal/InternalExtensions.kt` | `groupHandle`, `isConcurrent`/`isSequential`, `diagnosticName` |
| `internal/InternalUtils.kt` | `requireSupportedChannelConfig`, `tagLabel` |

---

## Coding Conventions

### From `.editorconfig`
- 4-space indent, continuation indent 4, max line 120 chars
- LF endings, UTF-8, trailing comma at declaration + call site
- `kotlin.code.style=official`

### Naming
- Types: PascalCase (`CounterViewModel`, `HybridStrategyConfig`)
- Functions/properties: camelCase (`collectState`, `debounceLeading`)
- Constants: UPPER_SNAKE_CASE
- Packages: `cc.colorcat.mvi[.sample][.internal]`
- Files named after primary class/interface

### Test conventions
- Class: `<Subject>Test` (e.g., `ReactiveContractImplTest`)
- Methods: Kotlin backticks (`` `dispatch after cancel does not crash` ``)
- Shared test rule: `TestLogger.kt` prints `[TEST START/PASS/FAIL/ERROR]`
- Test fixtures (`TestState`, `TestEvent`, `TestIntent`) defined **per test class**, not shared
- Coroutine pattern: most tests use `runBlocking`; reactive tests use `runTest(UnconfinedTestDispatcher())`
- Flow assertions via `first()`, `single()`, `toList()`, `collect {}` — no Turbine
- Tests mutating `KMvi` config must reset in `@Before`/`@After`:
  ```kotlin
  @Before fun setUp() { KMvi.configure { KMvi.Configuration() } }
  ```

### Key patterns
- `PartialChange` must be **pure and non-throwing** — async work belongs in `IntentHandler.handle()`
- Handler may return a single `PartialChange` inline or a `Flow<PartialChange>` for multi-step
- `Snapshot.updateState` clears event; use `updateWith(event) { copy(...) }` to set both
- `Snapshot.withEvent(event).updateState { ... }` in one change **drops the event** — use `updateWith`
- Handler registration uses `register<MyIntent> { intent -> ... }` DSL
- `defaultHandler` for centralized dispatch: unregistered intents routed silently to it
- `eventFlow` collects in UI with `collectEvent(viewModel.contract) { event -> ... }`
- Intents implement `Mvi.Intent.Concurrent` or `Mvi.Intent.Sequential` for HYBRID routing

---

## Runtime / Tooling

- **Gradle**: 8.13 (wrapper at `gradlew`/`gradlew.bat`)
- **Kotlin**: 1.9.10, `jvmTarget=17`
- **AGP**: 8.13.1
- **Java**: 17 (`sourceCompatibility`/`targetCompatibility`)
- **Android SDK**: `compileSdk=34`, `minSdk=24`, `targetSdk=34`
- **No Compose** — sample uses XML layouts, ViewBinding, Navigation Component
- **No DI** — dependencies flow via constructor + `ViewModel.contract(...)` extensions + `KMvi` global config
- **Publishing**: Maven Publish → GitHub Packages (`https://maven.pkg.github.com/ccolorcat/k-mvi`);
  credentials via `-Pgpr.personal.user`/`-Pgpr.personal.key` or env vars `USERNAME`/`TOKEN`
- **Local substitution**: root `build.gradle.kts` rewrites `cc.colorcat.mvi:*` dependencies to `project(":core")`
- **Logging**: framework uses `Logger` with lazy message; default threshold WARN; all internal logs tagged `k-mvi`

---

## Git Workflow

- Branch naming from commits: `release/<version>`, `dev` for active development
- Commit style: Conventional Commits (`chore:`, `refactor:`, `feat:`, `fix:`)
- Current branch: `release/1.4.1`

---

## CI/CD

- GitHub Actions workflow: `.github/workflows/publish.yml`
- **Manual trigger only** (`workflow_dispatch`)
- Single job: checkout → JDK 17 → `chmod +x gradlew` → `./gradlew publishReleasePublicationToGitHubPackagesRepository`
- Credentials from secrets: `MVN_USERNAME`, `MVN_PERSONAL_ACCESS_TOKEN`
- No automated test step in CI (tests run locally before publishing)

---

## Testing Coverage

| Area | Coverage |
|---|---|
| `Snapshot`, `PartialChange`, `asContract` | Well-covered |
| `Logger`, `KMvi` validation | Well-covered |
| Intent classification, handler delegation | Well-covered |
| Transformer strategy routing | Well-covered |
| `CoreReactiveContract` / `StrategyReactiveContract` | Well-covered |
| Lazy contract, reactive contract state/event propagation | Well-covered |
| `groupHandle`, `InternalExtensions` | Covered |
| `app/src/test` | Stub only |
| Instrumented tests (`core` + `app`) | Template stubs only |
| UI / Fragment / Navigation / Espresso | None |

---

## Tips for AI Agents

- **Start with `Mvi.kt`** — it defines the foundational types (`Intent`, `State`, `Event`, `PartialChange`, `Snapshot`)
- **The `internal` package is not part of the public API** — do not suggest internal types in user-facing documentation or samples
- **`KMvi.Configuration` is a data class** with defaults — use `copy()` inside `KMvi.configure { ... }`
- **Intent marker interfaces are mutually exclusive** — do not implement both `Concurrent` and `Sequential` on one intent
- **Pipeline runs on `Dispatchers.Default`** — blocking I/O in handlers needs explicit `withContext(Dispatchers.IO)`
- **Snapshot buffer DROP_OLDEST** may silently drop events if the UI collector is slow — keep event handlers lightweight
- **`dispatch()` returns `DispatchResult`** (not `Boolean`) — check the sealed class for queue status
- **Handler lookup is exact class match** — registering a handler for a parent class does NOT trigger for subclass dispatches
- **3 test files are in `internal/`** (private impl) — for internal changes, run both `core/src/test` and `core/src/test/java/.../internal/`
