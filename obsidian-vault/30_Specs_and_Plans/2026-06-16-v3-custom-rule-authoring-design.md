---
title: "Aegis Debug — V3.1 Custom Rule Authoring (Design)"
type: "spec"
status: "active"
related_components: []
aliases: []
tags:
  - aegis-debug
---

# Aegis Debug — V3.1 Custom Rule Authoring (Design)

**Date:** 2026-06-16
**Status:** Draft — forward-looking. First pillar of the V3 frontier
(`docs/superpowers/specs/2026-06-16-v3-frontier-overview-design.md`). **Not** part of
v2-stabilization; begins after that phase exits and ship-vs-build is decided.
**Version target:** V3.1 (post-2.0.0).

---

## 0. Summary

Let a developer extend Aegis's analysis **without forking the plugin or writing code**: drop a
declarative `.yml` file in `.aegis/rules/` and get a flagged finding on matching code, optionally
with a one-click deterministic fix. The design reuses three already-shipped seams so that almost no
new machinery is invented:

1. **Detection** plugs in as a single new `Analyzer` (`CustomRuleAnalyzer`) added to
   `AnalysisEngine.analyzers` — the same list that already holds `NullSafetyAnalyzer`,
   `StateInitAnalyzer`, etc.
2. **Fixes** reuse `FixPlan` (which is already `@Serializable`) — a rule's `fix:` block is
   decoded into a `FixPlan` exactly the way `FixPlanCodec` decodes the AI planner's output, then
   applied by `FixPlanApplicator` through the existing verify gate. Custom fixes inherit
   PSI-validity for free.
3. **Surfacing** reuses the V2 inspection/Problems path — a custom finding is just an `Issue` with
   a new `IssueSource.CUSTOM` and its originating rule id.

The net new code is a YAML schema, a loader, a bounded matcher evaluator, and the `CUSTOM`
provenance — not a new analysis or fix pipeline.

---

## 1. Motivation (brief — see frontier overview §1)

The fix engine made user-authored *fixes* safe to offer (they compose verified `FixOperation`s
instead of free-form text), and V2's inspection surfaces give custom findings a home. Custom rules
are also load-bearing for V4's breakpoint-aware relevance ranking. This is the foundation pillar:
rule packs (V3.2), the SDK (V3.4), and the fix-preview UX (V3.3) all build on the schema and loader
defined here.

---

## 2. Scope decisions (locked in)

| # | Decision | Rationale |
|---|---|---|
| D1 | **YAML-declarative, no code execution.** A rule is data, not a script. | Privacy/safety: rules load from any repo; loading them must never execute untrusted code. The `.jar` SDK (V3.4) is the opt-in escape hatch for real logic. |
| D2 | **A custom fix is a `FixPlan`, never raw text.** The `fix:` block deserializes via `FixPlan.serializer()` and runs through `FixPlanApplicator` + the verify gate. | "Deterministic fixes only." Reusing the engine means a custom fix that can't be made PSI-valid is *refused at apply time* exactly like an AI-planned one — never half-applied. |
| D3 | **Bounded v1 matcher vocabulary (~8 predicates), not a query language.** | Cover the roadmap's own fixer candidates without re-inventing a structural-search DSL. The long tail goes to the SDK. |
| D4 | **Fail closed (conservative-miss) for user rules too.** Undecidable matcher (unresolved symbol, `KaErrorType`) → **no flag.** | The false-positive-aversion invariant extends to user rules — a noisy user rule erodes trust in *all* findings. |
| D5 | **`CUSTOM` provenance carries the rule id.** New `IssueSource.CUSTOM`; the detail panel always names the originating rule. | "Provenance always visible." A user must always see *which of their rules* flagged a line. |
| D6 | **Detection-only is a first-class option.** `fix:` is optional; a rule with no expressible deterministic fix simply omits it. | A rule should never be pressured into guessing a fix it can't make safe. |

---

## 3. The rule schema

A `.aegis/rules/*.yml` file holds one or more rules. Worked example — **dogfooding the project's
own #1 convention** (every `catch (e: Exception)` must rethrow `ProcessCanceledException`):

```yaml
# .aegis/rules/pce-rethrow.yml
version: 1
rules:
  - id: pce-rethrow-missing
    language: kotlin
    severity: warning            # error | warning | weak-warning | info
    message: "catch (e: Exception) must rethrow ProcessCanceledException first"
    match:
      element: catch-clause
      parameter-type: java.lang.Exception     # the caught type (effectiveType-resolved)
      unless:                                   # negative guard — skip if already compliant
        contains-text: "is ProcessCanceledException) throw"
    fix:                          # optional; a FixPlan composed of existing FixOperations
      description: "Insert PCE rethrow as the first statement"
      operations:
        - op: InsertLinesAfter
          anchor: catch-block-open-brace
          lines:
            - "            if (e is ProcessCanceledException) throw e"
```

Schema fields:

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Stable rule identifier — appears in the finding's provenance and in suppression memory keys. Must be unique per project. |
| `language` | yes | One of `kotlin` / `java` / `typescript` / `javascript` (the native targets). |
| `severity` | yes | Maps to the existing `Issue` severity model. |
| `message` | yes | Shown in the detail panel and Problems entry. |
| `match` | yes | The matcher (see §4). |
| `fix` | no | A `FixPlan` (see §5). Omit for detection-only (D6). |

The whole file is parsed by `kotlinx.serialization` (YAML front-end) into `@Serializable
CustomRuleFile(version: Int, rules: List<CustomRule>)`. The `fix.operations` list maps to the
existing `FixPlan` shape so it can be handed to `FixPlan.serializer()` (see §5).

---

## 4. The matcher (bounded v1 vocabulary)

The matcher is a **declarative predicate over a single PSI element**, evaluated inside the existing
Analysis-API chokepoint (`withKtAnalysis` for Kotlin). v1 vocabulary — chosen to cover the
roadmap's own fixer candidates (unused import, const/let, redundant await, promise-without-handler,
missing default case, dead code):

| Predicate | Matches when… | Notes |
|---|---|---|
| `element:` | the node is of the given kind (`call-expression`, `property`, `function`, `catch-clause`, `import`, `when-expression`, `switch-statement`) | the one **required** matcher key |
| `name-matches:` | the element's name matches the regex | anchored, `RegexOption.IGNORE_CASE` opt-in |
| `text-matches:` | the element's source text matches the regex | for shallow textual rules |
| `receiver-type:` / `parameter-type:` / `argument-type:` | the resolved type equals/sub-types the FQN | uses `effectiveType` (smart-cast-aware — see CLAUDE.md gotcha), **fails closed on `KaErrorType`** |
| `inside:` | the element is structurally contained in an ancestor of the given kind | e.g. `inside: catch-clause` |
| `annotated-with:` | the element carries the annotation FQN | |
| `contains-text:` | the element's subtree contains the substring | used in `unless:` guards |
| `unless:` | a nested matcher that, if it matches, **suppresses** the rule | the compliance escape (see the PCE example) |

**Evaluation contract (D4):** a predicate that needs type resolution and gets `KaErrorType` /
unresolved → the *whole rule does not match* for that element. Undecidable ⇒ silent, never a guess.
This is the single most important behavior in the pillar: it is what keeps user rules from
re-introducing the false positives V1.0/V1.1 spent two releases living down.

Anything the vocabulary can't express is explicitly **out of v1** and is the V3.4 SDK's job — the
overview's "90% declarative / 10% `.jar`" split.

---

## 5. The fix path — a custom `fix:` is a verified `FixPlan`

This is the keystone (D2). The existing engine already proves a model-authored fix is safe; a
custom fix takes the identical road:

```
YAML fix.operations  ──serialize──>  JsonElement  ──FixPlan.serializer()──>  FixPlan
        │                                                                       │
        │  (same decode contract FixPlanCodec uses for AI output)               ▼
        └────────────────────────────────────────────────>  FixPlanApplicator.apply
                                                                  │
                                                          verify gate (FixVerifier
                                                          + PSI-validity Tier-1 gate)
                                                                  │
                                                   valid ─> apply   |   invalid ─> refuse (null)
```

- The YAML `operations:` list is structurally the existing `FixPlan`'s operation list, so it
  decodes through `FixPlan.serializer()` — **no new fix-application code.** Each `op:` is an
  existing `FixOperation` subtype (`InsertLinesAfter`, `ReplaceRange`, `ReplaceLines`, …) from
  `fix/engine/FixOperation.kt`; a YAML referencing an unknown op fails validation at load (§7).
- **Anchors** (`catch-block-open-brace`, etc.) are a small, named, declarative vocabulary resolved
  by a new `RuleAnchorResolver` into the concrete offsets the `FixOperation`s need — the user never
  writes raw offsets (which would be unportable and PSI-unsafe).
- Apply still routes through the verify gate: a custom fix that would produce malformed PSI is
  **refused** (returns null → no fix offered / no apply), exactly like the deterministic-fixer
  `return null` contract. A user *cannot* author a PSI-breaking one-click fix.

A detection-only rule (no `fix:`) simply produces an `Issue` with no associated `FixPlan` — the
Alt+Enter quick-fix is absent, the flag still shows.

---

## 6. Integration points (named, existing seams)

| Concern | Seam | Change |
|---|---|---|
| Run the rules during analysis | `AnalysisEngine.analyzers: List<Analyzer>` | add `CustomRuleAnalyzer()` to the list |
| Implement the analyzer | `analysis/Analyzer.kt` (+ `analyzers/KotlinAnalyzer.kt` base, via `withKtAnalysis`) | new `CustomRuleAnalyzer` loads rules + evaluates matchers |
| Load + cache rules per project | new `CustomRuleService` (`@Service(Service.Level.PROJECT)`) | reads `.aegis/rules/*.yml`, watches for changes, exposes parsed `List<CustomRule>` |
| Provenance | `model/AnalysisModels.kt` `IssueSource` | add `CUSTOM`; thread the rule id onto the `Issue` |
| Fix decode/apply | `fix/engine/` (`FixPlan`, `FixPlanApplicator`, `FixVerifier`) | **reused unchanged**; add `RuleAnchorResolver` only |
| Surface findings | `AegisLocalInspection`, `ProblemsViewCoordinator` | **reused unchanged** — custom `Issue`s flow through the existing path |
| Suppression / confidence | `SuppressionMemoryService`, the confidence pill | **reused** — keyed by `(rule id, fingerprint)` |

`CustomRuleService` follows the facade rules in CLAUDE.md: `@Service(Service.Level.PROJECT)`,
`Disposer.register(project, this)` in `init {}`, project state read via
`GhostDebuggerService.getInstance(project)` where needed. It does **not** become a new state owner —
its findings flow into the facade's `currentIssues` through the normal `AnalysisEngine` path.

---

## 7. Error handling (fail safe, fail loud-in-logs, never crash analysis)

| Failure | Behavior |
|---|---|
| Malformed YAML | the file is **skipped**, a single warning is logged with the file + parse error; other rule files still load. Analysis never aborts on a bad rule file. |
| Unknown `op:` or `element:` kind | the **rule** is dropped (not the whole file) with a logged diagnostic; valid sibling rules load. |
| Duplicate `id` across files | last-loader-wins is forbidden — both are dropped with a diagnostic (ambiguous provenance is worse than a missing rule). |
| Matcher throws / `KaErrorType` | the rule does not match (D4); `ProcessCanceledException` rethrows immediately per the catch convention. |
| Fix decodes but fails the verify gate | the fix is not offered (or, if invoked, refuses with null) — detection still stands. |

The guiding bias: **a broken rule file degrades to "that rule is off," never to a crash, a false
positive, or a corrupted fix.**

---

## 8. Testing approach

- **Loader tests:** valid file → N rules; malformed YAML → skipped + logged; unknown op → rule
  dropped; duplicate id → both dropped. Plain JUnit (no Analysis API needed).
- **Matcher tests:** extend `AegisKotlinAnalysisTestCase` (off-EDT, stdlib descriptor). Each
  predicate gets a positive + negative fixture; **a dedicated `KaErrorType` canary** per
  type-predicate asserting *no flag* on unresolved code (the conservative-miss guarantee — the same
  canary pattern the `/new-analyzer` skill scaffolds).
- **Fix tests:** a YAML `fix:` round-trips to a `FixPlan`, applies, and the result is PSI-valid;
  a deliberately PSI-breaking `fix:` is **refused** by the verify gate (mirrors the fix-engine
  revert test).
- **End-to-end:** the PCE-rethrow dogfood rule (§3) flags a non-compliant `catch` in a fixture and
  its fix inserts the rethrow as the first statement, verified clean.

---

## 9. Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Matcher vocabulary too weak to be useful | Medium | v1 is sized to the roadmap's own fixer candidates; the SDK (V3.4) absorbs the long tail. Ship, gather rules users actually write, grow the vocabulary from evidence. |
| User rules reintroduce false positives | Medium | D4 fail-closed + mandatory `KaErrorType` canary tests + the V2 suppression/confidence machinery demoting noisy rules. |
| A custom fix corrupts a file | Low | D2 routes every fix through `FixPlanApplicator`'s verify gate — a PSI-invalid custom fix is refused, never applied. |
| YAML becomes a code-execution vector | Low | Declarative-only by construction (D1); `kotlinx.serialization` decode of a closed schema executes no user code. |
| Rule-loading cost on large repos | Low | `CustomRuleService` parses + caches per project, invalidating only on `.aegis/` change; matchers run inside the existing single-pass analysis, not a separate sweep. |

---

## 10. Success criteria

1. A `.aegis/rules/*.yml` rule flags matching code, labelled `CUSTOM` + rule id in the detail panel
   and the native Problems window.
2. A rule's optional `fix:` applies via Alt+Enter and is **guaranteed PSI-valid** (verify gate) or
   not offered.
3. An undecidable matcher (`KaErrorType` / unresolved) does **not** flag — proven by a canary test.
4. A malformed rule file degrades to "rule off" with a log diagnostic; analysis never crashes.
5. No regression to V2's trust surfaces; `./gradlew test` + `detekt` green, `verifyPlugin` Compatible.

---

## 11. Non-goals

- **Executing user code from YAML** — declarative only (the `.jar` SDK is V3.4).
- **Rule packs** — curated bundles are V3.2; this pillar ships the rule model they bundle.
- **Org/remote rule registries** — V5 git-overlay; v1 is repo-local `.aegis/` only.
- **A structural-search query language** — bounded vocabulary now; expressiveness via SDK later.
- **Auto-applying custom fixes without review** — standing roadmap non-goal.

---

## 12. Hand-off

This spec is ready for its **writing-plans** cycle:
`docs/superpowers/plans/2026-06-16-v3-custom-rule-authoring.md` (written when V3.1 begins). The plan
will sequence: (1) schema + `@Serializable` model, (2) `CustomRuleService` loader + caching,
(3) `CustomRuleAnalyzer` + matcher evaluator with `KaErrorType` canaries, (4) `IssueSource.CUSTOM`
+ provenance threading, (5) `RuleAnchorResolver` + the `FixPlan` fix path, (6) end-to-end dogfood
rule + green bar.

**Begins only after** v2-stabilization reaches its finish line and the ship-vs-build decision is made.
