# ProGuard / R8 Obfuscation Security Audit

**Project**: K-MVI (`cc.colorcat.mvi:core`)
**Date**: 2026-06-19
**Scope**: `core/` (library) + `app/` (sample) — all `.kt` source files
**Minification status**: Disabled in both modules (`isMinifyEnabled = false`)

---

## Executive Summary

The project currently has **zero** active ProGuard/R8 keep rules across both modules. While minification is disabled today, the library is published as an AAR with an **empty `consumer-rules.pro`**, meaning any downstream consumer that enables R8 will silently corrupt the library's type-based dispatch and event filtering. The sample app's `ViewBindingDelegate` uses reflection that will crash under R8.

**3 HIGH-risk, 2 MEDIUM-risk, 3 info items** found.

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

**Recommended fix**: Replace reflection with direct static method calls — the reified type parameter already makes the concrete class available at compile time:

```kotlin
inline fun <reified T : ViewBinding> bind(view: View): T = T::bind(view)
inline fun <reified T : ViewBinding> inflate(inflater: LayoutInflater): T = T::inflate(inflater)
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

**Recommended fix**: Add to `core/consumer-rules.pro`:

```pro
# Keep all MVI core public API — library consumers need these
-keep class cc.colorcat.mvi.** { *; }

# Keep all intent, state, event, and partial change subtypes
# (used by IntentHandlerRegistry and collectTyped filtering)
-keep class ** implements cc.colorcat.mvi.Mvi$Intent { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$State { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$Event { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$PartialChange { *; }

# Keep ViewBinding generated classes and their static factory methods
-keep class * implements androidx.viewbinding.ViewBinding {
    public static * bind(android.view.View);
    public static * inflate(android.view.LayoutInflater);
}
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

## Detailed Requirement vs. Actual Coverage

| Protection Need | Current Status | Required Action |
|---|---|---|
| `consumer-rules.pro` for library consumers | Empty file | Add keep rules for `cc.colorcat.mvi.**` and MVI marker hierarchy |
| ViewBinding reflection in sample app | Unprotected `getMethod("bind")` | Replace with `T::bind()` direct call or add `-keep` for ViewBinding types |
| Intent handler type-based dispatch | Uses `Class` identity (safe from renaming) | Add `-keep class ** implements Mvi$Intent` for consumer safety |
| Event type filtering via `KClass.isInstance` | Subject to R8 dead-class elimination | Annotate event subtypes with `@Keep` or add consumer rule |
| Logging / diagnostics class names | Documented as obfuscation-unstable | No action needed — intentional behavior |
| `@Keep` annotations | Zero present | Consider adding `@Keep` to sealed interface subtypes in sample |

---

## Recommended ProGuard Rules for `core/consumer-rules.pro`

```pro
# ──────────────────────────────────────────────────────────────────
# K-MVI Library — Consumer ProGuard / R8 Keep Rules
# ──────────────────────────────────────────────────────────────────

# Keep the complete public API surface of the library
-keep class cc.colorcat.mvi.** { *; }

# Keep all intent, state, event, and partial change subtypes.
# These are resolved at runtime via Class identity (IntentHandlerRegistry)
# and KClass.isInstance (collectTyped filtering).
-keep class ** extends cc.colorcat.mvi.Mvi$Intent { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$Intent { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$State { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$Event { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$PartialChange { *; }

# Keep ViewBinding classes and their required static factory methods
-keep class * implements androidx.viewbinding.ViewBinding {
    public static * bind(android.view.View);
    public static * inflate(android.view.LayoutInflater);
}
```

---

## Recommended Fix: ViewBindingDelegate (sample app)

Replace the reflective `getMethod` calls in `ViewBindingDelegate.kt` with direct method references:

```kotlin
// BEFORE (line 89-93):
inline fun <reified T : ViewBinding> bind(view: View): T {
    return T::class.java.getMethod("bind", View::class.java)
        .invoke(null, view) as T
}

// AFTER:
inline fun <reified T : ViewBinding> bind(view: View): T = T::bind(view)

// BEFORE (line 127-130):
inline fun <reified T : ViewBinding> inflate(inflater: LayoutInflater): T {
    return T::class.java.getMethod("inflate", LayoutInflater::class.java)
        .invoke(null, inflater) as T
}

// AFTER:
inline fun <reified T : ViewBinding> inflate(inflater: LayoutInflater): T = T::inflate(inflater)
```

---

## Source Code Reference Index

| File | Lines | Finding |
|---|---|---|
| `app/.../util/ViewBindingDelegate.kt` | 89-93, 127-130 | **H1** — Reflection on ViewBinding |
| `core/consumer-rules.pro` | 1 (empty) | **H2** — No consumer rules |
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

## Summary of Recommended Actions

| Priority | Action | Effort | Impact |
|---|---|---|---|
| **P0** | Add keep rules to `core/consumer-rules.pro` | 5 min | Prevents all consumer-side breakage |
| **P1** | Fix `ViewBindingDelegate` reflection → direct calls | 5 min | Prevents crash in sample app |
| **P1** | Refine consumer rules to be minimal (not `**`) | 15 min | Better for consumer's optimization |
| **P2** | Add `@Keep` annotations to sample's sealed intent/event/state subtypes | 5 min | Self-documenting guard |
| **P3** | Document R8/ProGuard requirements in README | 10 min | Developer guidance |
