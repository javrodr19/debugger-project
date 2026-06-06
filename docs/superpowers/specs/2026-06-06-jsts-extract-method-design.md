# JS/TS Extract-Method — Design (Sub-project B, spec 3: JS/TS structural simplification)

**Date:** 2026-06-06
**Status:** Approved (design); pending spec review → implementation plan
**Sub-project:** B (code simplification), spec 3. Extends the Kotlin-only AI extract-method
(`2026-06-05-ai-extract-method-design.md`, merged as C1+C2) to `.ts`/`.js`, the follow-on that spec
deferred (its §2/§7) because per-function measurement needs a parser JS/TS lacks in IntelliJ Community.

## 1. Motivation

`AEG-CPX-001` (high complexity) fires for JS/TS files too — `GraphBuilder.estimateComplexity` is
content-based and language-agnostic. The AI extract-method path (C2) routes `AEG-CPX-001` candidates
that *add a function* to `ExtractMethodVerifier`, but that verifier measures per-function complexity
with the **Kotlin** PSI measurer (`PerFunctionComplexity`, which parses content as a `KtFile`). For a
`.ts` file that parse yields an empty/garbage map, so a JS/TS extraction is currently **declined**
(no source "got simpler") — safe, but unsupported. This spec makes JS/TS extractions *acceptable*.

Three facts shape the design:

1. **No JS/TS PSI.** IntelliJ Community ships no JS/TS parser, so `FixPlanApplicator`'s Tier-1
   `PsiErrorElement` gate is a no-op for `.ts`/`.js` (the file is plain text). Per-function complexity
   must be measured by **regex + balanced brace-matching** over comment/string-masked content, and a
   **substitute structural gate** (delimiter balance) must stand in for the missing parse-validity net.
2. **The ops, the dispatch trigger, the metric, and the prompt are already language-agnostic.**
   `ReplaceLines`/`InsertLinesAfter` are content-based (already used by JS/TS in B2); `FunctionCounter`
   counts `function`/`=>`; `GraphBuilder.estimateComplexity(body, 1)` is the per-function metric;
   `planFix`'s extract-method section says "function"/"closing brace" generically. So JS/TS work is
   concentrated in **measurement** (§3.1) and the **substitute Tier-1** (§3.2), plus a small dispatch
   branch (§3.4).
3. **JS has no overloading.** Two functions with the same name in one scope are illegal/shadowing, so
   JS/TS per-function complexity uses a **name-only key** (vs Kotlin's `name/arity`).

Design decisions (from brainstorming): **(a)** compensate for the missing parser with a lightweight
**brace/paren/bracket balance check** (substitute Tier-1), stacked with the per-function decomposition
gate + no-regression re-analysis; **(b)** scope to **`.ts`/`.js`**, **named `function` + const-arrow
block functions** only (reuse `TsJsRegexSymbolExtractor`'s detection; exclude class methods and
`.tsx`/`.jsx`).

## 2. Goals / Non-goals

**Goals**
- `JsTsPerFunctionComplexity.measure(content): PerFunctionComplexity.Result` — name-keyed per-function
  complexity for `.ts`/`.js` via regex + balanced brace-matching; same `Result` shape as the Kotlin
  measurer so the gate is reused.
- `JsTsStructuralCheck.isBalanced(content): Boolean` — masked `()`/`{}`/`[]` balance, the JS/TS
  substitute Tier-1.
- Generalize `ExtractMethodVerifier` to accept an injected per-function `measure` (default = Kotlin),
  so the identical five-condition gate serves both languages.
- Extend `FixEngine`'s `AEG-CPX-001` extraction branch to dispatch on `issue.filePath`: `.kt` → Kotlin
  measurer; `.ts`/`.js` → balance-check-then-JS/TS-measurer.

**Non-goals**
- **`.tsx`/`.jsx`** — JSX text isn't masked (could be miscounted as decision points) and angle-bracket
  syntax stresses balancing; a clean later extension.
- **Class methods / object-method shorthand** — `TsJsRegexSymbolExtractor` doesn't detect them and a
  reliable method-vs-call regex is hard without a parser; later extension.
- **Expression-body arrows** (`const f = () => expr`) — no block to extract from / into; skipped.
- **No change** to `ReplaceLines`/`InsertLinesAfter`, the dispatch trigger, `planFix`, the threshold,
  `ComplexityAnalyzer`, or the Kotlin path (`PerFunctionComplexity`, the Kotlin gate behavior).
- **Semantic/behavioral verification** of the extraction — out of reach for a static gate (see §7).

## 3. Architecture

### 3.1 `JsTsPerFunctionComplexity` (measurement without PSI)

A new helper in `fix/engine/`. `measure(content: String): PerFunctionComplexity.Result` (reuses the
Kotlin measurer's `Result(byKey: Map<String,Int>, collision: Boolean)` type):

1. `masked = TsJsRegexSymbolExtractor().maskStringsAndComments(content)` — keywords/braces inside
   strings/comments are blanked (length+line preserving), so brace-matching and counting are safe.
2. Scan `masked` for function declarations with these patterns (matching the extractor's, position-aware):
   - `function <name>(` (optionally `export`/`default`/`async`), and
   - `const <name> = ( … ) =>` / `const <name> = async ( … ) =>` (optionally `export`).
3. For each declaration, **delimit the body**: from the declaration, balanced-paren-match the first
   `(…)` (the parameter list), then find the body `{` after the `)` (skipping a `: ReturnType`
   annotation that contains no `{`), then balanced-brace-match `{…}`. If there is no body block
   (expression-body arrow) or the braces don't balance before end-of-content, **skip** this function
   (it cannot be a measured source or target — the gate will then reject if it was needed).
4. `complexity = GraphBuilder().estimateComplexity(bodyText, 1)` (`bodyText` = the original substring
   for the body range; `estimateComplexity` re-masks internally). Same metric, single-sourced.
5. Key by **name** (no arity). On a duplicate name, set `collision = true` (the gate declines).

Balanced matching uses the masked content for delimiter counting; nested braces (object literals, inner
functions) are handled by depth counting. Like the Kotlin measurer, an inner function's decision points
count toward the enclosing function (consistent with `KtNamedFunction.bodyExpression.text`).

### 3.2 `JsTsStructuralCheck` (substitute Tier-1)

`isBalanced(content: String): Boolean` in `fix/engine/`: over `maskStringsAndComments(content)`, track
the running depth of `()`, `{}`, `[]`; return false if any closer makes a depth negative or any depth is
nonzero at end. This is the JS/TS stand-in for the PSI parse-validity gate that `.ts`/`.js` lack — it
catches the gross malformations (dropped/extra brace) an AI extraction could introduce. Best-effort, not
a parser; it does not validate JS grammar, only delimiter nesting.

### 3.3 `ExtractMethodVerifier` generalization

The gate's five conditions (no regression; exactly one new function; a source got strictly simpler;
source over threshold; new function strictly simpler than the original source) are language-independent —
only *measurement* differs. `ExtractMethodVerifier` gains a constructor parameter:

```
class ExtractMethodVerifier(
    project: Project,
    threshold: Int,
    measure: (String) -> PerFunctionComplexity.Result = { PerFunctionComplexity.measure(project, it) },
)
```

`decide(...)` calls `measure(originalContent)` / `measure(candidateContent)` instead of the hardcoded
Kotlin call. The default preserves C2's Kotlin behavior exactly (existing `ExtractMethodVerifierTest`
and the Kotlin dispatch are unchanged). For JS/TS, `FixEngine` injects `JsTsPerFunctionComplexity::measure`.

### 3.4 `FixEngine` dispatch — add a language branch

The existing `AEG-CPX-001` acceptance (C2) dispatches on whether the candidate added a function. The
**extraction** branch now also branches on `issue.filePath`:

```
extraction branch (FunctionCounter.count(candidate) > count(original)):
    if issue.filePath ends with ".kt":
        ExtractMethodVerifier(project, threshold).decide(...)                       // Kotlin (unchanged)
    else if issue.filePath ends with ".ts" / ".js":
        if !JsTsStructuralCheck.isBalanced(candidate): Reject("Extraction left unbalanced delimiters.")
        else ExtractMethodVerifier(project, threshold, JsTsPerFunctionComplexity::measure).decide(...)
    else:
        ExtractMethodVerifier(project, threshold).decide(...)                       // default (Kotlin)
in-place branch: ComplexityVerifier(FunctionCounter.count(original)).decide(...)    // unchanged, language-agnostic
```

`threshold` is read live from settings (as today). The in-place (B2 collapse) branch is content-based
and untouched. Non-CPX rules → null (default `FixVerifier` gate).

### 3.5 Ops & prompt — unchanged

`ReplaceLines`/`InsertLinesAfter` already resolve against content for any language. `planFix`'s
extract-method section (C2) is phrased generically ("the most complex function", "the source's closing
brace") and already renders for any `AEG-CPX-001` issue. No change.

## 4. Verification semantics

For an AI-proposed JS/TS extraction candidate (`AEG-CPX-001`, `.ts`/`.js`, function added):
1. **Structural** (substitute Tier-1): candidate delimiters balanced (`JsTsStructuralCheck`), else reject.
2. **Structure**: exactly one new function appeared (routes here + gate condition).
3. **Per-function decomposition**: the source function was over threshold and is now strictly simpler,
   and the new function is strictly simpler than the original source (`ExtractMethodVerifier`, JS/TS
   measurer).
4. **No regression**: candidate re-analysis introduces no new issue of any other rule.

All required to accept (save); otherwise revert. Deterministic verdict; the AI only proposes/revises.
(For `.kt`, step 1 is the real PSI Tier-1 in `FixPlanApplicator`; for `.ts`/`.js`, step 1 is the
balance check in the acceptance — the rest is identical to Kotlin.)

## 5. Testing strategy

- **`JsTsPerFunctionComplexity`**: a `.ts` snippet with a `function` and a `const`-arrow of known
  branchiness → expected `name → complexity`; nested braces / object literal inside a body measured
  correctly; expression-body arrow skipped; a brace-unbalanced function skipped; duplicate name → `collision`.
- **`JsTsStructuralCheck`**: balanced content → true; dropped `}` / extra `)` / unbalanced `[` → false;
  imbalance inside a string/comment is ignored (masked).
- **`ExtractMethodVerifier` (JS/TS measurer)**: accept a genuine `.ts` decomposition; reject the
  not-over-threshold / source-didn't-shrink / new-fn-not-simpler / regression cases — reusing the same
  gate via the injected measurer. (Existing Kotlin `ExtractMethodVerifierTest` stays green via the default.)
- **`FixEngine` dispatch**: a `.ts` `AEG-CPX-001` extraction candidate routes to the JS/TS gate (assert a
  genuine one is accepted and/or a not-over-threshold one rejected with the extract reason); a `.ts`
  candidate with unbalanced delimiters is rejected by the balance check; a `.kt` extraction still uses the
  Kotlin path; an in-place candidate still uses `ComplexityVerifier`.
- **End-to-end** (deterministic, C2 pattern): an over-threshold `.ts` function + a hand-authored
  `ReplaceLines` + `InsertLinesAfter` plan (simulating the AI) through `FixEngine.fixVerified` →
  Accept + the new function present in the saved text + the source's per-function complexity strictly
  lower. Threshold lowered via settings (restored in `finally`); `reanalyze` stubbed empty; the gate runs
  on real content. (No PSI needed for JS/TS, but `BasePlatformTestCase` + `Dispatchers.Unconfined` is used
  for the document/apply lifecycle.)

## 6. Phasing

A **single plan** (the pieces are tightly coupled — measurement is useless without the wiring), tasks
each independently green:
1. `JsTsPerFunctionComplexity` + tests.
2. `JsTsStructuralCheck` + tests.
3. `ExtractMethodVerifier` measurer injection (default-preserving) + tests.
4. `FixEngine` language dispatch + tests.
5. e2e.

## 7. Risks / open questions

- **Object-literal return types** (`function f(): { x: number } {`) can mis-delimit the body (the
  return-type `{` is matched as the body). Best-effort: such mis-delimiting usually fails to balance →
  the function is skipped → the gate rejects; the balance check + no-regression are further backstops.
  Rare in practice within the conservative scope. A typed-return-aware delimiter is a later refinement.
- **No real parser** — the balance check catches delimiter malformation but not JS grammar errors (e.g.
  a missing `;` that ASI wouldn't save). The same accepted ceiling as Kotlin §7: the gate guarantees
  structure, not semantics; the change surfaces to the user like any fix and is reversible.
- **Regex function detection gaps** — generators (`function*`), decorators, or unusual formatting may not
  be detected; those functions are simply not measured (conservative decline), never mis-applied.
- **`name`-only keys** assume no same-name functions in one file; a duplicate sets `collision` and the
  gate declines (acceptable under the conservative-miss bias).
