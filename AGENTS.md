# Aegis Debug — Agent Guide

Cross-agent guardrails for any AI assistant (Claude, Cursor, Copilot Workspace, Cody,
Codex CLI, etc.) working on this repository. This file is intentionally
agent-agnostic — it captures *how the project is built*, not how a specific tool
operates.

If you are Claude Code: read `CLAUDE.md` first; this document complements it but does
not replace it. `CLAUDE.md` carries the Claude-specific build prerequisites (JBR /
`JAVA_HOME` quirk) and the reasoning behind several conventions.

---

## 0. Read these before you touch anything

The single most important habit in this repo is **don't write code before you
understand the conventions**. Skim, in order:

1. `docs/aegis_debug_roadmap_v2_to_v5.md` — the long-term north star. Every PR should
   move the project *toward* a roadmap milestone, not sideways.
2. `CLAUDE.md` — build prerequisites and code conventions with their reasoning.
3. `docs/superpowers/specs/` — most recent spec for context on the current release line.
4. `docs/superpowers/plans/` — the matching plan, if one exists.
5. `docs/aegis_v1_history.md` — V1 phases and why decisions were made as they were.

Three to ten minutes of reading prevents most of the mistakes documented below.

---

## 1. The guiding principles (never violate without explicit user opt-in)

These four are quoted verbatim from the roadmap and apply to every change:

1. **Static-first, AI-optional.** AI never becomes load-bearing for correctness.
   Deterministic engines decide; AI augments.
2. **Privacy by default.** No telemetry. No cloud unless the user explicitly enabled
   it. No "anonymous" identifiers either — that's still telemetry.
3. **Deterministic fixes only.** If a fixer can't guarantee PSI validity, it returns
   `null` and the AI fallback handles it. No "best effort" fixes.
4. **Provenance always visible.** `STATIC` / `AI_LOCAL` / `AI_CLOUD` /
   `RUNTIME_CONFIRMED` stays labeled on every finding the user sees.

Any feature that breaks one of these needs an explicit, user-visible opt-in. If your
proposed design seems to require breaking one of them, it's almost certainly the
wrong design — propose a different shape.

A fifth principle is operational, not a roadmap quote, but equally load-bearing:

5. **Conservative-miss bias.** When an analyzer can't decide (unresolved symbol,
   `KaErrorType`, ambiguous type), it must *not* flag. False positives cost more
   trust than false negatives. See `CLAUDE.md` § Analyzer bias for the V1.0/V1.1
   incident this rule came from.

---

## 2. Repository structure (where things live)

```
build.gradle.kts                       # version source-of-truth, Gradle config
src/main/kotlin/com/ghostdebugger/
    GhostDebuggerService.kt            # facade — single writer of project state
    AnalysisOrchestrator.kt            # analysis lifecycle + cascade + test seams
    UIEventRouter.kt                   # UIEvent dispatch + AI service caching
    FileChangeWatcher.kt               # VFS auto-refresh
    DebugSessionCoordinator.kt         # XDebugger plumbing + cross-check
    intentions/                        # IntentionAction quick-fix entries
    inspections/                       # LocalInspectionTool entries (V2 beta.1+)
    parser/                            # PSI-backed symbol extraction
    analysis/analyzers/                # one class per rule ID
    fix/                               # deterministic fixers + applicator
    graph/                             # in-memory graph + cycle detection
    ai/                                # BaseAIService + Ollama / OpenAI
    bridge/                            # JCEF bridge to webview
    annotator/                         # ExternalAnnotator for editor markup
    toolwindow/                        # tool-window factory + JCEF panel
    actions/                           # menu/keymap actions
    settings/                          # PasswordSafe + Configurable
    model/                             # AnalysisModels — Issue, IssueSource, etc.
src/test/kotlin/com/ghostdebugger/     # mirror layout of src/main
src/main/resources/META-INF/plugin.xml # registers services, intentions, actions
webview/                               # React + JCEF detail panel and NeuroMap
docs/
    aegis_debug_roadmap_v2_to_v5.md    # the north star
    aegis_v1_history.md                # V1 phase summary
    superpowers/specs/                 # YYYY-MM-DD-<topic>-design.md
    superpowers/plans/                 # YYYY-MM-DD-<topic>.md
site/                                  # landing-page source (gh-pages)
CLAUDE.md                              # Claude-specific conventions
AGENTS.md                              # this file
```

Two seams that are easy to misuse:

- **`GhostDebuggerService` is a facade, not a god class anymore.** It owns the
  project-state fields (`currentIssues`, `issuesByFile`, `currentGraph`,
  `lastInMemoryGraph`, `suppressUntil`) and delegates behavior to the four
  collaborators. New collaborators read state via `service.X` and mutate via
  `service.updateIssues(...)`. Never re-introduce direct writes from a collaborator
  — that's the V1.5 mistake we explicitly fixed.
- **The Kotlin Analysis API has exactly one entry point:**
  `parser/KotlinAnalysisHelpers.withKtAnalysis(...)`. Calling `analyze { }` directly
  bypasses the centralized PCE rethrow and exception handling. Don't.

---

## 3. Versioning and branch discipline

### 3.1 Versions

The version source-of-truth is `build.gradle.kts`. `plugin.xml`'s inline
`<version>` is cosmetic and must be kept in sync manually when bumping. (V1.3 drifted;
commit `7e31776` fixed it.)

Version cadence:

- **Patch** (`1.4.1`, `1.4.2`) — bug fixes, no new analyzer/fixer, no UI changes.
- **Minor** (`1.4.0`, `1.5.0`, `2.1.0`) — new analyzer, new fixer, new language tier,
  or refactor-class release.
- **Major** (`2.0.0`) — roadmap milestone (V2 = dynamic validation + language breadth;
  V3 = fixer breadth + custom rules; etc.).
- **Pre-release** (`2.0.0-alpha.1`, `2.0.0-beta.1`) — incremental shipments of a major
  release where each slice is internally complete but the major theme isn't.

When in doubt, prefer pre-release suffixes over rushing a `2.0.0` GA. The marketplace
reads pre-release versions fine; users can install them.

### 3.2 Branches

One branch per release-line item. Naming: `v<version>-<short-kebab-topic>`. Examples
from history:

- `v1.4.1-audit-fixes`
- `v1.5-pre-v2-refactor`
- `v2.0.0-alpha.1-dynamic-validation`
- `v2.0.0-alpha.2-runtime-trust`

The branch always ships through a PR to `main`. Direct commits to `main` are reserved
for landing-page tweaks and trivial doc fixes; anything that touches `src/` goes
through a branch + PR + green CI.

### 3.3 Tags

After merge, tag the release as `v.<version>`. Yes, with the leading dot — that's the
existing tag style (`v.1.5.0`). Don't change it; tooling and links may depend on the
exact format.

---

## 4. Documenting changes (specs and plans)

Every non-trivial change line — a new analyzer, a refactor, a language addition, a
roadmap milestone — gets a spec *and* a plan before code is written.

### 4.1 Spec — `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`

**Required sections, in order:**

1. **Header block** — `Date:`, `Status:` (`Draft — pending review` → `Approved` →
   `Shipped in <version>`), `Version target:`, `Base branch:`,
   `Target working branch:`.
2. **§0 Summary** — one or two paragraphs that say what's shipping and how it's
   sliced into commits. A reader who reads only the summary should know whether the
   spec is relevant to them.
3. **§1 Motivation** — *why now?* Tie back to the roadmap, a user incident, a prior
   audit finding, or a deferred TODO. Never "because it would be nice."
4. **§2 Scope decisions (locked in)** — a table of Q1..QN decisions with rationales.
   This is the most important section. Future readers will use it to understand why
   the implementation looks the way it does. Each row should be a decision you could
   plausibly have made differently, with a short reason for the path taken.
5. **§3 Architecture** — what files, what classes, what extension points. Include
   ASCII diagrams when the dependency shape isn't obvious from prose.
6. **§4 Risks and mitigations** — a table of (Risk, Likelihood, Mitigation). Be
   honest about the medium/high-likelihood items; the mitigation column is what
   makes the spec actionable.
7. **§5 Non-goals** — explicitly out of scope, with a reason. This protects scope
   from creeping during implementation.
8. **§6 Open questions** — things you punted on. Include a default behavior so
   implementation can proceed without waiting.

**Tone:**

- **Explain *why*, not just *what*.** A spec that says "we will add a
  `RuntimeConfirmation` field" is useless. A spec that says "we will add a
  `RuntimeConfirmation` field because users currently can't tell static findings
  from dynamically-verified ones, and the V2 thesis hinges on that distinction" is
  load-bearing.
- **Reference prior versions, prior incidents, prior audits.** The codebase has six
  years of decisions; pretend they don't exist and you'll rebuild bugs we already
  fixed.
- **Use full sentences in prose sections, terse cells in tables.** Don't write
  bullet lists where prose would explain the connections better.
- **Lock decisions before writing code.** Q1..QN exists so implementation doesn't
  re-litigate them. If a decision needs to change mid-implementation, update the
  spec — don't leave it stale.

### 4.2 Plan — `docs/superpowers/plans/YYYY-MM-DD-<topic>.md`

**Required sections, in order:**

1. **Header** — Goal, Architecture (one paragraph), Tech Stack, Spec reference,
   "Spec adjustments recorded here" note.
2. **File structure** — Files created / modified / deleted, plus module boundaries
   (what stays public, what becomes internal).
3. **Numbered tasks**, each containing:
   - `Files:` what gets touched.
   - `Rationale:` why this task exists (one or two sentences).
   - `Step N:` checkbox sub-steps with concrete commands or code snippets.
4. **Out of scope** — anything that creeps in during implementation gets held out
   and moved to a follow-up plan.

**Tone:**

- **Plans are executable, not aspirational.** Every checkbox should be something an
  agent (or human) can do in 5–30 minutes. If a step is bigger, split it.
- **Include the exact `./gradlew` commands.** Save the next agent from re-deriving
  the JBR `JAVA_HOME` setup, the right `-x` exclusions for the webview tasks, etc.
- **End each substantive task with a "compile + test" step.** Green bar before
  moving on; never accumulate broken state across tasks.
- **Use the `### Plan note:` heading inside a task to record spec divergence.**
  Don't silently change scope — document the deviation so the spec can be updated
  on the next pass.

### 4.3 Changelog (`plugin.xml` `<change-notes>`)

Every release gets an entry in `plugin.xml`'s `<change-notes>` block. Format:

```html
<h3>X.Y.Z[-pre.N] — <one-line theme></h3>
<ul>
    <li><strong>Headline feature.</strong> One or two sentences explaining
        user-visible impact.</li>
    <li>Subsequent bullets in order of user-visible impact, not implementation
        order.</li>
    <li><em>Internal:</em> non-user-facing changes prefixed with the "Internal:"
        em-tag so users can skip them.</li>
</ul>
```

Newest entry at the top. Never delete prior entries — the marketplace shows the
whole history.

---

## 5. Code conventions

These are the rules that recur in code review. Internalize them and the review cycle
shortens.

### 5.1 Error handling

Every `catch (e: Exception)` block in production code must rethrow
`ProcessCanceledException` immediately:

```kotlin
} catch (e: Exception) {
    if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
    log.warn("...")
}
```

Reason: PCE is how the IntelliJ platform delivers cancellation. Swallowing it leaves
operations running after the user clicked Cancel, holds locks the IDE thinks are
released, and corrupts progress-bar state. Non-negotiable.

### 5.2 Facade state ownership

`GhostDebuggerService` is the only writer of `currentIssues` / `issuesByFile`.
Collaborators read via `service.X` and mutate via `service.updateIssues(...)`. The
exceptions, both `internal var` fields you may assign to from a collaborator, are:

- `service.currentGraph`
- `service.lastInMemoryGraph`

Anything else: route through the facade. If you find yourself wanting a new state
field on a collaborator, add it to the facade instead — the V2 collaborators that
will land later (test-runner cross-check, Problems-view emit) read the same state,
and divergent per-collaborator copies cause UI inconsistencies.

### 5.3 New collaborators

Register as `@Service(Service.Level.PROJECT)` in `plugin.xml`. In `init { }`, call
`Disposer.register(project, this)`. Access state only via
`GhostDebuggerService.getInstance(project)`. To send UI events, use
`service.jcefBridge()?.send*()` for JCEF-only methods or
`service.bridgeChannel()?.send*()` for `BridgeChannel` methods (the latter respects
the test-recording stub installed via `setBridgeForTest`).

### 5.4 Analyzers

- One file per rule ID. Filename matches the rule ID's human name (e.g.,
  `NullSafetyAnalyzer.kt` for `AEG-NULL-001`).
- Inherit from the `Analyzer` interface; don't invent parallel hierarchies.
- Conservative-miss bias: if the type system can't decide, don't flag. Document the
  decision in KDoc.
- Kotlin Analysis API calls go through `parser/KotlinAnalysisHelpers.withKtAnalysis`,
  no exceptions.
- Smart-cast aware: `expressionType` ignores smart casts; use `effectiveType` or
  `effectiveTypeWithStructuralSmartCast` when narrowing matters.

### 5.5 Fixers

- One file per fixer. Inherit from the `Fixer` interface.
- `derive(...)` must return `null` if the fix can't be guaranteed PSI-valid. The
  orchestrator falls back to the AI path automatically.
- Fixers run inside a `WriteCommandAction` via `FixApplicator`; do not start your
  own write action.
- PSI-driven, not regex-on-source. The V1.4.1 audit found a fixer that rewrote
  variable names inside string literals because it used a regex — don't repeat
  that.

### 5.6 Tests

- Mirror `src/main` layout under `src/test`.
- Inherit from `BasePlatformTestCase` for IDE-integration tests; from
  `AegisKotlinAnalysisTestCase` (in `src/test/kotlin/com/ghostdebugger/`) for tests
  that exercise an Analysis API analyzer. The Kotlin base class runs off-EDT and
  loads `kotlin-stdlib.jar` from the unpacked IDE.
- Tests must be deterministic. No `Thread.sleep`; use the platform's `runInEdtAndWait`
  / `runWriteCommandAction` helpers.
- New analyzer → at least one positive case, one negative (no-flag) case, one
  ambiguous-type case (should not flag, conservative-miss). Same for fixers.
- Don't mock the database... there is no database. The analogous rule here: don't
  mock the IntelliJ platform. Use the in-process test fixtures.

### 5.7 Webview

- React + TypeScript under `webview/`. JCEF transport via `bridge/`.
- New events: add the type to `webview/src/types/index.ts`, the handler in the
  appropriate panel component, and the Kotlin sender method in `bridge/JcefBridge.kt`
  or `bridge/BridgeChannel.kt`.
- Inline styles for one-offs; CSS variables for design tokens (see
  `webview/src/index.css`).
- Accessibility: ARIA roles on tabs/panels, focus styles, keyboard navigation. The
  landing-page accessibility pass set the bar — match it.

---

## 6. Commit and PR conventions

### 6.1 Commit messages

Conventional-commits style with a scope:

```
<type>(<scope>): <short summary in imperative mood>

<wrapped body explaining what changed and *why*. Reference prior
versions, audit findings, or roadmap items when relevant. Multi-paragraph
is fine for non-trivial changes.>

Co-Authored-By: <agent identity if applicable>
```

Common types: `feat`, `fix`, `refactor`, `chore`, `docs`, `ci`, `test`, `style`.
Common scopes: `v2.0.0-alpha.1`, `analyzer`, `fixer`, `site`, `webview`,
`bridge`, `model`, `tests`.

The summary line stays under 72 characters. The body wraps at 80.

### 6.2 Squash vs merge

Don't squash refactor-class branches that ship as a series of small dependency-ordered
commits (the V1.5 pattern). Each commit's diff should stand alone and pass tests.

Do squash branches that ended up with churn commits ("fix typo", "address review")
— the PR title becomes the squashed commit message.

### 6.3 Pull requests

Title: `<type>(<version>): <short summary>` — same shape as the headline commit.

Body template:

```markdown
## Summary
- 1–3 bullets, user-visible impact first.

## Out of scope
- What this PR does *not* do, mapped to a follow-up plan or issue.

## Known follow-ups
- Anything small you noted but didn't fix; what triggers fixing it later.

## Test plan
- [ ] Automated: which Gradle tasks pass.
- [ ] Manual: which IDE scenarios you verified.
```

Always reference the spec and plan files in the PR body — reviewers shouldn't have
to dig for them.

### 6.4 Verifying before claiming "ready"

Before opening a PR or saying "done":

1. `./gradlew compileKotlin` — exit 0.
2. `./gradlew test --tests <new test classes>` — green.
3. `./gradlew verifyPlugin` if your change touches plugin metadata, extension
   points, or IDE APIs — three Compatible verdicts (IU 2024.3.2.2 / 2025.1 / 2026.1).
4. Manual smoke test in the IDE if the change is UI-visible. Type checking is not
   feature checking.

If any of these fail, fix the failure or revise the scope. Never report "done" with
known failures.

---

## 7. Things not to do

A non-exhaustive list of mistakes prior agents have made:

- **Don't** create new top-level docs (`README.md`, `CHANGELOG.md`, `NOTES.md`) without
  user request. Existing docs (`CLAUDE.md`, `AGENTS.md`, `docs/`) cover all the
  documentation slots the project needs.
- **Don't** add `// removed for V2` comments or rename unused params to `_param` to
  preserve "intent." Delete the code; git history preserves intent.
- **Don't** bump the platform `sinceBuild` to gain access to an API without a roadmap
  reason. Users on older IDEs lose the plugin silently.
- **Don't** add HTTP client config, JSON serializers, or coroutine scopes ad-hoc. Use
  the shared `BaseAIService` infrastructure or follow the existing pattern.
- **Don't** introduce new permissions, telemetry, or cloud calls — even feature-flagged.
  See § 1 principle 2.
- **Don't** swallow `ProcessCanceledException`. See § 5.1.
- **Don't** edit `src/main/resources/web/` directly — it's the built webview output.
  Edit `webview/src/` and let `./gradlew buildWebview` regenerate.
- **Don't** commit `.antigravitycli/`, `.claude/`, `.superpowers/`, `.gemini/`, or any
  local tool state — `.gitignore` excludes them and the V1.5 cleanup explicitly
  un-tracked the ones that had leaked in.
- **Don't** introduce backwards-compat shims for code you wrote yesterday. The
  facade's public API (the six methods listed in `GhostDebuggerService.kt`) needs
  compatibility; everything else is `internal` and you can change it.
- **Don't** mark anything `@ApiStatus.Experimental` or expose a plugin extension point
  unless the spec explicitly designs it as third-party-facing. Public API is a
  long-term commitment.

---

## 8. When this guide is wrong

If a spec, a CLAUDE.md section, or this AGENTS.md conflicts with what the code
actually does, the code wins as the description of *current* state, but trust the
docs as the description of *intent*. Reconcile by either:

- Updating the doc if the code's behavior is correct and the doc is stale.
- Filing a follow-up if the code's behavior is wrong and the doc is correct.

Don't silently propagate the discrepancy.

If a user instruction conflicts with a guideline here, the user wins. Note the
divergence and ask whether the guideline should be updated to match.

---

## 9. Glossary

- **JCEF** — JetBrains Chromium Embedded Framework. The browser surface the webview
  runs in.
- **JBR** — JetBrains Runtime. The customized JDK the IDE bundles; required for
  `instrumentTestCode` to work.
- **PCE** — `ProcessCanceledException`. The platform's cancellation signal. See § 5.1.
- **PSI** — Program Structure Interface. IntelliJ's syntax tree.
- **Analysis API** — Kotlin K2's typed-PSI API. Replaced the deprecated descriptor /
  resolve infrastructure in V1.3.
- **Wolf** — short for `WolfTheProblemSolver`, the project-level problem store the
  Problems tool window subscribes to.
- **Facade** — `GhostDebuggerService`, post-V1.5.
- **Collaborator** — one of the four project-scoped services the facade delegates to
  (`AnalysisOrchestrator`, `UIEventRouter`, `FileChangeWatcher`,
  `DebugSessionCoordinator`).
- **Provenance tier** — the `IssueSource` enum value on an `Issue`: `STATIC`,
  `AI_LOCAL`, `AI_CLOUD`, or `RUNTIME_CONFIRMED` (V2+).
