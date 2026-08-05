---
title: "Parallel Execution Coordination Plan"
type: "plan"
status: "active"
related_components: []
aliases: []
tags:
  - aegis-debug
---

# Parallel Execution Coordination Plan

> **For agentic workers:** This is the **umbrella** plan. It does not implement a feature — it
> defines the branch model, shared-file ownership, and integration order that let the four stream
> plans run **at the same time** without colliding. Read this first; then each stream follows its own
> plan. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let four work streams proceed concurrently and integrate cleanly, by partitioning every
shared file so no two streams edit the same region, and by sequencing the one operation that *cannot*
parallelize (the git history rewrite) to run last.

**Architecture:** One branch per stream off a common base. Each stream owns a disjoint set of files;
the handful of genuinely-shared files are partitioned **by region** with a named owner. A fixed
integration order plus a rebase-before-merge rule keeps merges trivial. The destructive history
rewrite is excluded from the parallel set and run after everything else has merged.

**Tech Stack:** git (branch-per-stream), the four stream plans below.

---

## 1. The four streams

| Stream | Plan | Spec | Footprint summary |
|---|---|---|---|
| **A — Stabilization** | `plans/2026-06-09-v2-stabilization-reconcile.md` *(exists)* | `specs/2026-06-09-…-design.md` | CLAUDE.md, CHANGELOG, roadmap, `build.gradle.kts` plugins block (kover), audit doc, fix/engine + store **fixes/tests** |
| **B — V3.1 Custom Rules** | `plans/2026-06-16-v3-custom-rule-authoring.md` | `specs/2026-06-16-v3-custom-rule-authoring-design.md` | `build.gradle.kts` deps block (YAML lib), `AnalysisEngine` analyzers list, `model/AnalysisModels.kt` enum, `plugin.xml` extensions, **new** rule loader/analyzer/anchor-resolver files |
| **C — Repo/Git/Actions cleanup** | `plans/2026-06-16-repo-git-actions-cleanup.md` | `specs/2026-06-16-repo-git-actions-cleanup-design.md` | `.github/`, `.gitmessage`, `CONTRIBUTING.md`, `.githooks/` (safe) + **git history rewrite** (gated, sequential) |
| **D — Plugin Actions Batch 1** | `plans/2026-06-16-plugin-actions-batch1.md` | `specs/2026-06-16-plugin-actions-product-roadmap-design.md` | `plugin.xml` actions section, **new** `actions/*.kt`, `ConfigureApiKeyAction` label |

> The `specs/2026-06-16-v3-frontier-overview-design.md` is a **sequencing doc**, not an implementable
> unit — it has no plan of its own; its first pillar (B) is the one with a plan.

---

## 2. Shared-file ownership matrix (the core of conflict-free parallelism)

Only **four** files are touched by more than one stream in the parallel set. Each is partitioned by
region with a single owner per region. **A stream edits only its owned region.**

| Shared file | Region → owner | Why it's conflict-free |
|---|---|---|
| `build.gradle.kts` | `plugins { }` (L4–9) → **A** (kover) · `dependencies { }` (~L46) → **B** (YAML lib) | Different blocks, ~40 lines apart |
| `plugin.xml` | `<extensions>` projectService (~L154–198) → **B** (CustomRuleService) · `<actions>` (L308–331) → **D** (new actions) | Different top-level sections, ~110 lines apart |
| `analysis/AnalysisEngine.kt` | `analyzers` list (L30–42) → **B** (one line) · gate/late-pass (~L160+) → **A** (audit fix) | Different methods/regions |
| `model/AnalysisModels.kt` | `IssueSource` enum → **B** (append `CUSTOM`) · everyone else **reads only** | Additive enum value; no behavioral edit elsewhere |

**`fix/engine/` rule:** **A owns all edits to *existing* fix-engine files** (its audit fixes). **B and
D only *add new* files** (`RuleAnchorResolver`, etc.) and **call existing public APIs** (`FixPlan`,
`FixPlanApplicator`, `FixVerifier`). If A must change a public signature B/D depend on, A flags it in
the stream-A branch and B/D rebase — see §4.

**`CLAUDE.md` / `CHANGELOG.md`:** in the parallel set, **only A** edits these (Phase 0). Stream C's
SHA-remap of `CLAUDE.md:142` is part of the **gated** rewrite (§5), which runs *after* A has merged —
so they never overlap in time.

---

## 3. Branch model

- [ ] **Step 1: Establish the common base.** After all four plans are committed (this commit set),
  fast-forward the doc commits onto `main` so every stream branches from a base that contains all
  plans:

```bash
# doc-only commits, safe; gives all streams the same plan base
git checkout main
git merge --ff-only v2-stabilization-reconcile   # if FF-able; else --no-ff (docs only)
```

- [ ] **Step 2: Cut one branch per stream off `main`.**

```bash
git checkout main
git branch stream/v3-custom-rules
git branch stream/cleanup
git branch stream/plugin-actions
# Stream A continues on the existing branch:
git branch -m v2-stabilization-reconcile stream/stabilization   # optional rename for symmetry
```

- [ ] **Step 3: Each stream works only on its branch**, commits per its plan's commit steps, and runs
  its own green bar (JBR env exported — CLAUDE.md Build prerequisites). No stream merges to `main`
  except via §4.

---

## 4. Integration order (rebase-before-merge)

Merge to `main` in this order — chosen so the zero-conflict stream lands first and each subsequent
stream rebases onto a `main` that already contains the prior streams' (disjoint) regions:

- [ ] **Merge 1 — Stream C (cleanup, safe block only).** It touches **no code files** — only
  `.github/`, `.gitmessage`, `CONTRIBUTING.md`, `.githooks/`, and deletes merged local branches.
  Zero code conflict; merges first so the new **CI runs on every subsequent stream's PR**.

```bash
git checkout main && git merge --no-ff stream/cleanup
```

- [ ] **Merge 2 — Stream A (stabilization).** Lands kover (`plugins{}`), the Phase-0 doc edits, the
  fix/engine audit fixes + tests. Before merging, rebase A on `main`:

```bash
git checkout stream/stabilization && git rebase main   # absorbs C; no overlap
git checkout main && git merge --no-ff stream/stabilization
```

- [ ] **Merge 3 — Stream B (V3.1).** Rebase on `main` first; B's regions (deps block, analyzers list,
  enum, extensions, new files) are disjoint from A's, so the rebase is clean:

```bash
git checkout stream/v3-custom-rules && git rebase main
git checkout main && git merge --no-ff stream/v3-custom-rules
```

- [ ] **Merge 4 — Stream D (plugin actions).** Rebase on `main` first; D shares only `plugin.xml`
  with B, in a different section (`<actions>` vs `<extensions>`) — a clean three-way merge:

```bash
git checkout stream/plugin-actions && git rebase main
git checkout main && git merge --no-ff stream/plugin-actions
```

- [ ] **After each merge:** run the green bar on `main` (`test` + `detekt` + `verifyPlugin`, JBR env).
  A red bar blocks the next merge until fixed on the offending stream's branch.

> **Order rationale:** C first (orthogonal, gives CI). A second (stabilizes fix/engine, which B/D
> build on; lands kover). B third, D fourth (they share only the cleanly-partitioned `plugin.xml`).
> If two code streams finish out of order, the rebase-first rule still makes the merge clean because
> the regions are disjoint — the order is an optimization, not a hard requirement, **except** that
> the gated rewrite (§5) is strictly last.

---

## 5. The one thing that is NOT parallel — the git history rewrite (Stream C, gated)

- [ ] **Run only after Merges 1–4 are on `main` and the bar is green.** The genesis-history rewrite
  (cleanup plan Phase G) re-SHAs the entire history and **remaps the doc SHA references**
  (`CLAUDE.md:142`, the stabilization plan/spec). Running it while any stream is still producing
  commits or SHA references would invalidate the remap. It is therefore **excluded from the parallel
  set** and executed as the final, explicitly-gated step (force-push). See the cleanup plan, Phase G.

---

## 6. Amendment to the existing Stream-A plan

The stabilization plan (`plans/2026-06-09-v2-stabilization-reconcile.md`) Phase 2 Task 9 Step 4 says
"merge to `main` locally." **Under this coordination plan, A does not race to `main` independently —
it merges at Integration Order Merge 2 (§4)**, after C. No other change to the A plan.

---

## 7. Definition of done (the parallel effort)

1. Four stream branches existed, were worked concurrently, and merged to `main` in the §4 order with
   **no manual conflict resolution** (proof that the partition held).
2. `main` is green (`test` + `detekt` + `verifyPlugin`) after every merge.
3. The gated history rewrite ran last (or was deferred), with all doc SHA references still resolving.
4. No stream edited another stream's owned region (verifiable: `git log -p` per shared file shows one
   owner per region).

---

## 8. Execution handoff

Each stream is independently executable (subagent-driven or inline). Recommended: dispatch the four
stream plans to **four parallel subagents/workers**, each on its own `stream/*` branch, with this
coordination plan as the shared contract. The controller (you) performs the §4 integration merges and
the §5 gated rewrite. Start the streams now; integrate as each reports green.
