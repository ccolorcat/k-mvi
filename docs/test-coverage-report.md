# K-MVI Unit Test Coverage Report

_Generated: 2026-06-30_

---

## Executive Summary

| Module | Source Files | Test Files | Test Methods | All Passed | Coverage |
|--------|-------------|------------|-------------|------------|----------|
| `:core` | 17 | 13 | **140** | ✅ | Core runtime: **~85%** |
| `:app` | 14 | 1 (stub) | **1** | ✅ | **~0%** (meaningful) |
| **Total** | **31** | **14** | **141** | ✅ | |

All 140 core tests pass. No failures, no errors, no skips.

The core library has strong test coverage for its public API, intent handling pipeline, and configuration validation. The app module (sample) has no meaningful tests — its single test file is the generated `ExampleUnitTest` stub. Two core source files (`MviCollects.kt`, `MviViewModels.kt`) have no tests because they depend on Android framework types (`Lifecycle`, `ViewModel`) that require Robolectric or an instrumented environment.

---

## Module: `:core` — Source vs. Test Mapping

| Source File | Test File(s) | Tests | Status |
|---|---|---|---|
| `Mvi.kt` | `MviTest.kt` | 22 | ✅ Comprehensive |
| `Contracts.kt` | `ReactiveContractImplTest.kt` (indirect) | — | ✅ Implicitly covered |
| `KMvi.kt` | `KMviTest.kt` | 23 | ✅ Comprehensive |
| `Logger.kt` | `LoggerTest.kt` | 11 | ✅ Comprehensive |
| `FatalErrorHandler.kt` | `FatalErrorHandlerTest.kt` | 2 | ✅ Covered |
| `IntentHandlers.kt` | `IntentHandlersTest.kt` | 8 | ✅ Comprehensive |
| `IntentTransformers.kt` | `IntentTransformersTest.kt` | 16 | ✅ Comprehensive |
| `ReactiveContractImpl.kt` | `ReactiveContractImplTest.kt` | 23 | ✅ Comprehensive |
| — | `ReactiveContractLazyTest.kt` | 5 | ✅ Covered |
| `InternalExtensions.kt` | `InternalExtensionsTest.kt` | 7 | ✅ Comprehensive |
| `LoggerExtensions.kt` | `LoggerExtensionsTest.kt` | 12 | ✅ Comprehensive |
| `MviExtensions.kt` | `MviExtensionsTest.kt` | 3 | ✅ Covered |
| — | `DebounceLeadingTest.kt` | 7 | ✅ Covered |
| `DispatchResult.kt` | — (no dedicated) | 0 | ⚠️ Indirect only |
| `HandleStrategies.kt` | `IntentTransformersTest.kt`, `KMviTest.kt` (indirect) | — | ⚠️ Indirect only |
| `IntentQueueConfig.kt` | `KMviTest.kt` (indirect) | — | ⚠️ Indirect only |
| `InternalUtils.kt` | `KMviTest.kt` (indirect) | — | ⚠️ Indirect only (channel config validation tested) |
| `MviCollects.kt` | — | 0 | ❌ Untested (Android dependency) |
| `MviViewModels.kt` | — | 0 | ❌ Untested (Android dependency) |

---

## Module: `:app` — Source vs. Test Mapping

| Source File | Test File | Tests | Status |
|---|---|---|---|
| All 14 sample files | `ExampleUnitTest.kt` | 1 (`2+2=4`) | ❌ No meaningful tests |

Sample app files include `CounterContract.kt`, `CounterViewModel.kt`, `CounterFragment.kt`,
`LoginContract.kt`, `LoginViewModel.kt`, `LoginFragment.kt`,
`DashboardContract.kt`, `DashboardViewModel.kt`, `DashboardFragment.kt`,
`NavigationFragment.kt`, `SampleActivity.kt`, `SampleApplication.kt`, `Utils.kt`, `ViewBindingDelegate.kt`.

---

## Detailed Test Breakdown

### `MviTest.kt` — 22 tests

Covers `Snapshot` creation/immutability, `updateState` (clears event, pure, receiver context),
`withEvent` (attaches, overwrites, preserves reference), `updateWith` (both state+event),
`PartialChange.apply` (state, event, combined, composition, error clearing), and `asContract`.

### `KMviTest.kt` — 23 tests

Covers default configuration values, custom configuration, and validation for:
- `IntentQueueConfig`: zero, CONFLATED, UNLIMITED, positive capacity; rejects negative
- `IntentQueueConfig`: rejects CONFLATED + DROP overflow combination
- `HybridStrategyConfig`: BUFFERED, positive, UNLIMITED group channel capacity
- `HybridStrategyConfig`: positive, Int.MAX_VALUE group count warning threshold; rejects zero/negative
- Handle strategy assignment, fatal error handler, logger injection, retry policy changes

### `IntentHandlersTest.kt` — 8 tests

Covers `IntentHandlerDelegate`:
- Delegates to registered handler
- Falls back to defaultHandler for unregistered type
- Handle with no registered handlers uses default
- null defaultHandler produces emptyFlow + WARN log for unregistered intent
- null defaultHandler is silent for intents with registered handler
- Single-change `register()` runs handler lazily on collection
- Register replaces existing handler
- Handles multiple intents in sequence

### `IntentTransformersTest.kt` — 16 tests

Covers all three handling strategies:
- CONCURRENT: transforms intents, empty flow, concurrent processing order
- SEQUENTIAL: in-order processing, handler returning emptyFlow
- HYBRID: routing by intent type (Concurrent → parallel, Sequential → serial, Fallback → grouped)
- HYBRID: group tag selector behavior, multiple intents same/different tags
- HYBRID: both Concurrent & Sequential marker → fallback with one-time warning
- `toPartialChange` extension
- Transformer factory creates correct strategy type
- Edge cases: empty flow, silent handler, timeout delay handling

### `ReactiveContractImplTest.kt` — 23 tests

Covers:
- Basic dispatch → stateFlow update (CONCURRENT, SEQUENTIAL)
- Multiple intents accumulate state
- `PartialChange.apply` exception → fatal error handler + pipeline termination
- `dispatch` with CONCURRENT strategy
- Event emission
- `DispatchResult.Unavailable` after scope cancel
- `dispatch` after cancel does not crash
- `dispatch` with custom intent queue config
- Full queue returns `DispatchResult.Full`
- Queue config with `DROP_OLDEST` and `DROP_LATEST`
- `CONFLATED` queue: only latest intent processed
- SEQUENTIAL ordering: intents processed in order
- Conflated stateFlow: slow collector skips intermediate values
- CONCURRENT: late-arriving PartialChanges don't corrupt final state
- Handler returning multiple PartialChanges in sequence
- Event not retained in stateFlow value
- `dispatch` with `Unlimited` queue config
- `dispatch` with retry succeeds after handler failure
- Retry exhausted ends pipeline
- `eventFlow` emits events correctly
- `eventFlow` with no events is empty
- Error during concurrent handler doesn't crash other handlers
- Handler error with custom fatal error handler

### `ReactiveContractLazyTest.kt` — 5 tests

Covers `ReactiveContractLazy`: creates and caches contract, `isInitialized()`, returns same instance,
lazy value not null.

### `InternalExtensionsTest.kt` — 7 tests

Covers `diagnosticName` for named intents, data class intents, anonymous intents, sensitive data not leaked,
and `groupHandle` warning behavior (doubled thresholds, below-threshold silence, Int.MAX_VALUE disabled).

### `LoggerExtensionsTest.kt` — 12 tests

Covers all log extensions: `v`, `d`, `i`, `w`, `e`, `e` with throwable, `assert`;
tag passing; lazy message lambda not evaluated when ignored; null error in e and assert;
stack trace string with chained cause.

### `LoggerTest.kt` — 11 tests

Covers: default logger creation, custom thresholds (DEBUG, ERROR), custom logger captures priority/tag/throwable,
threshold constant ordering, lazy message lambda, factory threshold filtering, VERBOSE threshold passes all.

### `MviExtensionsTest.kt` — 3 tests

Covers `asSingleFlow`: emits partial change, exactly one emission, supports intent that is also partial change.

### `DebounceLeadingTest.kt` — 7 tests

Covers `debounceLeading`: emits first immediately, suppresses rapid successive events, emits after sufficient gap,
sliding window suppresses mid-burst event, uses monotonic nanosecond clock, throws on zero/negative millis.

### `FatalErrorHandlerTest.kt` — 2 tests

Covers `Rethrow` throws original error, custom handler records before throwing.

---

## Coverage Gaps

### High Priority

1. **`MviCollects.kt` (551 lines)** — Lifecycle-aware State/Event collection DSL. Zero tests.
   - Depends on `androidx.lifecycle.Lifecycle`, `LifecycleOwner`, `lifecycleScope`
   - Would require Robolectric or Android instrumented tests
   - **Risk**: The most complex file in the core library (550+ lines) is untested

2. **`MviViewModels.kt` (244 lines)** — `ViewModel.contract()` extensions. Zero tests.
   - Depends on `androidx.lifecycle.ViewModel`, `viewModelScope`
   - Would require Robolectric or Android instrumented tests

### Medium Priority

3. **`DispatchResult.kt`** — No dedicated test class. The sealed class values (`Submitted`, `Unavailable`, `Full`) are returned by dispatch and tested implicitly in `ReactiveContractImplTest`, but the documented queue-policy semantics and edge cases are not directly validated.

4. **`HandleStrategies.kt`** — `GroupTagSelector.byClass()` factory has no direct unit test. The `GroupTagSelector` interface and `HybridStrategyConfig` validation are tested indirectly through `IntentTransformersTest` and `KMviTest`.

5. **`IntentQueueConfig.kt`** — Validation is tested through `KMviTest`, but the data class itself has no dedicated tests (getters, copy, toString).

6. **`InternalUtils.kt`** — `requireSupportedChannelConfig()` validation logic is tested through `KMviTest` (capacity validation). The `tagLabel` extension has no dedicated test.

### Low Priority

7. **`app/` module** — 14 source files with no meaningful tests. These are sample/demo code. Adding tests would be valuable for regression but lower priority than core library coverage.

8. **Instrumented tests** — Both modules have only stub `ExampleInstrumentedTest.kt` files. No Android instrumented tests exist anywhere.

---

## Recommendations

1. **Add Robolectric tests for `MviCollects.kt`** — This is the highest-risk untested surface. Create lifecycle-aware test harnesses for `collectState`, `collectEvent`, `StateCollector.collectProperty`, and `StateCollector.collectWhole`.

2. **Add Robolectric tests for `MviViewModels.kt`** — Verify `ViewModel.contract()` extension functions create the correct contract type with the right scope.

3. **Add direct tests for `DispatchResult`** — Validate the sealed class serialization/toString behavior and queue policy documentation.

4. **Add direct tests for `InternalUtils.kt`** — Explicitly test `requireSupportedChannelConfig` boundary cases and `tagLabel` formatting.

5. **Consider sample app tests** — Add unit tests for ViewModels in the sample app using the core library's test patterns. These serve as documentation and regression.

---

## Test Infrastructure

| Detail | Value |
|--------|-------|
| Test framework | JUnit 4 |
| Coroutines testing | `runBlocking` + `runTest(UnconfinedTestDispatcher())` |
| Assertions | JUnit `Assert.*` |
| Turbine | Not used (raw `first()`, `toList()`, `collect {}`) |
| Test logger | Custom `TestLogger` Rule |
| KMvi reset | `@Before fun setUp() { KMvi.configure { KMvi.Configuration() } }` |
| Test count total | **140** (core) + **1** (app stub) = **141** |
| All pass | ✅ |
