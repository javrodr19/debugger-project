# 🔍 Aegis Debug — V1 Gap Analysis & Completion Plan

**Date:** 2026-04-15
**Scope:** Audit of Phases 1–5 implementation against `aegis_debug_true_v1_spec.md`
**Status:** Implementation delivery assessment + remaining work for V1 GA
**Companion docs:** `aegis_debug_phase_{1..5}_*.md`, `aegis_debug_true_v1_spec.md`

---

## 0. Executive Summary

Aegis Debug has shipped the **structural** V1 surface area across Phases 1–5: rebrand, static-first pipeline, file-cap enforcement, fingerprint merge with provenance, three deterministic PSI fixers, enterprise UI refresh, engine status bridge, and the AI service abstraction with Ollama + OpenAI backends.

However, **V1 is not GA-ready**. Four categories of work remain:

1. **Phase 5 migration is incomplete** — `GhostDebuggerService` and `AIAnalyzer` still instantiate `OpenAIService` directly instead of routing through `AIServiceFactory`, so the Ollama backend is only wired into the analysis pass, not into fix/explain/system flows. Spanish strings still leak into user-facing text.
2. **IntelliJ platform compliance gaps** — no cancellation, no progress indicator. V1 §11 and §18.5 require both.
3. **Fix safety is nominal, not structural** — `FixApplicator` applies text replacements via `WriteCommandAction` (undo-safe) but performs **no** PSI validity check or syntax re-parse. V1 §8 requires both before an "Engine Verified" label is legitimate.
4. **Phase 6 (Hardening) is entirely deferred** — performance testing, FP/FN audit, fallback message polish, and release-asset prep have not started. V1 cannot ship without them.

This document catalogs every gap, links it to the V1 spec clause it violates, and proposes a **Phase 6** implementation plan ordered by release-blocking severity.

---

## 1. Audit Method

For each phase:
- Compared the phase `.md` spec against the current file tree (`src/main/kotlin/com/ghostdebugger/**`, `webview/src/**`, `src/test/kotlin/**`).
- Re-read key contract files to verify the binding (types, factory, engine, applicator, bridge, UI subscriptions).
- Flagged deviations as either **spec drift** (implementation and spec disagree), **residual debt** (spec delivered but V1 quality bar not met), or **deferred** (spec explicitly punted).

For V1 non-functional clauses (§11, §14, §15, §16, §17, §18, §20 Phase 6):
- Searched the codebase for the required API surface (`ProgressIndicator`, `checkCanceled`, `PasswordSafe`, etc.).
- Verified via `src/test/kotlin` whether the matching test coverage exists.

---

## 2. Phase-by-Phase Verdict

| Phase | Title | Verdict | Blockers |
|-------|-------|---------|----------|
| 1 | Foundations | ✅ Complete | — |
| 2 | Core Engine | ✅ Complete | — |
| 3 | Fix Pipeline | 🟡 Mostly complete | PSI validity check missing; post-fix re-analysis is full, not targeted |
| 4 | UI Refresh | ✅ Complete | — |
| 5 | AI Augmentation | 🟡 Partially wired | `GhostDebuggerService`/`AIAnalyzer` bypass `AIServiceFactory`; Spanish strings remain |
| 6 | Hardening | ❌ Not started | Performance, FP/FN, release assets |

---

## 3. Phase 1 — Foundations

### Verified delivered
- Plugin rebrand to **Aegis Debug** (`plugin.xml` line 3; tool-window id `GhostDebugger` retained for settings-storage compatibility, which is acceptable).
- `AIProvider` enum with `NONE`, `OPENAI`, `OLLAMA` at `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt:9`.
- Settings refactor with `State` data class, `snapshot()`, `update { }`, and `validate()` at `GhostDebuggerSettings.kt:19–57`.
- V1 additive flags present: `maxAiFiles`, `aiTimeoutMs`, `allowCloudUpload`, `analyzeOnlyChangedFiles` (`GhostDebuggerSettings.kt:25–32`).
- Analyzer contract with `ruleId` / `defaultSeverity` / `description` declared in `src/main/kotlin/com/ghostdebugger/analysis/Analyzer.kt`.
- Baseline tests: `GhostDebuggerSettingsTest.kt`, `AnalyzerContractTest.kt`, per-analyzer tests under `src/test/kotlin/com/ghostdebugger/analysis/analyzers/`.

### Gaps
None material. Legacy `openAiModel`/`maxFilesToAnalyze`/`autoAnalyzeOnOpen` getters/setters at `GhostDebuggerSettings.kt:61–71` are intentional backward-compat shims and can stay.

---

## 4. Phase 2 — Core Engine

### Verified delivered
- Static-first pipeline: `runStaticPass` precedes `runAiPass` at `AnalysisEngine.kt:47–49`.
- File cap enforced before expensive work: `context.limitTo(settings.maxFilesToAnalyze)` at `AnalysisEngine.kt:45`.
- File prioritization: `AnalysisContextPrioritization.kt` (top-level file) implements current-file / changed-files / hotspot ordering.
- Analyzer exception isolation: per-analyzer `try/catch` at `AnalysisEngine.kt:82–99` with `log.warn("Analyzer … failed; continuing", e)` — matches V1 §18.1.
- Fingerprint merge with provenance preservation at `AnalysisEngine.kt:237–245`.
- Provider fallback table: `runOpenAiPass` emits `DISABLED` / `FALLBACK_TO_STATIC` / `ONLINE` states at `AnalysisEngine.kt:118–178`; same for `runOllamaPass` at `AnalysisEngine.kt:180–235`.
- Tests: `AnalysisEngineStaticFirstTest.kt`, `AnalysisEngineFileCapTest.kt`, `AnalysisEngineProviderFallbackTest.kt`, `AnalysisEngineAnalyzerIsolationTest.kt`, `AnalysisEngineOllamaPassTest.kt`, `AnalysisContextPrioritizationTest.kt`.

### Gaps
None material at the unit level. Cancellation / progress concerns are called out under §7 below (cross-cutting).

---

## 5. Phase 3 — Fix Pipeline

### Verified delivered
- `Fixer` SPI + `FixerRegistry` at `src/main/kotlin/com/ghostdebugger/fix/Fixer.kt`, `FixerRegistry.kt`.
- Three deterministic PSI fixers: `NullSafetyFixer.kt`, `StateInitFixer.kt`, `AsyncFlowFixer.kt`.
- `FixApplicator` uses `WriteCommandAction.runWriteCommandAction` at `FixApplicator.kt:29`, which gives native IDE undo support (matches V1 §18.3 "undoable").
- `UIEvent.ApplyFixRequested` wired through `GhostDebuggerService.handleApplyFixRequested` at `GhostDebuggerService.kt:489–530`.
- Full fixer test suite: `FixerContractTest.kt`, `NullSafetyFixerTest.kt`, `StateInitFixerTest.kt`, `AsyncFlowFixerTest.kt`, `FixerRegistryTest.kt`.

### Gaps

#### 5.1 No PSI/syntax validity check after fix (V1 §8 requirement)
V1 §8 "Required Safety Checks" demands **before** an issue is labeled **Engine Verified**:
1. transformation completed successfully ← ✓ (boolean return)
2. PSI tree remained valid ← ❌ not checked
3. resulting code is syntactically valid in the file context ← ❌ not checked

`FixApplicator.kt:29–34` performs a raw `document.replaceString(startOffset, endOffset, fix.fixedCode)` with no re-parse, no `PsiDocumentManager.commitDocument()`, and no inspection re-run. If a fixer produces malformed output, the IDE will happily save broken code and the "Engine Verified" badge becomes a lie.

**Severity:** Release-blocking. The product's core promise (trust) is violated.

#### 5.2 Post-fix re-analysis is full-project, not targeted
V1 §8 step 6 and §18.3 require **targeted** re-analysis. `GhostDebuggerService.kt:523` calls the full `analyzeProject()` — correct behaviorally but wasteful on medium/large repos and not what the spec promises.

**Severity:** Low for correctness, medium for perceived responsiveness.

#### 5.3 Line-range fix model is fragile
`FixApplicator.kt:31–32` computes `document.getLineEndOffset(fix.lineEnd - 1)` from `CodeFix.lineEnd` which was set as `issue.line + originalSnippet.lines().size` in the AI path (`OpenAIService.kt:139`, `OllamaService.kt:170`). For deterministic PSI fixes this is fine; for AI fixes this can over-replace or clip partial lines if the snippet had leading/trailing partial fragments.

**Severity:** Medium. Relevant once AI fixes are shown in the Apply Fix CTA (currently deterministic-only via `FixerRegistry.forIssue`).

---

## 6. Phase 4 — UI Refresh

### Verified delivered
- 22-token `:root {}` block in `webview/src/index.css` (Dark Navy `#0A1128`, Cream `#FDFBF7`, radii, spacing).
- Four `@fontsource/ibm-plex-*` imports in `webview/src/main.tsx` — local bundling (no Google Fonts CDN fetch per V1 §12 typography rule).
- `EngineStatusPill` named export from `webview/src/components/layout/StatusBar.tsx` with five status cases.
- `ProvenanceBadge` and `TrustBadge` components in `webview/src/components/detail-panel/DetailPanel.tsx`.
- Apply Fix CTA with loading state + `applyingFix` thread-through.
- Two V1 test files present: `webview/src/__tests__/engineStatusPill.test.tsx` (5 cases), `src/test/kotlin/com/ghostdebugger/bridge/EngineStatusBridgeTest.kt` (2 cases).

### Gaps

#### 6.1 Focus treatment audit not performed
`grep focus|outline|:focus-visible|aria-` matches 5 files (`DetailPanel.tsx`, `appStore.ts`, `index.css`, `CustomNode.tsx`, `NeuroMap.tsx`) but **no keyboard/focus end-to-end test exists**. V1 §18.5 requires "Focus/selected states are visible in the UI."

**Severity:** Low–medium. Tokens exist (`--focus-ring: #FDFBF7`); systematic use across interactive elements is unverified.

#### 6.2 No explicit "Cloud Active" indicator beyond the pill
V1 §14 "Mandatory User Transparency" requires the UI to make it clear "when code may be sent externally." Current implementation relies on the `EngineStatusPill` text (`OpenAI · Online`). That is acceptable, but a dedicated privacy indicator on the issue card when `sources: [AI_CLOUD]` is not visually distinct from `AI_LOCAL` — both render under one `ProvenanceBadge` `AI` bucket.

**Severity:** Medium. V1 privacy promise is a core claim.

---

## 7. Phase 5 — AI Augmentation

### Verified delivered
- `AIService` interface at `src/main/kotlin/com/ghostdebugger/ai/AIService.kt` — 4 methods.
- `OllamaService.kt` — full implementation, `/api/chat` endpoint, `stream=false`, system + user message pattern.
- `AIServiceFactory.kt` — correct branches for NONE / OPENAI (with key check) / OLLAMA.
- `OpenAIService.kt` now accepts `timeoutMs` and `cacheTtlSeconds` from settings (`OpenAIService.kt:22–27`).
- `AnalysisEngine.runOllamaPass` at `AnalysisEngine.kt:180–235` tags issues with `IssueSource.AI_LOCAL` + `EngineProvider.OLLAMA`.
- Prompts converted to English in `PromptTemplates.kt` and `SystemPrompts.kt`.
- Tests: `AIServiceFactoryTest.kt`, `OllamaServiceParseTest.kt`, `OpenAIServiceTimeoutTest.kt`, `AnalysisEngineOllamaPassTest.kt`.

### Gaps

#### 7.1 `GhostDebuggerService` still uses `OpenAIService` directly — Ollama users get no enrichment (spec drift)
Four call sites instantiate `OpenAIService(apiKey)` directly instead of going through `AIServiceFactory.create(settings, apiKey)`:
- `GhostDebuggerService.kt:338` — pre-fetch AI explanations for critical issues
- `GhostDebuggerService.kt:381` — `handleNodeClicked` explanation
- `GhostDebuggerService.kt:475` — `handleFixRequested` AI fallback when no deterministic fixer matches
- `GhostDebuggerService.kt:550` — `handleExplainSystem`

`private var openAIService: OpenAIService?` at `GhostDebuggerService.kt:54` is still typed as the cloud-specific class.

**Consequence:** A user configured with `aiProvider = OLLAMA` will get static analysis with Ollama-driven missed-issue discovery, but clicking a node, requesting a fix, or asking "Explain System" silently tries to read an OpenAI key. If the key is absent, the user falls back to the local summary or an error — **Ollama is never consulted** for these flows.

**Severity:** Release-blocking. Ollama support is currently cosmetic for three of the four user-visible AI touchpoints.

**Fix:** Replace `openAIService: OpenAIService?` with `aiService: AIService?` and a `resolveAiService(): AIService?` helper that calls `AIServiceFactory.create(GhostDebuggerSettings.getInstance().snapshot(), ApiKeyManager.getApiKey())`. Migrate the four call sites. Phase 5 spec already describes this change.

#### 7.2 `AIAnalyzer` hardcodes `OpenAIService` (spec drift)
`AIAnalyzer.kt:24–29` constructs `OpenAIService(apiKey, model, timeoutMs, cacheTtlSeconds)` unconditionally. The `AnalysisEngine.runAiPass` routes OPENAI here via `aiPassRunner`, but the class is named generically and should delegate to `AIServiceFactory` so that future non-OpenAI cloud providers can drop in without a second class.

**Severity:** Low. Functionally correct for current OpenAI-only path; becomes medium if additional cloud providers are added.

#### 7.3 Spanish strings still present in `GhostDebuggerService`
Three locations remain in Spanish despite Phase 5 prompt conversion:
- `GhostDebuggerService.kt:392–393` — `"Error al obtener explicación: ${e.message}"`
- `GhostDebuggerService.kt:537` — `"Por favor, analiza el proyecto primero con 'Analyze Project'."`
- `GhostDebuggerService.kt:620–629` — entire `buildLocalSystemSummary` response (`"Resumen del Proyecto"`, `"Módulos analizados"`, `"Archivos con errores"`, `"Configura tu API key..."`)

**Severity:** Medium. These are user-visible strings on a product whose entire UI is English.

#### 7.4 `AICache` has no disk persistence and no user-disable toggle hookup
V1 §14 "Data Handling Rules" requires the system to answer: "whether cached data is stored on disk" and "how users disable cache." The cache is in-memory per `AIService` instance (recreated on each `OpenAIService`/`OllamaService` construction) and the `cacheEnabled: Boolean` setting at `GhostDebuggerSettings.kt:28` is **unread** by any code.

**Severity:** Medium. The flag exists but does nothing.

---

## 8. Cross-Cutting V1 Requirements — Gaps

### 8.1 §11 IntelliJ Platform — Cancellation & Progress (release-blocking)

Search across `src/main/kotlin` for `ProgressIndicator`, `checkCanceled`, `ProcessCanceledException`, `ProgressManager` returned **zero matches**.

Consequences:
- **§11 mandatory:** "long scans must support cancellation" — violated.
- **§11 mandatory:** "scan progress must be visible to the user" — violated.
- **§18.5:** "Long-running scans can be canceled" — violated.
- **§15:** "user can cancel analysis safely" — violated.

`GhostDebuggerService.analyzeProject` at `GhostDebuggerService.kt:264` launches on `Dispatchers.Default` via a `SupervisorJob` scope. A user has no cancel handle in the UI and the `BulkFileListener` auto-refresh (`GhostDebuggerService.kt:103–112`) will stack up jobs.

**Required for V1:**
1. Wrap the analysis body in `ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Aegis Debug: Analyzing project", true) { … })`.
2. Pass the `ProgressIndicator` down to `AnalysisEngine.analyze` and call `indicator.checkCanceled()` between analyzer loops and between files in `runStaticPass` and each AI pass.
3. Wire a UI cancel button into the status pill or an inline spinner.
4. Ensure `scope.cancel()` on `dispose()` cascades into running jobs (currently it does, but the analysis body does not check coroutine `isActive`).

### 8.2 §8 Fix Safety — PSI validity + syntax check (release-blocking)
Covered under §5.1 above. Must implement `PsiDocumentManager.getInstance(project).commitDocument(document)` after `replaceString`, followed by a re-parse assertion (`PsiErrorElement` walk or `FileASTNode.elementType` sanity check).

### 8.3 §14 Privacy — Data handling policy + cloud indicator (medium)
V1 §14 requires the team to document and implement explicitly:
- what code/context is sent to AI providers ← document only
- whether uploads are file-level / snippet-level / issue-context-only ← document only
- whether prompts/responses are cached ← wire `cacheEnabled` into `OpenAIService`/`OllamaService` constructors, default cache off when flag is false
- whether cached data is stored on disk ← confirm in-memory-only; document
- how users disable cache ← expose `cacheEnabled` in `GhostDebuggerConfigurable`
- whether any telemetry exists ← confirm none; document

No `DATA_HANDLING.md` or equivalent privacy doc exists in the repo.

### 8.4 §16 Observability — Error categorization (medium)
V1 §16 lists 8 error categories. Today the code logs ad-hoc via `log.warn` / `log.error` without category tags. A typed sealed class or a `reason: ErrorCategory` field on internal logs would satisfy the spec and make user-facing messages more consistent.

### 8.5 §17 Test Strategy — Missing layers (medium, partly release-blocking)

| Layer | Required by V1 §17 | Current |
|-------|--------------------|---------|
| Unit (analyzers, merge, settings, fallback) | ✓ | ✓ ample coverage |
| PSI / fix generation | ✓ | ✓ per-fixer tests |
| PSI / fix **apply + undo** behavior | ✓ | ❌ missing |
| PSI / fix **syntax-validity post-transform** | ✓ | ❌ missing |
| Integration — end-to-end static scan | ✓ | ❌ missing |
| Integration — AI unavailable → fallback | ✓ | 🟡 unit-level only |
| Integration — engine-status bridge propagation | ✓ | 🟡 serialization shape only |
| Integration — diff preview + apply flow | ✓ | ❌ missing |
| UI — issue badge rendering | ✓ | 🟡 pill only |
| UI — engine status rendering | ✓ | ✓ |
| UI — fallback message rendering | ✓ | ❌ missing |
| UI — keyboard/focus behavior | ✓ | ❌ missing |
| Fixture repositories (positive, FP, fix, large-ish) | ✓ | ❌ none — `src/test/resources/` does not exist |

### 8.6 §20 Phase 6 (Hardening) — entirely deferred (release-blocking)

V1 §20 items 21–24 have no implementation, no tracking, no test plan:

21. **Performance testing on small/medium/large fixtures.** No fixture repos exist. No benchmark harness. V1 §15 targets ("near-immediate small, responsive medium, degrade-via-prioritization large") are asserted, not measured.
22. **FP/FN audit on analyzers.** No systematic false-positive prevention suite. Each analyzer has a pair of positive + negative tests but no regression corpus.
23. **Polish fallback states and user messaging.** Mixed-language strings (see §7.3) and the generic `"OpenAI unreachable (IOException); static results returned."` pattern at `AnalysisEngine.kt:172` could be friendlier and more actionable (e.g., "Check your network or switch to Ollama in Settings").
24. **Freeze V1 scope and prepare release assets.** Plugin marketplace description (`plugin.xml:7–20`) is draft-quality. No screenshots, no changelog, no version bump plan. Logo V1 (shield + node hybrid per V1 §13) — only `aegis.svg` is verified to exist; design fidelity to the spec ("abstract geometric mark, shield + node/graph hybrid, cream mark on deep navy") is not validated. Legacy `ghost.svg` still in `src/main/resources/icons/`.

### 8.7 §13 Branding — legacy asset + marketplace prep (low)
- `src/main/resources/icons/ghost.svg` still present. Remove unless referenced in a transitional codepath (it is not — `plugin.xml` uses `aegis.svg` exclusively).
- Plugin `id` is still `com.ghostdebugger` (`plugin.xml:2`). This is the stable settings storage key and should **not** change in V1 (it would discard user settings), but the decision should be documented.

---

## 9. Acceptance Criteria Scorecard (V1 §18)

| § | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 18.1 | Static runs with `AIProvider.NONE` | ✅ | `AnalysisEngine.runAiPass` NONE branch returns empty + `DISABLED` |
| 18.1 | AI failure does not block static | ✅ | `runOpenAiPass` / `runOllamaPass` `onFailure` branches preserve static issues |
| 18.1 | Analyzer exception does not crash scan | ✅ | `runStaticPass` per-analyzer try/catch |
| 18.1 | File caps enforced pre-analysis | ✅ | `context.limitTo(settings.maxFilesToAnalyze)` |
| 18.2 | Every issue has source/provider metadata | ✅ | `runStaticPass` + `runOpenAiPass` + `runOllamaPass` copy-tag |
| 18.2 | Merged issues preserve multi-engine evidence | ✅ | `mergeIssues` distinct-union on sources/providers |
| 18.2 | Fallback states visible in UI | ✅ | `EngineStatusPill` renders FALLBACK_TO_STATIC case |
| 18.3 | Supported deterministic fixes show diff preview | ✅ | `sendFixSuggestion` in `handleFixRequested` |
| 18.3 | Fix application is undoable | ✅ | `WriteCommandAction.runWriteCommandAction` |
| 18.3 | Targeted re-analysis after fix | 🟡 | Full re-analysis instead (§5.2) |
| 18.3 | Engine Verified label only when deterministic validation passes | ❌ | No PSI/syntax validation (§5.1) |
| 18.4 | Static works offline | ✅ | NONE branch + allowCloudUpload gate |
| 18.4 | Cloud requires explicit enablement | ✅ | `allowCloudUpload` + `ApiKeyManager` |
| 18.4 | API keys not in settings XML | ✅ | `ApiKeyManager` uses `PasswordSafe` |
| 18.4 | UI indicates cloud provider active | 🟡 | Pill shows it, but issue cards do not distinguish `AI_CLOUD` vs `AI_LOCAL` visually (§6.2) |
| 18.5 | Plugin responsive during scans | 🟡 | Off-UI thread, but no progress (§8.1) |
| 18.5 | Long scans can be canceled | ❌ | No cancellation (§8.1) |
| 18.5 | List/cards usable when graph is degraded | ✅ | Issue list is independent component |
| 18.5 | Focus/selected states visible | 🟡 | Tokens exist; systematic audit not done (§6.1) |
| 18.6 | Consistent Aegis Debug naming | ✅ | `plugin.xml`, UI, messaging |
| 18.6 | Ghost motif removed from primary surfaces | 🟡 | Legacy `ghost.svg` asset still in `resources/icons/` (§8.7) |

**Pass:** 14. **Partial:** 6. **Fail:** 2. **V1 GA gate:** all release-blocking rows must reach ✅.

---

## 10. Proposed Phase 6 — Hardening Backlog

Ordered by release-blocking severity. Sections reference the V1 spec clauses above.

### 10.1 P0 — Release blockers (must fix before V1 GA)

1. **Wire cancellation + progress into `GhostDebuggerService.analyzeProject`.**
   - Use `Task.Backgroundable` + `ProgressIndicator`.
   - Thread indicator through `AnalysisEngine.analyze` and call `checkCanceled()` between analyzer passes and per-file in AI passes.
   - Show a cancel affordance in the webview (button in `StatusBar.tsx`).
   - Integration test: starts scan, cancels mid-flight, asserts clean coroutine teardown and no partial graph emission.

2. **Add PSI validity + syntax check to `FixApplicator`.**
   - After `replaceString`, call `PsiDocumentManager.getInstance(project).commitDocument(document)`.
   - Walk the resulting PSI for `PsiErrorElement` nodes; if any, roll back via `document.replaceString(start, end, originalText)` and return `false`.
   - Gate the `bridge.sendFixApplied` signal on the success boolean.
   - Tests: a positive path, a negative path (inject syntax-breaking fix text), assert rollback.

3. **Complete Phase 5 service migration.**
   - Replace `GhostDebuggerService.openAIService: OpenAIService?` with `aiService: AIService?`.
   - Add `private fun resolveAiService(): AIService? = AIServiceFactory.create(GhostDebuggerSettings.getInstance().snapshot(), ApiKeyManager.getApiKey())`.
   - Migrate the 4 call sites (explain-issue prefetch, `handleNodeClicked`, `handleFixRequested` AI fallback, `handleExplainSystem`).
   - Translate the 3 Spanish string blocks (§7.3).
   - Integration test: configure `aiProvider = OLLAMA`, trigger `handleExplainSystem`, assert `OllamaService.explainSystem` was called (mock Ollama endpoint).

4. **Fixture repositories and integration tests.**
   - Create `src/test/resources/fixtures/{small,medium,large}/` with representative Kotlin/Java snippets.
   - Add integration test that runs `AnalysisEngine.analyze` end-to-end against each fixture and asserts expected issue counts (golden files).
   - Add AI-unavailable integration test (spin up a mock server that returns 503, assert fallback).

### 10.2 P1 — GA quality (should fix before V1 GA)

5. **Hook `cacheEnabled` setting.** Pass `cacheEnabled` into `OpenAIService` / `OllamaService` constructors; short-circuit `cache.get`/`cache.put` when false.
6. **FP/FN audit.** Run each analyzer against the fixture corpus; snapshot findings; triage false positives by raising `ruleId`-level exclusions or tightening heuristics.
7. **Provenance badge split: `AI_LOCAL` vs `AI_CLOUD`.** Two distinct visual treatments in `DetailPanel.ProvenanceBadge` so the privacy-active state is obvious.
8. **Targeted post-fix re-analysis.** Re-run only the changed file through the static pass; merge into `currentIssues` incrementally instead of full `analyzeProject()`.
9. **User-facing fallback message polish.** Replace `"OpenAI unreachable (IOException)"` with actionable copy ("Cannot reach OpenAI. Check network, or switch to Ollama in Settings → Tools → Aegis Debug").

### 10.3 P2 — Release assets (must ship with V1 GA)

10. **Plugin marketplace description.** Write a real paragraph + 3–5 screenshots (NeuroMap, issue card with trust badge, Apply Fix diff preview, settings panel, engine status pill states).
11. **Logo V1 compliance pass.** Validate `aegis.svg` against V1 §13 design spec (shield + node hybrid, cream on navy); produce light-mode variant if IDE theme toggles exist.
12. **Remove `ghost.svg`** from `src/main/resources/icons/`.
13. **`DATA_HANDLING.md`** — document the 6 points in V1 §14.4 (what is sent, cache scope, disk storage, telemetry).
14. **CHANGELOG.md + version bump** to `1.0.0` in `plugin.xml` + build file.
15. **`since-build` / `until-build`** pinning in `plugin.xml` for the supported IntelliJ Platform range.

### 10.4 P3 — Nice to have (post-GA if time-boxed)

16. **Error category sealed class** per V1 §16 — `AnalyzerFailure`, `ParsingFailure`, `ProviderUnavailable`, `ProviderTimeout`, `BridgeFailure`, `FixGenerationFailure`, `FixApplicationFailure`, `ValidationFailure`.
17. **Systematic focus-ring audit** + keyboard-nav tests for the issue list and detail panel.
18. **`AIAnalyzer` factory refactor** per §7.2.

---

## 11. Recommended GA Gate

V1 is GA-ready when **all P0 items (§10.1) ship with tests**, **P1 items §10.1–§10.2 items 5, 7, 8, 9 ship**, and **the P2 release-asset checklist (§10.3) is complete**.

The remaining P1 items (FP/FN audit #6) and all P3 items can be deferred to a patch release (1.0.1) without compromising the V1 trust promise.

---

## 12. Appendix — Files Referenced in This Audit

- `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt`
- `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt`
- `src/main/kotlin/com/ghostdebugger/analysis/AnalysisContextPrioritization.kt`
- `src/main/kotlin/com/ghostdebugger/analysis/Analyzer.kt`
- `src/main/kotlin/com/ghostdebugger/analysis/analyzers/AIAnalyzer.kt`
- `src/main/kotlin/com/ghostdebugger/ai/AIService.kt`
- `src/main/kotlin/com/ghostdebugger/ai/AIServiceFactory.kt`
- `src/main/kotlin/com/ghostdebugger/ai/ApiKeyManager.kt`
- `src/main/kotlin/com/ghostdebugger/ai/OllamaService.kt`
- `src/main/kotlin/com/ghostdebugger/ai/OpenAIService.kt`
- `src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt`
- `src/main/kotlin/com/ghostdebugger/ai/prompts/SystemPrompts.kt`
- `src/main/kotlin/com/ghostdebugger/bridge/JcefBridge.kt`
- `src/main/kotlin/com/ghostdebugger/fix/FixApplicator.kt`
- `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt`
- `src/main/resources/META-INF/plugin.xml`
- `src/main/resources/icons/aegis.svg`, `analyze.svg`, `ghost.svg`
- `webview/src/components/layout/StatusBar.tsx`
- `webview/src/components/detail-panel/DetailPanel.tsx`
- `webview/src/index.css`
- `webview/src/main.tsx`
- `webview/src/__tests__/engineStatusPill.test.tsx`
- `src/test/kotlin/com/ghostdebugger/bridge/EngineStatusBridgeTest.kt`
- `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineOllamaPassTest.kt`

---

**End of gap analysis.** Next action: scope Phase 6 tickets against §10 and schedule a V1 GA readiness review once P0 items land.
