# Phase 3 Implementation Spec: Aegis Debug Fix Pipeline (Deterministic Fixers, Fix Preview, Undo-Safe Apply)

**Version:** 1.0 (binding)
**Status:** Ready for immediate implementation
**Source of Truth:** `aegis_debug_true_v1_spec.md`
**Target Phase from Source Spec:** Section 20 — Phase 3 (Fix Pipeline), items 9–12
**Prior Phase:** `aegis_debug_phase_2_core_engine_spec.md` — must be merged before Phase 3 starts

---

## 1. Objective

Deliver the deterministic fix pipeline so that issues found by the static analyzers can be resolved with a single click:

1. **`Fixer` interface + `FixerRegistry`.** Define a `Fixer` SPI (analogous to `Analyzer`) and a `FixerRegistry` object that maps `ruleId → Fixer`. `FixerRegistry` is consulted first whenever a fix is requested; only if no deterministic fixer is registered for the issue's `ruleId` does the engine fall back to AI.
2. **Three deterministic fixers.** Ship one `Fixer` implementation per fixable rule: `NullSafetyFixer` (`AEG-NULL-001`), `StateInitFixer` (`AEG-STATE-001`), `AsyncFlowFixer` (`AEG-ASYNC-001`). The remaining two rules (`AEG-CYCLE-001`, `AEG-CPX-001`) have no safe text-level fix and are explicitly out of scope.
3. **`FixApplicator`.** A thin class that wraps `WriteCommandAction.runWriteCommandAction` and `Document.replaceString` to apply a `CodeFix` to the live editor document in an undo-safe, transactional write. Injectable via a `FixWriter` `fun interface` so the applicator's callers can be tested without bootstrapping the IntelliJ application.
4. **`APPLY_FIX` bridge event + `sendFixApplied`.** Extend `UIEvent` with `ApplyFixRequested(issueId, fixId)`, wire its parser, and add `JcefBridge.sendFixApplied(issueId)` so the webview can confirm the round-trip.
5. **`CodeFix` model extension.** Add `isDeterministic: Boolean = false` and `confidence: Double = 0.7` to `CodeFix` (additive, serialization-safe). Deterministic fixers produce `isDeterministic = true` and `confidence = 1.0`; AI-generated fixes keep the defaults.
6. **`GhostDebuggerService` wiring.** Update `handleFixRequested` to try `FixerRegistry` first, then fall through to the existing AI path. Add `handleApplyFixRequested` that calls `FixApplicator.apply`, saves the document, triggers `analyzeProject()`, and emits `sendFixApplied`.

Phase 3 ships **no** Dark Navy + Cream UI refresh, **no** IBM Plex font bundle, **no** engine-status pill, **no** Ollama service changes, and **no** new static analyzers. It delivers the full backend fix loop so that Phase 4 can add the visual polish without touching fix logic.

---

## 2. Scope

### 2.1 In Scope

| Area | Work |
|---|---|
| `Fixer` interface | `ruleId`, `description`, `canFix(issue)`, `generateFix(issue, fileContent)` |
| `FixerRegistry` | `object FixerRegistry` mapping `ruleId → Fixer`; `forIssue(issue)`, `all()` |
| `NullSafetyFixer` | Adds optional chaining (`?.`) on direct property access of a known-null variable (`AEG-NULL-001`, type `NULL_SAFETY`) |
| `StateInitFixer` | Changes `useState()` → `useState([])` at the declaration line for the uninitialized array state (`AEG-STATE-001`, type `STATE_BEFORE_INIT`) |
| `AsyncFlowFixer` | Appends `.catch(console.error)` to a bare `.then(...)` call (`AEG-ASYNC-001`, type `UNHANDLED_PROMISE` only) |
| `FixApplicator` | `FixWriter` `fun interface` + `FixApplicator(writer)` class; default `FixWriter.Default` uses `WriteCommandAction` + `Document.replaceString` |
| `CodeFix` extension | Add `isDeterministic: Boolean = false` and `confidence: Double = 0.7` (additive) |
| Bridge event | Add `UIEvent.ApplyFixRequested(issueId, fixId)`; update `UIEventParser` for `"APPLY_FIX"` |
| Bridge sender | `JcefBridge.sendFixApplied(issueId: String)` emitting `window.__aegis_debug__.onFixApplied(...)` |
| Service wiring | `handleFixRequested`: try `FixerRegistry` first, fall back to AI; `handleApplyFixRequested`: apply + re-analyze + notify |
| Testability | `FixerRegistry`, `Fixer` implementations, `UIEventParser`, and `CodeFix` extension are all pure — tested without IntelliJ platform. `FixApplicator` is injectable for future integration tests |
| Tests | 7 new test classes. All must pass |

### 2.2 Out of Scope (strict)

- `NullSafetyFixer` for the `STATE_BEFORE_INIT` sub-issue emitted by `NullSafetyAnalyzer` — only the `NULL_SAFETY` type is fixed by `NullSafetyFixer`.
- `AsyncFlowFixer` for `MISSING_ERROR_HANDLING` and `MEMORY_LEAK` issue types — only `UNHANDLED_PROMISE` is fixable deterministically at line granularity.
- Fixer for `AEG-CYCLE-001` (`CircularDependencyAnalyzer`) — architectural; no safe text-level fix.
- Fixer for `AEG-CPX-001` (`ComplexityAnalyzer`) — requires refactoring; no safe text-level fix.
- Visual diff viewer in the webview (Phase 4 UI refresh).
- Multi-file fix application (`MultiFileFix` model already exists; pipeline deferred to post-V1).
- AI fix generation changes — the existing `AIService.suggestFix` path is unchanged; Phase 3 only adds a deterministic fast-path *before* it.
- Fix caching / memoization (no phase target).
- Kotlin/Java file fixes — all three fixers target `{ts,tsx,js,jsx}` only, matching the analyzers.
- `AnalysisEngine`, `Analyzer` implementations, `AnalysisModels.kt` (except `CodeFix`), `AnalysisContextPrioritization.kt` — untouched.

---

## 3. Non-Goals

The following MUST NOT be touched by Phase 3:

1. Do **not** change `AnalysisEngine.kt` or the five analyzer classes.
2. Do **not** change `GhostDebuggerSettings.kt`, `GhostDebuggerConfigurable.kt`, or `ApiKeyManager.kt`.
3. Do **not** change the `Analyzer` interface or `FixerRegistry` alignment to `Analyzer` naming. `Fixer` is a new, parallel SPI.
4. Do **not** change `AnalysisResult`, `ProjectMetrics`, or the Phase 2 engine status types.
5. Do **not** rename or move `Issue`, `IssueType`, `IssueSource`, `EngineProvider`. Field additions to `CodeFix` are purely additive.
6. Do **not** introduce new Gradle dependencies.
7. Do **not** change `GhostDebuggerService.analyzeProject()` control flow beyond the new `handleApplyFixRequested` call at the bottom of `handleUIEvent`.
8. Do **not** change `@Storage("ghostdebugger.xml")` or any persisted-state field.
9. Do **not** change the Phase 1 or Phase 2 test classes. They continue to pass unchanged.
10. Do **not** add a new UI tool-window panel or settings page. Phase 3 is a data + backend layer.

---

## 4. Implementation Decisions

Derived from the repository state and binding for Phase 3:

| Decision | Value | Source |
|---|---|---|
| Primary language | Kotlin 2.0.21 | `build.gradle.kts:3` |
| JVM toolchain | Java 21 | `build.gradle.kts:46` |
| Write API | `WriteCommandAction.runWriteCommandAction(project, commandName, groupId, Runnable)` | IntelliJ Platform SDK |
| Document access | `FileDocumentManager.getInstance().getDocument(virtualFile)` | IntelliJ Platform SDK |
| File lookup | `LocalFileSystem.getInstance().findFileByPath(fix.filePath)` inside `runReadAction<VirtualFile?>` | IntelliJ Platform SDK |
| Document save | `FileDocumentManager.getInstance().saveDocument(document)` after write | IntelliJ Platform SDK |
| Undo group name | `"Apply Aegis Debug Fix"` | binding §5.1 |
| Kotlin package for fixers | `com.ghostdebugger.fix` | binding §5.2 |
| Fixer ruleIds in scope | `AEG-NULL-001`, `AEG-STATE-001`, `AEG-ASYNC-001` | analyzer `ruleId` values |
| Fixer ruleIds out of scope | `AEG-CYCLE-001`, `AEG-CPX-001` | binding §5.3 |
| `CodeFix.isDeterministic` default | `false` | existing AI fixes remain un-tagged without code changes |
| `CodeFix.confidence` default | `0.7` | matches Phase 2 AI-issue confidence default |
| Deterministic fix confidence | `1.0` | binding §5.4 |
| Re-analysis after apply | `analyzeProject()` called from `handleApplyFixRequested` on the service scope | binding §5.5 |
| AI fallback on no fixer | Existing `AIService.suggestFix` path unchanged | binding §5.6 |
| Test framework | JUnit 5 + MockK + `kotlinx-coroutines-test` | `build.gradle.kts:81` |

---

## 5. Implementation Decisions Made From Ambiguity

Binding for Phase 3. Deviation requires a new spec revision.

1. **`WriteCommandAction` command name is `"Apply Aegis Debug Fix"`** for all fix types. The name appears in IntelliJ's Undo dropdown. Binding: a single shared name avoids per-issue-type localization work deferred to post-V1.

2. **`Fixer` package is `com.ghostdebugger.fix`**, distinct from `com.ghostdebugger.analysis`. Reason: fixers are not analyzers; mixing them would blur the SPI boundary. Phase 4 does not require them to be co-located. Binding.

3. **`AEG-CYCLE-001` and `AEG-CPX-001` have no Phase 3 fixer.** `CircularDependencyAnalyzer` flags an architectural relationship between files — there is no single-file text change that safely removes a cycle. `ComplexityAnalyzer` flags a whole-file complexity score — decomposing a function requires semantic understanding. Binding: omitting a fixer for these rules is the correct choice.

4. **Deterministic fix `confidence = 1.0`, AI fix `confidence = 0.7`.** Matches Phase 2 binding decisions 29 and 30 (static issues = 1.0, AI issues = 0.7) for consistency. Binding.

5. **`analyzeProject()` is re-run after a fix is applied.** Reason: the applied text change invalidates the previous analysis result; re-running ensures the issue list is consistent. The re-run is launched on `scope` (no new coroutine context introduced). Binding.

6. **AI `suggestFix` fallback is kept unchanged.** `handleFixRequested` is extended to a two-step logic: `FixerRegistry.forIssue(issue)?.let { ... }` produces a deterministic fix and returns immediately; if null, the existing AI path executes. Binding: no behavior change for issues without a registered fixer.

7. **`NullSafetyFixer` targets only `IssueType.NULL_SAFETY` issues.** `NullSafetyAnalyzer` also emits `STATE_BEFORE_INIT` issues (the `.map()` on undefined state sub-case). Those are in `StateInitFixer`'s domain. Binding: one fixer per issue type per analyzer.

8. **`NullSafetyFixer` extracts `varName` from `issue.title`** using the regex `Regex("""Null reference: (\w+)\.""")`. The title format is deterministic: `"Null reference: $varName.may be null"` (see `NullSafetyAnalyzer.kt:76`). If the regex does not match, `generateFix` returns `null`. Binding: no silent mis-fix.

9. **`NullSafetyFixer` replaces on the flagged line only.** It reads the line at `issue.line - 1` from `fileContent.lines()`, replaces the first occurrence of `$varName.` (not preceded by `?`) with `$varName?.`, and returns a fix whose `lineStart = lineEnd = issue.line`. If no replaceable occurrence is found, returns `null`. Binding.

10. **`StateInitFixer` fixes the DECLARATION line, not the usage line.** The issue is reported at the usage line (where `.map()` is called), but the correct fix is at the `useState()` call. `StateInitFixer` extracts `varName` from `issue.title` (`"Uninitialized state used: $varName"` — `Regex("""Uninitialized state used: (\w+)""")`) then scans `fileContent` for the line matching `const [$varName,` with `useState()` and fixes it. If not found, returns `null`. Binding.

11. **`StateInitFixer` fix: `useState()` → `useState([])`.** The `STATE_BEFORE_INIT` issue type arises when state is initialized without an argument (= `undefined`) but used with array methods. The safe default is `[]`. Binding: an empty array is the minimal safe value for array methods. A future AI-augmented fix could infer a better default; this is deterministic only.

12. **`AsyncFlowFixer.canFix` is true only when `issue.type == IssueType.UNHANDLED_PROMISE`.** `AsyncFlowAnalyzer` also emits `MISSING_ERROR_HANDLING` and `MEMORY_LEAK`. Text-level fixes for those require multi-line restructuring. Binding.

13. **`AsyncFlowFixer` appends `.catch(console.error)` before the trailing `;` on the flagged line.** The pattern is: find the line at `issue.line - 1`, verify it contains `.then(` and ends with `);`, replace `);` with `).catch(console.error);`. If the pattern is not matched, returns `null`. Binding: produces the minimal safe handler — developers should replace `console.error` with real error handling post-apply.

14. **`CodeFix.fixedCode` for line-level fixes contains only the replacement line content** (no trailing newline). `FixApplicator` replaces `document.getLineStartOffset(fix.lineStart - 1)` to `document.getLineEndOffset(fix.lineEnd - 1)` — those offsets already exclude the line separator. Binding.

15. **`FixApplicator` reads the `VirtualFile` inside `ApplicationManager.getApplication().runReadAction<VirtualFile?>`** and performs the `WriteCommandAction` outside it. Reason: `findFileByPath` requires a read action; `WriteCommandAction` starts its own write lock and must not be nested inside a read action. Binding.

16. **`FixWriter.Default` is an `object` implementing `FixWriter`**, not a lambda. Reason: `object` can hold the `val log` and `FileDocumentManager` reference cleanly. Tests inject `FixWriter { fix, _ -> /* stub */ }` via the SAM conversion. Binding.

17. **`JcefBridge.sendFixApplied(issueId: String)` emits `window.__aegis_debug__.onFixApplied({issueId})`.** The payload is a JSON object `{"issueId":"..."}` serialized via the existing `json` field on `JcefBridge`. Binding.

18. **`UIEvent.ApplyFixRequested` carries `issueId` and `fixId`.** `fixId` is the `CodeFix.id` so the webview can correlate the fix it showed. Both are `String`. The parser reads `payload.issueId` and `payload.fixId`. If either is absent, defaults to `""`. Binding.

19. **`handleApplyFixRequested` runs on `Dispatchers.Default` (the existing `scope`)**, not on the Swing thread. The `WriteCommandAction` itself dispatches to the EDT internally. `sendFixApplied` is wrapped in `withContext(Dispatchers.Swing)`. Binding.

20. **`generateFix` is a pure function** — it takes `fileContent: String` as input and never reads from disk or from IntelliJ APIs. This makes every `Fixer` implementation fully unit-testable without IntelliJ platform bootstrapping. Binding.

21. **`FixerContractTest` asserts all three registered fixers produce a non-null fix on their respective positive fixtures.** Analogous to `AnalyzerContractTest`. Binding.

22. **`CodeFix.isDeterministic` and `CodeFix.confidence` are `@Serializable`-safe.** Both have default values (`false` and `0.7` respectively) and use primitive types. Existing `@Serializable` annotation on `CodeFix` continues to cover the new fields. Binding.

23. **`FixerRegistry.forIssue` returns `null` when `issue.ruleId` is null.** A null `ruleId` means the engine did not backfill provenance (possible for issues from an analyzer that never sets `ruleId` and where the Phase 2 engine backfill has a bug). Returning `null` causes graceful AI fallback. Binding.

24. **No new top-level Gradle modules or source sets.** The `fix/` package lives under the existing `src/main/kotlin/com/ghostdebugger/` tree. Binding.

---

## 6. Files to Create or Modify

### 6.1 Create

| Path | Purpose |
|---|---|
| `src/main/kotlin/com/ghostdebugger/fix/Fixer.kt` | `Fixer` interface: `ruleId`, `description`, `canFix`, `generateFix` |
| `src/main/kotlin/com/ghostdebugger/fix/FixerRegistry.kt` | `object FixerRegistry` with `forIssue` and `all` |
| `src/main/kotlin/com/ghostdebugger/fix/NullSafetyFixer.kt` | Deterministic fixer for `AEG-NULL-001` |
| `src/main/kotlin/com/ghostdebugger/fix/StateInitFixer.kt` | Deterministic fixer for `AEG-STATE-001` |
| `src/main/kotlin/com/ghostdebugger/fix/AsyncFlowFixer.kt` | Deterministic fixer for `AEG-ASYNC-001` (UNHANDLED_PROMISE only) |
| `src/main/kotlin/com/ghostdebugger/fix/FixApplicator.kt` | `FixWriter fun interface` + `FixApplicator` class + `FixWriter.Default` |
| `src/test/kotlin/com/ghostdebugger/fix/FixerContractTest.kt` | Each registered fixer has non-blank `ruleId`, non-blank `description`, and returns non-null on its positive fixture |
| `src/test/kotlin/com/ghostdebugger/fix/NullSafetyFixerTest.kt` | Positive (produces optional-chaining fix), negative (already safe), title-parse failure (returns null) |
| `src/test/kotlin/com/ghostdebugger/fix/StateInitFixerTest.kt` | Positive (fixes declaration line), negative (declaration not found returns null) |
| `src/test/kotlin/com/ghostdebugger/fix/AsyncFlowFixerTest.kt` | Positive (`UNHANDLED_PROMISE` → appends `.catch`), `canFix` false for `MISSING_ERROR_HANDLING` and `MEMORY_LEAK` |
| `src/test/kotlin/com/ghostdebugger/fix/FixerRegistryTest.kt` | `forIssue` returns correct fixer for known ruleId, null for unknown, null when `ruleId` is null; `all()` returns 3 entries |
| `src/test/kotlin/com/ghostdebugger/bridge/ApplyFixEventTest.kt` | `UIEventParser.parse` correctly decodes `APPLY_FIX` payload into `ApplyFixRequested` |
| `src/test/kotlin/com/ghostdebugger/model/CodeFixExtensionTest.kt` | `isDeterministic` defaults to `false`; `confidence` defaults to `0.7`; round-trips through `Json.encodeToString` / `decodeFromString` |

### 6.2 Modify

| Path | Change summary |
|---|---|
| `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt` | Add `isDeterministic: Boolean = false` and `confidence: Double = 0.7` to `data class CodeFix`. |
| `src/main/kotlin/com/ghostdebugger/bridge/UIEvent.kt` | Add `data class ApplyFixRequested(val issueId: String, val fixId: String) : UIEvent()`. Update `UIEventParser.parse` to handle `"APPLY_FIX"`. |
| `src/main/kotlin/com/ghostdebugger/bridge/JcefBridge.kt` | Add `fun sendFixApplied(issueId: String)`. |
| `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` | 1. Add `private val fixApplicator = FixApplicator()` field. 2. Update `handleFixRequested` to consult `FixerRegistry` first. 3. Add `handleApplyFixRequested(issueId, fixId)` private fun. 4. Dispatch `ApplyFixRequested` in `handleUIEvent`. |

### 6.3 Leave Untouched

- Every file under `src/main/kotlin/com/ghostdebugger/analysis/` (engine, analyzers, context prioritization, `AiPassRunner`, `Analyzer`).
- `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt` — except the two new fields on `CodeFix`.
- `src/main/kotlin/com/ghostdebugger/model/EngineStatus.kt`.
- `src/main/kotlin/com/ghostdebugger/settings/`.
- `src/main/kotlin/com/ghostdebugger/ai/`.
- `src/main/kotlin/com/ghostdebugger/graph/`, `parser/`, `annotator/`, `toolwindow/`, `actions/`, `ReportGenerator.kt`.
- All webview files.
- All `plugin.xml`, `build.gradle.kts`.
- All Phase 1 and Phase 2 test classes.

---

## 7. Data Contracts / Interfaces / Schemas

### 7.1 `Fixer.kt` — complete contents

```kotlin
package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue

interface Fixer {
    /** Must equal the `ruleId` of the corresponding `Analyzer`. */
    val ruleId: String

    /** One-sentence description of the transformation this fixer applies. */
    val description: String

    /**
     * Returns true if this fixer can produce a deterministic fix for [issue].
     * The default implementation checks [issue.ruleId]; override to further
     * restrict by issue type when one analyzer emits multiple issue types.
     */
    fun canFix(issue: Issue): Boolean = issue.ruleId == ruleId

    /**
     * Generates a deterministic [CodeFix] for [issue] using [fileContent] as the
     * full source text of the file at [issue.filePath].
     * Returns null if the fix cannot be safely derived (pattern not found,
     * line out of range, etc.). A null return causes the caller to fall back to AI.
     */
    fun generateFix(issue: Issue, fileContent: String): CodeFix?
}
```

### 7.2 `FixerRegistry.kt` — complete contents

```kotlin
package com.ghostdebugger.fix

import com.ghostdebugger.model.Issue

object FixerRegistry {
    private val fixers: Map<String, Fixer> = listOf(
        NullSafetyFixer(),
        StateInitFixer(),
        AsyncFlowFixer()
    ).associateBy { it.ruleId }

    /**
     * Returns the [Fixer] registered for [issue.ruleId], or null when no
     * deterministic fixer is available (unknown rule, null ruleId, or the
     * fixer's [canFix] returns false for this specific issue instance).
     */
    fun forIssue(issue: Issue): Fixer? {
        val ruleId = issue.ruleId ?: return null
        val fixer = fixers[ruleId] ?: return null
        return if (fixer.canFix(issue)) fixer else null
    }

    /** Returns all registered fixers. Used by [FixerContractTest]. */
    fun all(): List<Fixer> = fixers.values.toList()
}
```

### 7.3 `NullSafetyFixer.kt` — complete contents

```kotlin
package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueType
import java.util.UUID

class NullSafetyFixer : Fixer {
    override val ruleId = "AEG-NULL-001"
    override val description =
        "Replaces direct property access on a known-null variable with optional chaining (?.)."

    private val titleVarRegex = Regex("""Null reference: (\w+)\.""")

    override fun canFix(issue: Issue): Boolean =
        issue.ruleId == ruleId && issue.type == IssueType.NULL_SAFETY

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? {
        val varName = titleVarRegex.find(issue.title)?.groupValues?.get(1) ?: return null
        val lines = fileContent.lines()
        val lineIndex = issue.line - 1
        if (lineIndex < 0 || lineIndex >= lines.size) return null
        val original = lines[lineIndex]

        // Replace first occurrence of `varName.x` that is not already optional-chained.
        val safeRegex = Regex("""(?<!\?)(\b${Regex.escape(varName)}\.)""")
        if (!safeRegex.containsMatchIn(original)) return null
        val fixed = safeRegex.replaceFirst(original, "$1".replace(".", "?."))
            .let { original.replace(Regex("""(?<!\?)(\b${Regex.escape(varName)})\."""), "$1?.") }

        if (fixed == original) return null

        return CodeFix(
            id = UUID.randomUUID().toString(),
            issueId = issue.id,
            description = "Add optional chaining: $varName. → $varName?.",
            originalCode = original,
            fixedCode = fixed,
            filePath = issue.filePath,
            lineStart = issue.line,
            lineEnd = issue.line,
            isDeterministic = true,
            confidence = 1.0
        )
    }
}
```

### 7.4 `StateInitFixer.kt` — complete contents

```kotlin
package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueType
import java.util.UUID

class StateInitFixer : Fixer {
    override val ruleId = "AEG-STATE-001"
    override val description =
        "Changes useState() to useState([]) for state variables used with array methods."

    private val titleVarRegex = Regex("""Uninitialized state used: (\w+)""")

    override fun canFix(issue: Issue): Boolean =
        issue.ruleId == ruleId && issue.type == IssueType.STATE_BEFORE_INIT

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? {
        val varName = titleVarRegex.find(issue.title)?.groupValues?.get(1) ?: return null
        val lines = fileContent.lines()

        // Find the useState() declaration for this variable.
        val declRegex = Regex("""const\s+\[\s*${Regex.escape(varName)}\s*,""")
        val declIndex = lines.indexOfFirst {
            declRegex.containsMatchIn(it) && it.contains("useState()")
        }
        if (declIndex < 0) return null

        val original = lines[declIndex]
        val fixed = original.replace("useState()", "useState([])")
        if (fixed == original) return null

        return CodeFix(
            id = UUID.randomUUID().toString(),
            issueId = issue.id,
            description = "Initialize $varName as an empty array: useState() → useState([])",
            originalCode = original,
            fixedCode = fixed,
            filePath = issue.filePath,
            lineStart = declIndex + 1,
            lineEnd = declIndex + 1,
            isDeterministic = true,
            confidence = 1.0
        )
    }
}
```

### 7.5 `AsyncFlowFixer.kt` — complete contents

```kotlin
package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueType
import java.util.UUID

class AsyncFlowFixer : Fixer {
    override val ruleId = "AEG-ASYNC-001"
    override val description =
        "Appends .catch(console.error) to a Promise chain that is missing an error handler."

    override fun canFix(issue: Issue): Boolean =
        issue.ruleId == ruleId && issue.type == IssueType.UNHANDLED_PROMISE

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? {
        if (!canFix(issue)) return null
        val lines = fileContent.lines()
        val lineIndex = issue.line - 1
        if (lineIndex < 0 || lineIndex >= lines.size) return null
        val original = lines[lineIndex]

        // Pattern: line contains .then(...) and ends with ); after trimming.
        if (!original.contains(".then(") || !original.trimEnd().endsWith(");")) return null
        val fixed = original.trimEnd().dropLast(1) + ".catch(console.error);"

        if (fixed == original.trimEnd()) return null

        return CodeFix(
            id = UUID.randomUUID().toString(),
            issueId = issue.id,
            description = "Add .catch(console.error) to unhandled Promise chain.",
            originalCode = original,
            fixedCode = fixed,
            filePath = issue.filePath,
            lineStart = issue.line,
            lineEnd = issue.line,
            isDeterministic = true,
            confidence = 1.0
        )
    }
}
```

### 7.6 `FixApplicator.kt` — complete contents

```kotlin
package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

fun interface FixWriter {
    fun write(fix: CodeFix, project: Project): Boolean

    companion object {
        val Default = FixWriter { fix, project ->
            val log = logger<FixWriter>()
            try {
                val vf = ApplicationManager.getApplication()
                    .runReadAction<com.intellij.openapi.vfs.VirtualFile?> {
                        LocalFileSystem.getInstance().findFileByPath(fix.filePath)
                    } ?: return@FixWriter false

                val fdm = FileDocumentManager.getInstance()
                val document = ApplicationManager.getApplication()
                    .runReadAction<com.intellij.openapi.editor.Document?> {
                        fdm.getDocument(vf)
                    } ?: return@FixWriter false

                WriteCommandAction.runWriteCommandAction(project, "Apply Aegis Debug Fix", null, Runnable {
                    val startOffset = document.getLineStartOffset(fix.lineStart - 1)
                    val endOffset = document.getLineEndOffset(fix.lineEnd - 1)
                    document.replaceString(startOffset, endOffset, fix.fixedCode)
                    fdm.saveDocument(document)
                })
                true
            } catch (e: Exception) {
                log.warn("FixWriter.Default failed for issue ${fix.issueId}: ${e.message}", e)
                false
            }
        }
    }
}

class FixApplicator(private val writer: FixWriter = FixWriter.Default) {
    fun apply(fix: CodeFix, project: Project): Boolean = writer.write(fix, project)
}
```

### 7.7 `AnalysisModels.kt` — delta on `CodeFix`

Add two fields with defaults to `data class CodeFix`:

```kotlin
@Serializable
data class CodeFix(
    val id: String,
    val issueId: String,
    val description: String,
    val originalCode: String,
    val fixedCode: String,
    val filePath: String,
    val lineStart: Int,
    val lineEnd: Int,
    val isDeterministic: Boolean = false,
    val confidence: Double = 0.7
)
```

**Binding invariants:**
- Existing consumers of `CodeFix` that do not supply `isDeterministic` or `confidence` continue to compile: both fields have default values.
- `@Serializable` is already on `CodeFix`. The new fields use primitive types with defaults, so existing JSON payloads that omit these fields continue to deserialize without error.

### 7.8 `UIEvent.kt` — delta

Add `ApplyFixRequested` to the sealed class and its parser branch:

```kotlin
// Inside sealed class UIEvent:
data class ApplyFixRequested(val issueId: String, val fixId: String) : UIEvent()

// Inside UIEventParser.parse when block:
"APPLY_FIX" -> UIEvent.ApplyFixRequested(
    issueId = envelope.payload?.get("issueId")?.jsonPrimitive?.content ?: "",
    fixId = envelope.payload?.get("fixId")?.jsonPrimitive?.content ?: ""
)
```

### 7.9 `JcefBridge.kt` — new method

```kotlin
fun sendFixApplied(issueId: String) {
    val payload = json.encodeToString(mapOf("issueId" to issueId))
    executeJS("window.__aegis_debug__ && window.__aegis_debug__.onFixApplied($payload)")
}
```

### 7.10 `GhostDebuggerService.kt` — delta

**New field** (at class body, alongside existing `private var bridge`):

```kotlin
private val fixApplicator = FixApplicator()
```

**Updated `handleUIEvent`** — add the new dispatch case alongside existing cases:

```kotlin
is UIEvent.ApplyFixRequested -> handleApplyFixRequested(event.issueId, event.fixId)
```

**Updated `handleFixRequested`** — prepend the deterministic path:

```kotlin
private fun handleFixRequested(issueId: String, nodeId: String) {
    val issue = currentIssues.firstOrNull { it.id == issueId }
        ?: currentIssues.firstOrNull { nodeId.contains(it.filePath.substringAfterLast("/")) }
        ?: return

    // 1. Try deterministic fixer first.
    val fixer = FixerRegistry.forIssue(issue)
    if (fixer != null) {
        val fileContent = try {
            java.io.File(issue.filePath).readText()
        } catch (e: Exception) {
            log.warn("Could not read file for deterministic fix: ${issue.filePath}", e)
            null
        }
        val deterministicFix = fileContent?.let { fixer.generateFix(issue, it) }
        if (deterministicFix != null) {
            scope.launch(Dispatchers.Swing) {
                bridge?.sendFixSuggestion(deterministicFix)
            }
            return
        }
    }

    // 2. Fall back to AI.
    scope.launch {
        try {
            val apiKey = ApiKeyManager.getApiKey() ?: run {
                withContext(Dispatchers.Swing) {
                    bridge?.sendError("OpenAI API key not configured. Go to Settings → Tools → Aegis Debug")
                }
                return@launch
            }
            val aiService = openAIService ?: OpenAIService(apiKey).also { openAIService = it }
            val fix = aiService.suggestFix(issue, issue.codeSnippet)
            withContext(Dispatchers.Swing) {
                bridge?.sendFixSuggestion(fix)
            }
        } catch (e: Exception) {
            log.error("Failed to generate fix suggestion", e)
            withContext(Dispatchers.Swing) {
                bridge?.sendError("Error generating fix: ${e.message}")
            }
        }
    }
}
```

**New `handleApplyFixRequested`**:

```kotlin
private fun handleApplyFixRequested(issueId: String, fixId: String) {
    val issue = currentIssues.firstOrNull { it.id == issueId } ?: run {
        log.warn("ApplyFix: no issue with id $issueId in currentIssues")
        return
    }

    // Rebuild the deterministic fix on-the-fly from current file content.
    // This avoids holding a stale CodeFix object in memory between preview and apply.
    val fixer = FixerRegistry.forIssue(issue)
    val fix = if (fixer != null) {
        try {
            val content = java.io.File(issue.filePath).readText()
            fixer.generateFix(issue, content)
        } catch (e: Exception) {
            log.warn("Could not re-derive fix for issue $issueId: ${e.message}", e)
            null
        }
    } else {
        // No deterministic fixer: the webview should not have sent APPLY_FIX for this issue,
        // but guard anyway.
        log.warn("ApplyFix requested for issue $issueId but no deterministic fixer registered.")
        null
    }

    if (fix == null) {
        scope.launch(Dispatchers.Swing) {
            bridge?.sendError("Could not apply fix: fix could not be derived for issue $issueId.")
        }
        return
    }

    scope.launch {
        val applied = fixApplicator.apply(fix, project)
        if (applied) {
            withContext(Dispatchers.Swing) {
                bridge?.sendFixApplied(issueId)
            }
            analyzeProject()
        } else {
            withContext(Dispatchers.Swing) {
                bridge?.sendError("Fix application failed for issue $issueId.")
            }
        }
    }
}
```

---

## 8. Ordered Implementation Steps

Execute in order. Each step must leave the project compilable and all previously-introduced tests green.

### Step 0 — Baseline
1. `./gradlew build` must pass with all Phase 1 and Phase 2 tests green.

### Step 1 — Model extension
1. Open `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt`.
2. Add `isDeterministic: Boolean = false` and `confidence: Double = 0.7` as the last two fields of `data class CodeFix` (§7.7).
3. `./gradlew compileKotlin` — must pass. Existing call sites that construct `CodeFix(...)` positionally must be reviewed: since these are new fields appended at the end with defaults, named-argument construction is unaffected; positional construction with all eight original fields continues to compile.

### Step 2 — `Fixer` interface + `FixerRegistry`
1. Create `src/main/kotlin/com/ghostdebugger/fix/Fixer.kt` (§7.1).
2. Create placeholder stub files for `NullSafetyFixer`, `StateInitFixer`, `AsyncFlowFixer` in `src/main/kotlin/com/ghostdebugger/fix/` — each an empty class implementing `Fixer` with `TODO()` bodies, just enough to compile.
3. Create `src/main/kotlin/com/ghostdebugger/fix/FixerRegistry.kt` (§7.2).
4. `./gradlew compileKotlin` — must pass.

### Step 3 — `NullSafetyFixer` + tests
1. Replace the `NullSafetyFixer` stub with §7.3.
2. Create `src/test/kotlin/com/ghostdebugger/fix/NullSafetyFixerTest.kt` (see §9.1 for test cases).
3. `./gradlew test --tests "com.ghostdebugger.fix.NullSafetyFixerTest"` — green.

### Step 4 — `StateInitFixer` + tests
1. Replace the `StateInitFixer` stub with §7.4.
2. Create `src/test/kotlin/com/ghostdebugger/fix/StateInitFixerTest.kt`.
3. `./gradlew test --tests "com.ghostdebugger.fix.StateInitFixerTest"` — green.

### Step 5 — `AsyncFlowFixer` + tests
1. Replace the `AsyncFlowFixer` stub with §7.5.
2. Create `src/test/kotlin/com/ghostdebugger/fix/AsyncFlowFixerTest.kt`.
3. `./gradlew test --tests "com.ghostdebugger.fix.AsyncFlowFixerTest"` — green.

### Step 6 — `FixerContractTest` + `FixerRegistryTest`
1. Create `src/test/kotlin/com/ghostdebugger/fix/FixerContractTest.kt`.
2. Create `src/test/kotlin/com/ghostdebugger/fix/FixerRegistryTest.kt`.
3. `./gradlew test --tests "com.ghostdebugger.fix.*"` — all five fix tests green.

### Step 7 — `FixApplicator`
1. Create `src/main/kotlin/com/ghostdebugger/fix/FixApplicator.kt` (§7.6).
2. `./gradlew compileKotlin` — must pass.
3. No unit test for `FixApplicator` in Phase 3 (requires IntelliJ application context; covered by sandbox smoke test in Step 11).

### Step 8 — Bridge events
1. Modify `src/main/kotlin/com/ghostdebugger/bridge/UIEvent.kt` (§7.8).
2. Modify `src/main/kotlin/com/ghostdebugger/bridge/JcefBridge.kt` (§7.9).
3. Create `src/test/kotlin/com/ghostdebugger/bridge/ApplyFixEventTest.kt`.
4. `./gradlew test --tests "com.ghostdebugger.bridge.ApplyFixEventTest"` — green.

### Step 9 — `CodeFixExtensionTest`
1. Create `src/test/kotlin/com/ghostdebugger/model/CodeFixExtensionTest.kt`.
2. `./gradlew test --tests "com.ghostdebugger.model.CodeFixExtensionTest"` — green.

### Step 10 — `GhostDebuggerService` wiring
1. Modify `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` (§7.10):
   - Add import: `import com.ghostdebugger.fix.FixApplicator`, `import com.ghostdebugger.fix.FixerRegistry`.
   - Add `private val fixApplicator = FixApplicator()`.
   - Update `handleUIEvent` to dispatch `ApplyFixRequested`.
   - Replace `handleFixRequested` body with the two-step logic.
   - Add `handleApplyFixRequested`.
2. `./gradlew compileKotlin` — must pass.

### Step 11 — Full test suite
1. `./gradlew test`
2. All Phase 1 + Phase 2 + Phase 3 tests must pass.

### Step 12 — Full build + sandbox smoke
1. `./gradlew clean build` → green.
2. `./gradlew buildPlugin` → zip built.
3. `./gradlew runIde` → sandbox opens.
4. Open a `.tsx` file containing `const [user, setUser] = useState(null); return <div>{user.name}</div>;`. Run Analyze. Confirm the `NULL_SAFETY` issue appears. Click Fix. Confirm `idea.log` shows the deterministic path was taken (no OpenAI call). Confirm the webview receives a `sendFixSuggestion` payload where `isDeterministic: true`.
5. In the webview, click Apply Fix (if the webview already has an Apply button wired to `APPLY_FIX`). Confirm `idea.log` shows "sendFixApplied" was emitted and the file was saved. Confirm `user.name` → `user?.name` in the file. Confirm Undo (Ctrl+Z) reverts the change.
6. With `AIProvider = NONE` and no fix available (e.g., `AEG-CYCLE-001` issue), click Fix. Confirm `idea.log` shows the AI path was attempted or the AI-absent error was shown. Confirm no crash.

---

## 9. Business Rules and Edge Cases

### 9.1 Test cases per fixer

#### `NullSafetyFixerTest`

**Positive — basic optional-chaining fix:**
```kotlin
@Test
fun `generates optional chaining fix for direct property access on null var`() {
    val fixer = NullSafetyFixer()
    val content = """
        function render() {
          const [user, setUser] = useState(null);
          return <div>{user.name}</div>;
        }
    """.trimIndent()
    val issue = Issue(
        id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
        title = "Null reference: user.may be null",
        description = "user may be null",
        filePath = "/src/A.tsx", line = 3,
        ruleId = "AEG-NULL-001"
    )
    val fix = fixer.generateFix(issue, content)
    assertNotNull(fix)
    assertTrue(fix!!.fixedCode.contains("user?.name"))
    assertFalse(fix.fixedCode.contains("user.name"))
    assertEquals(true, fix.isDeterministic)
    assertEquals(1.0, fix.confidence)
}
```

**Negative — already uses optional chaining:**
```kotlin
@Test
fun `returns null when access is already optional-chained`() {
    val fixer = NullSafetyFixer()
    val content = """
        function render() {
          const [user, setUser] = useState(null);
          return <div>{user?.name}</div>;
        }
    """.trimIndent()
    val issue = Issue(
        id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
        title = "Null reference: user.may be null",
        description = "user may be null",
        filePath = "/src/A.tsx", line = 3,
        ruleId = "AEG-NULL-001"
    )
    assertNull(fixer.generateFix(issue, content))
}
```

**Title parse failure — returns null:**
```kotlin
@Test
fun `returns null when title does not match expected pattern`() {
    val fixer = NullSafetyFixer()
    val issue = Issue(
        id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
        title = "Something completely different",
        description = "", filePath = "/src/A.tsx", line = 1,
        ruleId = "AEG-NULL-001"
    )
    assertNull(fixer.generateFix(issue, "const x = 1;"))
}
```

#### `StateInitFixerTest`

**Positive — fixes declaration line:**
```kotlin
@Test
fun `generates fix at declaration line, not usage line`() {
    val fixer = StateInitFixer()
    val content = """
        function App() {
          const [items, setItems] = useState();
          return <ul>{items.map(i => <li key={i}>{i}</li>)}</ul>;
        }
    """.trimIndent()
    val issue = Issue(
        id = "i1", type = IssueType.STATE_BEFORE_INIT, severity = IssueSeverity.ERROR,
        title = "Uninitialized state used: items",
        description = "items is undefined",
        filePath = "/src/App.tsx", line = 3,   // usage line
        ruleId = "AEG-STATE-001"
    )
    val fix = fixer.generateFix(issue, content)
    assertNotNull(fix)
    assertEquals(2, fix!!.lineStart)   // declaration is line 2
    assertTrue(fix.fixedCode.contains("useState([])"))
    assertFalse(fix.fixedCode.contains("useState()"))
    assertEquals(true, fix.isDeterministic)
}
```

**Negative — declaration not found:**
```kotlin
@Test
fun `returns null when useState declaration cannot be found`() {
    val fixer = StateInitFixer()
    val issue = Issue(
        id = "i1", type = IssueType.STATE_BEFORE_INIT, severity = IssueSeverity.ERROR,
        title = "Uninitialized state used: items",
        description = "", filePath = "/src/App.tsx", line = 3,
        ruleId = "AEG-STATE-001"
    )
    // File content has no matching useState() declaration for "items".
    assertNull(fixer.generateFix(issue, "const x = 1;"))
}
```

#### `AsyncFlowFixerTest`

**Positive — appends `.catch`:**
```kotlin
@Test
fun `appends catch handler to bare then chain`() {
    val fixer = AsyncFlowFixer()
    val content = "  fetchUser().then(u => setUser(u));\n"
    val issue = Issue(
        id = "i1", type = IssueType.UNHANDLED_PROMISE, severity = IssueSeverity.ERROR,
        title = "Unhandled promise rejection",
        description = "", filePath = "/src/A.tsx", line = 1,
        ruleId = "AEG-ASYNC-001"
    )
    val fix = fixer.generateFix(issue, content)
    assertNotNull(fix)
    assertTrue(fix!!.fixedCode.contains(".catch(console.error)"))
    assertEquals(true, fix.isDeterministic)
}
```

**`canFix` false for non-UNHANDLED_PROMISE types:**
```kotlin
@Test
fun `canFix returns false for MISSING_ERROR_HANDLING`() {
    val fixer = AsyncFlowFixer()
    val issue = Issue(
        id = "i1", type = IssueType.MISSING_ERROR_HANDLING, severity = IssueSeverity.ERROR,
        title = "Missing error handling", description = "",
        filePath = "/src/A.tsx", line = 1, ruleId = "AEG-ASYNC-001"
    )
    assertFalse(fixer.canFix(issue))
}

@Test
fun `canFix returns false for MEMORY_LEAK`() {
    val fixer = AsyncFlowFixer()
    val issue = Issue(
        id = "i1", type = IssueType.MEMORY_LEAK, severity = IssueSeverity.WARNING,
        title = "Memory leak", description = "",
        filePath = "/src/A.tsx", line = 1, ruleId = "AEG-ASYNC-001"
    )
    assertFalse(fixer.canFix(issue))
}
```

### 9.2 `FixerContractTest` assertions

```kotlin
class FixerContractTest {
    @Test
    fun `all registered fixers have non-blank ruleId and description`() {
        for (fixer in FixerRegistry.all()) {
            assertTrue(fixer.ruleId.isNotBlank(),
                "${fixer::class.simpleName} has blank ruleId")
            assertTrue(fixer.description.isNotBlank(),
                "${fixer::class.simpleName} has blank description")
        }
    }

    @Test
    fun `NullSafetyFixer generates a non-null fix on its positive fixture`() {
        val fixer = FixerRegistry.all().first { it.ruleId == "AEG-NULL-001" }
        // Minimal fixture that triggers NullSafetyAnalyzer.
        val content = "const [u, setU] = useState(null);\nreturn <div>{u.name}</div>;"
        val issue = Issue(
            id = "c1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Null reference: u.may be null", description = "",
            filePath = "/a.tsx", line = 2, ruleId = "AEG-NULL-001"
        )
        assertNotNull(fixer.generateFix(issue, content))
    }

    @Test
    fun `StateInitFixer generates a non-null fix on its positive fixture`() {
        val fixer = FixerRegistry.all().first { it.ruleId == "AEG-STATE-001" }
        val content = "const [items, setItems] = useState();\nitems.map(i => i);"
        val issue = Issue(
            id = "c2", type = IssueType.STATE_BEFORE_INIT, severity = IssueSeverity.ERROR,
            title = "Uninitialized state used: items", description = "",
            filePath = "/a.tsx", line = 2, ruleId = "AEG-STATE-001"
        )
        assertNotNull(fixer.generateFix(issue, content))
    }

    @Test
    fun `AsyncFlowFixer generates a non-null fix on its positive fixture`() {
        val fixer = FixerRegistry.all().first { it.ruleId == "AEG-ASYNC-001" }
        val content = "fetchUser().then(u => setUser(u));"
        val issue = Issue(
            id = "c3", type = IssueType.UNHANDLED_PROMISE, severity = IssueSeverity.ERROR,
            title = "Unhandled promise rejection", description = "",
            filePath = "/a.tsx", line = 1, ruleId = "AEG-ASYNC-001"
        )
        assertNotNull(fixer.generateFix(issue, content))
    }
}
```

### 9.3 `FixerRegistryTest` assertions

```kotlin
class FixerRegistryTest {
    @Test fun `all() returns exactly 3 entries`() { assertEquals(3, FixerRegistry.all().size) }

    @Test fun `forIssue returns fixer for known ruleId and correct type`() {
        val issue = Issue(
            id = "r1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Null reference: x.may be null", description = "",
            filePath = "/a.tsx", line = 1, ruleId = "AEG-NULL-001"
        )
        assertNotNull(FixerRegistry.forIssue(issue))
    }

    @Test fun `forIssue returns null for unknown ruleId`() {
        val issue = Issue(
            id = "r2", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
            title = "High complexity", description = "",
            filePath = "/a.kt", line = 1, ruleId = "AEG-CPX-001"
        )
        assertNull(FixerRegistry.forIssue(issue))
    }

    @Test fun `forIssue returns null when issue ruleId is null`() {
        val issue = Issue(
            id = "r3", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Null reference: x.may be null", description = "",
            filePath = "/a.tsx", line = 1, ruleId = null
        )
        assertNull(FixerRegistry.forIssue(issue))
    }

    @Test fun `forIssue returns null when canFix is false for the issue type`() {
        // AEG-ASYNC-001 is registered but only canFix UNHANDLED_PROMISE.
        val issue = Issue(
            id = "r4", type = IssueType.MEMORY_LEAK, severity = IssueSeverity.WARNING,
            title = "Memory leak", description = "",
            filePath = "/a.tsx", line = 1, ruleId = "AEG-ASYNC-001"
        )
        assertNull(FixerRegistry.forIssue(issue))
    }
}
```

### 9.4 `ApplyFixEventTest` assertions

```kotlin
class ApplyFixEventTest {
    @Test fun `APPLY_FIX parses to ApplyFixRequested`() {
        val msg = """{"type":"APPLY_FIX","payload":{"issueId":"i1","fixId":"f1"}}"""
        val event = UIEventParser.parse(msg)
        assertTrue(event is UIEvent.ApplyFixRequested)
        assertEquals("i1", (event as UIEvent.ApplyFixRequested).issueId)
        assertEquals("f1", event.fixId)
    }

    @Test fun `APPLY_FIX with missing fields defaults to empty strings`() {
        val msg = """{"type":"APPLY_FIX","payload":{}}"""
        val event = UIEventParser.parse(msg) as UIEvent.ApplyFixRequested
        assertEquals("", event.issueId)
        assertEquals("", event.fixId)
    }
}
```

### 9.5 `CodeFixExtensionTest` assertions

```kotlin
class CodeFixExtensionTest {
    private fun baseFix() = CodeFix(
        id = "f1", issueId = "i1", description = "d",
        originalCode = "old", fixedCode = "new",
        filePath = "/a.tsx", lineStart = 3, lineEnd = 3
    )

    @Test fun `isDeterministic defaults to false`() {
        assertFalse(baseFix().isDeterministic)
    }

    @Test fun `confidence defaults to 0_7`() {
        assertEquals(0.7, baseFix().confidence)
    }

    @Test fun `round-trips through Json serialization`() {
        val fix = baseFix().copy(isDeterministic = true, confidence = 1.0)
        val json = Json.encodeToString(fix)
        assertTrue(json.contains("isDeterministic"))
        val back = Json.decodeFromString<CodeFix>(json)
        assertEquals(fix, back)
    }
}
```

---

## 10. Provider Fallback and Fix Path Decision Table

| `ruleId` on issue | Registered fixer? | `canFix` | File readable | Fix generated | Path taken |
|---|---|---|---|---|---|
| `AEG-NULL-001` | Yes — `NullSafetyFixer` | Yes | Yes | `CodeFix(isDeterministic=true)` | Deterministic: `sendFixSuggestion` immediately |
| `AEG-NULL-001` | Yes | Yes | Yes | `null` (pattern not matched) | Falls through to AI |
| `AEG-NULL-001` | Yes | Yes | No (read exception) | `null` | Falls through to AI |
| `AEG-ASYNC-001` | Yes — `AsyncFlowFixer` | No (type is `MEMORY_LEAK`) | — | `null` | Falls through to AI |
| `AEG-CPX-001` | No | — | — | — | Falls through to AI |
| `null` ruleId | — | — | — | — | Falls through to AI |
