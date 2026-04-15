# Aegis Debug — V1 Full Implementation Audit

**Date:** 2026-04-15
**Scope:** Complete codebase audit vs. V1 specification (`aegis_debug_v1.md`)
**Supersedes:** `aegis_debug_v1_gap_analysis.md` (first pass was incomplete)
**Status:** **NOT GA-READY** — two spec-vs-code integrity gaps plus ~18 smaller items

---

## 0. How to read this document

This is the third pass of the V1 audit. The first pass (gap analysis) focused on missing
features. The second pass (Phase 6 spec) proposed a hardening plan. **This pass is the
reconciliation** — what the V1 spec promises vs. what the code actually does, and what the
Phase 6 spec assumed was missing but is already built.

Two numbers matter most:
- **Analyzer coverage of Kotlin/Java files: 2 of 5 analyzers** (Complexity + Circular). The
  other three (`NullSafetyAnalyzer`, `StateInitAnalyzer`, `AsyncFlowAnalyzer`) have a hard
  `if (extension !in setOf("ts","tsx","js","jsx")) continue` at their top.
- **"PSI-based fixers" in plugin.xml: 0 of 3.** All three fixers use `Regex.replace` on raw
  text. `NullSafetyFixer.tryFix` builds a regex like `"""(?<!\?)(\b${Regex.escape(varName)})\."""`
  and calls `replaceFirst`. No PSI, no write-action validity check, no rollback.

If you only have time to fix two things before GA, fix those two.

---

## 1. Headline finding #1 — Language-scope divergence

### What V1 says

- §3.2: "Primary: Kotlin. Secondary: Java."
- §20.2 (Phase 2 acceptance): "NullSafetyAnalyzer catches the canonical Kotlin NPE test
  case on `NullSafetyFixture.kt`."
- Marketplace description (`build.gradle.kts:69`): "Null safety, state-before-init, async
  flow, circular dependencies, complexity" — unqualified, sold as language-agnostic.

### What the code does

| Analyzer | Files it touches | Kotlin/Java coverage |
|---|---|---|
| `NullSafetyAnalyzer` | `.ts .tsx .js .jsx` only (line 30 guard) | **None** |
| `StateInitAnalyzer` | `.ts .tsx .js .jsx` only (line 17 guard) | **None** |
| `AsyncFlowAnalyzer` | `.ts .tsx .js .jsx` only (line 17 guard) | **None** |
| `ComplexityAnalyzer` | Any node in the graph | Graph-derived ✅ |
| `CircularDependencyAnalyzer` | Any edge in the graph | Graph-derived ✅ |

`FileScanner` scans 13 extensions (`kt, java, ts, tsx, js, jsx, py, go, rs, cs, rb, swift,
php`) and adds them all to the graph. The three primary analyzers ignore 9 of those 13.
Kotlin and Java files are in the graph but are effectively invisible to the analyzers the
marketplace description names.

### Why this is a blocker

- The plugin **does not do what its marketplace description says** for its two named primary
  languages. This is not a cosmetic issue — it will generate support complaints and negative
  reviews from Kotlin/Java developers who install based on the name "IntelliJ plugin" + the
  listed features.
- V1 §18 acceptance criteria explicitly includes "Plugin catches canonical Kotlin NPE."
  That criterion is not met.

### Two resolutions

- **Option A — port analyzers to Kotlin/Java PSI.** Honest to V1, heavy (3–5 weeks).
  `NullSafetyAnalyzer` becomes a PSI walker that finds `KtNameReferenceExpression` nodes
  with a nullable type and no `?.` / `!!`. `StateInitAnalyzer` becomes a `lateinit` / `lazy`
  / `by Delegates.notNull()` safety checker. `AsyncFlowAnalyzer` becomes a coroutine-scope
  / supervisorJob safety checker. Fixers likewise.
- **Option B — update V1 spec and marketplace copy to say TS/JS primary.** Lighter (1–2
  weeks), more truthful to what exists. Kotlin/Java users still get the NeuroMap, the
  graph, complexity and cycle detection — real value, just not what §3.2 claims.

Recommendation: **Option B for the 1.0.0 marketplace release, Option A queued for 1.1.**
The product is more honest about what it actually delivers, and the Kotlin/Java PSI work
is large enough to deserve its own release cycle.

---

## 2. Headline finding #2 — Fixers are regex-based, not PSI-based

### What V1 says

- §8.1: "Fixers operate on PSI, never on raw text."
- §8.2: "Every fix is applied in a `WriteCommandAction` with PSI-level validity check; on
  parse failure, the edit is rolled back."
- `build.gradle.kts:72`: "Three **PSI-based fixers** with diff preview and native undo."

### What the code does

All three fixers are in `src/main/kotlin/com/ghostdebugger/fix/`. None of them import a
single PSI class. Concrete evidence:

**`NullSafetyFixer.tryFix`:**
```kotlin
val safeRegex = Regex("""(?<!\?)(\b${Regex.escape(varName)})\.""")
val fixed = safeRegex.replaceFirst(original, "$1?.")
```

**`StateInitFixer.tryFix`:**
```kotlin
val declRegex = Regex("""const\s+\[\s*${Regex.escape(varName)}\s*,""")
val fixed = original.replace("useState()", "useState([])")
```

**`AsyncFlowFixer.tryFix`:**
```kotlin
if (!original.contains(".then(") || !original.trimEnd().endsWith(");")) return null
val fixed = original.trimEnd().dropLast(1) + ".catch(console.error);"
```

### Why this is a blocker

- These are **string edits**. There is no guarantee they produce valid syntax; they can and
  will corrupt files on edge cases (multi-line method chains, template literals, comments
  containing the variable name, etc.).
- `CHANGELOG.md` already advertises "PSI-validity check on deterministic fix application
  with automatic rollback on parse error" for 1.0.0. That line is aspirational — no code
  implements it.
- If we ship this as "PSI-based," the first user who opens a file post-fix and sees a
  syntax error will file a bug that Anthropic-grade QA should have caught.

### Resolution options

- **If Option A (Kotlin PSI port):** rewrite each fixer to produce `PsiElement`s through
  `KtPsiFactory` / `PsiElementFactory`, apply via `element.replace(newElement)` inside a
  `WriteCommandAction`, then call `PsiDocumentManager.commitDocument` and
  `KtFile.analyzeWithContent()` to verify the result parses — rollback on failure.
- **If Option B (JS/TS primary):** rename the user-facing description to "deterministic
  line-level fixers" and still add the parse-check-on-apply step by re-parsing the resulting
  TypeScript with a lightweight tokenizer, rolling back if token count changes unexpectedly.
  Less rigorous than PSI, but truthful.

---

## 3. Spec-vs-code truth reconciliation

| V1 / marketing claim | Code reality | Verdict |
|---|---|---|
| "Primary: Kotlin, Secondary: Java" | 3 of 5 analyzers skip Kotlin/Java | ❌ False |
| "Three PSI-based fixers" | Three regex-based fixers | ❌ False |
| "PSI validity check with rollback" (CHANGELOG) | No such code | ❌ False |
| "Cancellable background analysis" (CHANGELOG) | Webview dispatches `SET_ANALYSIS_PROGRESS`; `UIEvent.CancelAnalysisRequested` parses; but `GhostDebuggerService.analyzeProject` doesn't listen for a cancel signal or emit progress | 🟡 Partial — UI wired, service not |
| "Engine status pill (ONLINE/OFFLINE/DEGRADED/FALLBACK_TO_STATIC/DISABLED)" | `EngineStatus.kt` present, `onEngineStatus` wired in App.tsx, `StatusBar` renders it | ✅ Done |
| "Provenance badges distinguishing engine, local-AI, and cloud-AI findings" | `Issue.provenance` field exists but UI renders a single generic "AI" badge regardless of provider | 🟡 Partial |
| "Ollama (local) and OpenAI (cloud) with graceful fallback" | `AIServiceFactory` + `AiPassRunner` implement fallback | ✅ Done |
| "API keys via IntelliJ PasswordSafe" | `GhostDebuggerSettings.getApiKey` uses `PasswordSafe` | ✅ Done |
| "No telemetry, no cloud uploads unless explicitly configured" | `allowCloudUpload` gate works | ✅ Done |
| "AI response cache — in-memory, disable from Settings" (DATA_HANDLING.md) | Cache exists; **Settings has no toggle for it** | 🟡 Partial |
| "Cache TTL defaults to 1 hour, configurable in Settings" | Cache TTL is hard-coded; Settings has no field | ❌ False |
| "Targeted post-fix re-analysis covers the modified file only" (CHANGELOG) | `FixApplicator.applyFix` does not trigger any re-analysis | ❌ Not implemented |
| "Default provider is NONE" | `AIProvider.NONE` is the default — verified in settings | ✅ Done |
| "Very large repos (>500 files) are subject to the `maxFilesToAnalyze` cap" | Cap is applied in `FileScanner`; test `AnalysisEngineFileCapTest` covers it | ✅ Done |
| "Dark Navy + Cream UI with locally-bundled IBM Plex" | Theme tokens in CSS, fonts in `webview/public/fonts/` | ✅ Done |
| "NeuroMap visual project graph with per-file issue overlay" | Implemented in `NeuroMap.tsx`, issue overlay via `node.health` | ✅ Done |

**Scorecard:** 8 ✅ / 5 🟡 / 5 ❌ out of 18 V1 promises audited.

The gap analysis doc claimed 14 ✅ / 6 🟡 / 2 ❌. The earlier pass was too generous — it
didn't check marketplace/description claims against actual code, only whether a feature
"existed in some form."

---

## 4. Phase 6 spec corrections — what's already done

The `aegis_debug_phase_6_hardening_spec.md` proposed adding several items that are
actually already in the codebase. Whoever executes Phase 6 should treat these as
**already complete** and not re-do them.

| Phase 6 spec says "add" | Actual status |
|---|---|
| `DATA_HANDLING.md` at repo root | Exists (but content is aspirational — see §3) |
| `CHANGELOG.md` at repo root | Exists (but content is aspirational) |
| `plugin.xml` `since-build` / `until-build` | Present as `ideaVersion` in `build.gradle.kts:85-88` |
| `intellijPlatform.pluginConfiguration` with full marketplace description | Present at `build.gradle.kts:49-95` |
| `vendor { … }` block | Present at `build.gradle.kts:90-94` |
| `UIEvent.CancelAnalysis` case class | Present as `CancelAnalysisRequested` (`UIEvent.kt:25`) |
| `UIEventParser` handling `"CANCEL_ANALYSIS"` | Present (`UIEvent.kt:67`) |
| Webview `bridge.onAnalysisProgress` subscription | Present (`App.tsx:24`) |
| Webview `SET_ANALYSIS_PROGRESS` reducer action | Dispatched (`App.tsx:24`) |
| `StatusBar.analysisProgress` prop | Present (`App.tsx:86`) |

So Phase 6 has roughly 30–40% of its scaffolding already in place on the UI side. **The
missing half is on the Kotlin service side** — the plugin never actually emits progress,
never honors cancellation, and the docs describe features the code doesn't implement.

---

## 5. New gaps and code-quality findings (not in prior audit)

### 5.1 Settings UI is incomplete

`GhostDebuggerConfigurable.kt` is the only settings screen. Fields the settings model or
CHANGELOG/DATA_HANDLING reference but the UI does not expose:

- `cacheEnabled` (DATA_HANDLING: "disable via Settings")
- `cacheTtlSeconds` (DATA_HANDLING: "configurable in Settings")
- `aiTimeoutMs`
- `maxAiFiles`
- `autoAnalyzeOnOpen`
- `showInfoIssues`
- `analyzeOnlyChangedFiles`

All are referenced in spec or docs but invisible to the user. Either add the fields or
delete the promises.

### 5.2 "Test Connection" button is a placebo

`GhostDebuggerConfigurable.kt:51-64` renders a "Test Connection" button that on click shows
a dialog but **does not make any network call**. It's a UI decoration. Either wire it to
`AiPassRunner.pingProvider` or remove it.

### 5.3 Mutable fields on `Issue` model

`AnalysisModels.kt`:

```kotlin
data class Issue(
    …
    var explanation: String? = null,
    var suggestedFix: CodeFix? = null,
)
```

`var` on a data class used as a DTO across concurrent analyzers and the AI pass is a
concurrency smell. Make these `val` and use `copy(explanation = …)` at call sites.

### 5.4 Dead enum value

`IssueType.ASYNC_FLOW` is declared but **no analyzer ever produces an `Issue` with that
type**. `AsyncFlowAnalyzer` produces `IssueType.PROMISE_HANDLING` and
`IssueType.EFFECT_CLEANUP`. Either:
- Delete `IssueType.ASYNC_FLOW`, or
- Rename one of the two live types to `ASYNC_FLOW` and delete the loser.

### 5.5 Unused models

`MultiFileFix` and `BrokenNeighborhood` (in `AnalysisModels.kt`) are declared but never
constructed anywhere in the codebase. V1 §4.3 mentions them as post-V1. They should either
move to a `post_v1.kt` file with a comment, or be deleted until needed.

### 5.6 FileScanner scans 9 languages it can't analyze

`FileScanner.kt` accepts `.py .go .rs .cs .rb .swift .php` and adds them to the graph.
Zero analyzers in `src/main/kotlin/com/ghostdebugger/analysis/` look at these files
(except for complexity/cycle detection, which operates on the graph, not the source).
Every file in these languages is read, tokenized for the graph, and then ignored. On a
polyglot monorepo this is pure waste.

Either:
- Add real analyzers for these, or
- Remove them from `FileScanner.supportedExtensions` and cap scanning to `.kt .java .ts .tsx .js .jsx`.

### 5.7 Legacy `ghost.svg` still shipping

`src/main/resources/icons/ghost.svg` is still in the repo after the GhostDebugger → Aegis
Debug rebrand. `plugin.xml` no longer references it. It ships in the jar for nothing.
Delete.

### 5.8 `README.md` still a "temporary" placeholder

The `README.md` at repo root is labeled "temporary" and describes the product as "under
development." For a GA release that's linked from marketplace, it needs a rewrite that
mirrors the marketplace description in `build.gradle.kts`.

### 5.9 Version mismatch

- `build.gradle.kts:9`: `version = "0.1.0"`
- `CHANGELOG.md`: `[1.0.0] — 2026-04-XX`
- `DATA_HANDLING.md`: `Product version: 1.0.0`

Docs claim 1.0.0, code ships 0.1.0. Bump to `1.0.0` when the GA is real.

### 5.10 Placeholder dates in docs

Both `CHANGELOG.md` and `DATA_HANDLING.md` have `2026-04-XX` — the `XX` day must be
resolved to the real release date. Today is 2026-04-15, pick a date.

### 5.11 `GhostDebuggerAnnotator` has no cancellation check

The `ExternalAnnotator` at `GhostDebuggerAnnotator.kt` surfaces `service.currentIssues` on
every file open / edit. It never checks `ProgressManager.checkCanceled()`. On a huge file
with hundreds of issues, the annotator can block indexing. Add the check.

### 5.12 `AnalyzeProjectAction` is ~18 lines with no cancellation surface

`AnalyzeProjectAction.kt` just calls `GhostDebuggerService.analyzeProject()` inline. If
the user hits "Analyze" twice, there's no debounce — a second analysis starts on top of
the first. Add an `isAnalyzing` guard or wire through the already-defined `CancelAnalysisRequested`.

---

## 6. Revised Phase 6 backlog

The Phase 6 spec's P0/P1/P2/P3 labels are still useful; the items below are **what's
actually missing** after reconciliation.

### P0 — release blockers

1. **Strategic language-scope decision** (Option A vs B in §1). Everything else depends
   on this.
2. **Make marketplace description match reality** — change `build.gradle.kts` description
   if Option B is chosen.
3. **Actually wire cancellation end-to-end** — webview emits, service listens,
   `AnalysisEngine` checks `ProgressManager.checkCanceled()` in its main loop.
4. **Actually emit progress** — `AnalysisEngine` calls a progress callback that reaches
   `bridge.onAnalysisProgress`. Webview wiring already exists.
5. **Implement PSI-validity check on fix apply** (or its regex equivalent in Option B) —
   `CHANGELOG.md` already advertises this as 1.0.0.
6. **Bump version 0.1.0 → 1.0.0** in `build.gradle.kts`.
7. **Resolve `2026-04-XX`** in `CHANGELOG.md` and `DATA_HANDLING.md`.

### P1 — quality critical

8. **Port or relabel fixers** — PSI (Option A) or relabel as "deterministic line fixers"
   (Option B). Either way, add the parse-check-on-apply.
9. **Cache toggle** — `cacheEnabled` field in settings UI + wire to `AIResponseCache`.
   DATA_HANDLING already promises this.
10. **Delete dead code** — `IssueType.ASYNC_FLOW`, `MultiFileFix`, `BrokenNeighborhood`,
    `ghost.svg`.
11. **Trim `FileScanner`** to languages with actual coverage.
12. **Fix "Test Connection" button** — wire to a real provider ping, or remove.
13. **Clean up `Issue` mutable fields** — `var` → `val` + `copy()`.
14. **Rewrite `README.md`** for GA.
15. **Add `ProgressManager.checkCanceled()`** in `GhostDebuggerAnnotator`.
16. **Add debounce** in `AnalyzeProjectAction`.

### P2 — pre-GA polish

17. **Provenance badge split** — render distinct badges for `ENGINE`, `LOCAL_AI`,
    `CLOUD_AI` instead of a single "AI" badge.
18. **Settings fields for `aiTimeoutMs`, `maxAiFiles`, `autoAnalyzeOnOpen`, `showInfoIssues`,
    `analyzeOnlyChangedFiles`, `cacheTtlSeconds`** — or delete from the settings model.
19. **Targeted post-fix re-analysis** — re-run analysis on the single modified file after
    `FixApplicator.applyFix` (CHANGELOG promises this).
20. **Integration test fixtures** — at least one `NullSafetyFixture.*` per supported
    language, gated by Option A vs B.

### P3 — post-GA

21. Kotlin/Java PSI analyzer port (if Option B shipped).
22. `MultiFileFix` implementation.
23. `BrokenNeighborhood` detection.
24. Analyzers for Python/Go/Rust/C#/Ruby/Swift/PHP — or remove from `FileScanner`.

---

## 7. Acceptance criteria for true V1 GA

Before bumping `build.gradle.kts` to `1.0.0` and publishing to the marketplace, every line
below must be true:

- [ ] `plugin.xml` / marketplace description names languages the plugin actually analyzes
  meaningfully, not aspirationally.
- [ ] "PSI-based fixers" is either accurate (Option A) or the phrase is removed (Option B).
- [ ] `AnalysisEngine` respects `ProgressManager.checkCanceled()` at least once per file.
- [ ] `GhostDebuggerService.analyzeProject` emits progress events to the UI bridge.
- [ ] `FixApplicator.applyFix` verifies the post-edit file parses (PSI or tokenizer) and
  rolls back on failure. Unit test covers both success and rollback paths.
- [ ] `cacheEnabled` is a real settings toggle, respected by `AIResponseCache`.
- [ ] Every claim in `DATA_HANDLING.md` maps to code.
- [ ] Every item in `CHANGELOG.md` 1.0.0 section maps to code.
- [ ] `build.gradle.kts` version is `1.0.0`.
- [ ] `CHANGELOG.md` and `DATA_HANDLING.md` dates are real.
- [ ] `README.md` is not labeled "temporary" and matches the marketplace description.
- [ ] `ghost.svg` deleted.
- [ ] Dead enum `IssueType.ASYNC_FLOW` deleted or wired.
- [ ] `Issue.explanation` and `Issue.suggestedFix` are `val`.
- [ ] At least one integration test per analyzer hits a realistic fixture file, not a
  hand-built `CodeNode` mock.
- [ ] "Test Connection" button either works or is gone.
- [ ] `FileScanner.supportedExtensions` matches the set the plugin actually analyzes.

When all 16 boxes are ticked, the plugin is honest, reviewable, and shippable as 1.0.0.

---

## 8. Open questions for the team

1. **Option A vs B.** This is the single largest decision. My recommendation is B for 1.0.0
   and A for 1.1.0 — but that's a product call, not a tech one.
2. **Do we keep `MultiFileFix` / `BrokenNeighborhood` in the tree as dead code, or move
   them to a `post_v1` branch until they're needed?** Dead code attracts `TODO` comments
   and drifts from reality.
3. **`AIResponseCache` TTL** — DATA_HANDLING claims "1 hour, configurable." If the real
   implementation uses a different TTL, who gets changed: the code or the doc?
4. **Post-fix re-analysis scope** — CHANGELOG says "modified file only." Is that still the
   right call, or should we re-analyze the modified file + its direct dependents?

---

## 9. What to cut from scope if the GA deadline is hard

If shipping by end of April 2026 is non-negotiable, cut in this order:

1. Drop Option A entirely — rewrite the marketplace description to JS/TS primary. (~2 days)
2. Skip per-language integration fixtures — rely on unit tests. (~1 day)
3. Defer `cacheTtlSeconds`, `aiTimeoutMs`, `maxAiFiles` settings fields — hard-code values,
   delete from `DATA_HANDLING.md`. (~0.5 day)
4. Defer provenance badge split — keep the single "AI" badge. (~0.5 day)
5. Defer "Test Connection" — remove the button. (~0.5 day)

That leaves the irreducible core: cancellation, progress, parse-check-on-fix, honest
copy, dead-code deletion, version bump. Roughly 4–6 focused days if the language-scope
decision is Option B.
