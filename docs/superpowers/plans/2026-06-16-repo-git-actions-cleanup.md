# Repo / Git / GitHub-Actions Cleanup — Implementation Plan (Stream C)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or
> superpowers:executing-plans. Read `plans/2026-06-16-parallel-execution-coordination.md` first — this
> is **Stream C**. Work on branch `stream/cleanup`. The **safe block (Phases 1–4) is parallel**
> (coordination Merge 1); **Phase G (history rewrite) runs LAST, after all four streams merge**
> (coordination §5). Steps use checkbox (`- [ ]`) syntax.

**Goal:** Clean the repo's git debt — prune merged branches, fix tags, codify a commit convention,
replace the Gemini-bot workflow pile with real CI + release automation — and (gated, last) rewrite the
bad genesis commit messages without breaking any doc SHA reference.

**Architecture:** The safe block touches only non-code files (`.github/`, `.gitmessage`,
`CONTRIBUTING.md`, `.githooks/`) and local branches — zero conflict with the code streams, so it
merges first and gives every later PR a CI gate. The destructive history rewrite is isolated in
Phase G as an atomic, reversible, map-driven operation. Spec:
`specs/2026-06-16-repo-git-actions-cleanup-design.md`.

**Tech Stack:** git, `git filter-repo`, GitHub Actions (`actions/setup-java` `distribution: jetbrains`
— the JBR that IPGP's `instrumentTestCode` requires), `gradle/actions/setup-gradle`.

> **Note on testing style:** this stream is infra/config — "tests" are **verification commands**
> (lint, dry-run, CI run), not unit tests. That is the correct discipline here.

---

## Phase 1 — Commit convention (SAFE, lands first)

### Task 1: `.gitmessage` template + `CONTRIBUTING.md` + commit-msg hook

**Files:** Create `.gitmessage`, `CONTRIBUTING.md`, `.githooks/commit-msg`

- [ ] **Step 1: Create `.gitmessage`:**

```
# <type>(<scope>): <imperative subject, <=72 chars>
#
# type ∈ feat|fix|docs|test|chore|refactor|build|perf|ci|revert
# Body (wrap 72): WHY, not what.
# Footer: Co-Authored-By:, refs #issue
```

- [ ] **Step 2: Create `.githooks/commit-msg`** (rejects non-conforming subjects):

```bash
#!/usr/bin/env bash
subject=$(head -1 "$1")
case "$subject" in
  Merge*|Revert*) exit 0 ;;
esac
if ! printf '%s' "$subject" | grep -qE '^(feat|fix|docs|test|chore|refactor|build|perf|ci|revert)(\(.+\))?(!)?: .+'; then
  echo "✖ commit subject must be: type(scope): subject  (see .gitmessage)" >&2
  exit 1
fi
```

- [ ] **Step 3: Create `CONTRIBUTING.md`** documenting: the commit convention (types above), the
  branch convention (short-lived `feat/`·`fix/` off `main`, delete after merge), and the **JBR build
  prerequisite** (copy the `JAVA_HOME` export from CLAUDE.md › Build prerequisites).

- [ ] **Step 4: Wire the hook + template (local, opt-in) and verify it rejects a bad subject:**

```bash
git config core.hooksPath .githooks && chmod +x .githooks/commit-msg
git config commit.template .gitmessage
git commit --allow-empty -m "update" 2>&1 | grep -q "✖ commit subject" && echo "HOOK OK"
```
Expected: `HOOK OK` (the bad message is rejected).

- [ ] **Step 5: Commit.**

```bash
git add .gitmessage CONTRIBUTING.md .githooks/commit-msg
git commit -m "chore(repo): add commit-message convention (.gitmessage + hook + CONTRIBUTING)"
```

## Phase 2 — Delete merged local branches (SAFE)

### Task 2: Prune the 6 merged-into-main branches

**Files:** none (git refs only)

- [ ] **Step 1: Confirm each is merged, then delete** (`-d` refuses unmerged — a safety net):

```bash
git branch --merged main   # confirm the 6 appear
git branch -d fix/codebase-audit-bugs v1.5-pre-v2-refactor v1.4.1-audit-fixes \
              v1.4-cleanup v1.3-k2-migration v1.2-hardening
git branch   # expect: main, stream/* only
```
Expected: 6 deletions; no `error: branch is not fully merged`.

> No commit — branch deletions are ref changes, not tree changes.

## Phase 3 — Prune Gemini workflows + add `.gitignore`/repo hygiene (SAFE)

### Task 3: Remove the unused Gemini-bot suite (keep `site.yml`)

**Files:** Delete `.github/workflows/gemini-*.yml`, `.github/commands/*.toml`

- [ ] **Step 1: Confirm none are secret-backed / in use** (if the user confirms they *do* use Gemini,
  SKIP this task). Then remove the suite:

```bash
git rm .github/workflows/gemini-dispatch.yml .github/workflows/gemini-invoke.yml \
       .github/workflows/gemini-review.yml .github/workflows/gemini-triage.yml \
       .github/workflows/gemini-scheduled-triage.yml .github/workflows/gemini-plan-execute.yml \
       .github/commands/gemini-*.toml
```

- [ ] **Step 2: Verify `site.yml` remains.** Run: `ls .github/workflows/` → expect `site.yml` (+ the
  new `ci.yml`/`release.yml` after Phase 4).

- [ ] **Step 3: Commit.**

```bash
git commit -m "ci(repo): remove unused Gemini-bot workflow suite (keep site deploy)"
```

## Phase 4 — Add real CI + release automation (SAFE — the headline win)

### Task 4: `ci.yml` — JBR-provisioned test/detekt/verifyPlugin on PR

**Files:** Create `.github/workflows/ci.yml`

- [ ] **Step 1: Create the workflow** (JBR is the critical detail — a generic JDK fails IPGP's
  `instrumentTestCode` with "Packages does not exist"):

```yaml
name: CI
on:
  pull_request:
  push:
    branches: [main, "stream/**", "v2-*"]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: jetbrains   # JetBrains Runtime — required by instrumentTestCode
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew test detekt verifyPlugin
```

- [ ] **Step 2: Verify it parses + runs** by pushing `stream/cleanup` and watching the run, or locally:

```bash
gh workflow view ci.yml 2>/dev/null || echo "push the branch to trigger the first run"
```
Expected: the run sets up a JBR, runs the three gates green. If it fails on JBR, confirm
`distribution: jetbrains` (not `temurin`).

### Task 5: `release.yml` — build + draft GitHub Release on tag (publish gated)

**Files:** Create `.github/workflows/release.yml`

- [ ] **Step 1: Create the workflow:**

```yaml
name: Release
on:
  push:
    tags: ['v*']
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: jetbrains, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew buildPlugin
      - uses: softprops/action-gh-release@v2
        with:
          draft: true   # never auto-publishes a release
          files: build/distributions/ghostdebugger-*.zip
      # NOTE: `./gradlew publishPlugin` (JetBrains Marketplace) is intentionally OMITTED —
      # gated behind the deferred ship-vs-build decision (spec D5).
```

- [ ] **Step 2: Commit Phase 4.**

```bash
git add .github/workflows/ci.yml .github/workflows/release.yml
git commit -m "ci(repo): add real CI (JBR test/detekt/verifyPlugin) + draft-release on tag"
```

> **End of the SAFE block.** `stream/cleanup` is now ready for coordination **Merge 1** (it touches no
> code files; merges first so every later stream PR gets the CI gate). Phases G run only after all
> four streams have merged (coordination §5).

---

## Phase G — Tags + history rewrite (DESTRUCTIVE · GATED · runs LAST · not parallel)

> **Pre-conditions (all must hold):** Merges 1–4 of the coordination plan are on `main`; the bar is
> green; you have explicit go to force-push (a deliberate exception to "don't push"). This phase
> re-SHAs the entire history and **remaps the doc SHA references**, so it cannot run while any stream
> still produces commits.

### Task G1: Backup (the undo button)

- [ ] **Step 1:** `git checkout main && git branch backup/pre-rewrite-2026-06-16 && git tag backup/pre-rewrite-state`
- [ ] **Step 2:** Verify: `git log --oneline -1 backup/pre-rewrite-2026-06-16` shows current `main` tip.

### Task G2: Reword the genesis junk (non-interactive)

**Files:** none in worktree (history op)

- [ ] **Step 1: Prepare a per-commit message map** keyed on original commit id (subjects derived from
  each commit's diff). Seed (precise text resolved from `git show <sha>` at execution): `update`/`updates`/`windows patch`/`vuelta al prehistoceno` → `chore:`/`fix(build):` describing the real
  change; `fallos menores`×2 → `fix:` specific; `Add files via upload`×3 → `feat:` or squash;
  `Delete <x>`×6 → `chore: remove <x>`; `Refactor code structure…`×2 → `refactor:` specific;
  `COMO USES ESTO… perf:` → `perf: reduce memory footprint & analysis latency`.

- [ ] **Step 2: Run filter-repo** (NOT `rebase -i` — unavailable here; filter-repo is the right tool):

```bash
git filter-repo --commit-callback '
  m = {
    b"<orig_id_1>": b"chore(build): Windows path/line-ending compat",
    # ... one entry per genesis junk commit, keyed on commit.original_id ...
  }
  if commit.original_id in m:
      commit.message = m[commit.original_id] + b"\n"
'
```

- [ ] **Step 3: Capture the map.** Confirm `.git/filter-repo/commit-map` exists (old→new SHAs).

### Task G3: Remap the doc SHA references (mandatory — the cascade)

**Files:** Modify `CLAUDE.md`, `docs/superpowers/plans/2026-06-09-v2-stabilization-reconcile.md`, `docs/superpowers/specs/2026-06-09-v2-stabilization-reconcile-design.md`

- [ ] **Step 1: For each `(old,new)` in the commit-map that appears in those 3 files, rewrite the
  reference** (drive from the map — do NOT hand-edit). The referenced short SHAs are
  `7e31776` (CLAUDE.md), `32c351e`/`ec91efd`/`7171d6b`/`1384f1f`/`52e5fd8` (the two docs):

```bash
# illustrative: translate each short SHA via the map, then sed the 3 files
while read old new; do
  for f in CLAUDE.md docs/superpowers/plans/2026-06-09-v2-stabilization-reconcile.md \
           docs/superpowers/specs/2026-06-09-v2-stabilization-reconcile-design.md; do
    sed -i "s/${old:0:7}/${new:0:7}/g" "$f"
  done
done < .git/filter-repo/commit-map
```

- [ ] **Step 2: Verify no orphaned reference remains.**

```bash
for s in 7e31776 32c351e ec91efd 7171d6b 1384f1f 52e5fd8; do
  git cat-file -e "$s" 2>/dev/null && echo "$s still valid (mapped to itself? check)" || echo "$s remapped"
done
git add -A && git commit -m "docs(repo): remap commit SHA references after genesis history rewrite"
```

### Task G4: Recreate tags on the new SHAs (semver convention)

- [ ] **Step 1:** Delete junk + rename + backfill, all on the rewritten history:

```bash
git tag -d debugger debugger1.1.0 v.1.5.0
git tag -a v1.1.0 <new-sha-for-1.1> -m "v1.1.0"
git tag -a v1.5.0 <new-sha-for-1.5> -m "v1.5.0"
# backfill from the release-merge commits (new SHAs):
git tag -a v1.2.0 <new-sha> -m "v1.2.0"; git tag -a v1.3.0 <new-sha> -m "v1.3.0"
git tag -a v1.4.0 <new-sha> -m "v1.4.0"; git tag -a v1.4.1 <new-sha> -m "v1.4.1"
```

### Task G5: Force-push + remote-branch cleanup (the gated, outward step)

- [ ] **Step 1 (explicit go only):** `git push --force-with-lease origin main`
- [ ] **Step 2:** `git push origin --tags --force` then delete the old remote tags
  (`git push origin --delete debugger debugger1.1.0 v.1.5.0`).
- [ ] **Step 3:** Delete stale remote branches:
  `git push origin --delete feat/fix-engine-phase2b v1.2-hardening v1.4.1-audit-fixes v1.5-pre-v2-refactor`
- [ ] **Step 4: Rebase any survivor branches** onto the rewritten `main`. Done.

---

## Self-review (against the spec)

- T1 branches → Phase 2 (local) + G5 (remote) ✅ · T2 tags → G4 + G5 ✅
- T3 commits: convention → Phase 1 ✅; rewrite → Phases G1–G3 (backup + map-driven remap) ✅
- T4 Actions: prune Gemini → Phase 3 ✅; real CI (JBR) → Phase 4 Task 4 ✅; release (publish gated) → Task 5 ✅
- **Safe/destructive seam honored:** Phases 1–4 are non-code, parallel-safe (Merge 1); Phase G is last + gated.
- **Doc-SHA cascade handled:** G3 remaps the exact 3 files; G2 backs up first; G5 uses `--force-with-lease`.

## Execution handoff

Stream-C branch `stream/cleanup`. The **safe block (Phases 1–4)** integrates at coordination **Merge 1**
(first, zero code conflict). **Phase G** runs **after all four streams merge** (coordination §5), on
explicit go. Subagent-driven recommended for Phases 1–4; Phase G is controller-run.
