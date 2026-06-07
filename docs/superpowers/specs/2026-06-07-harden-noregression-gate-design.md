# Harden the No-Regression Gate — Design

**Date:** 2026-06-07
**Status:** Approved (design); pending spec review → implementation plan
**Context:** Consolidation of the AI-supervised fix engine (sub-projects A + B). The verify gate's
no-regression arm is the safety net the whole "AI proposes, gate disposes" philosophy rests on, but it
is currently blind for an important class of files. This spec makes it real.

## 1. Motivation

The Tier-2 verify gate (`FixVerifier`, `ComplexityVerifier`, `ExtractMethodVerifier`) rejects a fix that
**introduces a new issue** by comparing the file's issues before vs. after. That comparison is undermined
by two facts, and every e2e to date sidestepped them by stubbing `reanalyze = { emptyList() }`:

1. **Late analyzers are shadowed for files with errors.** `AnalysisEngine.doStaticPasses`
   (`AnalysisEngine.kt:91-99`) drops any file that produced an **early** issue (syntax *or* compile
   error) from the context before running the **late** analyzers (null-safety, type-mismatch,
   redundant-let, state-init, async-flow, complexity). So `SingleFileStaticReanalysis` returns no
   late-rule issues for a file with a compile error — the gate cannot see a late-rule regression there.
2. **The baseline is computed differently from the candidate.** `AnalysisOrchestrator.applyVerifiedFix`
   (`AnalysisOrchestrator.kt:507`) derives the baseline from the full-project `currentIssues`
   (`baselineFor(...)`, which shadowed), while the candidate comes from single-file `reanalyze()`. If we
   simply un-shadow the candidate path, the candidate would surface late rules the baseline never had →
   **good fixes get falsely rejected**.

The chosen approach (brainstormed): **gate-scoped non-shadowing** — the single-file re-analysis runs all
analyzers (safe, because Tier-1 already guarantees the candidate is parse-clean), and the orchestrator
computes the baseline via the *same* single-file pass on the original, so baseline and candidate are
produced identically. The full-project pipeline is left untouched.

## 2. Goals / Non-goals

**Goals**
- `SingleFileStaticReanalysis` runs the late analyzers regardless of early/compile errors, so the gate
  sees late-rule issues (null-safety, type-mismatch, redundant-let, state-init, async-flow) on the file.
- The gate's baseline is computed by the *same* single-file pass on the original (pre-fix) content, so
  the no-regression comparison is apples-to-apples (no false rejection from un-shadowing).
- Tests that exercise the gate with **real** re-analysis (not stubbed), proving it catches a regression a
  compile-error file previously hid, and accepts a clean fix.

**Non-goals**
- **No change to the full-project analysis pipeline.** `AnalysisEngine`'s default behavior
  (`excludeBrokenFromLate = true`) is byte-for-byte unchanged; normal IDE runs surface no new findings.
- **Graph rules stay out of single-file scope.** `SingleFileStaticReanalysis` uses an empty
  `InMemoryGraph`, so complexity / circular-dependency produce nothing in the gate regardless. These are
  inherently cross-file; the simplification gates already verify complexity directly. Not addressed here.
- **No change to `applyVerified`, the acceptance seam, or the verifiers** (`FixVerifier`,
  `ComplexityVerifier`, `ExtractMethodVerifier`). They simply receive a better `baselineForFile`.
- No new analyzer, rule, or fixer.

## 3. Architecture

### 3.1 Non-shadowing single-file re-analysis

Add a parameter to `AnalysisEngine`, threaded into `doStaticPasses`:

```
analyzeStaticOnly(context, indicator = null, excludeBrokenFromLate: Boolean = true)
  -> doStaticPasses(context, settings, indicator, excludeBrokenFromLate)
```

In `doStaticPasses`, when `excludeBrokenFromLate` is `false`, skip the broken-file filtering (the
`brokenFilePaths` / `filteredFiles` block) and run the late analyzers on the full `limitedContext`
(`filteredContext = limitedContext`). When `true` (the default, used by `analyze` and the existing
cascade), behavior is exactly as today.

`SingleFileStaticReanalysis.issuesFor` calls `engineFactory().analyzeStaticOnly(ctx, excludeBrokenFromLate = false)`.

**Safety:** the gate only consults `reanalyze()` on a candidate that already passed Tier-1
(`FixPlanApplicator` reverts a candidate containing a `PsiErrorElement`), so the file is parse-clean —
the late analyzers receive valid PSI. Semantic compile errors (e.g. a type mismatch) leave the PSI valid;
the analyzers are `withKtAnalysis`-guarded and do not flag on `KaErrorType`, so they degrade safely. The
baseline (original) is likewise re-analyzed the same way (§3.2); a genuinely unparseable original is the
degenerate case where both sides see the same early issue and the comparison still holds.

### 3.2 Consistent baseline (orchestrator)

`AnalysisOrchestrator.applyVerifiedFix` gains an injectable `baselineProvider` seam and uses it instead
of `baselineFor(service().currentIssues, …)`:

```
internal fun applyVerifiedFix(
    issue, virtualFile, content,
    baselineProvider: suspend (VirtualFile) -> List<Issue> =
        { SingleFileStaticReanalysis(project).issuesFor(it) },
    fixVerified: suspend (Issue, VirtualFile, String, List<Issue>) -> FixApplyResult = { … },
): Job = scope.launch {
    val baseline = baselineProvider(virtualFile)   // single-file, non-shadowing, on the ORIGINAL (pre-fix)
    when (val result = fixVerified(issue, virtualFile, content, baseline)) { … }
}
```

`baselineProvider` runs inside the existing `scope.launch` (off-EDT) **before** `fixVerified` applies the
fix, so it sees the original document. Because it is the same `SingleFileStaticReanalysis` the candidate
path uses, baseline and candidate are produced identically.

**Both production fix entry points get the same change.** `UIEventRouter.kt:290` (the JCEF "apply fix"
handler) is the other caller of `FixEngine.fixVerified` and currently uses the same shadowed
`baselineFor(svc.currentIssues, …)`; it is switched to the single-file baseline too, so the UI fix path
is hardened identically. With both call sites changed, the `baselineFor` helper
(`AnalysisOrchestrator.kt:56`) has no remaining caller — it (and its direct unit test `BaselineForTest`)
is removed. Path-scoping is not lost: `SingleFileStaticReanalysis` already returns file-scoped issues.

### 3.3 Unchanged

`FixEngine.fixVerified`/`fixSupervised`, `FixPlanApplicator.applyVerified`, the acceptance seam, and all
three verifiers are untouched — they receive the consistent `baselineForFile` and the comprehensive
candidate issues and compare them as they already do.

## 4. Verification semantics (what becomes true)

After this change, for a fix on a parse-clean candidate:
- The no-regression arm sees **every static late rule** on the file (not just the rules that survive the
  broken-file filter), so a fix that introduces a null-safety / type-mismatch / redundant-let /
  state-init / async-flow issue is **caught and rejected** — even on a file that also has a compile error.
- Baseline and candidate are measured by the identical single-file non-shadowing pass, so un-shadowing
  does not cause false rejections (a pre-existing late issue appears in both, nets zero).
- Graph-level rules (complexity, circular-dependency) remain unverified by the single-file gate
  (empty graph) — unchanged, and acceptable (the simplification gates verify complexity directly).

## 5. Testing strategy

- **Un-shadowing (the core proof)** — `SingleFileStaticReanalysisTest` (extends
  `AegisKotlinAnalysisTestCase`, since the late analyzers use the Kotlin Analysis API): a Kotlin file with
  **both** a semantic compile error and a nullable-access construct → `issuesFor` returns the
  `AEG-NULL-KT-001` late-rule issue (which the old shadowing dropped). A focused engine-level companion
  may assert `analyzeStaticOnly(ctx, excludeBrokenFromLate = false)` yields a late issue where
  `excludeBrokenFromLate = true` yields none, pinning the flag's effect.
- **Gate fed by real re-analysis** — a test that takes a clean original and a candidate that introduces a
  nullable access, runs the **real** `SingleFileStaticReanalysis` on each, and asserts
  `FixVerifier().decide(target, baselineIssues, candidateIssues)` is `Reject` (new `AEG-NULL-KT-001`); and
  a clean candidate (no new issue) is `Accept`. This exercises the gate with non-stubbed analysis.
- **Orchestrator baseline source** — update `AnalysisOrchestratorApplyVerifiedFixTest`: inject a stub
  `baselineProvider` and assert `applyVerifiedFix` threads its result into `fixVerified` (replacing the
  old `currentIssues`-subset assertion).
- The existing stubbed-`reanalyze` e2es (B2/C/D) remain valid as fixer→op→apply wiring tests and are left
  as-is.

## 6. Phasing

A **single plan**, tasks each independently green:
1. `AnalysisEngine.excludeBrokenFromLate` flag + an engine-level test pinning its effect.
2. `SingleFileStaticReanalysis` calls the non-shadowing pass + the un-shadowing analyzer test.
3. `AnalysisOrchestrator` `baselineProvider` seam + updated orchestrator test (remove `baselineFor` if unused).
4. Gate-fed-by-real-re-analysis test (regression rejected / clean accepted).

## 7. Risks / open questions

- **Late analyzers on a semantically-broken file** — mitigated: candidate is Tier-1 parse-clean; analyzers
  are `withKtAnalysis`-guarded and `KaErrorType`-conservative (the project's no-false-positive bias), so
  they under-report rather than mis-report on the broken parts.
- **Latency** — one extra single-file static pass per fix (the baseline), off-EDT, one file. Negligible
  against the correctness gain.
- **`AegisKotlinAnalysisTestCase` required** for the real-analysis tests (Analysis API throws on the EDT;
  needs Kotlin stdlib). Established base class; tests run off-EDT (`runInDispatchThread() = false`).
- **Empty-graph / graph rules** — explicitly out of scope (cross-file). If single-file complexity
  regression detection is ever wanted, a one-node graph for the file is a separate follow-on.
