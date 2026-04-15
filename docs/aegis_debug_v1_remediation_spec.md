# V1 Remediation Spec: Aegis Debug — Close Remaining Drift Before GA

**Version:** 1.0 (binding)
**Status:** Ready for immediate implementation
**Source of Truth:** `aegis_debug_true_v1_spec.md`
**Target Phase from Source Spec:** Post-Phase 6 clean-up — all prior phases remain the primary references
**Prior Phase:** `aegis_debug_phase_6_hardening_spec.md` — already merged

---

## 1. Objective

Phases 1–6 of Aegis Debug have landed. A fresh full-repo sweep on 2026-04-15 shows the plugin is functionally complete against the V1 spec (five analyzers, three deterministic fixers with PSI-validity rollback, cancellable background analysis, provenance-labelled findings, Ollama+OpenAI backends with static fallback, enterprise Navy+Cream UI, engine status pill, NeuroMap graph, password-safe key storage).

The remaining problems are **not** missing features — they are **drift between artifacts that describe the product**, plus a handful of dead-code/cleanup items in files that were edited during earlier phases. These issues will not cause a runtime failure, but they WILL cause:

- Marketplace reviewers seeing three different statements of "what languages are supported" depending on which file they read.
- Users clicking "What's New" and reading `2026-04-XX` as the release date.
- Future maintainers believing fixers are PSI-based (they are regex with a PSI post-validity guard).
- The `NullSafetyAnalyzer` quietly duplicating `STATE_BEFORE_INIT` issues that the dedicated `StateInitAnalyzer` already emits, causing the fingerprint-merge deduplication path to carry avoidable load.

This spec closes that gap. When it merges, Aegis Debug v1.0.0 is consistent across `plugin.xml`, `build.gradle.kts`, `README.md`, `CHANGELOG.md`, and in-code strings, with no dead patterns or dead branches remaining in the five analyzers or the settings configurable.

Scope is **doc-sync + surgical code cleanup only**. No new features. No language-scope changes. No new analyzers, fixers, or bridge events. No schema changes.

---

## 2. Scope

### 2.1 In Scope

| Area | Work |
|---|---|
| `plugin.xml` description | Rewrite to match `build.gradle.kts` pluginConfiguration description verbatim (TS/JS primary, Kotlin/Java graph-only; "deterministic" fixers, not "PSI-based") |
| `plugin.xml` change-notes | Replace `2026-04-XX` placeholder with the release date `2026-04-15` |
| `CHANGELOG.md` line 9 | "Three PSI-based deterministic fixers" → "Three deterministic fixers (regex construction, PSI-validated on apply)" |
| `CHANGELOG.md` line 26 | "Primary language support: Kotlin. Secondary: Java." → "Primary: TypeScript and JavaScript (full analysis + fixers). Secondary: Kotlin and Java (graph and circular-dependency analysis only)." |
| `NullSafetyAnalyzer.kt` | Remove the two unused `val` patterns (`nullStatePatterns`, `dangerousAccessPatterns`) at lines 14 and 21; fix the `"Null reference: $varName.${"may be null"}"` title concatenation artifact on line 77; delete the `STATE_BEFORE_INIT` emission branch (lines 90–115) so the analyzer has a single responsibility |
| `StateInitAnalyzer.kt` | Fix the `"on line X. ${"array method"}"` interpolation artifact on lines 52–53 — replace the hardcoded `"array method"` string with the captured regex group for the actual method name |
| `GhostDebuggerConfigurable.kt` | Delete the dead `formPanel.add` / `formPanel.removeAll()` pattern at lines 153–156; keep only the correct-order block from line 157 onward |
| `build.gradle.kts` | Remove the unused `testImplementation("junit:junit:4.13.2")` on line 42 (JUnit 4) — the suite runs on JUnit Jupiter (`useJUnitPlatform()`), and `kotlin-test-junit5` + `junit-jupiter` already cover the test surface |
| Tool window ID comment | Add a one-line KDoc comment above the `<toolWindow id="GhostDebugger">` in `plugin.xml` recording that the ID is kept stable at `GhostDebugger` for backward compatibility with users' pinned IDE layouts (display name is `Aegis Debug` via the tool window factory). No behavioural change |
| Tests | One new test: `NullSafetyAnalyzerSingleResponsibilityTest.kt` — asserts the analyzer never emits `IssueType.STATE_BEFORE_INIT`. Existing tests must continue to pass without modification |

### 2.2 Out of Scope (strict)

- Renaming the `com.ghostdebugger` Kotlin package tree. The plugin ID `com.ghostdebugger` and every settings `@Storage("ghostdebugger.xml")` file are stable contracts; breaking them would orphan installed users' configuration. Internal package name is decoupled from marketing brand "Aegis Debug" and will remain so.
- Renaming the tool window ID `GhostDebugger`. Same reason as above — pinned tool-window state keys off the ID.
- Converting fixers from regex to PSI. Phase 3 shipped them as regex + `FixApplicator` PSI-validity rollback, and the combination is verified safe by `FixApplicatorValidityTest.kt` and `FixerContractTest.kt`. Rewriting to PSI is a post-V1 refactor tracked separately.
- Changing any analyzer's language guard (`if (file.extension !in setOf("ts","tsx","js","jsx"))`). Language scope is already documented and consistent with `FileScanner.supportedExtensions`.
- Adding a Kotlin/Java null-safety or state-init analyzer. Expanding analyzer language coverage is a V2 item.
- Touching the webview (`webview/src/**`). The webview is consistent with the current bridge contract.
- Touching `AnalysisEngine.kt`, `PartialReanalyzer.kt`, the three fixers, or `FixApplicator.kt`.
- Bumping `version = "1.0.0"` or `sinceBuild`/`untilBuild`.
- Adding new Gradle dependencies or changing the Kotlin/JVM toolchain.
- Publishing to Marketplace. This spec's "done" state is a clean local build + passing test suite. Marketplace submission is a separate step.

---

## 3. Non-Goals

The following MUST NOT be touched:

1. **No rename of `com.ghostdebugger` package.** Keep the full Kotlin source tree under `com.ghostdebugger.*`. Do not introduce an `com.aegisdebug` alias.
2. **No change to `<id>com.ghostdebugger</id>` in `plugin.xml`.** The plugin ID is a marketplace-stable identifier.
3. **No change to any `@Storage` filename** or `PersistentStateComponent` field.
4. **No change to the `Analyzer` or `Fixer` SPI signatures.** The analyzer cleanup in `NullSafetyAnalyzer` is a body edit, not an interface change.
5. **No new `IssueType` values** and **no removal of existing `IssueType` values**. `STATE_BEFORE_INIT` remains in the enum — only its emission source changes from two analyzers to one.
6. **No change to `ExternalAnnotator` registration** or to any action ID (`GhostDebugger.Analyze`, `GhostDebugger.ExplainSystem`, `GhostDebugger.ConfigureApiKey`). Existing keymap entries depend on these IDs.
7. **No rewording of the `ideaVersion { sinceBuild / untilBuild }` block** beyond what is strictly required.
8. **No change to the AI layer** (`AIService`, `AIServiceFactory`, `AICache`, `OpenAIService`, `OllamaService`).
9. **No change to webview bridge events** or event parsers.
10. **No change to the CHANGELOG's `### Added` / `### Security & Privacy` section order** — only the two specified lines change.
11. **No commit message or Git history rewrites.** All edits are forward-only.
12. **No change to `vendor { name, email, url }` or license.**

---

## 4. Implementation Decisions

Derived from the repository state as of 2026-04-15 and binding for this remediation:

| # | Decision | Rationale |
|---|---|---|
| D1 | `plugin.xml` description block is **overwritten** with the body of `build.gradle.kts` `pluginConfiguration.description` verbatim (lines 60–89 of `build.gradle.kts`). | The Gradle IntelliJ Platform plugin already injects `pluginConfiguration.description` into the packaged `plugin.xml` at build time, so the authored `plugin.xml` description is only read during IDE dev-run from source. Making them identical eliminates drift at the source level. |
| D2 | `plugin.xml` change-notes `2026-04-XX` → hardcoded `2026-04-15`. No templating. | The release date is known, fixed, and matches `CHANGELOG.md` heading. Keeping a placeholder in a shipped artifact is worse than a stale-but-real date. |
| D3 | `CHANGELOG.md` line 9 replaces "PSI-based" with "deterministic (regex construction, PSI-validated on apply)". | Truth in labeling. Fixers are regex-constructed; safety comes from `FixWriter.Default` running `PsiTreeUtil.findChildOfType(psiFile, PsiErrorElement)` after `document.replaceString` and rolling back on parse error. Marketing this as "PSI-based" misdescribes the architecture. |
| D4 | `CHANGELOG.md` line 26 is rewritten to match `README.md` language-support section. | Single source of truth. README is the authored surface users read on GitHub; CHANGELOG must not contradict it. |
| D5 | `NullSafetyAnalyzer.STATE_BEFORE_INIT` emission (the `stateMapRegex` + `mapAccessRegex` block, lines 90–115) is **deleted**. | `StateInitAnalyzer` already covers `useState()` + iteration-method access on the same file with a stricter regex and emits issues with the same `ruleId = AEG-STATE-001` equivalent type. Dual emission causes the engine's fingerprint merge (`AnalysisEngine` merge step) to carry near-duplicate issues until dedup, wasting CPU and producing confusing test output. One analyzer, one responsibility. |
| D6 | `NullSafetyAnalyzer` title artifact `"Null reference: $varName.${"may be null"}"` → `"Null reference: $varName may be null"`. | The `${"may be null"}` is a literal-string interpolation that compiles cleanly but reads as a typo (remnant from an earlier `.${fieldName}` placeholder). The dot before the interpolation was intentional-but-wrong; remove it. |
| D7 | `NullSafetyAnalyzer`'s unused `nullStatePatterns` and `dangerousAccessPatterns` `val`s are **deleted**. | They are `private val`s never referenced by any function in the class. IntelliJ flags them as unused. Keeping them suggests future use; there is no future use planned. |
| D8 | `StateInitAnalyzer` description's `"with .${"array method"} on line ${index + 1}"` is fixed to use the captured regex group `match.groupValues[1]`. | Same pattern as D6. The hardcoded `"array method"` string is a placeholder that shipped. The regex already has a capture group for the method name (`map|filter|...`); use it. |
| D9 | `GhostDebuggerConfigurable.createComponent` dead-code pattern (add to panel, `removeAll()`, re-add in correct order) is **collapsed** to a single, correctly-ordered add sequence. | The current code builds the panel twice and relies on `removeAll` to discard the first attempt. This is a refactor remnant. A reader sees it as potentially-meaningful logic and wastes time. Collapsing is zero-risk because the "second" order is the one the IDE actually renders. |
| D10 | JUnit 4 dependency (`testImplementation("junit:junit:4.13.2")`) is **removed** from `build.gradle.kts`. | `test { useJUnitPlatform() }` runs on Jupiter. `kotlin-test-junit5` and `junit-jupiter` cover Kotlin + Java test assertions. No production test class uses `org.junit.Test` (JUnit 4) — all use `org.junit.jupiter.api.Test` or `kotlin.test.Test`. Keeping JUnit 4 on the classpath is a supply-chain cost with no consumer. |
| D11 | Tool window ID remains `GhostDebugger`. A single-line XML comment is added above the `<toolWindow>` declaration explaining this is intentional for backward compatibility. | Renaming the tool window ID orphans users' pinned IDE layouts — a worse UX than the stale internal name. The display name shown in the IDE gutter is set by `GhostDebuggerToolWindowFactory` and is already "Aegis Debug". |
| D12 | New test class `NullSafetyAnalyzerSingleResponsibilityTest.kt` asserts `issues.none { it.type == IssueType.STATE_BEFORE_INIT }`. | Prevents regression of D5 — guards against a future contributor re-adding the `STATE_BEFORE_INIT` branch to `NullSafetyAnalyzer`. |
| D13 | No webview build re-run is required. | The webview bundle is unchanged. Gradle's `buildWebview` task is input-keyed on `webview/src/**` and `webview/package.json` — none of which we touch. |

---

## 5. Decisions Made From Ambiguity

Where the repository or prior specs left choices open, this section records the path taken and why:

1. **"PSI-based" vs "PSI-validated" wording.** Documentation previously used both. This spec standardizes on **"deterministic fixers, PSI-validated on apply"** — accurate to `FixWriter.Default`, and readable by non-IntelliJ users (who don't need to know what PSI is to understand the safety story). Alternatives considered: "regex fixers" (true but sounds unsafe), "PSI fixers" (false). Chosen wording splits the truth: fixers *produce* regex-based text edits, and `FixApplicator` *validates* the result via PSI before saving.

2. **Whether to delete `NullSafetyAnalyzer.STATE_BEFORE_INIT` emission or consolidate into `StateInitAnalyzer`.** Chosen: delete from `NullSafetyAnalyzer`. `StateInitAnalyzer` already covers 10 iteration methods (`map|filter|forEach|reduce|find|some|every|length|slice|join`); `NullSafetyAnalyzer`'s branch only covered `.map`. The broader coverage wins. No issue coverage is lost.

3. **Whether to rename tool window ID from `GhostDebugger` to `AegisDebug`.** Chosen: keep `GhostDebugger`. Tool window IDs participate in the IDE's layout persistence (`workspace.xml` `ToolWindowManager` entries). Renaming would cause every user who has pinned the tool window to have to re-pin it after the update. Cost of keeping the name: a string in the XML. Cost of renaming: every existing user's layout resets. Keep.

4. **Whether to delete the `CHANGELOG.md` "Known Limitations" section entirely.** Chosen: rewrite it, not delete. Users expect to know what's limited in a 1.0.0; replacing the inaccurate Kotlin-primary line with an accurate TS/JS-primary line preserves the section's intent.

5. **Whether to remove JUnit 4 right now or defer.** Chosen: remove now. Leaving a dead dependency on the plugin classpath through V1 publication bloats the distributed JAR unnecessarily and carries a CVE surface area (JUnit 4 has a known `RunListener` deserialization issue, not exploitable in our usage but noise we don't need on marketplace compliance scans).

6. **Whether to rewrite `GhostDebuggerConfigurable` into a proper form-builder or just collapse the dead code.** Chosen: collapse only. A form-builder rewrite is a refactor, not a remediation; scope creep is the enemy of this pass.

7. **Whether to fix the `StateInitAnalyzer` description string using the captured group or by constructing the description after the regex match.** Chosen: the latter — capture the method name during the iteration match and inject it into the description. This keeps the description accurate (shows the actual method name the user's code used) without restructuring the analyzer.

8. **Whether the new test class goes next to `NullSafetyAnalyzerTest.kt` or in a dedicated "contract" package.** Chosen: next to the existing analyzer test, following the project convention (tests co-located with analyzer tests in `src/test/kotlin/com/ghostdebugger/analysis/analyzers/`).

9. **Whether to mention the remediation in `CHANGELOG.md` under a new `[1.0.0] — 2026-04-15` sub-section or quietly correct the existing release notes.** Chosen: correct in place. These are pre-GA corrections, not post-GA patches. A `[1.0.1]` entry would falsely suggest there was a shipped 1.0.0 with the inaccurate text.

---

## 6. Files

### 6.1 Files to Modify

| # | Path | Change |
|---|---|---|
| M1 | `src/main/resources/META-INF/plugin.xml` | Rewrite `<description>` body to mirror `build.gradle.kts` pluginConfiguration description (TS/JS primary, "deterministic" fixers). Replace `2026-04-XX` with `2026-04-15` in `<change-notes>`. Add single-line XML comment above `<toolWindow id="GhostDebugger">` explaining the ID is kept stable for backward compatibility. |
| M2 | `CHANGELOG.md` | Line 9: replace "PSI-based deterministic fixers" with "deterministic fixers (regex construction, PSI-validated on apply)". Line 26: replace "Primary language support: Kotlin. Secondary: Java." with "Primary: TypeScript and JavaScript (full analysis + fixers). Secondary: Kotlin and Java (graph and circular-dependency analysis only)." |
| M3 | `src/main/kotlin/com/ghostdebugger/analysis/analyzers/NullSafetyAnalyzer.kt` | Delete the two unused `private val` regex lists at top of class (lines 14 and 21 — `nullStatePatterns` and `dangerousAccessPatterns`). Fix the title string to `"Null reference: $varName may be null"` (remove dot before interpolation, remove literal `${"may be null"}`). Delete the `STATE_BEFORE_INIT` emission branch (`stateMapRegex` block + its `issues.add` call). Keep the `NULL_SAFETY` emission branch unchanged. |
| M4 | `src/main/kotlin/com/ghostdebugger/analysis/analyzers/StateInitAnalyzer.kt` | In the `issues.add { … description = … }` block around lines 51–53, replace `"with .${"array method"} on line ${index + 1}"` with `"with .${match.groupValues[1]} on line ${index + 1}"`. The `match` variable from `iterationRegex.find(trimmed)` must be stored when found (currently `containsMatchIn` is used — switch to `find`). |
| M5 | `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerConfigurable.kt` | Delete lines 144–156 (the first `formPanel.add` block and the `formPanel.removeAll()` call). Keep lines 157 onward (the correctly-ordered add sequence starting with `formPanel.add(Box.createVerticalStrut(10))`). Remove the comment "Wait, fixed this below" and "Re-adding in correct order with sub-panels". |
| M6 | `build.gradle.kts` | Delete line 42: `testImplementation("junit:junit:4.13.2")`. Leave other test dependencies untouched. |

### 6.2 Files to Create

| # | Path | Purpose |
|---|---|---|
| C1 | `src/test/kotlin/com/ghostdebugger/analysis/analyzers/NullSafetyAnalyzerSingleResponsibilityTest.kt` | Regression guard for D5/D12. Feeds the analyzer a `useState()` + `.map()` fixture and asserts `result.none { it.type == IssueType.STATE_BEFORE_INIT }`. Also asserts `NULL_SAFETY` issues still fire on the null-state fixture from `NullSafetyAnalyzerTest.kt` (sanity check that the cleanup didn't over-delete). |

### 6.3 Files to Leave Untouched (confirmed consistent)

| Path | Why untouched |
|---|---|
| `README.md` | Already accurate as of the Phase 6 pass (TS/JS primary, Kotlin/Java graph-only). |
| `DATA_HANDLING.md` | Already accurate; date resolved. |
| `build.gradle.kts` (except line 42) | `version`, `pluginConfiguration`, `ideaVersion`, `vendor`, and task registrations are all correct. |
| `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` | Phase 6 hardening complete — Task.Backgroundable wrapping, synchronized cancel, `handleApplyFixRequested`, `reanalyzeFile`, `resolveAiService` all in place. |
| `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt` | Progress/cancellation correctly plumbed through static and AI passes. |
| `src/main/kotlin/com/ghostdebugger/analysis/PartialReanalyzer.kt` | Single-file re-analysis semantics intentional; full-graph pass for cycle/complexity is correct (dedup handles overlap). |
| `src/main/kotlin/com/ghostdebugger/analysis/analyzers/AsyncFlowAnalyzer.kt` | Emissions and language guard correct. |
| `src/main/kotlin/com/ghostdebugger/analysis/analyzers/CircularDependencyAnalyzer.kt` | Graph-based, language-agnostic — correct. |
| `src/main/kotlin/com/ghostdebugger/analysis/analyzers/ComplexityAnalyzer.kt` | Graph-based — correct. |
| `src/main/kotlin/com/ghostdebugger/fix/*` | All three fixers + applicator + registry verified by `FixerContractTest.kt`, `FixApplicatorValidityTest.kt`, per-fixer unit tests. |
| `src/main/kotlin/com/ghostdebugger/ai/*` | `AICache`, `AIServiceFactory`, `OpenAIService`, `OllamaService` all consistent with Phase 5 + Phase 6 contracts. |
| `src/main/kotlin/com/ghostdebugger/bridge/*` | `JcefBridge`, `UIEvent`, `UIEventParser` all carry the correct events (ApplyFixRequested, CancelAnalysisRequested, sendFixApplied, sendAnalysisProgress, sendEngineStatus). |
| `src/main/kotlin/com/ghostdebugger/annotator/GhostDebuggerAnnotator.kt` | `ProgressManager.checkCanceled()` in place; issue-to-annotation mapping correct. |
| `src/main/kotlin/com/ghostdebugger/actions/*` | Debounced `isAnalyzing` guard in place; tool window show-then-analyze flow correct. |
| `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt` | Immutable `val` for `Issue.explanation`/`suggestedFix`; `CodeFix.isDeterministic`/`confidence` present; no dead `MultiFileFix` / `BrokenNeighborhood`. |
| `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt` | All settings fields present and correctly persisted. |
| `webview/**` (non-node_modules) | Bridge subscriptions, store actions, ProvenanceBadge, TrustBadge, StatusBar Cancel button, engine status pill states all verified against bridge contract. No changes. |
| All existing test classes under `src/test/kotlin` | Must continue to pass. No modification. |

---

## 7. Data Contracts / Interfaces / Schemas

**None changed.** This spec does not touch:

- Any `Issue`, `IssueType`, `IssueSeverity`, `IssueSource` definition.
- Any `CodeFix` field.
- Any `AnalysisResult`, `ProjectMetrics`, `EngineStatus`, or `EngineProvider` value.
- Any JSON bridge event payload on either the Kotlin or webview side.
- Any persisted-state field in `GhostDebuggerSettings.xml`.

The only text visible to users that *content*-changes is:

- `plugin.xml` `<description>` and `<change-notes>` HTML bodies.
- `CHANGELOG.md` lines 9 and 26.
- The description string of emitted `STATE_BEFORE_INIT` issues from `StateInitAnalyzer` (now contains the real method name instead of the literal `"array method"`).
- The title string of emitted `NULL_SAFETY` issues from `NullSafetyAnalyzer` (now reads `"Null reference: x may be null"` instead of `"Null reference: x.may be null"`).

No consumer parses these strings; they are display-only.

---

## 8. Test Plan

### 8.1 New tests

- **`NullSafetyAnalyzerSingleResponsibilityTest.kt`** — two cases:
  - Given a `.tsx` file with `const [items, setItems] = useState()` and `items.map(...)`, `NullSafetyAnalyzer.analyze(ctx).none { it.type == IssueType.STATE_BEFORE_INIT }` MUST hold. (Previously the analyzer emitted both NULL_SAFETY and STATE_BEFORE_INIT here; post-D5 it emits only NULL_SAFETY — or nothing, if the `items` variable is not also initialized to `null`.)
  - Given a `.tsx` file with `let user = null; user.name;`, the analyzer MUST still emit exactly one `NULL_SAFETY` issue with title matching `"^Null reference: user may be null$"` — confirms D6 title fix and D7 pattern deletion did not break detection.

### 8.2 Existing tests that must continue to pass unchanged

- `NullSafetyAnalyzerTest.kt` — verify the removal of the STATE_BEFORE_INIT branch does not regress any NULL_SAFETY assertion. If the existing test file has assertions against STATE_BEFORE_INIT issues emitted by `NullSafetyAnalyzer` specifically, those assertions MUST be moved to `StateInitAnalyzerTest.kt` — but do not relax them.
- `StateInitAnalyzerTest.kt` — verify the captured-method-name change still satisfies the existing `description` assertions. If any existing assertion expects the literal string `"with .array method on"`, update it to match the actual method (`"with .map on"`, etc.) — this is a genuine correction, not a test weakening.
- `FixerContractTest.kt`, `FixApplicatorValidityTest.kt`, `NullSafetyFixerTest.kt`, `StateInitFixerTest.kt`, `AsyncFlowFixerTest.kt` — no change expected; the fixers read issue types, not analyzer internals.
- All Phase-5 AI service tests (`AIServiceFactoryTest.kt`, `OllamaServiceParseTest.kt`, `OpenAIServiceTimeoutTest.kt`) — no change.
- All Phase-6 hardening tests (`AnalysisEngineCancellationTest.kt`, `ApplyFixEventTest.kt`, `EngineStatusBridgeTest.kt`) — no change.

### 8.3 Manual verification

1. `./gradlew clean build` — must pass with no JUnit-4 resolution warnings.
2. `./gradlew runIde` — open a project, click **Analyze Project**, verify:
   - The tool window title bar reads `Aegis Debug` (not `GhostDebugger`).
   - Settings → Tools → Aegis Debug renders the provider form in the correct order (no duplicate/misplaced fields).
   - The About / Plugin Manager description reads the corrected language-support and "deterministic" fixer wording.
   - The "What's new" popup reads `1.0.0 — V1 GA (2026-04-15)`, not `2026-04-XX`.
3. Trigger a `NULL_SAFETY` issue from a TS fixture — verify the title reads `"Null reference: <var> may be null"` (no stray dot, no literal `may be null` from the inner interpolation).
4. Trigger a `STATE_BEFORE_INIT` issue from a TS fixture using `.filter(...)` — verify the description includes `"with .filter on line N"` (not `"with .array method on line N"`).

---

## 9. Acceptance Criteria

The remediation is complete when all of the following hold:

1. `./gradlew clean build test` passes green with no JUnit-4 dependency in the build output.
2. `plugin.xml`, `build.gradle.kts` `pluginConfiguration.description`, `README.md`, and `CHANGELOG.md` agree on three statements:
   - **Fixer architecture:** "deterministic" (optionally with the qualifier "regex construction, PSI-validated on apply").
   - **Language scope:** TypeScript and JavaScript primary (full analysis + fixers); Kotlin and Java secondary (graph + circular-dependency only).
   - **Release date:** `2026-04-15` (no `XX` placeholder anywhere).
3. `NullSafetyAnalyzer.kt` contains no `private val` named `nullStatePatterns` or `dangerousAccessPatterns`, and contains no `IssueType.STATE_BEFORE_INIT` reference.
4. `NullSafetyAnalyzerSingleResponsibilityTest.kt` exists and passes.
5. `StateInitAnalyzer.kt` emits issue descriptions containing the real iteration method name, verified by at least one existing or updated test assertion.
6. `GhostDebuggerConfigurable.createComponent` contains exactly one `formPanel.add(…)` sequence (no `formPanel.removeAll()` call inside `createComponent`).
7. `grep -rn 'junit:junit:4' build.gradle.kts` returns nothing.
8. Running the plugin in a dev IDE and opening **Settings → Tools → Aegis Debug** shows the form fields in the canonical order: Provider → API Key → OpenAI Model → Ollama Endpoint → Ollama Model → Max Files → Max AI Files → AI Timeout → Cache → Allow Cloud → Auto-Analyze → Show Info → Changed Files Only.
9. No new `TODO`, `FIXME`, or `XXX` comments introduced by this spec's edits.
10. A diff of `plugin.xml` shows no unresolved placeholder tokens (`XX`, `TODO`, `?`) anywhere in `<description>` or `<change-notes>`.

---

## 10. Execution Order

Edits are independent and can be applied in any order, but this order minimizes churn risk:

1. M6 (`build.gradle.kts` — delete JUnit 4) — smallest, zero-code-impact.
2. M2 (`CHANGELOG.md`) — doc only.
3. M1 (`plugin.xml`) — doc + one comment.
4. M5 (`GhostDebuggerConfigurable.kt`) — local refactor, zero-logic-impact.
5. M3 (`NullSafetyAnalyzer.kt`) — behavioural change (analyzer emits less).
6. C1 (`NullSafetyAnalyzerSingleResponsibilityTest.kt`) — immediately after M3, to lock the invariant.
7. M4 (`StateInitAnalyzer.kt`) — behavioural change (analyzer emits more-accurate descriptions).
8. Run full suite after each of steps 5, 6, 7.

---

## 11. Rollback

Every edit in this spec is a local file change with no migration, no schema bump, and no stored-state impact. If an edit causes regression:

- For doc edits (M1, M2): `git revert` the commit.
- For code edits (M3, M4, M5, M6): `git revert` the commit; tests from the Phase 1–6 suite immediately regain their previous behaviour.
- The new test (C1) has no rollback consideration — if it fails, the underlying M3 change is incorrect and should be reverted with it.

No user-data migration is involved. No persisted setting changes. No bridge contract changes. No risk to existing installed users.

---

## 12. Summary

Aegis Debug v1.0.0 is functionally done. This spec is a consistency pass: bringing three marketing/doc surfaces into agreement, eliminating two dead analyzer patterns, fixing two string-interpolation artifacts, collapsing one refactor remnant, and removing one unused Gradle dependency. Net effect: a cleaner v1 that says the same thing everywhere and carries no dead weight into the marketplace submission.

— End of spec —
