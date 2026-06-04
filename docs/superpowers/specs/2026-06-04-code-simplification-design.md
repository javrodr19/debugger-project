# Code Simplification — Design (Sub-project B, spec 1: deterministic micro-simplification)

**Date:** 2026-06-04
**Status:** Approved (design); pending spec review → implementation plans
**Sub-project:** B (code simplification). This spec covers the **complexity-aware verify gate** + **deterministic branch-elimination**. AI extract-method / restructuring is a deliberate follow-on spec.

## 1. Motivation

`ComplexityAnalyzer` flags `AEG-CPX-001` when a graph node's estimated complexity exceeds the threshold (default 10), but no fixer exists — the user wants the engine to *simplify* flagged code, correctly. Two facts shape the whole design:

1. **The metric is recomputable from content.** `GraphBuilder.estimateComplexity(content, functionCount)` (module-`internal`) = `1 + decisionPoints / functionCount`, where `decisionPoints` counts `if`/`for`/`while`/`when`/`switch`/`case`/`catch`/`&&`/`||` over comment/string-masked content. So a verify gate can recompute a candidate's complexity directly.
2. **The single-file re-analysis gate is blind to complexity.** `SingleFileStaticReanalysis` builds an empty graph, so `ComplexityAnalyzer` (which reads graph nodes) produces no `AEG-CPX-001` for a candidate. The existing count-based "target resolved" check therefore cannot verify a complexity reduction — a dedicated **complexity-aware acceptance** is required.

A corollary constrains scope: the metric only **decreases** when a logical branch is **eliminated** (e.g. `if (C) return true else return false` → `return C`) or a function is **split** (extract-method raises the divisor). Nesting reduction that preserves branches (merging `if(a){if(b)}` → `if(a && b)`) does **not** move it. So deterministic simplification = branch-elimination; extract-method is the AI's job (follow-on).

## 2. Goals / Non-goals

**Goals**
- A **complexity-aware verify gate**: a simplification is accepted iff it is PSI-valid, the recomputed `estimateComplexity` **strictly decreases**, and it introduces no new issues of other rules.
- A deterministic `CollapseBooleanReturn` operation (`if (C) return true else return false` → `return C`; negated → `return !C`).
- A `ComplexitySimplifierFixer` (`AEG-CPX-001`) that scans the flagged file for collapsible boolean-return patterns and emits a plan; declines when none exist.
- Wire `AEG-CPX-001` through the complexity-aware gate; register the fixer.

**Non-goals**
- **AI extract-method / larger restructuring** — the high-value lever, but a separate follow-on spec (it changes `functionCount`, needs semantic naming, and is gate-verified the same way).
- **Nesting-reduction transforms that don't move the metric** (`merge-ifs`, `else`-removal) — they don't reduce the *flagged* complexity, so they aren't auto-applied here.
- No change to the threshold or `ComplexityAnalyzer` itself; no change to how complexity is computed.

## 3. Architecture

### 3.1 Complexity recompute

Reuse `com.ghostdebugger.graph.estimateComplexity(content, functionCount): Int` (module-`internal`, already content-based). The gate computes it for the **original** and the **candidate** content. `functionCount` is the flagged file's current function count; deterministic branch-elimination does not change it, so the same `functionCount` is used for both sides. (The AI extract-method follow-on will recompute `functionCount` per candidate, since it changes the divisor.)

### 3.2 Complexity-aware verify gate

`applyVerified`'s acceptance is generalized via an optional decision seam so simplification reuses the existing transactional lifecycle (EDT apply+commit+Tier-1 → off-EDT re-analyze → EDT save/revert):

```
acceptance(originalContent, candidateContent, candidateIssues): VerifyDecision
   default  = { _, _, cand -> FixVerifier().decide(target, baselineForFile, cand) }   // unchanged behavior
   for CPX  = ComplexityVerifier(functionCount).decide(target, baselineForFile, original, candidate, candidateIssues)
```

`ComplexityVerifier.decide` returns `Accept` iff:
- **no regression** — no *other* rule's per-`ruleKey` count increased vs `baselineForFile` (reuse `FixVerifier`'s count logic; `AEG-CPX-001` is naturally absent from candidate re-analysis and is ignored here), **and**
- **complexity strictly decreased** — `estimateComplexity(candidate, functionCount) < estimateComplexity(original, functionCount)`.

The complexity-decrease replaces the count-gate's "target resolved" (which can't apply to graph-level complexity). Tier-1 PSI-validity is unchanged and still runs first.

### 3.3 `CollapseBooleanReturn` operation

`@SerialName("collapseBooleanReturn")`, field `line`. Collapses an `if`/`else` whose both branches are boolean `return`s:
- `if (C) return true else return false` → `return C`
- `if (C) return false else return true` → `return !C`
- block bodies (`{ return true }`) handled.

**Kotlin**: PSI — locate the `KtIfExpression` on `line` whose `then`/`else` are `return true`/`return false` (either order); emit a `TextEdit` replacing the if-expression's range with `return <C>` / `return !<C>` (`<C>` = the condition's text; wrap in parens when negating a non-trivial condition). **JS/TS**: anchored multi-line regex over the masked line window; best-effort (gate-protected — a malformed collapse is rejected by Tier-2). Returns null when the pattern is absent/ambiguous.

### 3.4 `ComplexitySimplifierFixer` (`AEG-CPX-001`)

The issue is file-level (`line = 1`), so the fixer **scans the whole file** (Kotlin: all `KtIfExpression`s with boolean-return branches; JS/TS: regex) and emits a `FixPlan` of `CollapseBooleanReturn(line)` ops — one per collapsible site. All ops resolve their `TextEdit`s against the original content (offsets absolute), so a multi-collapse plan applies cleanly (descending-offset application, no line-shift interference). Declines (null) when no collapsible site exists — then the file is left to the AI extract-method path (follow-on). PSI-only for Kotlin (no Analysis API → thread-safe).

### 3.5 Wiring

`AEG-CPX-001` is routed through the complexity-aware acceptance (the orchestrator / `FixEngine` selects the `ComplexityVerifier` path for that rule, supplying the file's `functionCount`). The fixer is registered in `FixerRegistry`.

## 4. Verification semantics (the novel part)

For a simplification candidate:
1. **Tier-1**: PSI-valid (parse-clean) or revert — unchanged.
2. **Complexity**: `estimateComplexity(candidate) < estimateComplexity(original)` — the candidate must measurably simplify. A no-op or complexity-increasing edit is rejected.
3. **No regression**: candidate re-analysis introduces no new issue of any other rule (existing `FixVerifier` count check).

All three required to accept (save); otherwise revert. This keeps acceptance **deterministic** (no AI judgment) and guarantees the engine never "simplifies" by making code more complex or by breaking it.

## 5. Testing strategy

- `estimateComplexity` reuse: a focused test pinning that a boolean-return collapse lowers the score.
- `ComplexityVerifier.decide` (pure): accepts on strict decrease + no regression; rejects on equal/increased complexity; rejects on a new other-rule issue.
- `CollapseBooleanReturn`: KT PSI cases (both branch orders, block bodies, negation) + TS case + null on absent/ambiguous pattern.
- `ComplexitySimplifierFixer`: emits one op per collapsible site; declines when none.
- End-to-end (deterministic, Batch-1 pattern): a high-complexity file with boolean-return patterns → fixer → complexity-aware `applyVerified` → Accept + edits applied + recomputed complexity strictly lower. (`AEG-CPX-001` is graph-shadowed in single-file re-analysis, so the target/`functionCount` are supplied directly and `reanalyze` is stubbed; the complexity check runs on real content.)

## 6. Phasing

- **B1 — complexity-aware gate**: expose `estimateComplexity` use, add `ComplexityVerifier`, thread the acceptance seam through `applyVerified`. Tests prove accept-on-decrease / reject-on-non-decrease / reject-on-regression. (No ops yet — the gate is the foundation, independently testable.)
- **B2 — deterministic simplification**: `CollapseBooleanReturn` op + `ComplexitySimplifierFixer` + `AEG-CPX-001` wiring + e2e.

## 7. Risks / open questions

- **TS regex fragility**: JS/TS boolean-return collapse is regex-based (no Community PSI); a bad collapse is caught only by Tier-1 (no JS parser → weak) + the complexity/no-regression gate. Mitigation: conservative anchored patterns; decline on ambiguity; Kotlin (PSI) is the primary, fully-verified path.
- **`functionCount` stability**: assumed constant for branch-elimination (true). The AI extract-method follow-on must recompute it per candidate — noted there, out of scope here.
- **Narrow deterministic reach**: only boolean-return collapse moves the metric deterministically; many high-complexity files won't have such patterns and will (correctly) be declined, awaiting the AI path. That is acceptable — the gate + this op prove the mechanism end-to-end; the AI follow-on supplies breadth.
- **Strict-decrease vs below-threshold**: acceptance requires a strict decrease, not dropping below the threshold. A single collapse may not clear the threshold but still legitimately simplifies; requiring only a strict decrease keeps each applied fix a genuine improvement and composes across multiple sites.
