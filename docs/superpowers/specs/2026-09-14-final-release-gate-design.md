# Final Release: Capability Gate, Honest Docs, Reproducible Demo — Design

**Date:** 2026-09-14
**Branch:** `feat/final-release-gate` (off `fix/v3-analysis-hang`)
**Status:** approved, pre-implementation
**Target version:** 3.0.0 (never tagged; becomes the honest final release)

## 1. Problem

Aegis Debug reads, from its own documentation, as a finished product: eleven analyzers, five
PSI-validated fixers, an AI-supervised fix engine, custom rule authoring, rule packs, an external
analyzer SDK, dynamic runtime validation, and native IDE integration. The build is green — 508
tests, `detekt` clean, `verifyPlugin` Compatible against four IDE builds.

A sixteen-surface read-only audit of the code (2026-09-14, 16 parallel agents, every verdict
required to cite `file:line`) found that the documentation describes an intent, not the artifact.
Three surfaces are genuinely WORKS; thirteen are PARTIAL. Roughly 170 individual defects were
recorded. The dominant pattern is not shoddy work — it is **real infrastructure whose last mile to
the user was never closed**, and a test suite that covers the pieces rather than the circuits.

Representative instances, each independently re-verified by the lead before being accepted:

- `Issue.suggestedFix` is declared `= null` at `model/AnalysisModels.kt:24` and read twice in
  `actions/ApplyAllFixesAction.kt:18,25`. **Nothing in the repository ever assigns it.** The
  "Apply All Deterministic Fixes" menu item is an unconditional no-op.
- `parser/DependencyResolver.kt:57` creates an internal graph edge only for specifiers starting
  `./` or `../`. Kotlin and Java imports are fully-qualified, so **every JVM project graph has zero
  internal edges** — cycle detection, impact analysis and the dependent-cascade are structurally
  dead there. Measured on this repository: 130 Kotlin files produce 0 internal edges and 966 edges
  into 339 synthetic `ext:` nodes (70% of the rendered graph).
- `settings.allowCloudUpload` is read in exactly one production site,
  `analysis/AnalysisEngine.kt:220`. Both `resolveAiService()` methods (`UIEventRouter`,
  `AnalysisOrchestrator`) construct the OpenAI service without consulting it, so the explain and
  fix paths **upload code to OpenAI without the consent gate the README advertises**.
- Dead code confirmed by exhaustive grep: `PromptTemplates.whatIf` (zero callers, not even a
  test), `PromptTemplates.jointFix` (test-only), `AiJsonExtractor.telemetrySnapshot()`,
  `ApiKeyManager.hasApiKey()`, `ParsedFile.exports` (populated by all three extractors, read only
  by tests).
- `main` still selects the plugin JAR by filename with `Files.list(...).findFirst()`, and `lib/`
  contains both `ghostdebugger-3.0.0.jar` and `ghostdebugger-3.0.0-searchableOptions.jar`.
  `Files.list` ordering is unspecified, so **a release cut from `main` is a coin flip** between a
  working webview and "Failed to load NeuroMap". Only `fix/v3-analysis-hang` carries the fix
  (commit `1df90a5`); `git diff --stat main...HEAD` shows `NeuroMapPanel.kt` as the sole code delta.

The goal of this work is a release that a reviewer can pick up, run, and trust: everything it
claims is true, everything reachable either works or says plainly that it is not finished, and the
gap between the two is documented with evidence rather than hidden.

## 2. Decisions taken

Three scoping decisions were made by the owner and are settled inputs to this design:

**D1 — The entire fix path is gated.** Not only the AI-dependent parts. Aegis Debug 3.0 ships as a
read-only analysis and visualisation tool. Fix *application* is implemented, tested and documented,
but disabled.

This is also defensible on the evidence: `NullSafetyFixer` — the flagship JS/TS fixer — never fires
on real analyzer output; of 8 registered fixers only 2 are proven end-to-end through the real
registry; `FixPreviewDialog`, `BatchFixPreview` and `FixDiffGenerator` are dead code advertised in
the changelog as a "side-by-side diff preview"; and `ApplyAllFixesAction`, were it ever revived,
computes every plan against `document.text` read once *before* its apply loop, so the second fix in
a file lands on offsets the first already shifted.

**D2 — One native IDE dialog, everywhere.** A single Kotlin object owns the message. The webview
routes through the bridge to the same dialog rather than implementing a second modal, so the text
cannot drift and there is one place to lift the gate.

**D3 — The non-third-party breakage is gated with the same dialog, and the cheap wins are actually
fixed.** Deep breakage (JVM dependency graph, rule packs, external SDK, Problems-view emit,
debugger cross-check) is gated. Roughly six one-line defects that strand features which otherwise
work get repaired.

## 3. Architecture: the capability gate

### 3.1 Single source of truth

```kotlin
// src/main/kotlin/com/ghostdebugger/AegisCapability.kt
enum class AegisCapability(val label: String, val why: String) {
    FIX_APPLICATION(
        label = "Apply Fix",
        why   = "The fix engine is implemented and tested, but fix application is not enabled in 3.0.0.",
    ),
    AI_EXPLANATION(...),        // same shape, one label + one reason each
    AI_ANALYSIS(...),
    JVM_DEPENDENCY_GRAPH(...),
    RULE_PACKS(...),
    EXTERNAL_ANALYZERS(...),
    PROBLEMS_VIEW_EMIT(...),
    DEBUGGER_CROSS_CHECK(...),
}

object AegisCapabilityGate {
    /** The only place that decides. Empty in 3.0.0 — nothing is enabled. */
    private val enabled: Set<AegisCapability> = emptySet()

    fun isEnabled(c: AegisCapability): Boolean = c in enabled

    /** User-initiated surfaces: shows the dialog on the EDT, returns true when the caller must abort. */
    fun blockIfGated(project: Project?, c: AegisCapability): Boolean

    /** Background surfaces: silent, logs once, returns true when the caller must skip. */
    fun skipIfGated(c: AegisCapability): Boolean
}
```

Two variants exist because the context demands it: a modal dialog raised from inside a background
analysis pass would be a defect, not a feature. What the user *clicks* gets a dialog; what runs on
its own is skipped silently and logged.

Each enum constant carries its own `why` — the one-sentence reason it is gated — so the dialog text,
the implementation-status matrix and the changelog all read from the same string.

### 3.2 Guard sites — seven, not thirty

The codebase already funnels these paths through chokepoints; the gate exploits them rather than
scattering conditionals across 8 fixers, 13 actions and the webview.

| # | Site | Covers |
|---|---|---|
| 1 | `UIEventRouter.handle()` | The entire webview in one place: `FixRequested`, `ApplyFixRequested`, `ExplainSystemRequested`, the AI branch of `NodeClicked`, and `ImpactRequested` (the last under `JVM_DEPENDENCY_GRAPH`, see 3.3) |
| 2 | `AnalysisOrchestrator.applyVerifiedFix()` | Alt+Enter intention **and** the inspection quick-fix, in one place (`intentions/AegisQuickFixIntentionAction.kt:51`, `inspections/AegisLocalInspection.kt:31`) |
| 3 | `ApplyAllFixesAction.actionPerformed` | Tools ▸ Apply All Deterministic Fixes |
| 4 | `ExplainSystemAction.actionPerformed` | Tools ▸ Explain System |
| 5 | `AnalysisEngine` (three `skipIfGated` calls) | The AI pass, the external-analyzer loop (`AnalysisEngine.kt:112`), and rule-pack loading |
| 6 | `ProblemsViewCoordinator.publishToProblemsView` | The `wolf.reportProblems(...)`-from-EDT defect |
| 7 | `DebugObserver` + the null-safety probe in `DebugSessionCoordinator` | The debugger cross-check, unreachable on IDEA Community |

### 3.3 JVM dependency graph: gate the claim, not the analyzer

`CircularDependencyAnalyzer` is correct — a stack-safe, cached, iterative DFS — and it **works on
TS/JS**, where relative imports do produce internal edges. Gating the analyzer would remove a
working feature.

Instead: `ImpactRequested` is gated under `JVM_DEPENDENCY_GRAPH`, and the webview renders a one-line
notice when the built graph has zero internal edges. The feature stays alive where it works and
states the limitation where it does not.

### 3.4 Accepted trade-off

An Alt+Enter intention that appears and then reports "not implemented" is worse UX than one that
never appears. D2 was chosen deliberately so that a reviewer *sees* the feature is planned rather
than wondering whether it exists. Suppressing the offer at the intention surface only (leaving the
dialog on the menu and webview paths) remains a one-line change if that trade is revisited.

## 4. Real fixes

| Fix | Site | Effect |
|---|---|---|
| Tool window id `"Aegis Debug"` → `"GhostDebugger"` | `ShowInNeuroMapAction` | Dead action becomes functional; `?.show` no longer short-circuits |
| `analyzeProject()` → `reanalyzeFile(path)` | `ReanalyzeFileAction` | Does what its name promises; the correct method already exists and is used by two other callers |
| Deliver the message via notification balloon, not the null bridge | `ExportReportAction` | Report export — a genuinely working surface — stops being a silent no-op on first use |
| Consult `allowCloudUpload` | both `resolveAiService()` | Closes the consent bypass; makes the README privacy claim true |
| Explicit suppression bypasses the count threshold instead of recording one dismissal | `SuppressFindingAction` | An explicit "Suppress Finding Under Caret" command suppresses on the first invocation, not the third; the count threshold keeps governing *automatic* suppression |
| Remove five dead controls | `GhostDebuggerConfigurable` | `autoAnalyzeOnOpen`, `showInfoIssues`, `analyzeOnlyChangedFiles`, `coverageMode`, `showUnreached` have zero read sites; every remaining control has real effect |
| Explicit `stream = false` | `OllamaChatRequest` | The `encodeDefaults = false` omission is fixed rather than buried behind the AI gate |

## 5. Honest documentation

### 5.1 Measured ground truth

| Metric | Value | Source |
|---|---|---|
| Analyzers registered | 12 | `AnalysisEngine.kt:30-43` (11 built-in rule ids + the custom-rule dispatcher) |
| Built-in rule ids | 11 | `AEG-{SYNTAX,COMPILE,NULL,NULL-KT,STATE,ASYNC,CYCLE,CPX,CAST-KT,TYPE-KT,REDUNDANT-LET-KT}-001` |
| Fixers registered | 8 | `FixerRegistry.kt` |
| Local inspections | 11 | `plugin.xml` |
| Plugin actions | 13 | `plugin.xml` |
| Tests | 508 passing, 0 failures | `build/test-results/test/*.xml` |
| Production Kotlin | 130 files / 12,351 LOC | `find src/main -name '*.kt'` |
| Test Kotlin | 153 files / 9,630 LOC | `find src/test -name '*.kt'` |
| Plugin verification | Compatible against IU-243, IU-251, IU-261, IU-262 | `./gradlew verifyPlugin` |

### 5.2 Claims to correct

- "Eleven deterministic analyzers" and "five deterministic fixers" — in `README.md`,
  `plugin.xml` `<description>`, and `site/index.html` (including its `og:` and `twitter:` meta).
- README self-contradiction: "eleven deterministic analyzers" against "Five core analyzers".
- "Full static analysis" for TS/JS, when `parser/SymbolExtractor.kt:6-14` states in its own comment
  that TS/JS is regex-only because Community bundles no JS/TS PSI.
- Keyboard shortcuts `Ctrl+Alt+A` and `F2` / `Shift+F2` that are not registered.
- "Side-by-side diff preview", "keyboard navigation (Enter/Alt+A to Apply, Esc to Skip)",
  "batch-apply across multiple files", "interactive Swing preview dialog" — all describing dead code.
- `CHANGELOG.md` has no 3.0.0 entry; its newest heading is "[Unreleased] — 2.0.0 … Not tagged, not
  released". Eight dangling spec/plan references.
- `DATA_HANDLING.md` stamped "Product version: 1.0.0".
- `AGENTS.md` documents a repository layout that no longer exists.
- `docs/audit-2026-06-post-v2.md` is an all-PASS self-certification this audit contradicts.

### 5.3 New: implementation-status matrix

`docs/IMPLEMENTATION_STATUS.md`, linked from the README, listing every capability with a verdict
— **works** / **gated** / **documented, not implemented** — the reason, and the `file:line`
evidence. This is the document that distinguishes a project with known gaps from an unfinished one.

### 5.4 New: a test that pins the docs to the code

The audit's closing finding was "no test pins documentation counts to code". A test asserts the
registered analyzer, fixer, inspection and action counts against the numbers stated in `README.md`
and `plugin.xml`, so the two cannot drift again.

## 6. Reproducible demo

`samples/aegis-demo/` in **TypeScript/React**. The language is chosen on evidence, not taste:
relative imports are the only specifiers that produce internal graph edges, so the NeuroMap renders
as an actual graph — a JVM sample would render edgeless (§1).

Findings exercised with zero external dependencies, matched to the analyzers' real trigger patterns:

| File | Triggers |
|---|---|
| `useState()` with no argument, then `items.map(...)` | `AEG-STATE-001` (`StateInitAnalyzer.kt:73`) |
| `let user = null`, then `user.name` | `AEG-NULL-001` (`NullSafetyAnalyzer.kt:135`) |
| `.then(...)` with no `.catch` within the 12-line window | `AEG-ASYNC-001` unhandled promise |
| `setInterval` inside `useEffect` with no cleanup | `AEG-ASYNC-001` memory leak |
| A module above the complexity threshold (default 10) | `AEG-CPX-001` |
| `a.ts → b.ts → a.ts` via relative imports | `AEG-CYCLE-001` |
| A `.kt` file with an unsafe `as` cast and a redundant `?.let` | `AEG-CAST-KT-001`, `AEG-REDUNDANT-LET-KT-001` |

`DEMO.md` gives exact steps, the findings to expect, and — explicitly — **what will not happen**
(no AI, no fixes), so a reviewer does not read a gated feature as a malfunction.

## 7. Cleanup

Dead code, each verified by exhaustive grep before listing: `PromptTemplates.whatIf`,
`PromptTemplates.jointFix` + `PromptExamples.JOINT_FIX_EXAMPLES`,
`AiJsonExtractor.telemetrySnapshot()`, `ApiKeyManager.hasApiKey()`, `ParsedFile.exports`,
`toolwindow/ConfidencePill.kt` (unreferenced), `fix/AegisQuickFixIntentionAction.kt` (dead duplicate
of the live one in `intentions/`), and resolution steps 1 and 4 in `NeuroMapPanel` (they require an
`assets/` directory the single-file vite build never produces).

`docs/audit-2026-06-post-v2.md` is replaced by the real audit record.

## 8. Integration and verification

`main` carries the non-deterministic webview defect (§1). This branch must reach `main` for the
release to be trustworthy. Per the project's working rhythm: branch first, merge to `main` locally,
build the artifact, do not push.

Release gate — all must pass before the merge:

```bash
export JAVA_HOME=$(find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1)
export PATH=$JAVA_HOME/bin:$PATH
./gradlew test          # 508 existing + new gate tests, 0 failures
./gradlew detekt
./gradlew verifyPlugin  # Compatible against IU-243/251/261/262
./gradlew buildPlugin   # build/distributions/ghostdebugger-3.0.0.zip
```

New tests required:

1. Every `AegisCapability` is disabled in 3.0.0 (asserts the shipped gate state).
2. Each of the seven guard sites aborts or skips when its capability is gated.
3. `AegisCapabilityGate.blockIfGated` is never called from a background pass (the modal-in-analysis
   defect the two-variant design exists to prevent).
4. The documentation-count test of §5.4.

## 9. Non-goals

Explicitly out of scope, recorded so the omissions are decisions rather than oversights:

- Repairing the gated features. `DependencyResolver` FQN resolution, the compile-gate shadowing of
  `AEG-NULL-KT-001` / `AEG-TYPE-KT-001`, the three rule packs that cannot match, the absent external
  SDK, the Problems-view threading contract, and `RuntimeEvidenceStore` persistence all stay
  unfixed and gated. Each is a project in its own right.
- Analyzer precision work. The false positives the audit found in `NullSafetyAnalyzer`'s five-line
  guard lookback and in the Kotlin scope-function and generic-type cases are documented, not fixed.
- Importing Tailwind in the webview. It is configured but never imported, so its layout classes are
  inert; adding the import would restyle the whole UI and is not a release-eve change.
- Publishing to the JetBrains Marketplace. `release.yml` deliberately omits `publishPlugin`.
