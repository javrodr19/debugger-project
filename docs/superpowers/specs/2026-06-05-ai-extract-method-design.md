# AI Extract-Method — Design (Sub-project B, spec 2: AI-supervised structural simplification)

**Date:** 2026-06-05
**Status:** Approved (design); pending spec review → implementation plans
**Sub-project:** B (code simplification), spec 2. The deferred follow-on to `2026-06-04-code-simplification-design.md` (spec 1, deterministic micro-simplification — B1 gate + B2 `CollapseBooleanReturn`, both merged). This spec covers **AI-supervised extract-method**: the high-value structural simplifier the first spec named as a follow-on.

## 1. Motivation

`ComplexityAnalyzer` flags `AEG-CPX-001` when a file node's estimated complexity exceeds the threshold (default 10). B2 added a deterministic micro-fix (collapse boolean-return if/else), but the highest-value simplification for genuinely complex code is **extract-method**: pull a cohesive block of branching logic out of an over-long function into its own named function. Three facts shape this design:

1. **The metric is a per-file average.** `GraphBuilder.estimateComplexity(content, functionCount) = 1 + decisionPoints / functionCount`. Extract-method does **not remove** decision points — it raises `functionCount` (one more function), so the file average drops. This means *any* extraction, even a trivial one, mechanically lowers the file metric. A strict-decrease gate alone is therefore **gameable**.
2. **Per-function complexity is the same metric at function granularity.** `estimateComplexity(functionBody, 1) = 1 + decisionsInBody` is exactly that function's own complexity. So per-function measurement needs **no new metric** — it is the existing file-level metric applied to one body, keeping the definition single-sourced in `GraphBuilder`.
3. **The AI already authors text inside bounded ops.** The op catalog already lets the planner author free text in `ReplaceRange.text`, `InsertStatementAfter.statement`, `SurroundWithTryCatch.catchBody`, etc. The "AI never authors raw fix code" principle forbade free-form *whole-file* rewrites with no structure or verification — not text inside a bounded, gate-verified op. Extract-method via AI-composed ops is the same pattern at a larger granularity.

The design decisions (from brainstorming): **(a)** the AI composes ops to author the extraction (the engine applies bounded line-based ops; the gate verifies) — not a hand-rolled deterministic extractor, not the platform's internal refactoring engine; **(b)** the gate is **per-function** (genuine decomposition), not the gameable file-average; **(c)** **Kotlin-only** for this spec (the per-function gate needs PSI-backed function-body measurement; JS/TS is a later follow-on).

## 2. Goals / Non-goals

**Goals**
- A **per-function complexity** helper (`PerFunctionComplexity`): Kotlin content → `Map<name/arity, complexity>` via `estimateComplexity(body, 1)`.
- Two **general line-based ops** (`ReplaceLines`, `InsertLinesAfter`) the AI composes to author an extraction; exposed in the op catalog.
- A **per-function verify gate** (`ExtractMethodVerifier`): accepts an extraction iff PSI-valid, exactly one new function appeared, the source function was over threshold and got strictly simpler, the new function is strictly simpler than the original source, and no other rule regressed.
- **Auto-dispatch** for `AEG-CPX-001` in `FixEngine`: a candidate that *added a function* is judged by `ExtractMethodVerifier`; an in-place candidate (B2 collapse) keeps B2's `ComplexityVerifier`.
- A **planner-prompt** extension teaching extract-method for high-complexity issues.

**Non-goals**
- **JS/TS extract-method** — needs PSI-backed per-function measurement; a clearly-scoped later follow-on once the Kotlin mechanism is proven.
- **Extract-expression / extract-variable / inline** — only extract-statements-into-method.
- **A deterministic data-flow extractor or driving IntelliJ's `ExtractionEngine`** — explicitly rejected during brainstorming (large reimplementation / brittle internal-API integration under K2 + IPGP). The AI authors the extraction; the gate verifies.
- **A per-function analyzer / new rule** — `AEG-CPX-001` stays file-level; per-function measurement lives only in the *gate*.
- **No change to `estimateComplexity`, the threshold, or `ComplexityAnalyzer`.**
- **Semantic/behavioral verification** of the extraction — out of reach for a static gate (see §7).

## 3. Architecture

### 3.1 Per-function complexity (`PerFunctionComplexity`)

A new helper in `fix/engine/`. `measure(project, content): Map<String, Int>`:
- Parse `content` into an in-memory `KtFile` via `PsiFileFactory.getInstance(project).createFileFromText("temp.kt", KotlinFileType.INSTANCE, content)` inside a **read action** (structural PSI only — **no** Analysis API, so it is thread-safe off-EDT and never resolves types).
- For each `KtNamedFunction` with a body, key by **`"${fn.name}/${fn.valueParameters.size}"`** (name + arity — stable across an extraction, which does not change the source's signature) and value `GraphBuilder().estimateComplexity(fn.bodyExpression!!.text, 1)` (expression bodies and block bodies both have a `bodyExpression`; functions without a body are skipped).
- On a **duplicate key** (overloads with identical arity), the map cannot distinguish the two; `measure` records the collision (e.g. returns a marker the verifier reads) so the verifier **declines conservatively** rather than mis-attribute a complexity delta.

This is the single place per-function complexity is computed; reused for the original and the candidate.

### 3.2 Two line-based ops (`ReplaceLines`, `InsertLinesAfter`)

Char offsets are unreliable for an LLM to compute; **lines** are not. Two new `@Serializable` `FixOperation`s, both **verbatim** (the AI authors exact text, including indentation; the gate's PSI-validity check catches mistakes):

- **`ReplaceLines(startLine, endLine, text)`** `@SerialName("replaceLines")` — replace the half-open char range `[lineStart(startLine), lineEnd(endLine)]` with `text` verbatim. Returns null if either line is out of range or `startLine > endLine`. Used to swap the extracted block for the call (`val r = newName(a, b)`).
- **`InsertLinesAfter(afterLine, text)`** `@SerialName("insertLinesAfter")` — insert `"\n" + text` immediately after the end of `afterLine` (a blank-line-separated sibling block). Returns null if `afterLine` is out of range. Used to drop the new function `G` in after the source function `F`'s closing brace.

Both resolve against the original content snapshot (absolute offsets) and are applied descending-by-offset by `FixPlanApplicator`, so a two-op extraction never has the ops interfere: the block is inside `F`; `G` is inserted after `F`'s closing line — disjoint ranges. The AI authors **only** the call line(s) and the new function `G` — never re-authoring the unchanged body of `F` — which minimizes the AI-authored surface (hence transcription risk). Both ops are general (not extraction-specific) and are added to `FixOperationCatalog` so the planner can compose them (and `FixOperationCatalogTest`'s coverage assertion grows 15 → 17).

### 3.3 The per-function gate (`ExtractMethodVerifier`)

A new verifier in `fix/engine/`. `ExtractMethodVerifier(project, threshold)` with
`decide(target, baselineForFile, originalContent, candidateContent, candidateForFile): VerifyDecision`. It builds `PerFunctionComplexity.measure` for both sides and accepts **iff all** hold (else `Reject(reason)`):

1. **No regression** — no *other* rule's per-`ruleKey` count increased vs `baselineForFile` (reuse the count logic; `AEG-CPX-001` itself is ignored — graph-level, absent from single-file re-analysis). Same check as `ComplexityVerifier`.
2. **Exactly one new function** — `candidate.keys - original.keys` has size 1; call it `G`, complexity `cG`. (Zero ⇒ not an extraction; >1 ⇒ not a clean single extraction.) Also reject if either side reported a duplicate-key collision (§3.1).
3. **A source got simpler** — among shared keys, pick the source `F` as the one with the largest drop `original[F] - candidate[F] > 0`; reject if none decreased.
4. **Genuine target** — `original[F] > threshold` (only a genuinely over-threshold function is a valid extraction target; prevents extracting from already-simple functions just to pad `functionCount`).
5. **Genuine decomposition** — both halves strictly simpler than the original whole: `candidate[F] < original[F]` (from step 3) **and** `cG < original[F]`.

Like B2, acceptance requires a **strict decrease, not** dropping below threshold — so multiple extractions compose across a very complex function. A 0-decision block cannot pass (it would not shrink `F`, failing step 3). Tier-1 PSI-validity runs first (in `FixPlanApplicator`) and reverts a non-parsing candidate before this gate is consulted.

### 3.4 Routing — one rule, two strategies, auto-dispatched

`AEG-CPX-001` now has two simplifiers: B2's deterministic `CollapseBooleanReturn` (via `ComplexitySimplifierFixer`) and AI extract-method. `FixEngine`'s acceptance selection for `AEG-CPX-001` (today `complexityAcceptanceOrNull`, which always returns the `ComplexityVerifier` path) is generalized to **dispatch on what the candidate did**, using `FunctionCounter`:

```
acceptance(original, candidate, candidateIssues):
    if FunctionCounter.count(candidate) > FunctionCounter.count(original):   // a function was added → extraction
        ExtractMethodVerifier(project, threshold).decide(target, baseline, original, candidate, candidateIssues)
    else:                                                                    // in-place → B2 collapse
        ComplexityVerifier(FunctionCounter.count(original)).decide(target, baseline, original, candidate, candidateIssues)
```

`threshold = GhostDebuggerSettings.getInstance().snapshot().maxComplexity` (the same source `ComplexityAnalyzer` reads). Both simplifiers flow through the existing `fixSupervised` lifecycle (deterministic-first, then bounded AI attempts with rejection feedback); each candidate auto-routes to the correct gate. **B2's `ComplexityVerifier` is unchanged.** This is the minimal seam: replace the single-verifier body of `complexityAcceptanceOrNull` with the dispatch; everything else in `FixEngine`/`FixPlanApplicator` is untouched.

### 3.5 Prompt

`PromptTemplates.planFix` gains a conditional section, emitted only when `issue.ruleId == "AEG-CPX-001"`, that teaches extract-method: identify the most complex function; move a cohesive block of branching logic into a new, well-named function; emit a `ReplaceLines` swapping the block for a call plus an `InsertLinesAfter` defining the new function after the source's closing brace; **both** the shrunken function and the new function must end up simpler than the original. The two new ops already render into the catalog list, so no other prompt change is needed. (The deterministic `CollapseBooleanReturn` remains tried first; the AI extract-method is the fallback when no deterministic micro-fix applies — exactly the `fixSupervised` order.)

## 4. Verification semantics (the novel part)

For an AI-proposed extract-method candidate:
1. **Tier-1**: PSI-valid (parse-clean) or revert — unchanged, runs first.
2. **Structure**: exactly one new function appeared (else reject) — this is also what routes the candidate to this gate (§3.4).
3. **Per-function decomposition**: the source function was over threshold and is now strictly simpler, and the extracted function is strictly simpler than the original source (§3.3 steps 4-5).
4. **No regression**: candidate re-analysis introduces no new issue of any other rule.

All required to accept (save); otherwise revert. Acceptance stays **deterministic** (no AI judgment in the verdict) and guarantees the engine never "simplifies" by gaming the file average, by relocating complexity wholesale into an equally-complex helper, or by breaking the parse. The AI only *proposes and revises* (fed each rejection reason as feedback).

## 5. Testing strategy

- **`PerFunctionComplexity`**: a Kotlin file with two functions of known branchiness → the expected `name/arity → complexity` map; expression-body and block-body functions both measured; a duplicate name+arity overload pair → collision marker.
- **`ReplaceLines` / `InsertLinesAfter`** (op unit tests): apply → expected text; out-of-range/invalid line → null; verbatim text (indentation preserved as authored); descending-offset application of the two together on a sample yields a correct extraction.
- **`ExtractMethodVerifier`** (uses `project` to parse): **accept** a genuine decomposition; **reject** when no function was added, when the source was not over threshold, when the source did not get simpler, when `G` is not strictly simpler than the original source, and when a new other-rule issue appears (regression). Threshold injected via the constructor.
- **Routing** (`FixEngine`): an `AEG-CPX-001` candidate that adds a function is judged by the per-function gate (reject a non-genuine extraction with the extract-method reason), while an in-place collapse candidate is still judged by `ComplexityVerifier` (regression guard for B2 behavior).
- **End-to-end** (deterministic, B2 pattern): an over-threshold single Kotlin function + a **hand-authored** extraction plan (`ReplaceLines` block→call + `InsertLinesAfter` `G`) — simulating the AI — through `applyVerified` with the §3.4 dispatch → Accept + both functions present in the saved text + the source's per-function complexity strictly lower. The AI model is **not** under test (the wiring + gate are); `reanalyze` is stubbed empty so the gate runs on real content. PSI-only → `BasePlatformTestCase` + `Dispatchers.Unconfined`.

## 6. Phasing

- **C1 — building blocks**: `PerFunctionComplexity`, the `ReplaceLines` + `InsertLinesAfter` ops, catalog exposure (15 → 17) + codec round-trip. Deterministic, independently testable, no behavior change to existing rules. (The op-application machinery proven before any gate consumes it.)
- **C2 — gate + wiring**: `ExtractMethodVerifier`, the `FixEngine` auto-dispatch, the `planFix` extract-method prompt section, and the e2e. Lands the capability.

Each phase is independently green and mergeable, mirroring B1 → B2.

## 7. Risks / open questions

- **Semantic correctness of the AI extraction (primary, accepted).** The AI can author an extraction that *parses*, lowers per-function complexity, and adds no static issues yet changes behavior (e.g. a captured variable it forgot to pass that happens to resolve to an outer name; an altered evaluation order). A static gate cannot catch all behavior changes. Mitigation: Tier-1 PSI-validity + no-regression re-analysis + the change surfaces to the user like any fix (reversible). This is the inherent ceiling of AI-authored fixes and is accepted, consistent with the AI-supervised arc — the gate guarantees **structure**, not **semantics**.
- **Name+arity key collisions.** Overloads with the same arity are indistinguishable by the key; the gate declines conservatively (§3.1-3.2). Acceptable under the conservative-miss bias; a stricter key (including parameter types) is a possible later refinement.
- **In-memory parsing cost.** `ExtractMethodVerifier` parses two `KtFile`s per candidate. Files are single-source-file-sized and parsing is structural (no resolution); negligible vs. the existing re-analysis pass.
- **Average vs. per-function flag mismatch.** `AEG-CPX-001` flags the file average, but the gate verifies per-function. A file flagged purely by *many moderately-complex* functions (no single function over threshold) will have **no valid extraction target** (step 4 fails) and the AI path will correctly decline — leaving such files unsimplified by this mechanism. That is acceptable: extract-method's value is decomposing individual over-long functions; the file-average-only case is genuinely not an extract-method problem.
- **AI op reliability.** If the AI miscomputes a line number, the op returns null → the whole plan does not apply → that attempt is rejected and the next attempt gets feedback. No corruption; just a wasted attempt. Line-based ops (vs. offsets) keep this rare.
