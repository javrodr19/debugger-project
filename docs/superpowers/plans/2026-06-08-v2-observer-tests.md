# V2 Cross-Check Observer Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the test gap on the two trust-critical V2 cross-check observers (`TestRunObserver`, `DebugObserver`) by extracting their pure correlation logic into testable helpers and unit-testing it (plus the already-pure `StackTraceParser` / `debugProbe`), behavior-preservingly.

**Architecture:** Two new pure objects — `store/TestRunCorrelation` and `store/DebugObservationLogic` — hold the decisions the observers currently embed in `private`, IntelliJ-bound methods. The observers keep their listener glue but delegate. All helpers are dependency-free → plain JUnit. No behavior change.

**Tech Stack:** Kotlin 2.0.21, JUnit4 (plain, no IntelliJ fixture).

**Spec:** `docs/superpowers/specs/2026-06-07-v2-observer-tests-design.md`.

---

## Prerequisites (test prelude)

Tests need a JetBrains Runtime; the shell env does **not** persist between Bash calls. Prefix **every** gradlew call inline:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

Append `; echo EXIT=${PIPESTATUS[0]}`; treat any `BUILD FAILED`/non-zero `EXIT` as failure. (These are plain-JUnit tests — fast.)

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/store/StackTraceParser.kt` — `object StackTraceParser { fun parse(stackTrace: String?): List<ParsedFrame> }`; `data class ParsedFrame(val fileName: String, val line: Int)` (fileName is the simple name, after the last `/` and `\`). 3 regex patterns (JVM-paren, `at`-prefixed, raw-path), `distinctBy { Pair(fileName, line) }`.
- `src/main/kotlin/com/ghostdebugger/model/RuntimeEvidence.kt` — `enum class EvidenceOutcome { CONFIRMED, LIKELY, DEMOTED, UNREACHED }`; `enum class EvidenceSource { TEST_FAILURE, TEST_COVERAGE, TEST_OUTCOME_PASS, DEBUG_OBSERVATION }`; `data class RuntimeEvidence(fingerprint, source, outcome, timestamp, context?)`.
- `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt` — `Issue(...)` data class; `fun fingerprint()`; `ruleId`, `title`, `filePath`, `line`.
- `src/main/kotlin/com/ghostdebugger/analysis/analyzers/NullSafetyAnalyzer.kt:13` — `override fun debugProbe(issue: Issue): String?` — pure: returns the substring of `issue.title` between `"Null reference: "` and `" may be null"`, else null.
- `src/main/kotlin/com/ghostdebugger/store/TestRunObserver.kt:70-99` (`recordFromFailure`), `:101-157` (`harvestCoverage`) — the methods that delegate in Task 1.
- `src/main/kotlin/com/ghostdebugger/store/DebugObserver.kt:57-113` (`evaluateRelevantFindingsAtCurrentFrame` + its eval callback) — delegates in Task 2.

## File structure

- **Create** `store/TestRunCorrelation.kt`, `store/DebugObservationLogic.kt` (pure helpers).
- **Modify** `store/TestRunObserver.kt`, `store/DebugObserver.kt` (delegate — behavior-preserving).
- **Create** tests: `store/TestRunCorrelationTest.kt`, `store/DebugObservationLogicTest.kt`, `store/StackTraceParserTest.kt`, `analysis/analyzers/NullSafetyAnalyzerDebugProbeTest.kt`.

---

### Task 1: `TestRunCorrelation` + delegate `TestRunObserver`

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/store/TestRunCorrelation.kt`
- Modify: `src/main/kotlin/com/ghostdebugger/store/TestRunObserver.kt`
- Test: `src/test/kotlin/com/ghostdebugger/store/TestRunCorrelationTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/ghostdebugger/store/TestRunCorrelationTest.kt`:

```kotlin
package com.ghostdebugger.store

import com.ghostdebugger.model.EvidenceOutcome
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestRunCorrelationTest {
    private fun issue(path: String, line: Int) = Issue(
        id = "i", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
        title = "t", description = "", filePath = path, line = line, ruleId = "AEG-NULL-001"
    )

    @Test fun failureMatchesByLineAndFilename() {
        val issues = listOf(issue("/proj/src/Foo.ts", 12), issue("/proj/src/Bar.ts", 5))
        val frames = listOf(ParsedFrame("Foo.ts", 12))
        assertEquals(listOf(issues[0]), TestRunCorrelation.failureMatches(frames, issues))
    }

    @Test fun noMatchOnWrongLineOrFile() {
        val issues = listOf(issue("/proj/src/Foo.ts", 12))
        assertEquals(emptyList<Issue>(), TestRunCorrelation.failureMatches(listOf(ParsedFrame("Foo.ts", 99)), issues))
        assertEquals(emptyList<Issue>(), TestRunCorrelation.failureMatches(listOf(ParsedFrame("Other.ts", 12)), issues))
    }

    @Test fun emptyInputsYieldEmpty() {
        assertEquals(emptyList<Issue>(), TestRunCorrelation.failureMatches(emptyList(), listOf(issue("/a.ts", 1))))
        assertEquals(emptyList<Issue>(), TestRunCorrelation.failureMatches(listOf(ParsedFrame("a.ts", 1)), emptyList()))
    }

    @Test fun matchedIssueIsDistinctAcrossMultipleFrames() {
        val issues = listOf(issue("/proj/Foo.ts", 7))
        val frames = listOf(ParsedFrame("Foo.ts", 7), ParsedFrame("Foo.ts", 7))
        assertEquals(listOf(issues[0]), TestRunCorrelation.failureMatches(frames, issues))
    }

    @Test fun coverageEvidenceBranches() {
        assertNull(TestRunCorrelation.coverageEvidence(classFound = false, isCovered = false))
        assertEquals(EvidenceOutcome.LIKELY, TestRunCorrelation.coverageEvidence(classFound = true, isCovered = true))
        assertEquals(EvidenceOutcome.UNREACHED, TestRunCorrelation.coverageEvidence(classFound = true, isCovered = false))
    }
}
```

- [ ] **Step 2: Run → FAIL** (`TestRunCorrelation` unresolved).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.store.TestRunCorrelationTest"`

- [ ] **Step 3: Implement `TestRunCorrelation.kt`**

```kotlin
package com.ghostdebugger.store

import com.ghostdebugger.model.EvidenceOutcome
import com.ghostdebugger.model.Issue

/**
 * Pure correlation logic for [TestRunObserver] — extracted so it is testable without driving the real
 * `SMTRunnerEventsListener`. The observer keeps the IntelliJ glue (stacktrace + coverage data) and
 * delegates these decisions. Behavior-preserving.
 */
object TestRunCorrelation {
    /** Issues hit by any failure frame (same line + the issue's path ends with the frame's filename). */
    fun failureMatches(frames: List<ParsedFrame>, activeIssues: List<Issue>): List<Issue> =
        activeIssues.filter { issue ->
            frames.any { f -> issue.line == f.line && issue.filePath.replace("\\", "/").endsWith(f.fileName) }
        }

    /** Coverage verdict for an issue's line; null when its class was absent from the coverage data. */
    fun coverageEvidence(classFound: Boolean, isCovered: Boolean): EvidenceOutcome? = when {
        !classFound -> null
        isCovered -> EvidenceOutcome.LIKELY
        else -> EvidenceOutcome.UNREACHED
    }
}
```

- [ ] **Step 4: Run → PASS** (5 tests).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.store.TestRunCorrelationTest"`

- [ ] **Step 5: Delegate in `TestRunObserver.kt` (behavior-preserving)**

5a. Replace `recordFromFailure` (currently lines ~70-99) with:

```kotlin
    private fun recordFromFailure(test: SMTestProxy) {
        val stacktrace = test.stacktrace ?: return
        val frames = StackTraceParser.parse(stacktrace)
        if (frames.isEmpty()) return

        val service = GhostDebuggerService.getInstance(project)
        val store = RuntimeEvidenceStore.getInstance(project)
        val context = getContextName(test)

        for (issue in TestRunCorrelation.failureMatches(frames, service.currentIssues)) {
            store.record(
                RuntimeEvidence(
                    fingerprint = issue.fingerprint(),
                    source = EvidenceSource.TEST_FAILURE,
                    outcome = EvidenceOutcome.CONFIRMED,
                    timestamp = System.currentTimeMillis(),
                    context = "Failed in test: $context"
                )
            )
        }
    }
```

5b. In `harvestCoverage`, replace the trailing `if (classFound) { … }` block (currently lines ~143-155) with:

```kotlin
            val outcome = TestRunCorrelation.coverageEvidence(classFound, isCovered)
            if (outcome != null) {
                val text = if (isCovered) "Covered in suite: ${bundle.presentableName}"
                           else "Unreached in suite: ${bundle.presentableName}"
                store.record(
                    RuntimeEvidence(
                        fingerprint = issue.fingerprint(),
                        source = EvidenceSource.TEST_COVERAGE,
                        outcome = outcome,
                        timestamp = System.currentTimeMillis(),
                        context = text
                    )
                )
            }
```

Leave the rest of `harvestCoverage` (coverage-data access computing `classFound`/`isCovered`) unchanged.

- [ ] **Step 6: Run → PASS** (compile + the correlation test; the observer still compiles and behaves identically):

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.store.*"`

Expected: green (incl. `RuntimeEvidenceStoreTest`). If the observer fails to compile, confirm the `EvidenceSource`/`EvidenceOutcome`/`RuntimeEvidence` imports are still present (they were used before this change).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/store/TestRunCorrelation.kt \
        src/main/kotlin/com/ghostdebugger/store/TestRunObserver.kt \
        src/test/kotlin/com/ghostdebugger/store/TestRunCorrelationTest.kt
git commit -m "test(v2): extract + test TestRunObserver correlation (TestRunCorrelation)"
```

---

### Task 2: `DebugObservationLogic` + delegate `DebugObserver`

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/store/DebugObservationLogic.kt`
- Modify: `src/main/kotlin/com/ghostdebugger/store/DebugObserver.kt`
- Test: `src/test/kotlin/com/ghostdebugger/store/DebugObservationLogicTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/ghostdebugger/store/DebugObservationLogicTest.kt`:

```kotlin
package com.ghostdebugger.store

import com.ghostdebugger.analysis.analyzers.NullSafetyAnalyzer
import com.ghostdebugger.model.EvidenceOutcome
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugObservationLogicTest {
    private fun issue(
        path: String = "/proj/Foo.ts", line: Int = 7,
        rule: String = "AEG-NULL-001", title: String = "Null reference: x may be null"
    ) = Issue(
        id = "i", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
        title = title, description = "", filePath = path, line = line, ruleId = rule
    )

    @Test fun nullishOutcomeConfirmsNullAndUndefined() {
        assertEquals(EvidenceOutcome.CONFIRMED, DebugObservationLogic.nullishOutcome("null"))
        assertEquals(EvidenceOutcome.CONFIRMED, DebugObservationLogic.nullishOutcome("undefined"))
        assertEquals(EvidenceOutcome.DEMOTED, DebugObservationLogic.nullishOutcome("42"))
        assertEquals(EvidenceOutcome.DEMOTED, DebugObservationLogic.nullishOutcome("\"hello\""))
    }

    @Test fun frameMatchesByNormalizedPathAndLine() {
        val issues = listOf(issue(path = "/proj/Foo.ts", line = 7), issue(path = "/proj/Bar.ts", line = 7))
        assertEquals(listOf(issues[0]), DebugObservationLogic.frameMatches("/proj/Foo.ts", 7, issues))
        // backslash path normalizes to match a forward-slash issue path
        assertEquals(listOf(issues[0]), DebugObservationLogic.frameMatches("\\proj\\Foo.ts", 7, issues))
    }

    @Test fun frameMatchesExcludesLineOrFileMismatch() {
        val issues = listOf(issue(path = "/proj/Foo.ts", line = 7))
        assertEquals(emptyList<Issue>(), DebugObservationLogic.frameMatches("/proj/Foo.ts", 8, issues))
        assertEquals(emptyList<Issue>(), DebugObservationLogic.frameMatches("/proj/Other.ts", 7, issues))
    }

    @Test fun probeExpressionForNullSafetyRuleOnly() {
        val analyzer = NullSafetyAnalyzer()
        assertEquals("user", DebugObservationLogic.probeExpressionFor(
            issue(title = "Null reference: user may be null"), analyzer))
        assertNull(DebugObservationLogic.probeExpressionFor(issue(rule = "AEG-OTHER-001"), analyzer))
    }
}
```

- [ ] **Step 2: Run → FAIL** (`DebugObservationLogic` unresolved).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.store.DebugObservationLogicTest"`

- [ ] **Step 3: Implement `DebugObservationLogic.kt`**

```kotlin
package com.ghostdebugger.store

import com.ghostdebugger.analysis.analyzers.NullSafetyAnalyzer
import com.ghostdebugger.model.EvidenceOutcome
import com.ghostdebugger.model.Issue

/**
 * Pure correlation logic for [DebugObserver] — extracted so it is testable without driving an
 * `XDebugSession`/`XValue`. The observer keeps the XDebugger glue (frame, evaluation, value text) and
 * delegates these decisions. Behavior-preserving.
 */
object DebugObservationLogic {
    /** A debugger value of `null`/`undefined` CONFIRMS a null-safety finding; anything else DEMOTES it. */
    fun nullishOutcome(valueText: String): EvidenceOutcome =
        if (valueText == "null" || valueText == "undefined") EvidenceOutcome.CONFIRMED else EvidenceOutcome.DEMOTED

    /** Issues at the paused (file, line), path-normalized. */
    fun frameMatches(filePath: String, line: Int, activeIssues: List<Issue>): List<Issue> {
        val norm = filePath.replace("\\", "/")
        return activeIssues.filter { it.filePath.replace("\\", "/") == norm && it.line == line }
    }

    /** The probe expression for an issue, or null when the rule isn't debug-probeable. */
    fun probeExpressionFor(issue: Issue, analyzer: NullSafetyAnalyzer): String? =
        if (issue.ruleId == "AEG-NULL-001") analyzer.debugProbe(issue) else null
}
```

- [ ] **Step 4: Run → PASS** (4 tests).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.store.DebugObservationLogicTest"`

- [ ] **Step 5: Delegate in `DebugObserver.kt` (behavior-preserving)**

In `evaluateRelevantFindingsAtCurrentFrame`:

5a. Replace the inline match (currently lines ~71-73):
```kotlin
        val matchingIssues = activeIssues.filter { issue ->
            issue.filePath.replace("\\", "/") == filePath && issue.line == currentLine
        }
```
with:
```kotlin
        val matchingIssues = DebugObservationLogic.frameMatches(filePath, currentLine, activeIssues)
```

5b. Replace the probe selection (currently lines ~78-82):
```kotlin
            val expression = if (issue.ruleId == "AEG-NULL-001") {
                nullSafetyAnalyzer.debugProbe(issue)
            } else {
                null
            } ?: continue
```
with:
```kotlin
            val expression = DebugObservationLogic.probeExpressionFor(issue, nullSafetyAnalyzer) ?: continue
```

5c. Replace the outcome decision in the eval callback (currently lines ~88-89):
```kotlin
                            val isNullish = valueText == "null" || valueText == "undefined"
                            val outcome = if (isNullish) EvidenceOutcome.CONFIRMED else EvidenceOutcome.DEMOTED
```
with:
```kotlin
                            val outcome = DebugObservationLogic.nullishOutcome(valueText)
```

Leave the rest of the method (XDebugger frame/evaluator access, `fetchValueText`, `store.record`) unchanged.

- [ ] **Step 6: Run → PASS** (compile + store suite):

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.store.*"`

Expected: green. If `DebugObserver` fails to compile, confirm `EvidenceOutcome`/`EvidenceSource`/`RuntimeEvidence`/`NullSafetyAnalyzer` imports remain (all were used pre-change).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/store/DebugObservationLogic.kt \
        src/main/kotlin/com/ghostdebugger/store/DebugObserver.kt \
        src/test/kotlin/com/ghostdebugger/store/DebugObservationLogicTest.kt
git commit -m "test(v2): extract + test DebugObserver correlation (DebugObservationLogic)"
```

---

### Task 3: Cover the already-pure pieces (`StackTraceParser`, `debugProbe`)

**Files:**
- Test: `src/test/kotlin/com/ghostdebugger/store/StackTraceParserTest.kt`
- Test: `src/test/kotlin/com/ghostdebugger/analysis/analyzers/NullSafetyAnalyzerDebugProbeTest.kt`

- [ ] **Step 1: Write the tests**

`src/test/kotlin/com/ghostdebugger/store/StackTraceParserTest.kt`:

```kotlin
package com.ghostdebugger.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StackTraceParserTest {
    @Test fun parsesJvmParenthesizedFrame() {
        assertTrue(StackTraceParser.parse("\tat com.example.Foo.bar(Foo.kt:42)").contains(ParsedFrame("Foo.kt", 42)))
    }

    @Test fun parsesNodeWebpackFrame() {
        assertTrue(StackTraceParser.parse("at foo (webpack:///./src/Foo.ts:125:7)").contains(ParsedFrame("Foo.ts", 125)))
    }

    @Test fun parsesRawPathFrame() {
        assertTrue(StackTraceParser.parse("test/index.spec.js:15:10").contains(ParsedFrame("index.spec.js", 15)))
    }

    @Test fun stripsWindowsBackslashPathToSimpleName() {
        assertTrue(StackTraceParser.parse("at C:\\proj\\src\\File.ts:30:2").contains(ParsedFrame("File.ts", 30)))
    }

    @Test fun blankNullOrNonFrameYieldsEmpty() {
        assertTrue(StackTraceParser.parse(null).isEmpty())
        assertTrue(StackTraceParser.parse("   ").isEmpty())
        assertTrue(StackTraceParser.parse("no stack frames here").isEmpty())
    }

    @Test fun deduplicatesByFileAndLine() {
        val frames = StackTraceParser.parse("at a (Foo.ts:1:1)\nat b (Foo.ts:1:9)")
        assertEquals(1, frames.count { it == ParsedFrame("Foo.ts", 1) })
    }
}
```

`src/test/kotlin/com/ghostdebugger/analysis/analyzers/NullSafetyAnalyzerDebugProbeTest.kt`:

```kotlin
package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NullSafetyAnalyzerDebugProbeTest {
    private val analyzer = NullSafetyAnalyzer()
    private fun issue(title: String) = Issue(
        id = "i", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
        title = title, description = "", filePath = "/a.ts", line = 1, ruleId = "AEG-NULL-001"
    )

    @Test fun extractsTheVariableExpressionFromAWellFormedTitle() {
        assertEquals("user.profile", analyzer.debugProbe(issue("Null reference: user.profile may be null")))
    }

    @Test fun returnsNullForUnrecognizedTitles() {
        assertNull(analyzer.debugProbe(issue("Something unrelated")))
        assertNull(analyzer.debugProbe(issue("Null reference: x")))   // missing the suffix
    }
}
```

- [ ] **Step 2: Run → PASS** (these test existing, already-correct code, so they pass immediately):

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.store.StackTraceParserTest" --tests "com.ghostdebugger.analysis.analyzers.NullSafetyAnalyzerDebugProbeTest"`

Expected: green. If `parsesJvmParenthesizedFrame` fails, log `StackTraceParser.parse("\tat com.example.Foo.bar(Foo.kt:42)")` — the parser runs all three patterns and `distinctBy`s; the assertion uses `contains`, robust to extra incidental frames.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/ghostdebugger/store/StackTraceParserTest.kt \
        src/test/kotlin/com/ghostdebugger/analysis/analyzers/NullSafetyAnalyzerDebugProbeTest.kt
git commit -m "test(v2): cover StackTraceParser + NullSafetyAnalyzer.debugProbe"
```

---

## Final verification

- [ ] Targeted suites green:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test \
  --tests "com.ghostdebugger.store.*" --tests "com.ghostdebugger.analysis.analyzers.NullSafetyAnalyzerDebugProbeTest"
```

- [ ] Full suite green with real exit status (proves the two delegations are behavior-preserving):

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test 2>&1 | tail -8; echo EXIT=${PIPESTATUS[0]}
```

Expected: `BUILD SUCCESSFUL`, `EXIT=0`.

## Self-Review (completed during planning)

**1. Spec coverage**
- §3.1 `TestRunCorrelation` (`failureMatches`, `coverageEvidence`) + delegate `TestRunObserver` → Task 1.
- §3.2 `DebugObservationLogic` (`nullishOutcome`, `frameMatches`, `probeExpressionFor`) + delegate `DebugObserver` → Task 2.
- §3.3 thin glue (observers keep listener/IntelliJ access, lose only decisions) → Tasks 1-2 (delegations).
- §4 tests for all five helpers + `StackTraceParser` + `debugProbe` → Tasks 1-3. (`debugProbe` is pure string ops → plain JUnit, confirming the spec's base-class contingency resolves to no-fixture.)
- §6 behavior-preserving — final full-suite run is the regression guard.

**2. Placeholder scan** — none. Every helper + test + delegation edit is shown in full; every run step has the exact command.

**3. Type consistency**
- `TestRunCorrelation.failureMatches(frames: List<ParsedFrame>, activeIssues: List<Issue>): List<Issue>` and `coverageEvidence(classFound: Boolean, isCovered: Boolean): EvidenceOutcome?` — used identically in the `TestRunObserver` delegation (Task 1, steps 5a/5b).
- `DebugObservationLogic.nullishOutcome(valueText: String)`, `frameMatches(filePath, line, activeIssues)`, `probeExpressionFor(issue, analyzer)` — used identically in the `DebugObserver` delegation (Task 2, steps 5a-5c).
- `ParsedFrame(fileName, line)` and `EvidenceOutcome` values match `StackTraceParser.kt` / `RuntimeEvidence.kt`.
- `NullSafetyAnalyzer().debugProbe(issue)` returns the title-derived expression; `probeExpressionFor` gates it on `ruleId == "AEG-NULL-001"` — consistent across Tasks 2 and 3.
