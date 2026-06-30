# K-MVI Core Module Audit Report

- **Audit date**: 2026-06-30
- **Version audited**: 1.4.4-SNAPSHOT (cc.colorcat.mvi:core)
- **Scope**: `core/src/main/java/cc/colorcat/mvi/` (14 source files + 4 internal)
- **Test scope**: `core/src/test/java/cc/colorcat/mvi/` (5 test files)
- **Methodology**: Static analysis through full source and test reading; no runtime verification.

---

## Executive Summary

The K-MVI core module is a well-engineered, production-quality MVI library. The architecture is clean, the API surface is carefully designed with both low-level and high-level entry points, and the test coverage is robust. Below is a detailed breakdown per category.

---

## Design

Architecture decisions, pipeline design, concurrency model, and API surface evaluation.

- **Design-1**: Architecture is solid. The unidirectional data flow `Intent → Channel → Transformer → PartialChange → scan → Snapshot → StateFlow/EventFlow` is clean, well-encapsulated, and follows the MVI pattern faithfully.

- **Design-2**: Two API surfaces (Transformer-based low-level, Handler-based high-level) is a good design. Users can start with the simple DSL (`register<MyIntent> { ... }`) and graduate to full `IntentTransformer` control when needed.

- **Design-3**: The Snapshot frame model (`state + optional event`) with `updateState`/`withEvent`/`updateWith` is well-designed. The documentation explicitly warns about the `withEvent` then `updateState` pitfall within a single `PartialChange`.

- **Design-4**: `FatalErrorHandler.handle(error): Nothing` is a strong design choice — it forces implementations to not return normally, making unrecoverable error paths explicit at the type level.

- **Design-5**: The three `HandleStrategy` values (CONCURRENT, SEQUENTIAL, HYBRID) cover realistic concurrency needs. HYBRID's grouping model (concurrent sentinel, sequential sentinel, and tag-based fallback) is well-considered.

- **Design-6**: **[Limitation] `groupHandle` single-coroutine bottleneck.** The outer `collect { }` loop in `groupHandle` runs in exactly one coroutine. When `channel.send(intent)` suspends because a particular group's channel is full (due to a slow handler), **all** groups are blocked — even groups whose channels have free capacity. This is thoroughly documented but remains a fundamental design limitation for high-throughput or mixed-latency scenarios. Mitigation: choose appropriate `groupChannelCapacity` or use `Channel.UNLIMITED` (at the cost of unbounded memory).

- **Design-7**: **[Limitation] Snapshot buffer DROP_OLDEST can silently lose events.** The fused `flowOn + buffer(64, DROP_OLDEST)` boundary means if the `eventFlow` collector is slower than the state pipeline, the oldest snapshot (carrying its event) is dropped before the collector can read it. This is well-documented but is a design tradeoff rather than a bug — stale/late events are worse than lost events in a one-shot event model.

- **Design-8**: **[Limitation] KMvi global mutable singleton.** `KMvi.configure()` performs a non-atomic read-modify-write and is explicitly documented as not thread-safe. The backing config field is `@Volatile` only for reader visibility. While this is fine for single-threaded `Application.onCreate()` usage, the global singleton pattern is inherently fragile if configuration is ever needed from multiple points or in tests (tests must reset in `@Before`).

- **Design-9**: `IntentHandlerScope` as a `@JvmInline value class` wrapping `IntentHandlerRegistry` is an elegant solution to the Kotlin type-inference problem with reified generics. Binding S/E to the scope avoids the "all-or-nothing" type argument issue.

- **Design-10**: `eventFlow` uses `SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)`. The 5-second stop timeout is a good tradeoff: it survives configuration changes and Fragment back-stack transitions without restarting the upstream subscription, avoiding event loss during brief collector absences.

---

## Bug

Potential defects or correctness concerns found through static analysis.

- **Bug-1**: No confirmed runtime bugs found. The codebase is well-tested with 974 lines of `ReactiveContractImplTest` covering dispatch → state updates, multiple intent accumulation, `PartialChange.apply` exception handling, retry recovery, cancellation semantics, event emission, `DispatchResult` variants, and scope lifecycle. All reviewed logic appears correct.

- **Bug-2**: **[Edge case — not a bug per se]** `Handler lookup is exact class match` (documented in AGENTS.md and IntentHandlers.kt). Registering a handler for a parent class will NOT intercept subclass dispatches. This is intentional and documented, but is a frequent confusion point for users accustomed to polymorphic dispatch. Users must either register per-concrete-type or use `defaultHandler` with a `when` expression.

- **Bug-3**: **[Edge case — not a bug per se]** In `groupHandle`, when `flattenMerge` cancels an inner flow (e.g., due to scope cancellation), the corresponding channel becomes closed. The stale channel detection (`isClosedForSend` → reopen) correctly handles this case, preventing `ClosedSendChannelException` from killing the entire pipeline.

- **Bug-4**: **[Potential concern] `IntentQueueConfig.onBufferOverflow` defaults to `SUSPEND`, but `dispatch()` uses `Channel.trySend` which is non-blocking.** When the queue is full with `SUSPEND`, `trySend()` returns a failed `ChannelResult` (failure, not closed), which correctly maps to `DispatchResult.Full`. No bug — the semantics are correct — but the `SUSPEND` name in the config might misleadingly suggest `dispatch` could suspend. The KDoc clarifies this.

---

## Name

Naming clarity, consistency, and conciseness of types, functions, and parameters.

- **Name-1**: `Mvi` object as a namespace for `Intent`, `State`, `Event`, `PartialChange`, `Snapshot` — reasonable as a scoping namespace, though slightly unusual. It keeps the top-level namespace clean but adds nesting overhead (`Mvi.Intent`, `Mvi.PartialChange`) in every file.

- **Name-2**: `PartialChange` — good name. "Partial" accurately conveys that only part of the frame is changed. The associated terminology (frame, snapshot, migrate) is consistent.

- **Name-3**: `DispatchResult` with `Submitted` / `Unavailable` / `Full` — clear and unambiguous. The KDoc for each variant is thorough about what it means (and does not mean).

- **Name-4**: `StrategyReactiveContract` vs `CoreReactiveContract` — the naming doesn't clearly convey the inheritance relationship. `CoreReactiveContract` is the base with `IntentTransformer`; `StrategyReactiveContract` extends it with `HandleStrategy` + handler registration. Prefixes like `Base`/`Strategy` or `Transformer`/`Handler` would be more self-explanatory. However, both are `internal`, so this is a minor concern.

- **Name-5**: `IntentHandlerDelegate` — good name. Clearly conveys it's a wrapper that delegates to registered handlers with fallback.

- **Name-6**: `IntentHandlerScope` — good name for a DSL scope.

- **Name-7**: `debounceLeading` — excellent name. Clearly contrasts with the standard `debounce` (trailing edge) and immediately conveys "leading edge" behavior.

- **Name-8**: `asSingleFlow()` — acceptable but borderline. `asFlow()` or `toFlow()` would be more concise. The "Single" qualifier correctly suggests single emission, though.

- **Name-9**: `groupHandle` — adequate but slightly ambiguous: does it "handle groups" or "group and handle"? Given the internal context, it's clear.

- **Name-10**: `groupCountWarningThreshold` — clear but verbose. `groupCountThreshold` or `groupWarningThreshold` would be equally clear and shorter.

- **Name-11**: `ReactiveContractLazy` — clear. The name directly states it's a `Lazy` for `ReactiveContract`.

- **Name-12**: `setupIntentHandlers` — clear. The `setup` prefix matches the DSL builder pattern.

---

## Style

Kotlin code style, idiomatic usage, formatting conventions, and code consistency.

- **Style-1**: Code follows Kotlin `official` style consistently: 4-space indent, `PascalCase` for types, `camelCase` for functions/properties, `UPPER_SNAKE_CASE` for constants. Trailing commas are used. LF line endings.

- **Style-2**: Lazy logging (`logger.i(TAG) { "message" }`) is used consistently across all internal logging — good performance practice.

- **Style-3**: No unnecessary semicolons or explicit `return` in single-expression functions. Good use of Kotlin idioms.

- **Style-4**: Good use of `@Suppress("UNCHECKED_CAST")` with narrow scope (single expression, not the whole function/file).

- **Style-5**: **[Minor] Inconsistent code block ordering in contracts.** `ReactiveContract` is defined after `Contract` in the file, which is logical (read-only → full-featured). But `DispatchResult` is in a separate file, while `asContract()` extension is in `Contracts.kt`. This is reasonable separation, though `DispatchResult.kt` could arguably be merged into `Contracts.kt`.

- **Style-6**: Good use of `fun interface` for SAM types (`IntentHandler`, `PartialChange`, `FatalErrorHandler`, `Logger`, `IntentTransformer`, `GroupTagSelector`). These allow lambda-to-SAM conversion at call sites.

- **Style-7**: The `StrategyIntentTransformer.assignGroupTag` method has clean, well-commented code. The `when` expression with early conflict detection is well-organized.

- **Style-8**: **[Minor] Empty `TextWatcher` method bodies** in `doOnAfterTextChanged` — `beforeTextChanged` and `onTextChanged` have empty curly braces `{}`. This is idiomatic for Java listener interfaces in Kotlin, but `object : TextWatcher` with two empty methods is verbose. Acceptable given this is a standard Android pattern.

- **Style-9**: KDoc uses `[]` for Kotlin references correctly (`[Mvi.PartialChange]`, `[Snapshot.updateState]`). Code blocks are fenced with ` ``` ` consistently.

- **Style-10**: **[Minor] Test code uses `runBlocking` and `runTest(UnconfinedTestDispatcher())`** rather than a single consistent approach. The test file comment on `eventFlow` tests explains the `UnconfinedTestDispatcher` choice well, but the mixing of `runBlocking` and `runTest` without a uniform pattern is slightly inconsistent.

---

## Doc

Documentation completeness, correctness, and clarity of KDoc comments, READMEs, and inline comments.

- **Doc-1**: Overall documentation quality is excellent. Every public class, interface, function, and property has KDoc. Many internal classes also have thorough documentation.

- **Doc-2**: `Mvi.kt`'s top-level KDoc provides a comprehensive architecture overview with data flow diagrams — excellent for new users.

- **Doc-3**: `HandleStrategies.kt` has extensive KDoc with strategy comparisons, examples, and visual diagrams. However, it is **excessively verbose**. The `HYBRID` section alone spans ~80 lines of commentary with ASCII diagrams, multiple examples, and configuration snippets. Some of this content is duplicated in `HandleStrategy.SEQUENTIAL` and `.CONCURRENT`. A more concise version would be easier to maintain and read.

- **Doc-4**: `DispatchResult` KDoc is thorough about queue policy semantics. The explanation of how each `Channel` mode (RENDEZVOUS, CONFLATED, BUFFERED, UNLIMITED) interacts with `DispatchResult` is valuable.

- **Doc-5**: `groupHandle` KDoc is notably honest about the single-coroutine bottleneck (marked with ⚠️) and provides mitigation guidance. This level of transparency in internal documentation is commendable.

- **Doc-6**: `eventFlow` KDoc clearly warns about event loss scenarios (no subscriber, pipeline congestion) and provides code examples showing the correct subscription-before-dispatch pattern. Good.

- **Doc-7**: `Snapshot.updateState` KDoc explicitly warns about the `withEvent().updateState()` pitfall within a single `PartialChange`, with "❌" and "✅" code examples. Very helpful.

- **Doc-8**: **[Missing] No KDoc on the `@file:OptIn(FlowPreview::class)` annotation** in `MviExtensions.kt` and `IntentTransformers.kt`. Users importing these APIs need to know they should opt-in. A brief file-level KDoc note would help.

- **Doc-9**: **[Missing] No external package-level documentation** (no `package-info.kt` or `package.html`). The public API surface has good individual KDoc, but there's no single document describing the package structure for users browsing the API.

- **Doc-10**: **[Minor inconsistency]** Some KDoc code examples use ` ``` ` (triple backticks) while others use ` ``` `` `` ` (backtick-style inline). Not consistently formatted.

- **Doc-11**: `IntentHandlerRegistry.register` KDoc correctly documents the exact-class-match limitation, including the note about subclass dispatch falling through to `defaultHandler`. Good.

- **Doc-12**: The `build.gradle.kts` for core has no dependency version comments, but the version catalog (`libs.versions.toml`) is well-organized. Acceptable.

- **Doc-13**: Test file naming is clear (`MviTest`, `ReactiveContractImplTest`, `MviExtensionsTest`, `LoggerTest`, `FatalErrorHandlerTest`). Test method names use Kotlin backtick format with descriptive natural language.

- **Doc-14**: **[Missing]** The `ReactiveContractLazy` class KDoc lacks a note that `LazyThreadSafetyMode.NONE` means the initializer **may be called multiple times** under concurrent access. The KDoc says "may be called more than once" but doesn't explicitly warn about the consequence (duplicate contract creation). Since ViewModel access is expected to be single-threaded, this is acceptable, but a clearer warning would be prudent.

---

## Summary Statistics

| Category | Positive | Concerns/Suggestions |
|----------|----------|---------------------|
| **Design** | 10 strong design points | 3 documented limitations (groupHandle bottleneck, DROP_OLDEST event loss, global singleton) |
| **Bug** | No confirmed bugs | 2 edge cases documented (exact class lookup, stale channel recovery) |
| **Name** | 8 well-named items | 2 minor suggestions (StrategyReactiveContract/CoreReactiveContract naming, asSingleFlow conciseness) |
| **Style** | 9 well-styled items | 3 minor suggestions (test framework consistency, empty TextWatcher bodies, test coroutine approach mix) |
| **Doc** | 12 well-documented items | 4 minor gaps (OptIn annotation, package docs, code block consistency, LazyThreadSafetyMode clarity) |

## Recommendations (Optional)

These are suggestions, not issues:

1. **Medium**: Consider adding a `dispatcher` parameter to `CoreReactiveContract` to allow injecting a custom `CoroutineDispatcher` instead of hardcoding `Dispatchers.Default`. This would improve testability and allow platform-specific tuning.

2. **Low**: The `groupHandle` single-coroutine bottleneck could be mitigated by spawning one `produce` coroutine per group tag instead of routing all intents through a single `collect` loop. This would eliminate the head-of-line blocking at the cost of increased coroutine overhead.

3. **Low**: Consider extracting the `KMvi.Configuration` properties into a dedicated configuration object passed explicitly to contracts, reducing reliance on the global mutable singleton for library consumers who prefer dependency injection.

4. **Low**: Add `@file:OptIn` explanation in `MviExtensions.kt` and `IntentTransformers.kt` to guide users on the `FlowPreview` annotation requirement.
