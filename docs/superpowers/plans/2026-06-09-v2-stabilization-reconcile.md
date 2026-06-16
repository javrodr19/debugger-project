# V2 Stabilization & Reconciliation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the project's guiding docs tell the truth as of 2026-06-09, wire coverage *measurement*, and run a documented two-lens audit of the merged-but-unaudited post–May-31 code (the fix engine above all) — landing fixes TDD-style — without shipping a release or adding a feature.

**Architecture:** Three phases in strict order. **Phase 0** is pure documentation (one commit, zero production code) — instantly revertable, cannot break tests. **Phase 1** wires `kover` as report-only measurement, runs two audit lenses over `ec91efd..HEAD`, and lands fixes one-per-finding with regression tests. **Phase 2** is the green-bar gate + local merge. This is a *bounded prep phase* — it exits at the §6 finish line of the spec and explicitly stops before the ship-vs-build decision.

**Tech Stack:** IntelliJ Platform Gradle Plugin 2.14.0, Kotlin Analysis API (K2), JUnit (IPGP test fixtures), `org.jetbrains.kotlinx.kover` (new), the existing `detekt`-cli gate, the `aegis-convention-reviewer` subagent.

**Source spec:** `docs/superpowers/specs/2026-06-09-v2-stabilization-reconcile-design.md`

---

## ⚠️ Reconciliation note — this plan ADAPTS the spec's Phase 0

The spec was written on 2026-06-09. **After** it was written, commit `32c351e`
*("chore(cleanup): remove early .md files and leave just the essentials")* deleted 45
docs (~31k lines) — every V1.x–V3 spec/plan, `docs/bug_report.md`, and the v1 history —
leaving only the roadmap and the stabilization spec. Three spec-Phase-0 instructions
became impossible-as-written:

| Spec Phase 0 instruction | Problem | This plan's adaptation |
|---|---|---|
| §1 "keep the spec pointer to `…2026-05-31-ai-supervised-fix-engine-design.md`" | That file was **deleted** — the pointer in `CLAUDE.md:81` now dangles | **Repoint** to the CHANGELOG `[Unreleased] — 2.0.0` entry (the new home of the fix-engine history) |
| §4 "prepend an 'Actual outcome' note to `…2026-05-27-aegis-v2.x-continuation-design.md`" | That file was **deleted** | **No-op** — its "actual outcome" content is folded into the CHANGELOG + roadmap callout instead |
| §1/§4.1 use `docs/bug_report.md` as the audit baseline | **Deleted** | Audit scope is resolved as the git range **`ec91efd..HEAD`** (last pre-fix-engine commit → HEAD) |

This adaptation was chosen deliberately (the cleanup was intentional; resurrecting 31k
lines to satisfy a doc pointer would re-introduce the drift the cleanup removed). It is the
"Adapt the plan" decision. **The CHANGELOG (D2) is the single source of truth** — every
"where did X go?" pointer now resolves there.

---

## File map

| File | Phase | Responsibility |
|---|---|---|
| `CLAUDE.md` | 0 | Fix-engine block (76–81) → past tense + repointed reference; one stale Fixer-principle clause reconciled |
| `CHANGELOG.md` | 0 | New `[Unreleased] — 2.0.0` entry (the authoritative built-vs-shipped record) |
| `docs/aegis_debug_roadmap_v2_to_v5.md` | 0 | "Status as of 2026-06-09" callout near top (north-star body untouched) |
| `build.gradle.kts` | 1a | Apply `kover` plugin (report-only, no verify rules) |
| `docs/audit-2026-06-post-v2.md` | 1b | Audit findings doc (bug_report.md format): fixed / hardened / rejected |
| `src/main/...`, `src/test/...` | 1c–1d | One fix + regression test per finding; risk-weighted coverage gap-closing |

---

## Phase 0 — Reconcile docs (ONE commit, zero production code)

> Tasks 1–4 land as a **single commit**. No `.kt` file is touched, so test breakage is
> impossible by construction (D5). This is the safe, fast, unblocking first commit.

### Task 1: Rewrite the `CLAUDE.md` fix-engine block to past tense + repoint the dead pointer

**Files:**
- Modify: `CLAUDE.md:76-81` (the "Fix engine (V3, in progress)" block)
- Modify: `CLAUDE.md` Fixer-principle section (the one stale "AI fallback" clause)

- [ ] **Step 1: Replace the fix-engine block.** Find the block beginning at line 76
  (`**Fix engine (V3, in progress).**`) and ending at line 81 (the `See …` spec pointer).
  Replace the entire block with:

```markdown
**Fix engine (V3 — shipped, unreleased).** Fix *application* routes through `FixEngine`
(`fix/engine/`): a `Fixer`'s `CodeFix` is adapted to a single-op `FixPlan` and applied by
`FixPlanApplicator` with the same PSI-validity gate. **Both phases are complete and merged.**
Phase 2 made the AI a *planner/supervisor* that composes deterministic engine operations and
verifies them (the verify gate) — never authoring raw fix code. `fixSupervised` is the live,
AI-optional apply path (`7171d6b`); the free-form `suggestFix`/`parseFixResponse` fallback
was retired (`1384f1f`, marked complete in `52e5fd8`). The design history is recorded in the
CHANGELOG's `[Unreleased] — 2.0.0` entry. *(The standalone design spec was removed in the
2026-06-09 docs cleanup; the CHANGELOG is now the source of truth.)*
```

- [ ] **Step 2: Reconcile the one stale Fixer-principle clause.** In the "Fixer principle"
  section, the sentence describing the fallback as *"the AI path, which has no syntactic
  guarantees but also does not corrupt the PSI"* is now false — the supervised path *does*
  verify. Find:

```markdown
contract gives the orchestrator a clean signal to fall back to the AI path, which has no
syntactic guarantees but also does not corrupt the PSI.
```

  Replace with:

```markdown
contract gives the orchestrator a clean signal to fall back to the AI-supervised path
(`fixSupervised`), which composes deterministic engine operations and runs them through the
verify gate rather than authoring raw fix code — so it preserves the same PSI-validity guarantee.
```

  > **Why this is in scope:** Success criterion #1 is "no doc contradicts the code." The spec
  > scoped Phase 0 to lines 76–82, but leaving this clause stale fails that criterion. It is a
  > one-sentence reconciliation of the same drift, not new scope.

- [ ] **Step 3: Verify no other dangling reference to the deleted spec remains.**

Run: `grep -rn "2026-05-31-ai-supervised-fix-engine-design" CLAUDE.md docs/`
Expected: **no matches** (the only reference was `CLAUDE.md:81`, now removed).

### Task 2: Add the authoritative `[Unreleased] — 2.0.0` CHANGELOG entry

**Files:**
- Modify: `CHANGELOG.md` (insert directly under the `# Changelog` / intro lines, above `## 1.5.0`)

- [ ] **Step 1: Insert the entry.** After the intro line
  (`All notable changes to Aegis Debug are documented here.`) and before `## 1.5.0 — …`,
  insert:

```markdown
## [Unreleased] — 2.0.0

> **Not tagged, not released.** This entry records what is *built and merged to `main`* as of
> 2026-06-09. The last shipped tag is `v.1.5.0`. The ship-vs-build decision for a 2.0.0 release
> is deferred (a separate step, taken from this clean base). **`CHANGELOG.md` is the single
> source of truth for built-vs-shipped** — `CLAUDE.md` and the roadmap no longer make
> version-status claims.

### V2 — dynamic validation & IDE-native integration

- **Runtime-confirmed provenance tier.** A fourth source tag, `RUNTIME_CONFIRMED`, joins
  `STATIC` / `AI_LOCAL` / `AI_CLOUD`. Confirmed findings outrank unconfirmed siblings in sort.
- **Debug-session cross-check.** `DebugObserver` + `DebugObservationLogic` correlate variable
  values observed at breakpoints with null-safety / state-before-init findings, promoting or
  demoting them live (`DebugSessionCoordinator`).
- **Test-suite cross-check.** `TestRunObserver` + `TestRunCorrelation` correlate executed /
  failing-test code paths with static findings via `AegisTestStatusListener`; on-path findings
  become `RUNTIME_CONFIRMED`.
- **False-positive suppression memory.** `SuppressionMemoryService` auto-hides findings
  dismissed and never runtime-confirmed across N analyses; local-only, no telemetry.
- **Confidence pill.** `Issue.confidence` surfaces as a `CONFIRMED / LIKELY / UNCONFIRMED`
  pill and drives the default issue-list sort.
- **Native Problems panel.** `ProblemsViewCoordinator` publishes issues to IntelliJ's
  `WolfTheProblemSolver` so they appear in the native Problems tool window.
- **Inspection-profile integration.** `AegisLocalInspection` (base + 11 subclasses) makes the
  rules togglable under Settings → Editor → Inspections; the legacy annotator was removed.
- **Quick-fix intentions.** Fixers are exposed as `IntentionAction`s (Alt+Enter on a flagged line).
- **Streaming AI.** Detail-panel explanations stream token-by-token instead of wait-then-paste.

### V3 (pulled forward, ahead of custom-rules)

- **AI-supervised fix engine.** Fix application routes through `FixEngine.fixSupervised`: the
  AI plans/supervises deterministic `FixOperation`s and a verify gate checks the result; the
  free-form `suggestFix`/`parseFixResponse` path was retired. PSI-validity preserved end-to-end.
- **Fixer-catalog breadth (batches 1–5).** Null-safety, async/error, type-conversion,
  structural, and AI-catalog fixers.
- **Code-simplification fixers.** Complexity gate + collapse-boolean-return.
- **Extract-method.** AI + JS/TS extract-method with per-function gates.
- **No-regression gate.** `SingleFileStaticReanalysis` + the `excludeBrokenFromLate` flag guard
  the apply path against introducing new findings.
- **Detekt quality gate.** `./gradlew detekt` (JavaExec over detekt-cli + baseline).

### Scope change

- **Python support was discarded** (V2.0.0-alpha.3): scope narrowed to IntelliJ's native
  out-of-the-box languages (TypeScript / JavaScript / Kotlin / Java).
```

- [ ] **Step 2: Sanity-check the entry renders.**

Run: `grep -n "\[Unreleased\] — 2.0.0" CHANGELOG.md`
Expected: one match, above the `## 1.5.0` line.

### Task 3: Add the roadmap "Status as of 2026-06-09" callout

**Files:**
- Modify: `docs/aegis_debug_roadmap_v2_to_v5.md` (insert after the header block, before `## Guiding principles`)

- [ ] **Step 1: Insert the callout.** After the `**What this doc is:** …` line and the
  following `---`, before `## Guiding principles`, insert:

```markdown
> **Status as of 2026-06-09.** This roadmap is the long-term north star — it is **not** a
> to-do list of unbuilt work. As of 2026-06-09, **V2 is substantially built but uncut**
> (merged to `main`, no `v.2.0.0` tag — see the CHANGELOG `[Unreleased] — 2.0.0` entry).
> **Python was discarded** (native IntelliJ languages only). A large slice of **V3 landed
> ahead of schedule** — the AI-supervised fix engine and fixer-catalog breadth — *before* the
> V3 frontier of **custom-rule authoring / rule packs / analyzer SDK**, which remain the
> unbuilt frontier (designed in `docs/superpowers/specs/2026-06-16-v3-frontier-overview-design.md`).
> The CHANGELOG is the source of truth for built-vs-shipped.

---
```

- [ ] **Step 2: Confirm the north-star body is untouched.**

Run: `git diff --stat docs/aegis_debug_roadmap_v2_to_v5.md`
Expected: only insertions (the callout); no deletions.

### Task 4: Verify the deleted continuation-spec needs no action, then commit Phase 0

**Files:** none modified (verification + commit only)

- [ ] **Step 1: Confirm the continuation spec is gone (so the spec's §4 step is correctly a no-op).**

Run: `ls docs/superpowers/specs/2026-05-27-aegis-v2.x-continuation-design.md 2>&1`
Expected: `No such file or directory`. Its "actual outcome" content is already captured by
the CHANGELOG entry (Task 2) and the roadmap callout (Task 3) — **no file to amend.**

- [ ] **Step 2: Confirm zero production code changed in Phase 0.**

Run: `git diff --name-only | grep -E '\.(kt|java)$' || echo "NO CODE TOUCHED"`
Expected: `NO CODE TOUCHED`.

- [ ] **Step 3: Commit Phase 0 as one commit.**

```bash
git add CLAUDE.md CHANGELOG.md docs/aegis_debug_roadmap_v2_to_v5.md
git commit -m "docs(v2-stab): reconcile CLAUDE.md/CHANGELOG/roadmap with shipped code

Phase 0 of v2-stabilization-reconcile. Fix-engine docs flip to past tense
(shipped, unreleased); CHANGELOG gains the authoritative [Unreleased] 2.0.0
entry; roadmap gains a Status-as-of-2026-06-09 callout. No production code.
Repoints the dangling fix-engine-spec reference (deleted in 32c351e) to the
CHANGELOG, which is now the single source of truth for built-vs-shipped.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Phase 1a — Wire kover as report-only measurement

> Coverage is wired as **measurement, not a gate** (D3). No `koverVerify` threshold — a
> threshold now would block every merge on pre-existing gaps across 100+ files.

### Task 5: Apply the kover Gradle plugin (report-only)

**Files:**
- Modify: `build.gradle.kts` (the `plugins { }` block)

- [ ] **Step 1: Add the plugin to the `plugins { }` block.** Add this line alongside the
  existing Kotlin/IPGP plugin declarations:

```kotlin
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
```

  > If `0.9.1` fails to resolve, use the current `0.9.x` patch from
  > <https://plugins.gradle.org/plugin/org.jetbrains.kotlinx.kover>. Do **not** add a
  > `kover { }` verification block — report-only means the default `koverHtmlReport` /
  > `koverXmlReport` tasks and nothing else.

- [ ] **Step 2: Confirm the JBR env (required by IPGP — see CLAUDE.md "Build prerequisites").**

```bash
export JAVA_HOME=$(find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1)
export PATH=$JAVA_HOME/bin:$PATH
```

- [ ] **Step 3: Verify the report task resolves and runs.**

Run: `./gradlew koverHtmlReport`
Expected: BUILD SUCCESSFUL; an HTML report under `build/reports/kover/html/index.html`.

- [ ] **Step 4 (R1 contingency): if kover fights IPGP's `instrumentTestCode` / JBR.**
  If Step 3 fails with an instrumentation or `Packages does not exist`-class error that
  cannot be resolved by the JBR export, **do not fight it.** Per spec R1, document the
  limitation inline in `build.gradle.kts` (a comment above the plugin line explaining the
  IPGP conflict) and **drive coverage from the audit + manual reading instead of a number.**
  The phase still delivers; coverage measurement is a means, not a success criterion.
  Record the outcome in the audit doc (Task 6) under a "Coverage tooling" heading.

- [ ] **Step 5: Commit.**

```bash
git add build.gradle.kts
git commit -m "build(v2-stab): wire kover as report-only coverage measurement

Phase 1a. koverHtmlReport/koverXmlReport only — no verification threshold
(D3: measurement, not a gate). R1 fallback documented inline if kover cannot
integrate with IPGP's instrumentTestCode task.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Phase 1b — Run the two-lens audit

> **Audit scope = `ec91efd..HEAD`** — the last pre-fix-engine commit to HEAD. This captures
> the entire `fix/engine/` package (20 files), the engine-wired fixers + AI op catalog, the
> newest `store/` correlation code (`DebugObservationLogic`, `TestRunCorrelation`), and the
> no-regression-gate changes (`SingleFileStaticReanalysis`, `excludeBrokenFromLate`). Resolve
> the exact file set at execution time with the command in Step 1.

### Task 6: Run both audit lenses and record findings

**Files:**
- Create: `docs/audit-2026-06-post-v2.md`

- [ ] **Step 1: Resolve the concrete audited file set.**

Run: `git diff --stat ec91efd..HEAD -- src/main | sort`
Expected: ~52 changed files (+~1.8k LOC). This is the authoritative audit surface; paste the
list into the audit doc's "Scope" section.

- [ ] **Step 2: Create the findings doc skeleton** (mirrors the retired `bug_report.md` format —
  every finding ends as fixed-with-regression-test, hardened, or rejected-with-rationale):

```markdown
# Post-V2 Audit — 2026-06 (fix engine + store correlation + no-regression gate)

**Date:** 2026-06-16
**Scope:** `ec91efd..HEAD` (post–May-31-audit surface). File list below.
**Lenses:** (1) CLAUDE.md invariants via `aegis-convention-reviewer`; (2) JetBrains
platform-API misuse (manual). Mirrors the May-31 `bug_report.md` discipline.

## Scope (files audited)
<!-- paste `git diff --stat ec91efd..HEAD -- src/main` here -->

## Coverage tooling
<!-- kover wired (Phase 1a) OR R1 fallback note -->

## Findings

### Lens 1 — CLAUDE.md invariants
| # | Severity | File:line | Invariant | Finding | Disposition |
|---|---|---|---|---|---|

### Lens 2 — JetBrains platform-API misuse
| # | Severity | File:line | Category | Finding | Disposition |
|---|---|---|---|---|---|

## Dispositions legend
- **FIXED** — regression test first, then fix (commit hash).
- **HARDENED** — defensive change, no reproducing test feasible (commit hash).
- **REJECTED** — not a real defect; rationale recorded (keeps conservative-miss bias honest).
```

- [ ] **Step 3: Run Lens 1 — dispatch the `aegis-convention-reviewer` subagent over the diff.**
  Use the Agent tool with `subagent_type: aegis-convention-reviewer` and this prompt:

  > Review the changed code in `git diff ec91efd..HEAD -- src/main` against the five Aegis
  > invariants: (1) PCE escape from every `catch (e: Exception)`, (2) false-positive aversion
  > (analyzers don't flag on `KaErrorType`/unresolved), (3) fixer PSI-validity (return null
  > rather than emit malformed output), (4) facade single-writer state ownership (collaborators
  > read via `GhostDebuggerService`, write only via its mutators), (5) the `trimIndent` /
  > `effectiveType` traps. Focus on `fix/engine/`, `store/`, and the no-regression-gate code.
  > Report HIGH-confidence findings only, with `file:line`.

  Record each returned finding as a row in the Lens 1 table.

- [ ] **Step 4: Run Lens 2 — manual platform-API misuse pass** over the same diff, checking the
  four categories that produced 14 of May-31's 17 CRITICAL/HIGH findings. For each audited
  file, grep + read for:

  - **Read actions on background threads** — Analysis-API / PSI reads outside a read action
    on a pooled/background thread. Run: `grep -rn "executeOnPooledThread\|ApplicationManager.*invokeLater\|runReadAction\|ReadAction" src/main/kotlin/com/ghostdebugger/fix/engine src/main/kotlin/com/ghostdebugger/store`
    then read each hit's surrounding PSI access.
  - **Disposable lifecycle / leaks** — every `@Service` and listener registers
    `Disposer.register(...)`; message-bus connections are `.connect(disposable)`, not leaked.
    Run: `grep -rn "Disposer.register\|messageBus.connect\|\.connect(" src/main/kotlin/com/ghostdebugger/store`
  - **Thread-safety / shared-state races** — mutable shared collections without concurrent
    types or synchronization (cf. the V1.4.1 `InMemoryGraph` race).
    Run: `grep -rn "mutableMapOf\|mutableListOf\|HashMap\|HashSet\|var " src/main/kotlin/com/ghostdebugger/store src/main/kotlin/com/ghostdebugger/fix/engine`
    then judge which are touched from >1 thread.
  - **Un-closed resources** — any I/O / stream / `Response` without `use { }`.
    Run: `grep -rn "openStream\|FileInputStream\|Response\|BufferedReader" src/main/kotlin/com/ghostdebugger/fix src/main/kotlin/com/ghostdebugger/store`

  Record each genuine finding as a Lens 2 row.

- [ ] **Step 5: Triage and commit the findings doc** (before any fix, so the audit trail is a
  clean artifact independent of the fixes).

```bash
git add docs/audit-2026-06-post-v2.md
git commit -m "docs(v2-stab): post-V2 audit findings (two lenses over ec91efd..HEAD)

Phase 1b. Convention-invariant lens (aegis-convention-reviewer) + platform-API
misuse lens (manual). Findings recorded with dispositions; fixes land next, TDD.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Phase 1c — Land fixes (one per finding, TDD)

> **This is a per-finding protocol, not a fixed task list** — the findings are the *output* of
> Phase 1b and cannot be enumerated before it runs. Apply Task 7 once per `FIXED`-disposition
> finding. `REJECTED` findings get a rationale row only (no code). `HARDENED` findings follow
> the same loop minus the reproducing test, with the defensive rationale recorded.

### Task 7: Fix protocol (repeat per finding)

**Files (per finding):**
- Test: the matching `src/test/kotlin/.../<Subject>Test.kt`
- Modify: the offending `src/main/kotlin/.../<Subject>.kt`

- [ ] **Step 1: Write the failing regression test FIRST.** Reproduce the finding as a test
  that fails against current code. Worked example — a hypothetical "verify-fail must revert
  and not leave a partial edit" finding in the fix engine:

```kotlin
@Test
fun `fixSupervised reverts the document when the verify gate fails`() {
    val before = file.text
    val plan = planThatProducesInvalidResult()   // verifier will reject
    val result = fixEngine.fixSupervised(context, plan)
    assertNull(result)                            // no fix returned
    assertEquals(before, file.text)               // document fully restored — no partial edit
}
```

  > Guard-rails when writing the fix (the invariants this phase protects): (a) every new/edited
  > `catch (e: Exception)` keeps `if (e is ProcessCanceledException) throw e` first; (b) a fixer
  > fix must keep the PSI-validity contract — return null rather than emit malformed output;
  > (c) an analyzer fix must keep the false-positive-aversion bias — don't start flagging on
  > `KaErrorType`/unresolved; (d) Kotlin Analysis-API access goes through
  > `withKtAnalysis`; (e) Analysis-API tests extend `AegisKotlinAnalysisTestCase`.

- [ ] **Step 2: Run the test, confirm it fails for the right reason.**

Run (JBR env exported):
```bash
export JAVA_HOME=$(find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1)
export PATH=$JAVA_HOME/bin:$PATH
./gradlew test --tests "*<SubjectTest>*"
```
Expected: FAIL on the new assertion (not a compile error, not an unrelated failure).

- [ ] **Step 3: Apply the minimal fix** in the offending file. Smallest change that makes the
  test pass while honoring the Step-1 guard-rails.

- [ ] **Step 4: Run the test, confirm it passes.**

Run: `./gradlew test --tests "*<SubjectTest>*"`
Expected: PASS.

- [ ] **Step 5: Re-run Lens 1 over the fix diff** (the fix must not itself violate an
  invariant — R3). Dispatch `aegis-convention-reviewer` over `git diff` of this fix; expect no
  HIGH-confidence findings.

- [ ] **Step 6: Update the disposition row** in `docs/audit-2026-06-post-v2.md` to `FIXED`
  with the (pending) commit subject, then commit fix + test + doc together.

```bash
git add src/main/kotlin/.../<Subject>.kt src/test/kotlin/.../<SubjectTest>.kt docs/audit-2026-06-post-v2.md
git commit -m "fix(v2-stab): <one-line finding summary> (regression test)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Phase 1d — Close risk-weighted coverage gaps

> **Not a 100%-coverage chase** (spec §4.4) — target the high-risk *decision points* the audit
> and kover surface. If kover is live (Phase 1a), use `build/reports/kover/html/index.html` to
> find the uncovered branches in the targets below; if R1 fired, target them by reading.

### Task 8: Add tests for the named high-risk decision points (repeat per target)

**Files:** `src/test/kotlin/com/ghostdebugger/fix/engine/...`, `.../store/...`

The named targets (from spec §4.4) — each should have explicit branch coverage:

1. **Fix engine apply / verify / revert** — `FixPlanApplicator` apply success; verify-gate
   reject → full revert (no partial edit); the `fixSupervised` loop's accept/retry/give-up
   decision points (`FixEngine`, `FixVerifier`, `ComplexityVerifier`, `ExtractMethodVerifier`).
2. **Observer correlation edges** — `DebugObservationLogic` confirm vs. demote branches;
   `TestRunCorrelation` on-path-failing vs. unreached branches.
3. **No-regression gate** — `SingleFileStaticReanalysis` "new finding introduced → reject"
   vs. "clean → accept"; the `excludeBrokenFromLate` flag's on/off behavior in `AnalysisEngine`.

- [ ] **Step 1: For each target, write the branch-covering test.** Worked example — the
  no-regression gate's reject branch:

```kotlin
@Test
fun `no-regression gate rejects a fix that introduces a new static finding`() {
    val original = parseFixture("clean_then_broken_by_fix.kt")
    val candidate = applyCandidateFix(original)        // fix that adds a new issue
    val verdict = SingleFileStaticReanalysis.evaluate(original, candidate)
    assertFalse(verdict.accepted)                       // gate blocks the regression
    assertTrue(verdict.newFindings.isNotEmpty())
}
```

  > Match the real signatures at execution time — read the target file first; the example shows
  > the *shape* (arrange a known-good input, apply the operation, assert the decision branch),
  > not the exact API. Analysis-API tests extend `AegisKotlinAnalysisTestCase` and run off-EDT.

- [ ] **Step 2: Run it, confirm pass (and that it actually exercises the intended branch).**

Run: `./gradlew test --tests "*<TargetTest>*"`
Expected: PASS. If kover is live, re-run `./gradlew koverHtmlReport` and confirm the target
branch flipped to covered.

- [ ] **Step 3: Commit per target.**

```bash
git add src/test/kotlin/.../<TargetTest>.kt
git commit -m "test(v2-stab): cover <decision point> in <component>

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Phase 2 — Final green bar + local merge (the finish line)

### Task 9: Green bar, CHANGELOG stabilization line, merge to main locally (no push)

**Files:**
- Modify: `CHANGELOG.md` (one line on the `[Unreleased]` entry)

- [ ] **Step 1: Full green bar** (JBR env exported, per CLAUDE.md Build prerequisites):

```bash
export JAVA_HOME=$(find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1)
export PATH=$JAVA_HOME/bin:$PATH
./gradlew test
./gradlew detekt
./gradlew verifyPlugin
```
Expected: `test` BUILD SUCCESSFUL; `detekt` BUILD SUCCESSFUL (no findings outside baseline);
`verifyPlugin` reports **Compatible**.

- [ ] **Step 2: Add the stabilization-pass line to the CHANGELOG `[Unreleased]` entry.** Under
  the V3 section, append:

```markdown
- **Stabilization pass (2026-06).** Coverage measurement wired (kover, report-only); the
  post–May-31 surface (fix engine, store correlation, no-regression gate) audited under two
  lenses (`docs/audit-2026-06-post-v2.md`); findings fixed-with-regression-test, hardened, or
  rejected-with-rationale.
```

- [ ] **Step 3: Commit the CHANGELOG line.**

```bash
git add CHANGELOG.md
git commit -m "docs(v2-stab): record the 2026-06 stabilization pass in CHANGELOG

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 4: Merge to `main` locally and build the plugin — DO NOT PUSH.**

```bash
git checkout main
git merge --no-ff v2-stabilization-reconcile
./gradlew buildPlugin
ls build/distributions/ghostdebugger-2.0.0.zip
```
Expected: clean fast-forward-free merge; `buildPlugin` produces
`build/distributions/ghostdebugger-2.0.0.zip`. **Do not `git push`** (dev-workflow preference:
merge to main locally + build, never push).

- [ ] **Step 5: STOP.** The finish line is reached. The **ship-vs-build decision is explicitly
  out of scope** (spec §6.5, §7) — it is taken separately, from this now-clean base.

---

## Self-review (against the spec)

**Spec coverage:**
- §3 Phase 0 (4 edits, one commit) → Tasks 1–4 ✅ (continuation-spec edit correctly a no-op;
  CLAUDE.md pointer repointed — the adaptation).
- §4.3.1 wire kover (report-only) + R1 fallback → Task 5 ✅
- §4.3.2 two-lens audit → findings doc → Task 6 ✅
- §4.3.3 land fixes TDD → Task 7 ✅ (per-finding protocol — correct for discovery-driven work).
- §4.3.4 risk-weighted coverage gaps (named decision points) → Task 8 ✅
- §4.3.5 final bar (test + detekt + verifyPlugin) + CHANGELOG line → Task 9 ✅
- §8 sequencing (branch first ✅ already cut; periodic commits ✅; merge local + build ✅; no
  push ✅) → reflected in every commit step.

**Placeholder scan:** Phase 0 edits are verbatim. Phases 1c/1d are *protocols* with fully-worked
example tests — intentional and correct for an audit phase whose fixes are outputs of Task 6, not
inputs. No "TODO/TBD" left to an engineer's guess.

**Type/name consistency:** `fixSupervised`, `FixPlanApplicator`, `SingleFileStaticReanalysis`,
`excludeBrokenFromLate`, `DebugObservationLogic`, `TestRunCorrelation`, `aegis-convention-reviewer`
used consistently and verified present in the tree.

**Non-goals honored:** no feature/analyzer/fixer/language added; no coverage *gate*; no `STATUS.md`;
no version bump or tag; ship-vs-build deferred.

---

## Execution handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-09-v2-stabilization-reconcile.md`.**
Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks.
   Phase 0 is ideal as a single safe subagent task; Phase 1b's Lens 1 *is* a subagent dispatch.
2. **Inline Execution** — execute tasks in this session with checkpoints (note: Phases 1a/1c/1d/2
   need the JBR `JAVA_HOME` export and run real Gradle, so they are slower and machine-bound).

Which approach?
