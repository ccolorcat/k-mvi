# K-MVI Agent Guide

## Response Language

- Match the primary language of the user's current message.
- Mixed Chinese/English → default to Simplified Chinese.
- Code, commands, config keys, API names, and error messages kept verbatim.

## Project Overview

K-MVI is a lightweight, type-safe Android MVI library built on Kotlin Coroutines and Flow.
Artifact: `cc.colorcat.mvi:core`, published to GitHub Packages and Maven Central.
Two modules: `core` (library) and `app` (XML/ViewBinding sample app with Navigation Component).

---

## Build / Test / Lint

All from repo root with Gradle wrapper.

```bash
# Build
./gradlew :core:assemble                              # library AAR
./gradlew :app:assembleDebug                          # sample debug APK

# Core JVM unit tests (primary suite)
./gradlew :core:test
./gradlew :core:test --tests "cc.colorcat.mvi.internal.ReactiveContractImplTest"

# App unit tests (stub only)
./gradlew :app:testDebugUnitTest

# Instrumented tests (needs device/emulator)
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
UI → dispatch(intent) → intentsChannel (capacity 256, SUSPEND)
  → retryWhen (RetryPolicy)
  → IntentTransformer (strategy routing)
  → IntentHandler.handle(intent): Flow<PartialChange>
  → scan(PartialChange::apply) → Snapshot
  → flowOn(Default) + buffer(64, DROP_OLDEST)  ┄ fused into one channel
  → shareIn (Eagerly) → stateFlow / eventFlow
```

- **intentsChannel**: dispatch entry, default `IntentQueueConfig(capacity=256, SUSPEND)`
- **snapshot buffer**: fused `flowOn+buffer(64, DROP_OLDEST)` — stale snapshots with their events discarded if downstream slow
- `PartialChange.apply()` runs inside `scan` on `Dispatchers.Default` — **must be pure, non-throwing, no I/O**

### DispatchResult

`dispatch()` returns a sealed class:
- `Submitted` — accepted by queue (not a processing guarantee)
- `Unavailable` — contract scope done (terminal)
- `Full` — queue at capacity (transient, retry later)

### Handle Strategy

| Strategy | Operator | Behavior |
|---|---|---|
| `CONCURRENT` | `flatMapMerge` | All parallel |
| `SEQUENTIAL` | `flatMapConcat` | All serial |
| `HYBRID` (default) | `groupHandle` + `flattenMerge(MAX_VALUE)` | Concurrent→parallel, Sequential→serial, Fallback→grouped |

### Critical Gotchas

- `PartialChange.apply` exceptions: previous snapshot retained, pipeline continues (`CancellationException` re-thrown)
- `eventFlow` is `SharedFlow` with **no replay** — start collecting before dispatching
- `Snapshot.updateState` **clears the event** — use `updateWith(event) { copy(...) }` to set both
- `Snapshot.withEvent(event).updateState { ... }` **drops the event** — the `updateState` clears it
- Intent markers are **mutually exclusive**; implementing both → routed to fallback group with one-time warning
- `groupHandle` runs a **single coroutine** for routing — a blocked group channel blocks all groups
- Handler lookup is **exact class match** — parent-class registration does NOT catch subclasses
- `dispatch()` uses `Channel.trySend` — non-blocking; with `SUSPEND` overflow, returns `Full` instead of suspending
- Deciding in handler via `stateFlow.value` may read stale state under CONCURRENT/HYBRID — use `old.state` inside `apply` for freshness

---

## Key Public API (`cc.colorcat.mvi`)

| Type | Role |
|---|---|
| `Mvi.Intent` / `.Concurrent` / `.Sequential` | Marker interfaces (mutually exclusive) |
| `Mvi.State` / `Mvi.Event` | Persistent UI state / one-shot side effect |
| `Mvi.PartialChange<S,E>` | `fun interface`; `apply(oldSnapshot)` → new snapshot; must be pure |
| `Mvi.Snapshot<S,E>` | Immutable `(state, event?)`; `.updateState`, `.withEvent`, `.updateWith` |
| `Contract<S,E>` | Read-only: `stateFlow`, `eventFlow` |
| `ReactiveContract<I,S,E>` | Mutable: adds `dispatch(intent): DispatchResult` |
| `DispatchResult` | `Submitted` / `Unavailable` / `Full` |
| `IntentQueueConfig` | capacity (default 256) + `onBufferOverflow` (default SUSPEND) |
| `IntentHandler<I,S,E>` | `fun interface`; `handle(intent): Flow<PartialChange>` |
| `IntentHandlerScope` | DSL for `register<MyIntent> { ... }` |
| `IntentTransformer<I,S,E>` | `fun interface`; `Flow<I>` → `Flow<PartialChange>` |
| `KMvi` | Global config singleton; `KMvi.configure { copy(...) }` |
| `HandleStrategy` | `CONCURRENT`, `SEQUENTIAL`, `HYBRID` |
| `HybridStrategyConfig` | `groupChannelCapacity`, `groupCountWarningThreshold` |
| `GroupTagSelector<I>` | `fun interface`; default `byClass()` |
| `RetryPolicy` | `(attempt: Long, cause: Throwable) -> Boolean` |
| `Logger` | `fun interface`; `Logger(threshold)` factory backed by `android.util.Log` |

---

## Key Files

| File | Purpose |
|---|---|
| `Mvi.kt` | Root types: `Intent`, `State`, `Event`, `PartialChange`, `Snapshot` |
| `Contracts.kt` | `Contract`, `ReactiveContract`, `asContract()`, `DispatchResult` |
| `KMvi.kt` | Global config, `RetryPolicy`, `Configuration` data class |
| `HandleStrategies.kt` | `HandleStrategy` enum, `HybridStrategyConfig`, `GroupTagSelector` |
| `IntentQueueConfig.kt` | Dispatch queue config |
| `IntentHandlers.kt` | `IntentHandler`, `IntentHandlerRegistry`, `IntentHandlerScope` |
| `IntentTransformers.kt` | `IntentTransformer`, `strategyTransformer` |
| `MviViewModels.kt` | `ViewModel.contract(...)` — two APIs (transformer + handler) |
| `MviCollects.kt` | `collectState`, `collectEvent`, lifecycle-aware DSL builders |
| `MviExtensions.kt` | `doOnClick`, `debounceLeading`, `asSingleFlow` |
| `Logger.kt` | `Logger` fun interface with Android `Log` backend |
| `internal/ReactiveContractImpl.kt` | `CoreReactiveContract` + `StrategyReactiveContract` runtime |
| `internal/InternalExtensions.kt` | `groupHandle`, `isConcurrent`/`isSequential`, `diagnosticName` |

### Sample App (`app/src/main/java/cc/colorcat/mvi/sample/`)
- `count/` — sequential intents, distributed `register()` style
- `login/` — hybrid strategy, centralized `defaultHandler` style
- `dashboard/` — concurrent + sequential + grouped intents
- `util/` — `randomDelay`, `ViewBindingDelegate`, `doOnTextChanged`

---

## Coding Conventions

- 4-space indent, max 120 chars, LF, UTF-8, trailing comma, `kotlin.code.style=official`
- Types: PascalCase; functions/properties: camelCase; constants: UPPER_SNAKE_CASE
- Files named after primary class/interface
- Test classes: `<Subject>Test` (`ReactiveContractImplTest`)
- Test methods: Kotlin backticks (`` `dispatch after cancel does not crash` ``)
- Shared `TestLogger` rule prints `[TEST START/PASS/FAIL/ERROR]`
- Test fixtures defined **per test class**, not shared
- Tests use `runBlocking` or `runTest(UnconfinedTestDispatcher())`; assertions via `first()`, `toList()`, `collect {}` — no Turbine
- Tests mutating `KMvi` config must reset in `@Before`:
  ```kotlin
  @Before fun setUp() { KMvi.configure { KMvi.Configuration() } }
  ```

### Two Handler Patterns

1. **Distributed** (`register` DSL): one handler per intent type — used in Counter sample
2. **Centralized** (`defaultHandler`): single `when` expression routes all intents — used in Login sample

### Two Contract APIs

1. **Transformer-based**: `by contract(initState, transformer = ...)` — low-level, full Flow control
2. **Handler-based**: `by contract(initState, handleStrategy = ...) { register(...) }` — high-level, recommended

---

## Runtime / Tooling

- **Gradle**: 8.13, **Kotlin**: 1.9.10, **AGP**: 8.13.1, **Java**: 17
- **Android**: `compileSdk=34`, `minSdk=24`, `targetSdk=34`
- **No Compose** — XML + ViewBinding + Navigation Component
- **No DI** — constructor + `ViewModel.contract()` + `KMvi` global config
- **Publishing**: Maven Publish → GitHub Packages (`maven.pkg.github.com/ccolorcat/k-mvi`); also Maven Central
- **Local substitution**: root `build.gradle.kts` rewrites remote `cc.colorcat.mvi:*` deps to `project(":core")`
- **Logging**: `Logger` with lazy message; default threshold WARN; internal logs tagged `k-mvi`
- **Configuration**: `KMvi.configure { copy(...) }` once in `Application.onCreate()`; not thread-safe

---

## Git Workflow

- Branches: `dev` (active), `release/<version>` (release tags)
- Commit style: Conventional Commits (`feat:`, `fix:`, `refactor:`, `chore:`, `docs:`)

---

## CI/CD

- Manual trigger only (`workflow_dispatch` in `.github/workflows/publish.yml`)
- Single job: checkout → JDK 17 → `./gradlew publishReleasePublicationToGitHubPackagesRepository`
- Credentials from secrets: `MVN_USERNAME`, `MVN_PERSONAL_ACCESS_TOKEN`
- No automated test step in CI

---

## Testing Coverage

| Area | Coverage |
|---|---|
| `Snapshot`, `PartialChange`, `asContract` | Well-covered |
| `Logger`, `KMvi` validation, Intent classification | Well-covered |
| Transformer strategy routing, handler delegation | Well-covered |
| `CoreReactiveContract` / `StrategyReactiveContract` | Well-covered |
| `groupHandle`, `InternalExtensions` | Covered |
| `app/src/test` | Stub only |
| Instrumented tests | Template stubs |
| UI / Fragment / Navigation | None |
