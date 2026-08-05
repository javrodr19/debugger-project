---
title: "Aegis Debug — Repo, Git & GitHub-Actions Cleanup Roadmap"
type: "spec"
status: "active"
related_components: []
aliases: []
tags:
  - aegis-debug
---

# Aegis Debug — Repo, Git & GitHub-Actions Cleanup Roadmap

**Date:** 2026-06-16
**Status:** Draft — forward-looking. Separate from the v2-stabilization phase; this is
repo/dev-infra hygiene. No production code, no version bump.
**Companion:** `docs/superpowers/specs/2026-06-16-plugin-actions-product-roadmap-design.md`
(the *plugin* actions product roadmap — this doc covers *GitHub* Actions).

---

## 0. Summary

The repository carries accumulated git debt from a chaotic genesis that predates the project's
now-disciplined conventional-commits practice: **8 local branches** (6 already merged into `main`),
**stale remote branches**, **3 inconsistently-named published tags**, **~20 genuinely-bad commit
messages** in the oldest history, and **no real CI** — a plugin with a 472-test suite, a detekt
gate, and a `verifyPlugin` compatibility check that runs *none* of them on push/PR, behind a pile of
6 Gemini-bot workflows.

This roadmap cleans all of that in **four tracks**, deliberately split along a **safe / destructive**
seam so the safe work (local branch pruning, the commit *convention*, real CI) can land immediately
without waiting on the one genuinely-dangerous operation (rewriting published history).

| Track | Theme | Blast radius |
|---|---|---|
| **T1** | Branches | local = safe; remote = needs push |
| **T2** | Tags | published-tag edits = needs force/push |
| **T3** | Commit messages — *convention (safe)* + *history rewrite (destructive)* | rewrite = re-SHAs all history + needs force-push |
| **T4** | GitHub Actions: prune bots + add real CI | safe (new/edited workflows) |

---

## 1. Motivation (the verified state, 2026-06-16)

- **Branches (8 local):** `main`, `v2-stabilization-reconcile` (active), plus **6 merged-into-main**
  stragglers: `fix/codebase-audit-bugs`, `v1.5-pre-v2-refactor`, `v1.4.1-audit-fixes`,
  `v1.4-cleanup`, `v1.3-k2-migration`, `v1.2-hardening`. Remote has a `feat/fix-engine-phase2b`
  with **no local counterpart**, plus 3 stale merged ones.
- **Tags (3, all published):** `debugger` (junk — points at an old genesis commit),
  `debugger1.1.0` (no `v`, no separator), `v.1.5.0` (non-standard `v.` prefix). Releases
  `v1.2.0`–`v1.4.1` are documented in the CHANGELOG but **untagged**.
- **Commits (262, two eras):** recent history is clean conventional-commits; the **oldest ~20** are
  not — `update`×4, `updates`, `windows patch`, `fallos menores`×2, `vuelta al prehistoceno`,
  `Add files via upload`×3, `Delete <x>`×6, two identical generic `Refactor code structure…`, and a
  joke-prefixed `COMO USES ESTO TE EXPLOTA EL PC GG XD perf: …`.
- **CI: none for the plugin.** All 7 `.github/workflows/` are 6 Gemini-bot workflows
  (`gemini-dispatch` orchestrating `invoke`/`review`/`triage`/`plan-execute`, plus a cron
  `scheduled-triage`) + `site.yml` (landing-page deploy). **No workflow provisions a JVM or runs
  `gradle test` / `detekt` / `verifyPlugin`.** The local green-bar is never enforced.

---

## 2. Scope decisions (locked in)

| # | Decision | Rationale |
|---|---|---|
| D1 | **Safe / destructive seam.** T1-local, T3-convention, and T4 are non-destructive and land first/independently. T1-remote, T2-published, and T3-rewrite are isolated behind explicit gates. | The headline wins (real CI, a clean local branch list, a codified convention) should not be held hostage to the force-push decision. |
| D2 | **History rewrite is reversible and atomic.** A backup ref is created first; the reword + doc-SHA-remap + tag-recreate happen as **one** operation driven by the filter-repo commit-map. | A half-done rewrite (messages changed, references not remapped) is worse than no rewrite. Atomicity + backup is the safety contract. |
| D3 | **Anything touching `origin` is explicitly user-gated** — remote-branch deletion, tag moves, and the rewrite force-push are a deliberate, documented **exception** to the standing "don't push" workflow, taken only on explicit go. | The user's default is "merge to main locally + build, never push." These operations are the rare justified exceptions and must be opt-in each time. |
| D4 | **CI runs on a JetBrains Runtime, not a generic JDK.** `actions/setup-java` with `distribution: jetbrains`. | IPGP's `instrumentTestCode` probes for a JBR-only `Packages` dir and aborts with *"Packages does not exist"* on any generic JDK (CLAUDE.md › Build prerequisites). A naive `temurin` CI would fail before a single test runs. |
| D5 | **Marketplace publish stays gated** behind the deferred ship-vs-build decision. Release CI builds + drafts a GitHub Release; it does **not** `publishPlugin`. | Publishing is a "ship" action; the stabilization phase deferred ship-vs-build. CI can be ready without pulling that trigger. |

---

## 3. Track 1 — Branches

### T1a — Delete the 6 merged local branches (SAFE, no push)

All six are merged into `main` (verified via `git branch --merged main`); deleting them loses
nothing — their history lives in `main`.

```bash
git branch -d fix/codebase-audit-bugs v1.5-pre-v2-refactor v1.4.1-audit-fixes \
              v1.4-cleanup v1.3-k2-migration v1.2-hardening
```
Keep: `main`, `v2-stabilization-reconcile` (active, unmerged).

### T1b — Stale remote branches (GATED — requires push)

`origin/feat/fix-engine-phase2b` (no local branch), `origin/v1.2-hardening`,
`origin/v1.4.1-audit-fixes`, `origin/v1.5-pre-v2-refactor` are merged/abandoned. Deletion is a
push and so is **listed for explicit approval**, not done by default:

```bash
# ONLY on explicit go (D3):
git push origin --delete feat/fix-engine-phase2b v1.2-hardening v1.4.1-audit-fixes v1.5-pre-v2-refactor
```

### T1c — Convention going forward

Short-lived branches off `main` (`feat/…`, `fix/…`, `vX.Y-…`); delete after merge. Documented in
`CONTRIBUTING.md` (created in T3a).

---

## 4. Track 2 — Tags

### Current → target

| Current (published) | Points at | Action | Target |
|---|---|---|---|
| `debugger` | genesis commit | **delete** (junk) | — |
| `debugger1.1.0` | v1.1 release | **rename** | `v1.1.0` |
| `v.1.5.0` | v1.5 release | **rename** | `v1.5.0` |
| *(none)* | v1.2–v1.4.1 release merges | **backfill** | `v1.2.0`, `v1.3.0`, `v1.4.0`, `v1.4.1` |

**Convention:** semver, `vMAJOR.MINOR.PATCH`, annotated tags on the release merge commit. No `2.0.0`
tag — that release is deferred (D5 / the stabilization phase's ship-vs-build gate).

All tag edits touch published refs → **GATED (D3)**. Because tags are deleted/recreated on origin
anyway, **fold this into the T3 rewrite if the rewrite happens** (tags must be recreated on the new
SHAs regardless — see T3). If the rewrite is declined, do the tag fixes standalone (delete + re-tag
+ push, on explicit go).

---

## 5. Track 3 — Commit messages (*do both*)

### T3a — Convention going forward (SAFE — lands immediately)

- **`.gitmessage` template** (`git config commit.template .gitmessage`) seeding the
  `type(scope): subject` form the recent history already follows.
- **`CONTRIBUTING.md`** documenting the convention (types: `feat|fix|docs|test|chore|refactor|build|perf|ci`),
  the branch convention (T1c), and the JBR build prerequisite.
- **Local `commit-msg` hook** (under `.githooks/`, opt-in via `git config core.hooksPath .githooks`)
  rejecting non-conforming subjects — local-only, consistent with the gitignored-`.claude/` pattern
  of per-developer aids.
- *(Optional)* a `commitlint` config if a Node toolchain is acceptable; otherwise the shell hook
  suffices and adds no dependency.

### T3b — Rewrite the bad genesis history (DESTRUCTIVE — gated, reversible, atomic)

> **⚠️ The cascade.** Git SHAs are content-addressed including the parent SHA, so rewording *any*
> genesis commit **re-SHAs every commit after it** — the entire 262-commit history, including the
> SHAs the docs cite. This is why the reword is not a standalone step but an **atomic operation**
> bundled with reference-remapping and tag-recreation.

**The referenced SHAs that MUST be remapped** (verified — exactly 3 files contain them):

| File | SHAs cited |
|---|---|
| `CLAUDE.md` | `7e31776` |
| `docs/superpowers/plans/2026-06-09-v2-stabilization-reconcile.md` | `32c351e`, `ec91efd`, `7171d6b`, `1384f1f`, `52e5fd8` |
| `docs/superpowers/specs/2026-06-09-v2-stabilization-reconcile-design.md` | `32c351e`, `ec91efd`, `1384f1f`, `7171d6b`, `52e5fd8` |

**Procedure (atomic, reversible):**

1. **Backup** (the undo button): `git branch backup/pre-rewrite-2026-06-16 && git tag backup/pre-rewrite-tags-2026-06-16`.
   Push the backup branch to a throwaway remote ref too, on explicit go.
2. **Reword** non-interactively with `git filter-repo --commit-callback` (NOT `rebase -i` — interactive
   rebase is unavailable in this environment, and filter-repo is the right bulk tool). Key the callback
   on `commit.original_id`; set `commit.message` per-commit. Messages are derived from each commit's
   **diff at execution time** (the plan resolves them); the table below seeds the obvious ones.
3. **Capture the map:** filter-repo writes `.git/filter-repo/commit-map` (old-SHA → new-SHA).
4. **Remap doc references:** for each `(old,new)` in the map that appears in the 3 files above,
   rewrite the reference. Drive it from the map — do not hand-edit (a hand-edit will miss one).
   Commit the doc updates **on the rewritten history**.
5. **Recreate tags** (T2 targets) on the new SHAs from the map.
6. **Force-push** `main` + tags to origin — the **gated** final step (D3). `--force-with-lease`,
   never bare `--force`.
7. **Rebase `v2-stabilization-reconcile`** onto the rewritten `main` (its 2 commits replay cleanly).

**Seed reword table** (genesis junk → conventional; precise messages from diffs at plan time):

| Old subject | Proposed form |
|---|---|
| `update` / `updates` / `windows patch` / `vuelta al prehistoceno` | `chore: <what the diff actually changed>` (e.g. `fix(build): Windows path/line-ending compat`) |
| `fallos menores` (×2) | `fix: <specific minor fix from diff>` |
| `Add files via upload` (×3) | `feat: <files added>` or squash into the adjacent feature commit |
| `Delete <x>` (×6, GitHub-web churn) | `chore: remove <x>` — or squash obvious add/delete pairs |
| `Refactor code structure for improved readability…` (×2, generic) | `refactor: <the actual structural change>` |
| `COMO USES ESTO TE EXPLOTA EL PC GG XD perf: …` | `perf: reduce memory footprint & analysis latency` (strip the joke prefix) |
| `todo.txt` | drop the file or `chore: add scratch todo notes` |

> **Non-destructive alternative (recorded, not recommended):** `git notes` can *annotate* bad commits
> with corrected descriptions without rewriting SHAs — zero blast radius, but notes are off-by-default
> and easily lost, so they don't really "fix" the history. The user chose to rewrite; this is noted
> only as the fallback if the force-push is later declined.

---

## 6. Track 4 — GitHub Actions: prune bots + add real CI

### T4a — Triage the 6 Gemini-bot workflows

`gemini-dispatch` (fires on every PR/issue/issue_comment), `gemini-scheduled-triage` (cron + push +
PR + dispatch), and the `workflow_call` sub-workflows `gemini-invoke` / `gemini-review` /
`gemini-triage` / `gemini-plan-execute` were added wholesale in one genesis commit. **Decision per
workflow: keep only if actively used AND its `GEMINI_API_KEY`/secret is set** — otherwise they burn
Actions minutes and fail noisily on every PR. Default recommendation: **remove the Gemini suite**
(and `.github/commands/*.toml`) unless the user confirms they use it; **keep `site.yml`**.

### T4b — Add real CI (`.github/workflows/ci.yml`) — the headline win

On `pull_request` + `push` to `main`/`v2-*`:
- `actions/setup-java` with **`distribution: jetbrains`**, `java-version: 21` (D4 — the JBR that
  IPGP's `instrumentTestCode` requires).
- `gradle/actions/setup-gradle` for caching.
- Run `./gradlew test detekt verifyPlugin`. The plugin's existing gates become *enforced* checks.
- *(If kover lands from the stabilization phase)* upload `koverXmlReport` as an artifact —
  measurement, still not a gate (mirrors that phase's D3).

### T4c — Add release CI (`.github/workflows/release.yml`)

On `push` of a `v*` tag: `./gradlew buildPlugin`, attach `build/distributions/ghostdebugger-*.zip`
to a **draft** GitHub Release. `publishPlugin` to JetBrains Marketplace is present but **commented +
gated** behind the deferred ship decision (D5).

---

## 7. Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Rewrite orphans doc references / clones | High *if* done carelessly | D2 atomicity: backup ref + map-driven remap + tag-recreate as one operation; `--force-with-lease`; the 3 referenced files are enumerated (§5). |
| CI fails with "Packages does not exist" | Medium | D4: `distribution: jetbrains`. A generic-JDK CI is the predictable failure mode this explicitly avoids. |
| Deleting a Gemini workflow the user *does* rely on | Low | T4a is keep-vs-prune **per workflow on confirmation**, not a blind delete. |
| Force-push collides with collaborator work | Low (solo repo) | `--force-with-lease`; backup ref; do it when no PRs are open. |
| Tag renames break external links to releases | Low | Backfill + rename, keep a note mapping old→new tag names in the release bodies. |

---

## 8. Success criteria

1. `git branch` shows only live branches; merged stragglers gone; stale remote branches gone (on go).
2. `git tag` follows `vX.Y.Z`; `debugger` removed; `v1.1.0`/`v1.5.0` correct; `v1.2.0`–`v1.4.1` backfilled.
3. A commit convention is codified (`.gitmessage` + `CONTRIBUTING.md` + hook) and — if the rewrite
   runs — the genesis junk messages are gone **and every doc SHA reference still resolves**.
4. `ci.yml` runs `test`+`detekt`+`verifyPlugin` on a JBR for every PR; `release.yml` drafts a Release
   on tag; the Gemini bots are pruned to what's actually used.
5. Backup ref exists; nothing destructive happened without an explicit go (D3).

---

## 9. Non-goals

- **Publishing to JetBrains Marketplace** — gated behind ship-vs-build (D5).
- **Squashing/reordering beyond message fixes** — the rewrite fixes *messages* (and optional obvious
  churn pairs), not a wholesale history re-architecture.
- **Rewriting the *good* recent history** — only the genesis junk is touched.
- **A Node/commitlint dependency** if the shell hook suffices (YAGNI).
- **The plugin's IDE actions** — that is the companion product roadmap, not this doc.

---

## 10. Sequencing & hand-off

```
T3a convention (.gitmessage + CONTRIBUTING + hook)   → SAFE, lands first
T1a delete 6 merged local branches                   → SAFE
T4a–c prune Gemini + add ci.yml + release.yml         → SAFE
── gate (explicit go, "don't push" exception) ──
T1b stale remote-branch deletion                     → push
T2  tag delete/rename/backfill                        → folds into T3b if rewriting
T3b genesis history rewrite (backup→reword→remap→retag→force-push)
```

The safe block (T3a, T1a, T4) is a clean writing-plans target on its own. The gated block is a
second, explicitly-authorized plan. Next step: invoke writing-plans for the **safe block** so it can
land independently; schedule the gated block when you're ready to force-push.
