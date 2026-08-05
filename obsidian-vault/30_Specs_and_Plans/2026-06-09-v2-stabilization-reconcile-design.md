---
title: "Aegis Debug — V2 Stabilization & Reconciliation"
type: "spec"
status: "active"
related_components: []
aliases: []
tags:
  - aegis-debug
---

# Aegis Debug — V2 Stabilization & Reconciliation

**Date:** 2026-06-09
**Status:** Draft — pending review
**Branch:** `v2-stabilization-reconcile`
**Version target:** none — this is a **bounded prep phase**, not a release. No version bump, no tag.

---

## 0. Summary

A full V2 (dynamic validation + IDE-native integration) and a substantial slice of V3
(the AI-supervised fix engine, fixer catalog breadth, AI + JS/TS extract-method) are
**merged to `main` but never released** — the last tag is `v.1.5.0`, the `CHANGELOG`
tops out at `1.5.0`, and there is no `v.2.0.0` entry or tag. Meanwhile the project's own
guiding docs have drifted out of sync with the code: `CLAUDE.md` describes the fix engine
as "V3, in progress" when it is in fact complete.

This phase does **not** ship anything and does **not** add features. It does two things,
in order:

- **Phase 0 — Reconcile docs.** Make `CLAUDE.md`, `CHANGELOG.md`, the roadmap, and the
  V2.x continuation spec tell the truth as of 2026-06-09. Zero production code touched.
- **Phase 1 — Measure & audit the un-audited surface.** Wire up coverage measurement
  (none exists today), then run a fresh two-lens audit of the post–May-31 code — the fix
  engine above all — and land the fixes with regression tests.

The exit is a clean, *honest* base from which the deferred **ship-vs-build** decision can
be made with confidence. That decision is explicitly **out of scope** here.

---

## 1. Motivation

Three concrete, verified problems motivate this phase:

1. **The docs lie about the code, and it has already cost a session.**
   - `CLAUDE.md:76–82` frames the fix engine as "V3, **in progress**" with Phase 2
     "replacing the free-form AI fallback" as *future* work. Git shows otherwise: commit
     `1384f1f` **retired** the free-form `suggestFix`/`parseFixResponse` path, `7171d6b`
     made `fixSupervised` the live apply path, and `52e5fd8` marks the AI-supervised fix
     engine **complete** (Phase 2c-ii-b). The most detailed technical section in the
     conventions file actively misleads.
   - The roadmap reads like a to-do list, but V2 is built — a prior session's working
     memory already records this drift ("V2 already implemented — check code before
     planning V2 work"), i.e. the drift has *already* caused planning rework.

2. **The riskiest code has never been audited.** The May-31 audit (`docs/bug_report.md`,
   26 bugs: 23 fixed, 1 hardened, 2 rejected) was thorough — but everything in
   `fix/engine/`, the newest `store/` correlation code, and the no-regression gate landed
   *after* it. The most complex subsystem in the codebase (the AI-supervised fix engine)
   has had no comprehensive audit, despite the project's whole V2 thesis being "prove the
   findings are real."

3. **Coverage is unmeasurable.** There is no kover/jacoco wired up — only `detekt`
   (a style/quality gate). "Close the test-coverage gaps" currently has no instrument to
   even *see* the gaps.

The unifying observation: all three problems point at the same place — the post–May-31
code — so this phase aims its work there.

---

## 2. Scope decisions (locked in)

| # | Decision | Rationale |
|---|---|---|
| D1 | **Bounded prep phase.** Exit at the §6 finish line; the ship-vs-build decision is a *separate* step taken afterward. | The user framed this as "stabilize & reconcile *first*, then decide from a clean base." This is the "first," not an open-ended hardening crusade. |
| D2 | **`CHANGELOG.md` is the single source of truth** for what is built/shipped. `CLAUDE.md`/roadmap simply stop making stale claims. No new `STATUS.md`. | A dedicated status doc is a *third* artifact to keep in sync — a new drift surface. The CHANGELOG already exists for exactly this; making it authoritative removes a drift source instead of adding one. |
| D3 | **Coverage is wired as *measurement*, not a gate.** | A kover verification threshold now would block every merge on pre-existing gaps across 109 files. Prep phase = measure and target; a coverage gate is a separate, later decision. |
| D4 | **Two-lens audit** — CLAUDE.md invariants (subagent) *and* JetBrains platform-API misuse (manual). | 14 of May-31's 17 CRITICAL/HIGH bugs were platform-API misuse (read-action violations, disposable leaks, races) — a category the invariant lens does not target. One lens would repeat that gap. |
| D5 | **Phase 0 touches zero production code.** | Pure documentation lands as a safe, instantly-revertable first commit; test breakage in Phase 0 is impossible by construction, so it can land fast and unblock Phase 1. |
| D6 | **Findings recorded, not silently fixed.** Every audit finding is fixed-with-regression-test, hardened, or rejected-with-rationale, in a dated doc. | Mirrors how `bug_report.md` recorded its 2 rejections — an auditable trail, and it keeps the conservative-miss bias honest (a "finding" we reject is documented, not just dropped). |

---

## 3. Phase 0 — Reconcile docs

Four targeted edits, landing as **one commit** (no production code):

1. **`CLAUDE.md:76–82`** (the "Fix engine (V3, in progress)" block) — rewrite to past
   tense: the AI-supervised fix engine is **complete**; `fixSupervised` is the live,
   AI-optional apply path; the free-form `suggestFix`/`parseFixResponse` path was retired
   (`1384f1f`). Keep the spec pointer to
   `docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md`; flip
   "in progress" → "shipped, unreleased."

2. **`CHANGELOG.md`** — add a `## [Unreleased] — 2.0.0` entry (explicitly **not** tagged)
   in two groups:
   - *V2 — dynamic validation & IDE-native:* runtime-confirmed provenance tier, debug-
     and test-suite cross-check, suppression memory, confidence pill, native Problems
     panel (`ProblemsViewCoordinator`), inspection-profile integration
     (`AegisLocalInspection`), streaming AI.
   - *V3 pulled forward:* AI-supervised fix engine, fixer catalog breadth (batches 1–5),
     AI + JS/TS extract-method, simplification fixers, the no-regression gate, the detekt
     gate.
   - One line noting **Python was discarded** (scope narrowed to IntelliJ's native
     out-of-the-box languages).

3. **`docs/aegis_debug_roadmap_v2_to_v5.md`** — add a "**Status as of 2026-06-09**"
   callout near the top (north-star body untouched): V2 substantially built but uncut;
   Python discarded; V3 fixer-breadth + AI-supervised engine landed *ahead* of
   custom-rules / rule-packs, which remain the unbuilt V3 frontier.

4. **`docs/superpowers/specs/2026-05-27-aegis-v2.x-continuation-design.md`** — prepend a
   short "**Actual outcome (superseded)**" note rather than rewriting history: Python
   dropped, V3 fix-engine pulled forward, GA not cut, with per-milestone reality.

---

## 4. Phase 1 — Measure & audit the new surface

### 4.1 Audit scope

**Everything changed since the May-31 audit**, concretely:

- `fix/engine/` — the entire package (the crown jewel: `FixEngine.fixSupervised`,
  `FixPlanApplicator`, the verifiers, JS/TS checks, line ops).
- The engine-wired fixers and the AI op catalog (`CodeFixAdapter`,
  `FixOperationCatalog`).
- The newest `store/` correlation code — `DebugObservationLogic`, `TestRunCorrelation` —
  and a re-verify of `DebugObserver` / `TestRunObserver`.
- The no-regression-gate analysis changes — `SingleFileStaticReanalysis` and the
  `excludeBrokenFromLate` flag in `AnalysisEngine`.

(The precise file set is `git diff --stat` from the May-31 audit commit to `HEAD`,
resolved at execution time.)

### 4.2 Two lenses

- **Lens 1 — CLAUDE.md invariants**, via the `aegis-convention-reviewer` subagent:
  PCE escape, false-positive aversion, fixer PSI-validity, facade single-writer state
  ownership, the `trimIndent`/`effectiveType` traps.
- **Lens 2 — JetBrains platform-API misuse**, a manual pass modeled on the categories
  that produced May-31's CRITICAL/HIGH findings: read actions on background threads,
  disposable lifecycle / leaks, thread-safety / shared-state races, un-closed resources.

### 4.3 Steps & deliverables

1. **Wire kover** (`org.jetbrains.kotlinx.kover`), report-only (`koverHtmlReport` /
   `koverXmlReport`). See R1 for the IPGP interaction risk + fallback.
2. **Run both audit lenses** → record findings in a fresh dated doc,
   `docs/audit-2026-06-post-v2.md`, mirroring `bug_report.md`'s format (each finding:
   fixed-with-regression-test, hardened, or rejected-with-rationale).
3. **Land fixes TDD-style** — failing regression test first, then fix — committing per
   logical fix (periodic-commit convention).
4. **Close risk-weighted coverage gaps** — the fix engine's apply / verify / revert /
   `fixSupervised`-loop decision points, observer correlation edges, the no-regression
   gate. **Not** a 100%-coverage chase; target the high-risk decision points.
5. **Final bar** — `./gradlew test` and `./gradlew detekt` green, `verifyPlugin`
   Compatible; add a short "stabilization pass" line to the CHANGELOG entry.

---

## 5. Risks and mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| R1 — kover instruments the test JVM and fights IPGP's `instrumentTestCode` task / the JBR requirement | Medium | If kover cannot integrate cleanly with the IPGP test task, **document the limitation and drive coverage from the audit + manual reading** rather than a number. The phase still delivers — coverage measurement is a *means*, not a success criterion in itself. |
| R2 — the audit turns up a large number of findings, tempting scope creep | Medium | D1 holds: **record everything** in the findings doc, fix what is in-scope (correctness/safety/invariant violations in the audited surface). A decision to expand into a broader hardening pass is a *separate* call, made after this phase, not folded into it silently. |
| R3 — auditing the fix engine introduces a fix that itself violates the false-positive-aversion or PSI-validity invariants | Low | Every fix lands TDD-first with a regression test; Lens 1 (the convention subagent) re-runs over the diff before merge. The fix engine's own no-regression gate also guards behavior. |
| R4 — Phase 0 CHANGELOG entry implies a release that has not happened | Low | The entry is labelled `[Unreleased]` with an explicit "not tagged" note. D2 keeps the CHANGELOG honest about built-vs-shipped. |

---

## 6. Success criteria (the finish line)

1. No doc contradicts the code — `CLAUDE.md`, `CHANGELOG.md`, roadmap, and the
   continuation spec all tell the truth as of 2026-06-09.
2. Coverage is **measurable** (kover wired and runnable, or R1's fallback documented),
   and the high-risk new code's gaps are identified and closed to a sensible,
   risk-weighted bar.
3. The post–May-31 code has had a documented fresh audit
   (`docs/audit-2026-06-post-v2.md`); every finding is fixed-with-regression-test,
   hardened, or rejected-with-rationale.
4. `./gradlew test` and `./gradlew detekt` pass; `verifyPlugin` Compatible.
5. **Stop there.** Ship-vs-build is taken up separately, from this clean base.

---

## 7. Non-goals (out of scope)

- **The ship-vs-build decision itself** — deferred; this phase produces the clean base
  for it, nothing more.
- **A coverage *gate*** (verification threshold) — measurement only (D3).
- **Any new feature, analyzer, fixer, or language target.**
- **A new `STATUS.md`** — CHANGELOG is the source of truth (D2).
- **A version bump or release tag** — this is a prep phase, not a release.
- **Re-auditing the pre–May-31 code** — `bug_report.md` already covered it.

---

## 8. Sequencing & workflow

Per the established rhythm: **branch first** (`v2-stabilization-reconcile`, already cut),
**periodic commits** at logical stopping points, **merge to `main` locally + build** at
the end, **do not push**.

```
Phase 0 (docs)            → 1 commit  (no production code)
Phase 1a — wire kover     → 1 commit  (tooling; R1 fallback if needed)
Phase 1b — run audit      → 1 commit  (docs/audit-2026-06-post-v2.md)
Phase 1c — fixes          → N commits (TDD, one per logical fix)
Final — green bar + build → CHANGELOG "stabilization pass" line; merge to main locally
```

The implementation plan (task-by-task) lives at
`docs/superpowers/plans/2026-06-09-v2-stabilization-reconcile.md`, written next via the
writing-plans flow.

---

## Addendum — 2026-06-16: Phase 0 adapted for the post-spec docs cleanup

**This spec was written 2026-06-09. After it was written, commit `32c351e`**
*("chore(cleanup): remove early .md files and leave just the essentials")* **deleted 45
docs (~31k lines)** — every V1.x–V3 spec/plan, `docs/bug_report.md`, and the v1 history —
leaving only the roadmap and this spec. That invalidated three Phase-0 instructions as
literally written. The implementation plan adapts them (decision: *adapt the plan*, do not
resurrect the deleted docs — the cleanup was deliberate, and un-deleting 31k lines to satisfy
a doc pointer would re-introduce the very drift this phase removes):

| §3 / §4 instruction | Now-broken because | Adaptation in the plan |
|---|---|---|
| §1 "keep the spec pointer to `…2026-05-31-ai-supervised-fix-engine-design.md`" | file **deleted** — `CLAUDE.md:81` pointer dangles | **repoint** to the CHANGELOG `[Unreleased] — 2.0.0` entry |
| §4 "prepend an 'Actual outcome' note to `…2026-05-27-aegis-v2.x-continuation-design.md`" | file **deleted** | **no-op** — content folded into the CHANGELOG entry + roadmap callout |
| §1/§4.1 audit baseline = `docs/bug_report.md` | file **deleted** | audit scope = git range **`ec91efd..HEAD`** (last pre-fix-engine commit → HEAD) |

D2 holds throughout: the **CHANGELOG is the single source of truth**; every "where did X go?"
pointer now resolves there. This is itself an instance of the phase's thesis — a spec left
pointing at deleted files is the same doc-drift the phase exists to kill.

**Forward-looking note (out of this phase's scope, recorded for sequencing):** the genuinely
*unbuilt* V3 frontier — custom-rule authoring, rule packs, analyzer SDK, fix-preview UX — is
now designed in `docs/superpowers/specs/2026-06-16-v3-frontier-overview-design.md` (overview +
sequencing) and `docs/superpowers/specs/2026-06-16-v3-custom-rule-authoring-design.md` (the
first pillar's deep-dive). Those phases begin **only after** this stabilization reaches its §6
finish line and the deferred ship-vs-build decision is made.
