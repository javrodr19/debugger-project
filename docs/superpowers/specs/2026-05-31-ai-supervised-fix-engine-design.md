# AI-Supervised Fix Engine — Design

**Date:** 2026-05-31
**Status:** Approved (brainstorming) — pending implementation plan
**Roadmap fit:** V3 (fixer breadth). Inverts the V1 "deterministic fixer with AI fallback author" model.

## 1. Motivation

Today the fix pipeline treats the AI as a **fallback author**: when no deterministic `Fixer`
exists for a rule, `UIEventRouter.handleFixRequested` calls `AIService.suggestFix`, which asks the
model to *write the fixed code* (`BaseAIService.parseFixResponse` extracts a code block). Applying is
already deterministic-only, but *suggestion* coverage for unfixable rules depends on free-form LLM
output with no syntactic or semantic guarantees.

We are inverting the relationship:

> The **engine** does the fixing. The **AI** is a supervisor/co-worker — it never writes raw code;
> it uses the engine's deterministic operations to work out a solution, and it verifies that fixes
> are correct.

This keeps Aegis's privacy-first promise (the engine fixes offline) while making AI-assisted fixes
**deterministic in their edits** and **verified before they touch the user's file**.

## 2. Goals / Non-goals

**Goals**
- The AI authors no source code. It selects and parameterizes **deterministic engine operations**.
- Every fix — deterministic or AI-planned — passes a **deterministic verification gate** before it
  is applied.
- Deterministic single-fixer fixing **continues to work fully offline** (no regression, no new
  network dependency for the base path).
- AI, when enabled, **composes operations** to fix issues no single fixer covers, and **reviews**
  the result.

**Non-goals**
- Not building a general program-synthesis engine. The operation catalog is a curated, bounded set
  that grows deliberately.
- Not changing the analyzers (beyond reusing them for verification).
- Not requiring AI for any existing capability.

## 3. Architecture

Four new units plus changes to the fix entry points.

### 3.1 `FixOperation` (the engine primitives)
A `@Serializable sealed` catalog of deterministic, PSI-valid semantic transforms. Each operation is
pure data (its parameters) plus one behavior:

```kotlin
@Serializable
sealed class FixOperation {
    /** Produces the edit for this op against [file], or null if it does not apply
     *  (pattern absent, offset stale, would not be PSI-valid). Never throws on bad input. */
    abstract fun edit(file: PsiFile): TextEdit?
}
@Serializable data class AddElvisReturn(val line: Int, val fallback: String) : FixOperation()
@Serializable data class WrapInSafeCall(val offset: Int) : FixOperation()        // x.foo -> x?.foo
@Serializable data class ConvertToSafeCast(val offset: Int, val fallback: String) : FixOperation() // as -> as? ?: …
@Serializable data class InsertImport(val fqName: String) : FixOperation()
@Serializable data class SurroundWithTryCatch(val startOffset: Int, val endOffset: Int) : FixOperation()
@Serializable data class AddTimerCleanup(val effectStartLine: Int) : FixOperation()
@Serializable data class ReplaceRange(val startOffset: Int, val endOffset: Int, val text: String) : FixOperation()
```

`TextEdit` is a small value type `(startOffset, endOffset, replacement)`. Operations follow the
existing `Fixer` contract: **return null rather than produce invalid output** (CLAUDE.md › Fixer
principle). Each is independently unit-testable. The catalog starts small (above) and grows; the set
must cover at least the transforms the current 5 fixers perform.

### 3.2 `FixPlan`
```kotlin
@Serializable data class FixPlan(val issueId: String, val operations: List<FixOperation>)
```
Pure, serializable data — a recipe. Because it serializes, the AI can emit a plan as JSON and tests
can construct one literally.

### 3.3 `FixEngine` (core executor + verifier)
```kotlin
// Public entry: runs the policy (§3.6) to choose plan(s) and returns the outcome.
fun fix(issue: Issue, file: PsiFile): FixOutcome              // Applied(VerifiedFix) | NoFix(reason)
// Internal: execute + verify ONE candidate plan.
fun attemptFix(issue: Issue, file: PsiFile, plan: FixPlan): VerifiedFix?   // null carries a RejectedFix reason
```
`attemptFix` (one plan):
1. **Compose**: apply each op's `TextEdit` to an in-memory copy of the file content (edits applied
   in descending offset order so earlier offsets stay valid). No disk write.
2. **Verify gate — two tiers:**
   - **Tier 1 — PSI-validity (Phase 1).** The candidate parses with no `PsiErrorElement`. Achievable
     offline and synchronously via `PsiFileFactory` for languages with a Community PSI (Kotlin/Java);
     for TS/JS (no Community PSI) it is a structural no-op, matching today's behavior. Reuses the
     parse-check-and-revert that `FixApplicator` already performs at write time.
   - **Tier 2 — re-analysis (Phase 2).** (b) re-running the issue's analyzer on the candidate → the
     target **fingerprint is gone**; (c) → **no new fingerprints** vs. the pre-fix baseline.
     **Caveat discovered during planning:** TS/JS analyzers are content-based (trivial to re-run on
     candidate content), but Kotlin analyzers resolve via the in-module PSI
     (`PsiManager.findFile(virtualFile)`) for K2 type resolution, so re-analyzing *unsaved candidate*
     Kotlin content needs the transient-document approach (apply→commit→analyze→accept/revert,
     extending `FixApplicator`'s pattern). This is a Phase 2 design item, not Phase 1.
3. Returns `VerifiedFix(edits, evidence)` or a typed `RejectedFix(reason)` (e.g. `NotPsiValid`,
   `IssueStillPresent`, `IntroducedIssues(list)`, `OperationInapplicable`).

### 3.4 Direct path (offline, no AI)
The existing 5 fixers are adapted to the engine via a thin adapter that turns the current `Fixer`'s
`CodeFix` into a single-op `FixPlan` (`ReplaceRange`), **preserving existing fixer logic verbatim**.
`FixEngine` executes that plan through the **same verify gate**. (Re-expressing fixers as native
catalog operations is optional later work, not required for Phase 1.) This is today's deterministic
fix, now supervised by verification, and it runs with no AI provider.

### 3.5 `FixPlanner` (AI orchestrator, only when a provider is enabled)
Replaces `AIService.suggestFix`'s free-form code generation.
- Inputs to the model: the issue, the relevant code, and the **operation catalog as a schema**
  (names + parameters). Output: a `FixPlan` (JSON), parsed via the existing `AiJsonExtractor`.
- The model **authors no code** — only ops + params.
- **Bounded orchestration loop** (≤ 3 attempts): `FixEngine` executes + verifies the plan; on
  `RejectedFix`, the reason is fed back to the model for the next attempt. On a passing gate, the
  loop ends.
- **AI semantic review**: once verified, the model reviews the unified diff and returns
  approve/reject + reason. Only **verified AND approved** fixes are applied.

### 3.6 Policy / orchestration order
1. **Direct fixer exists** → build its plan → verify. If it passes, **apply** (deterministic wins;
   AI is not consulted even when enabled).
2. **No direct fixer, or its verify fails** → if AI is enabled, run the `FixPlanner` loop + review.
3. **AI disabled and no direct fixer** → no fix offered (today's behavior).
4. Retries bounded; on exhaustion, **graceful no-fix** with a clear status message.

## 4. Data flow

```
FixRequested(issue)
  → FixEngine.fix(issue, psiFile)
      → direct plan (FixerRegistry)  ── verify gate ──┐  pass → VerifiedFix
      → else if AI on: FixPlanner loop ─ verify gate ─┤  (≤3) → AI review → VerifiedFix
      → else: none                                    ┘
  → FixApplicator.apply(verifiedFix)   (write action, PSI re-read)
  → AnalysisOrchestrator.reanalyzeFile(path)
```

## 5. Offline / AI-optional behavior

AI orchestration engages **only** when `AIServiceFactory.create(...)` yields a service (provider
configured). Otherwise the engine runs the direct path. The verify gate is **always** deterministic
and offline. No base-path capability gains a network dependency.

## 6. Error handling & conventions

- Every `catch (Exception)` rethrows `ProcessCanceledException` first (CLAUDE.md › Error handling).
- Operations and the planner **never corrupt PSI**: inapplicable → null/`RejectedFix`, never a
  malformed edit (CLAUDE.md › Fixer principle).
- Kotlin analysis in the verify gate flows through `withKtAnalysis` (CLAUDE.md › single chokepoint).
- The AI loop is strictly bounded; no unbounded ret/regeneration.
- `FixEngine` is stateless (a helper, not a state holder); it reads issues via the facade and applies
  through `FixApplicator`. No new project-level mutable state, so the V1.5 facade ownership rule is
  untouched.

## 7. Integration points (what changes)

- `FixDeriver` → folded into / superseded by `FixEngine` (the "PSI-then-text" derivation becomes the
  direct-plan adapter).
- `UIEventRouter.handleFixRequested` / `handleApplyFixRequested`, `AegisQuickFixIntentionAction`,
  `AegisLocalQuickFix` → route through `FixEngine`.
- `BaseAIService.suggestFix` / `parseFixResponse` (free-form code gen) → **removed** in Phase 2,
  replaced by `FixPlanner`. `explainIssue` and other AI features are unchanged.

## 8. Testing strategy

- **Per `FixOperation`**: applies the right edit on a matching fixture; returns null on a
  non-matching one; output is PSI-valid. Kotlin ops use `AegisKotlinAnalysisTestCase`.
- **Verify gate**: a plan that resolves the issue passes; a plan that leaves it (or introduces a new
  issue) is rejected with the correct reason. Fixture-driven, offline.
- **Direct path**: each of the 5 adapted fixers produces a `VerifiedFix` for its rule's fixture.
- **Orchestration loop**: driven by a **stubbed `FixPlanner`** returning a deterministic `FixPlan`
  (and a stub that returns a bad plan then a good one, to exercise the retry/feedback path). **No
  live LLM in tests.** AI review is mocked.
- All tests run offline under the JBR test setup; no network.

## 9. Phasing

**Phase 1 — offline engine + Tier-1 (PSI-validity) gate (no AI changes).**
`TextEdit`, `FixOperation` (sealed; initial op = `ReplaceRange`), `FixPlan`, a `FixPlanApplicator`
(applies a plan's edits with the PSI-validity check + revert), `FixEngine` as the single fix entry
point, an adapter turning each existing fixer's `CodeFix` into a single-op `FixPlan`, and re-routing
all fix entry points (`UIEventRouter`, `AegisQuickFixIntentionAction`, `AegisLocalQuickFix`) through
`FixEngine`. The current `AIService.suggestFix` fallback is left in place untouched. Deliverable:
all fixing flows through the engine + `FixPlan` abstraction, PSI-validity-gated, fully offline — the
seam Phase 2 plugs into. (The richer semantic operations beyond `ReplaceRange` arrive in Phase 2
with their consumer, the AI planner — YAGNI for Phase 1.)

**Phase 2 — AI supervisor + Tier-2 (re-analysis) gate.**
The semantic operation catalog (`AddElvisReturn`, `ConvertToSafeCast`, `InsertImport`,
`SurroundWithTryCatch`, `AddTimerCleanup`, …), the Tier-2 re-analysis gate (incl. the transient-
document approach for Kotlin type resolution), `FixPlanner` (catalog-schema prompt → `FixPlan` JSON),
the bounded orchestration loop with verify feedback, and the AI semantic-review step. Remove
`suggestFix`/`parseFixResponse` free-form code generation. Deliverable: AI composes + supervises
engine operations for issues no single fixer covers, with every edit deterministic and every fix
verified.

## 10. Risks / open questions

- **Single-file verification fidelity.** Re-running analyzers on an in-memory single-file context may
  miss cross-file effects. Mitigation: the gate checks the *target file* only (same scope the
  fixers/`reanalyzeFile` already operate on); cross-file regressions remain the job of the next full
  analysis. Acceptable for a fix gate.
- **Offset drift across multi-op plans.** Mitigated by applying edits in descending-offset order and
  re-resolving PSI-bound ops against the original file before composition.
- **Operation-catalog coverage.** Phase 2's usefulness scales with the catalog; it starts modest and
  grows. The verify gate makes an inadequate plan fail safe (no fix) rather than apply a bad one.
- **AI review cost.** One extra model call per fix; gated behind AI-enabled and only on the
  AI-planned path (direct fixes skip it).
