# Fix Engine — Op-Catalog Breadth Design

**Date:** 2026-06-02
**Status:** Approved (design); pending spec review → implementation plans
**Sub-project:** Catalog breadth (sub-project A of the "fix anything / simplify" effort; the code-simplification subsystem is sub-project B, specced separately and built after this).

## 1. Motivation

The fix engine currently ships **3 operations** (`ReplaceRange`, `InsertImport`, `ConvertToSafeCast`) and deterministic fixers for **5 of 11** analyzer rules (`AEG-NULL-001`, `AEG-CAST-KT-001`, `AEG-REDUNDANT-LET-KT-001`, `AEG-STATE-001`, `AEG-ASYNC-001`). Six rules have no fixer. We want the engine to fix far more — *correctly* — and to give the AI planner (Phase 2c-ii-a) a richer set of primitives so it succeeds more often.

The Phase-2c-ii-a retrospective identified the planner's weak point: ops keyed by **character offset** (`ConvertToSafeCast.asOffset`) are hard for the AI to emit and brittle across a multi-op plan. Breadth must therefore come with **AI-friendly targeting**, not just more offset ops.

## 2. Goals / Non-goals

**Goals**
- Add ~11 semantic, language-dual operations covering null-safety, async, error-handling, type conversion, and structural edits.
- Add deterministic fixers for the safely-fixable unfixed rules: `AEG-NULL-KT-001` (Kotlin null-safety) and `AEG-TYPE-KT-001` (safe conversions), and broaden the JS/TS `NullSafetyFixer` / `AsyncFlowFixer`.
- Cover **both Kotlin (PSI-verified) and JS/TS (content + re-analysis gate)**.
- Make every new op usable by the **AI planner** via a self-describing op catalog that regenerates the `planFix` prompt (single source of truth).
- Introduce **line-based targeting** so ops are robust to line shifts and emittable by the AI.

**Non-goals (correctness honesty)**
- **No** deterministic fixers for `AEG-SYNTAX-001`, `AEG-COMPILE-001`, `AEG-CYCLE-001` — these require semantic/architectural judgment and would violate the no-false-positive rule. They remain AI-planner best-effort, protected by the verify gate.
- **No** new verification semantics here — these ops ride the existing Tier-2 count-based gate (+ Tier-1 PSI-validity for Kotlin). Complexity-aware verification belongs to sub-project B.
- **No** change to the user-triggered fix UX (intention / inspection / webview). Breadth only widens what those surfaces can offer.
- **No** auto-apply: nothing changes about when fixes run.

## 3. Architecture

### 3.1 Line-based targeting

New ops name their target by **`line: Int` (1-based) + an optional disambiguator** (e.g. a receiver identifier or matched token), and resolve the exact span at apply-time. This replaces blind offsets: the AI knows the issue's line and symbol, not byte positions, and a target stays valid when earlier edits shift lines.

A shared `LineLocator` helper provides:
- `lineSpan(content, line): IntRange?` — char range of a 1-based line (built on the existing `lineStartOffsets`).
- `firstMatch(content, line, token): IntRange?` — first occurrence of `token` within the line.
- `psiElementAt(ktFile, line, predicate): PsiElement?` — Kotlin PSI node on a line matching a predicate (e.g. first `KtDotQualifiedExpression`).

Each op resolves its target through `LineLocator`; **unresolved → `toEdit` returns null** (gate-safe: no blind edit). Existing offset ops are unchanged; `ConvertToSafeCast` may later be re-expressed line-based but that is out of scope here.

### 3.2 Language-dual operations

Each op supports one or both languages and chooses its path inside `toEdit`:
```
if (ctx.psiFile is KtFile) { /* PSI path: PSI-valid-by-construction */ }
else { /* content path: regex/text against ctx.content */ }
```
An op returns null for a language it does not support (e.g. `AddAwait` → null on Kotlin). Kotlin paths produce PSI-valid output (Tier-1 gate enforces); JS/TS paths produce syntactically plausible text and rely on the Tier-2 content re-analysis gate. The op-validity contract is unchanged: **never emit invalid output — return null and let the caller fall back.**

### 3.3 Op catalog (≈11 new operations)

All `@Serializable @SerialName(...)` subclasses of `FixOperation`. `KT` = Kotlin (PSI), `TS` = JS/TS (content).

| Op (`@SerialName`) | Fields | Effect | Langs |
|---|---|---|---|
| `wrapInSafeCall` | line, receiver | `r.m` → `r?.m` | KT, TS |
| `addElvisDefault` | line, expr, default | `e` → `e ?: d` (KT) / `e ?? d` (TS) | KT, TS |
| `surroundWithNullCheck` | line, variable | wrap statement in `if (x != null) { … }` | KT, TS |
| `addAwait` | line, call | prefix call with `await` | TS |
| `addPromiseCatch` | line, handler? | append `.catch(e => …)` | TS |
| `surroundWithTryCatch` | startLine, endLine, catchBody? | wrap a line range in try/catch | KT, TS |
| `addExplicitConversion` | line, expr, conversion | wrap expr (`.toString()`, `toIntOrNull()`, `String()`, `Number()`) | KT, TS |
| `removeRange` | startLine, endLine | delete located lines (dead code) | KT, TS |
| `replaceExpression` | line, find, replacement | replace first `find` on `line` with `replacement` | KT, TS |
| `insertStatementBefore` | line, statement | insert a statement line before `line` | KT, TS |
| `insertStatementAfter` | line, statement | insert a statement line after `line` | KT, TS |

Each op implements `toEdit(ctx): TextEdit?` returning null when unsupported-language or unresolved-target.

### 3.4 Self-describing catalog → AI prompt

Introduce `FixOperationCatalog`: a registry mapping each op to a one-line JSON-schema description (`{"type":"wrapInSafeCall","line":<int>,"receiver":"<id>"}`) plus its supported languages. `PromptTemplates.planFix` builds its operations section **from this registry** instead of a hardcoded list, so adding an op automatically updates the AI prompt. `FixPlanCodec` is unchanged (kotlinx already decodes the sealed hierarchy).

### 3.5 Deterministic fixers

- **`KotlinNullSafetyFixer`** (`AEG-NULL-KT-001`): PSI-driven; for a nullable member access produce `WrapInSafeCall`, or `AddElvisDefault` when a sensible default exists; null otherwise.
- **`KotlinTypeMismatchFixer`** (`AEG-TYPE-KT-001`): **only** unambiguously-safe conversions (e.g. `Int`→`String` via `.toString()`, `String`→`Int?` via `.toIntOrNull()`); null for anything requiring judgment.
- **Extended `NullSafetyFixer` (JS/TS)**: use `WrapInSafeCall` / `AddElvisDefault` where it currently produces narrower edits.
- **Extended `AsyncFlowFixer` (JS/TS)**: use `AddAwait` / `AddPromiseCatch` / `SurroundWithTryCatch`.

**Fixer → op path.** Today `Fixer.generateFix` returns a text `CodeFix` that `toFixPlan` adapts to a single `ReplaceRange`. To let a deterministic fixer emit a *semantic* op (so each transformation lives once, in the op, used by both the fixer and the AI), extend `Fixer` with an optional `fun generatePlan(issue: Issue, ctx: FixContext): FixPlan? = null`. `FixDeriver` tries `generatePlan` first, then falls back to the existing `generateFixFromPsi` / `generateFix` (→ `CodeFix` → `ReplaceRange`) path — fully backward-compatible (the 5 existing fixers keep working untouched). The new `KotlinNullSafetyFixer` / `KotlinTypeMismatchFixer` implement `generatePlan` to return the semantic ops directly. Fixers stay deterministic and return null rather than guess.

### 3.6 How it all composes

```
Issue ──► FixerRegistry.forIssue ──► deterministic FixPlan ─┐
                                                            ├─► applyVerified (Tier-2 gate)
AI planner (proposeFixPlan, richer catalog) ──► FixPlan ────┘
```
Unchanged orchestration (`fixSupervised`): deterministic first, then AI with rejection feedback, every candidate gated.

## 4. Verification

No new verification. New ops are exercised by:
- **Kotlin**: Tier-1 PSI-validity (parse-clean or revert) + Tier-2 count gate (target rule resolved, no rule count rises).
- **JS/TS**: Tier-2 content re-analysis gate only (documented weaker guarantee — accepted per the language-scope decision).

## 5. Testing strategy

- Each op: unit tests for the KT path (against a `BasePlatformTestCase` PSI file) and the TS path (content), incl. the **unresolved-target → null** and **unsupported-language → null** cases.
- Each fixer: tests that it emits the expected plan for representative issues and **returns null** for ambiguous cases (no-false-positive guard).
- `FixOperationCatalog`: a test asserting every `FixOperation` subclass has a catalog entry (so new ops can't silently miss the AI prompt).
- One end-to-end supervised test per batch (real analyzer issue → fix → gate accept).

## 6. Phasing

Each batch is its own implementation plan; each op/fixer is a TDD task.

1. **Targeting + null-safety**: `LineLocator`, the `Fixer.generatePlan` extension + `FixDeriver` wiring, `WrapInSafeCall`, `AddElvisDefault`, `SurroundWithNullCheck`, `KotlinNullSafetyFixer`.
2. **Async + errors**: `AddAwait`, `AddPromiseCatch`, `SurroundWithTryCatch`, extended `AsyncFlowFixer`.
3. **Type/conversion**: `AddExplicitConversion`, `KotlinTypeMismatchFixer`, extended JS/TS `NullSafetyFixer`.
4. **Structural**: `RemoveRange`, `ReplaceExpression`, `InsertStatementBefore/After`.
5. **AI catalog**: `FixOperationCatalog` + regenerate `planFix`, catalog-coverage test, end-to-end supervised test.

## 7. Risks / open questions

- **Semantic-changing fixes** (e.g. `WrapInSafeCall` makes a result nullable) can introduce a downstream type error. Mitigation: the Tier-2 re-analysis gate rejects candidates that add a new issue; the fixer prefers `AddElvisDefault`/null-check where it preserves the non-null contract.
- **JS/TS lacks PSI validity** — a malformed content edit is only caught if it produces a *detectable* new issue. Mitigation: conservative regex anchored to the issue's line; ops return null on ambiguity.
- **AI offset legacy**: `ConvertToSafeCast` remains offset-based; not migrated here. Acceptable — new ops set the line-based precedent.
- **Catalog/prompt size**: ~14 ops in the prompt grows token use. Acceptable; the registry keeps it one line per op.
