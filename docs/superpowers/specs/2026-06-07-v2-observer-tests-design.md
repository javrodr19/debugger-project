# V2 Cross-Check Observers — Test Coverage via Seam Extraction (Design)

**Date:** 2026-06-07
**Status:** Approved (design); pending spec review → implementation plan
**Context:** V2 dynamic validation is already implemented. Every component is tested **except the two
runtime cross-checks** — `store/TestRunObserver` and `store/DebugObserver` (0 tests each). These are the
trust-critical "does this finding actually manifest at runtime?" logic. This closes that gap.

## 1. Motivation

Both observers have the same shape: thin IntelliJ-listener glue (`SMTRunnerEventsListener` /
`XDebugSessionListener`) wrapping **pure correlation logic** trapped inside `private` methods. The glue
binds to `SMTestProxy` / `XDebugSession` / `XValue` / `CoverageDataManager`, which are impractical to
fake in a unit test (XDebugger especially). The valuable, regression-worthy logic is the *correlation*:
which finding a stack frame confirms, when a debugger value counts as "nullish," covered-vs-unreached.

Approach (brainstormed): **behavior-preserving seam extraction** — lift the correlation into pure
helpers the observers call, then unit-test the helpers. This mirrors the project's own pattern (pure
`FixVerifier` behind thin apply-glue) and is the only practical way to test this code without faking the
debugger. No behavior change; the listener glue stays thin and is left to manual/existing coverage.

## 2. Goals / Non-goals

**Goals**
- Extract each observer's correlation into a pure, dependency-free helper object.
- Unit-test that logic + the already-pure pieces it relies on (`StackTraceParser`,
  `NullSafetyAnalyzer.debugProbe`).
- Leave observed behavior identical (the recorded `RuntimeEvidence` is the same).

**Non-goals**
- **No driving the real listeners** (`SMTRunnerEventsListener`/`XDebugSession`) or faking
  `XValue`/`CoverageDataManager` — the thin glue stays as-is.
- **No behavior change** — existing V2 tests (`RuntimeEvidenceStoreTest` etc.) stay green; the evidence
  recorded for any given input is unchanged.
- No new V2 feature, no model/store change.

## 3. Architecture

### 3.1 `TestRunCorrelation` (new, `store/`, pure)
```
object TestRunCorrelation {
    /** Issues whose (line, simple-filename) is hit by any failure frame — recorded as CONFIRMED. */
    fun failureMatches(frames: List<ParsedFrame>, activeIssues: List<Issue>): List<Issue> =
        activeIssues.filter { issue ->
            frames.any { f -> issue.line == f.line && issue.filePath.replace("\\", "/").endsWith(f.fileName) }
        }

    /** Coverage verdict for an issue's line: null when its class wasn't in the coverage data. */
    fun coverageEvidence(classFound: Boolean, isCovered: Boolean): EvidenceOutcome? =
        if (!classFound) null else if (isCovered) EvidenceOutcome.LIKELY else EvidenceOutcome.UNREACHED
}
```
`TestRunObserver.recordFromFailure` becomes: parse stacktrace → `failureMatches(frames, currentIssues)`
→ record one `CONFIRMED` `TEST_FAILURE` evidence per match. (Equivalent to today's nested loop; matches
are de-duplicated to distinct issues — functionally identical, since `ConfidenceCalculator` is
set-membership based and the store caps entries.) `harvestCoverage` keeps its coverage-data access but
folds its per-issue `classFound`/`isCovered` flags through `coverageEvidence`.

### 3.2 `DebugObservationLogic` (new, `store/`, pure)
```
object DebugObservationLogic {
    fun nullishOutcome(valueText: String): EvidenceOutcome =
        if (valueText == "null" || valueText == "undefined") EvidenceOutcome.CONFIRMED else EvidenceOutcome.DEMOTED

    fun frameMatches(filePath: String, line: Int, activeIssues: List<Issue>): List<Issue> {
        val norm = filePath.replace("\\", "/")
        return activeIssues.filter { it.filePath.replace("\\", "/") == norm && it.line == line }
    }

    /** The probe expression for an issue, or null when the rule isn't debug-probeable. */
    fun probeExpressionFor(issue: Issue, analyzer: NullSafetyAnalyzer): String? =
        if (issue.ruleId == "AEG-NULL-001") analyzer.debugProbe(issue) else null
}
```
`DebugObserver.evaluateRelevantFindingsAtCurrentFrame` delegates its (file,line) match to `frameMatches`
and its probe selection to `probeExpressionFor`; the eval callback delegates its verdict to
`nullishOutcome`. The XDebugger evaluation + `fetchValueText` glue is unchanged.

### 3.3 Keep the glue thin
The observers retain: the message-bus subscription, IntelliJ-data gathering (stacktrace, coverage data,
debugger frame/value), and the `store.record(...)` calls. They lose only the embedded decisions, now
delegated. This is a behavior-preserving refactor.

## 4. Testing strategy (pure JUnit — no IntelliJ fixture needed)

- **`StackTraceParser`** (currently only incidentally referenced): JVM/Node/Vitest/Mocha/Windows-path
  frames → expected `ParsedFrame(simpleName, line)`; malformed/blank/null → empty; de-dup by (file,line).
- **`TestRunCorrelation.failureMatches`**: match on equal line + path-endsWith-filename; no match on
  wrong line or wrong file; multiple issues; empty inputs.
- **`TestRunCorrelation.coverageEvidence`**: `!classFound` → null; covered → `LIKELY`; not covered →
  `UNREACHED`.
- **`DebugObservationLogic.nullishOutcome`**: `"null"`/`"undefined"` → `CONFIRMED`; any other → `DEMOTED`.
- **`DebugObservationLogic.frameMatches`**: normalized path + line match; backslash/forward-slash
  equivalence; line/file mismatch excluded.
- **`DebugObservationLogic.probeExpressionFor`** + **`NullSafetyAnalyzer.debugProbe`**: an `AEG-NULL-001`
  issue → a non-null probe expression; a non-null-safety rule → null.

`Issue` instances are constructed directly (no PSI). `failureMatches`/`frameMatches`/`nullishOutcome`/
`coverageEvidence` are pure → plain JUnit. `debugProbe` may need `BasePlatformTestCase` if it inspects
PSI — the plan checks its body and chooses the base class accordingly.

## 5. Scope / phasing

One spec → one plan → build. Tasks: (1) `TestRunCorrelation` + tests + delegate `TestRunObserver`;
(2) `DebugObservationLogic` + tests + delegate `DebugObserver`; (3) `StackTraceParser` +
`debugProbe` tests. Each is independently green; the delegations are behavior-preserving (verified by the
full suite staying green).

## 6. Risks / open questions

- **Refactoring working V2 code** — mitigated: the extraction only *moves* expressions into pure
  functions the observers call; the recorded evidence is identical. The full suite (incl.
  `RuntimeEvidenceStoreTest`) is the regression guard.
- **`debugProbe` PSI dependency** — if `NullSafetyAnalyzer.debugProbe` resolves PSI/types, its test needs
  `AegisKotlinAnalysisTestCase`; if it's string/issue-only, plain JUnit. The plan inspects and picks.
- **Glue remains untested** — accepted: the listener wiring is declarative and low-churn; faking the
  debugger/test-runner has poor ROI. The pure correlation (where bugs hide) is now covered.
