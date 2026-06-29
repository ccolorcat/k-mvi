# ProGuard / R8 Obfuscation Security Audit

**Project**: K-MVI (`cc.colorcat.mvi:core`)
**Date**: 2026-06-19
**Scope**: `core/` (library) + `app/` (sample) — all `.kt` source files
**Minification status**: Disabled in both modules (`isMinifyEnabled = false`)

> Status: The H1/H2 release blockers have been addressed for the 1.4.1 release by removing
> reflection-based ViewBinding lookup from the sample app and adding active consumer R8 rules to
> `core/consumer-rules.pro`. The remaining items are either covered by those rules or diagnostic-only.

---

## Current Release Status

For 1.4.1, there are no remaining R8 release blockers from this audit.

| Finding | Current status |
|---|---|
| H1 ViewBinding reflection | Fixed. Sample delegates now require explicit generated factory references such as `FragmentCounterBinding::bind`. |
| H2 Empty consumer rules | Fixed. `core/consumer-rules.pro` now ships active rules for the K-MVI API and MVI marker subtypes. |
| H3 Intent handler type dispatch | Covered by marker-subtype consumer rules. Exact `Class` identity is stable under renaming when concrete intent classes remain reachable. |
| M1 Event type filtering | Covered by marker-subtype consumer rules for concrete event classes. |
| M2 Obfuscated diagnostic names | Accepted. These names are used only for logging and are not part of runtime semantics. |

## Original Audit Summary

At the time of the original audit, the project had **zero** active ProGuard/R8 keep rules across both modules. While minification was disabled, the library was published as an AAR with an **empty `consumer-rules.pro`**, meaning any downstream consumer that enabled R8 could corrupt the library's type-based dispatch and event filtering. The sample app's `ViewBindingDelegate` also used reflection that could crash under R8.

**3 HIGH-risk, 2 MEDIUM-risk, 3 info items** were found.

---

## 🔴 HIGH-RISK FINDINGS

### H1. ViewBinding Reflection — `getMethod("bind")` / `getMethod("inflate")`

**Files**:
- `app/src/main/java/cc/colorcat/mvi/sample/util/ViewBindingDelegate.kt:89-93`
- `app/src/main/java/cc/colorcat/mvi/sample/util/ViewBindingDelegate.kt:127-130`

```kotlin
// Line 89-93
inline fun <reified T : ViewBinding> bind(view: View): T {
    return T::class.java.getMethod("bind", View::class.java)
        .invoke(null, view) as T
}

// Line 127-130
inline fun <reified T : ViewBinding> inflate(inflater: LayoutInflater): T {
    return T::class.java.getMethod("inflate", LayoutInflater::class.java)
        .invoke(null, inflater) as T
}
```

**Risk mechanism**: R8 obfuscates generated ViewBinding classes (e.g. `FragmentLoginBinding`) and their static method names. The hardcoded string `"bind"` / `"inflate"` will mismatch → `NoSuchMethodException` crash at runtime.

**Affected call sites** (all crash if R8 is enabled):
- `CounterFragment.kt:76` — `private val binding: FragmentCounterBinding by viewBinding()`
- `LoginFragment.kt:39` — `private val binding by viewBinding<FragmentLoginBinding>()`
- `DashboardFragment.kt:44` — `private val binding: FragmentDashboardBinding by viewBinding()`
- `SampleActivity.kt:21` — `private val binding by viewBinding<ActivitySampleBinding>()`

**Implemented fix**: Replace reflection-based delegate helpers with delegates that require explicit generated
factory references at the call site:

```kotlin
private val binding by viewBinding(FragmentCounterBinding::bind)
private val binding by viewBinding(ActivitySampleBinding::inflate)
```

---

### H2. Empty Consumer ProGuard Rules — Library Ships No Protection

**File**: `core/consumer-rules.pro` — empty (0 bytes)
**File**: `core/proguard-rules.pro` — all comments, zero active rules
**Build config**: `core/build.gradle.kts:16` — `consumerProguardFiles("consumer-rules.pro")` (declared but empty)

**Risk mechanism**: When a downstream consumer project enables R8, the library's `consumer-rules.pro` is merged automatically. Since it is empty, none of the library's types are preserved. The consumer's R8 can rename/strip:

- `Mvi.Intent` subtypes → handler registry lookup fails silently
- `Mvi.State` subtypes → UI state collection breaks
- `Mvi.Event` subtypes → `collectTyped` filter never matches
- `Mvi.PartialChange` subtypes → state transitions fail

**Implemented fix**: Add active rules to `core/consumer-rules.pro`:

```pro
# Keep all MVI core public API.
-keep class cc.colorcat.mvi.** { *; }

# Keep all intent, state, event, and partial change subtypes.
-keep class ** implements cc.colorcat.mvi.Mvi$Intent { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$Intent$Concurrent { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$Intent$Sequential { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$State { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$Event { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$PartialChange { *; }
```

---

### H3. Reified Intent Handler Registration — Type Erasure Lookup at Runtime

**File**: `core/src/main/java/cc/colorcat/mvi/IntentHandlers.kt`
**Lines**: 259, 350, 373, 400

```kotlin
// Line 259 — runtime dispatch lookup:
val registered = handlers[intent.javaClass] as IntentHandler<I, S, E>?

// Line 349-352 — reified registration:
inline fun <reified T : I> register(noinline handler: ...) {
    registry.register(T::class.java) { ... }  // T::class.java inlined at call site
}

// Line 372-374 — reified registration with IntentHandler:
inline fun <reified T : I> register(handler: IntentHandler<T, S, E>) {
    registry.register(T::class.java, handler)  // T::class.java inlined at call site
}
```

**Risk mechanism**: Both `register` and `handle` use `Class` identity (`T::class.java` / `intent.javaClass`), so consistent renaming is fine. **However**, if R8's aggressive optimization determines that a particular intent subtype (e.g., `CounterIntent.Increment`) is "unused" (removal of dead classes, merging, or inlining), the handler entry is never registered or the intent class is eliminated. The intent then silently falls through to `defaultHandler` or produces `emptyFlow()` — **no crash, but behavior is silently wrong**.

**This is the number-one semantic risk for library consumers using R8.**

---

## 🟡 MEDIUM-RISK FINDINGS

### M1. Event Type Filtering via `KClass.isInstance` / `KClass.java.cast`

**File**: `core/src/main/java/cc/colorcat/mvi/MviCollects.kt`
**Lines**: 370-385 (reified overloads), 401-408 (KClass-based implementation)

```kotlin
// Line 382-385 — reified overload resolves to KClass at call site:
inline fun <reified A : E> collectTyped(
    state: Lifecycle.State,
    noinline block: suspend (A) -> Unit,
): Job = collectTyped(A::class, state, block)  // A::class inlined

// Line 401-408 — KClass-based filtering:
fun <A : E> collectTyped(
    clazz: KClass<A>,
    state: Lifecycle.State,
    block: suspend (A) -> Unit,
): Job {
    return flow
        .filter { clazz.isInstance(it) }       // Line 407
        .map { clazz.java.cast(it) as A }       // Line 408
        .launchWithLifecycle(owner, state, job, block)
}
```

**Risk mechanism**: `clazz.isInstance(it)` checks runtime class identity. If R8 eliminates an event subclass (deeming it "unused" because it's only referenced via `reified` or `KClass`), the filter never matches → **events silently lost**. The `A::class` reference is inlined as a concrete `KClass` literal by `inline fun`, so it reflects the original class name — but if R8 has removed that class, it's gone.

---

### M2. Diagnostic Name Uses `qualifiedName` — Obfuscation-Unsafe

**File**: `core/src/main/java/cc/colorcat/mvi/internal/InternalExtensions.kt`
**Line**: 57-58

```kotlin
internal val Mvi.Intent.diagnosticName: String
    get() = this::class.qualifiedName ?: this.javaClass.name
```

**Risk mechanism**: Under R8, both `qualifiedName` and `javaClass.name` return obfuscated names (e.g. `a.b`). While this is documented as "for logging only — not for persistence or serialization", it means debug logs become unreadable in release builds. This is primarily a debugging/maintenance concern, not a correctness issue.

---

## 🟢 SAFE / LOW-RISK ITEMS

| # | Location | Pattern | Verdict | Explanation |
|---|---|---|---|---|
| S1 | `InternalUtils.kt:37` | `javaClass.name` in `tagLabel` | **LOW** | Only used in diagnostic WARN logs — same as M2 |
| S2 | `IntentTransformers.kt:344` | `conflictIntentTypes.add(intent.javaClass)` | **SAFE** | Identity-based dedup set for one-time warnings only |
| S3 | `HandleStrategies.kt:237` | `GroupTagSelector.byClass()` returns `it.javaClass` | **SAFE** | Used as grouping key, not as name — identity-based |
| S4 | `HandleStrategies.kt` (KDoc line 207) | Docs mention "ProGuard/R8 class-name obfuscation" | **SAFE** | Already correctly documented as safe |
| S5 | No `@Keep` annotations anywhere | 0 occurrences | **INFO** | No current risk, but no guidance for consumers |
| S6 | No serialization | Gson/Moshi/kotlinx.serialization/Parcelable/Serializable | **NONE** | Not used |
| S7 | No META-INF / ServiceLoader | Not present | **NONE** | Not used |
| S8 | No JNI / native methods | Not present | **NONE** | Not used |
| S9 | 7 `fun interface` declarations | `IntentHandler`, `GroupTagSelector`, `IntentTransformer`, etc. | **NONE** | Lambda-converted at call sites — no reflective introspection |
| S10 | `HandleStrategy` enum | No `values()`/`valueOf()`/reflective name usage | **NONE** | Used via enum constant references, safe |
| S11 | `DispatchResult` sealed class | Not used in exhaustive `when` externally | **LOW** | Sealed class hierarchy safe under R8 |

---

## 1.4.1 Coverage

| Protection Need | Current Status | Required Action |
|---|---|---|
| `consumer-rules.pro` for library consumers | Active rules present | No release-blocking action |
| ViewBinding reflection in sample app | Removed | No action |
| Intent handler type-based dispatch | Uses `Class` identity; concrete intent subtypes kept by consumer rules | No release-blocking action |
| Event type filtering via `KClass.isInstance` | Concrete event subtypes kept by consumer rules | No release-blocking action |
| Logging / diagnostics class names | Documented as obfuscation-unstable | No action needed — intentional behavior |
| `@Keep` annotations | Zero present | Consider adding `@Keep` to sealed interface subtypes in sample |

---

## Current Consumer Rules

```pro
# K-MVI public API used by consumer projects.
-keep class cc.colorcat.mvi.** { *; }

# K-MVI resolves user-defined MVI types at runtime through exact Class/KClass identity.
# Keep these subtypes reachable when consumer apps enable R8 shrinking.
-keep class ** implements cc.colorcat.mvi.Mvi$Intent { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$Intent$Concurrent { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$Intent$Sequential { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$State { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$Event { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$PartialChange { *; }
```

---

## Implemented Fix: ViewBindingDelegate (sample app)

Replace the reflective reified helpers with delegate overloads that accept explicit generated factory
references. This keeps the binding method references visible to the compiler and avoids runtime method-name
lookup.

```kotlin
fun <T : ViewBinding> Fragment.viewBinding(factory: (View) -> T): ReadOnlyProperty<Fragment, T> {
    return FragmentViewBindingDelegate(factory)
}

fun <T : ViewBinding> Activity.viewBinding(factory: (LayoutInflater) -> T): ReadOnlyProperty<Activity, T> {
    return ActivityViewBindingDelegate(factory)
}

private val fragmentBinding by viewBinding(FragmentCounterBinding::bind)
private val activityBinding by viewBinding(ActivitySampleBinding::inflate)
```

---

## Source Code Reference Index

| File | Lines | Finding |
|---|---|---|
| `app/.../util/ViewBindingDelegate.kt` | Historical: 89-93, 127-130 | **H1** — Reflection on ViewBinding, fixed in 1.4.1 |
| `core/consumer-rules.pro` | 1-11 | **H2** — Consumer rules added in 1.4.1 |
| `core/proguard-rules.pro` | 1-21 (all comments) | **H2** — No library build rules |
| `core/.../IntentHandlers.kt` | 259, 350, 373, 400 | **H3** — Intent handler Class dispatch |
| `core/.../MviCollects.kt` | 401-408 | **M1** — Event type filtering |
| `core/.../internal/InternalExtensions.kt` | 57-58 | **M2** — Diagnostic name |
| `core/.../internal/InternalUtils.kt` | 37 | **S1** — Tag label |
| `core/.../IntentTransformers.kt` | 344 | **S2** — Conflict dedup |
| `core/.../HandleStrategies.kt` | 237 | **S3** — Group by class |
| `app/.../login/LoginViewModel.kt` | 80 | S3 usage |
| `app/.../dashboard/DashboardViewModel.kt` | 36 | S3 usage |

---

## Action Status

| Priority | Action | Status |
|---|---|---|
| **P0** | Add keep rules to `core/consumer-rules.pro` | Done |
| **P1** | Remove `ViewBindingDelegate` reflection | Done |
| **P1** | Refine consumer rules after minified consumer testing | Optional follow-up |
| **P2** | Add `@Keep` annotations to sample's sealed intent/event/state subtypes | Optional follow-up |
| **P3** | Document R8/ProGuard requirements in README | Done |
