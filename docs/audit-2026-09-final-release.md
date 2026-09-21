# Final Release Audit — 2026-09

**Date:** 2026-09-14 (audit), disposition recorded 2026-09-20 at the close of the release-gate work.
**Scope:** the full plugin surface — sixteen read-only surface audits, run in parallel, every
verdict required to cite `file:line`. The highest-stakes claims (the cloud-upload consent bypass,
the JVM-graph edge count, the dead-action findings) were independently re-verified by a second
pass before being accepted.
**Result at audit time:** 3 surfaces **WORKS**, 13 **PARTIAL**. Zero surfaces were found fully
broken; the dominant pattern was real, tested infrastructure whose last mile to the user was never
closed, plus several capabilities the documentation described as shipped when they were not wired
to anything reachable.
**This document replaces `docs/audit-2026-06-post-v2.md`.** That document recorded an all-PASS
self-certification across two narrower lenses (CLAUDE.md invariants, platform-API misuse) over 52
files. It was not wrong on its own terms — the six findings it recorded really were PASS under
those two lenses — but its scope was much narrower than a full-surface audit, and it asked a
different question. The difference between "all PASS" and "3 works / 13 partial" is scope and
adversarial framing, not a regression introduced between June and September.

Every citation below was re-verified against the current tree (commit range `b1bc5c8..e8c6a21` on
`feat/final-release-gate`) rather than copied from the original audit run, because ten remediation
tasks changed line numbers and, in several cases, the underlying facts between the audit and this
writeup.

## Verdict table

| # | Surface | Verdict (at audit) | Evidence | Disposition |
|---|---|---|---|---|
| 1 | Deterministic static analysis engine (12 analyzers: TS/JS/Kotlin/Java) | **WORKS** | `analysis/AnalysisEngine.kt:40-53` | No action needed — unconditional, core to the product. |
| 2 | Plugin packaging & webview resource resolution | **WORKS** | `toolwindow/NeuroMapPanel.kt:209-217` (`isPluginWebJar` — filename filter **and** a `jar.getJarEntry("web/index.html") != null` content check, so selection is deterministic even with two similarly-named JARs in `lib/`) | No action needed. Task 10 independently confirmed zero changes to this predicate across its cleanup diff. |
| 3 | Secure AI-provider key storage | **WORKS** | `ai/ApiKeyManager.kt` (IntelliJ `PasswordSafe`-backed; never written to `ghostdebugger.xml`) | No action needed. |
| 4 | Fix application (deterministic fixers + AI-supervised fix engine) | PARTIAL | `AnalysisOrchestrator.kt:499`; of 8 registered fixers (`fix/FixerRegistry.kt`), the flagship `NullSafetyFixer` does not fire on real analyzer output; `FixPreviewDialog`/`BatchFixPreview`/`FixDiffGenerator` (`fix/engine/`) are dead UI; `ApplyAllFixesAction`'s original body was an unconditional no-op (`Issue.suggestedFix` is never assigned) | **Gated** under `FIX_APPLICATION`. Dead preview UI recorded in `docs/IMPLEMENTATION_STATUS.md` under "Implemented, no consumer." |
| 5 | AI explanation (issue / system) | PARTIAL | `UIEventRouter.kt:135`, `actions/ExplainSystemAction.kt:15` | **Gated** under `AI_EXPLANATION`. |
| 6 | AI analysis pass | PARTIAL | `AnalysisEngine.kt:215` | **Gated** under `AI_ANALYSIS`. |
| 7 | JVM dependency graph (cycle detection, impact analysis for Kotlin/Java) | PARTIAL | `parser/DependencyResolver.kt:56-58` — `isRelativeImport` only creates an edge for specifiers starting `./` or `../`; Kotlin/Java imports are fully-qualified, so every JVM project graph is edgeless | **Gated** (the `ImpactRequested` action) under `JVM_DEPENDENCY_GRAPH`. The analyzer itself is correct and not gated — it works on TS/JS. Documented as a known limitation; the webview now shows an in-app notice instead of a silently edgeless graph (`webview/src/components/neuromap/NeuroMap.tsx`). |
| 8 | Rule packs (pre-packaged bundles) | PARTIAL | `rules/RulePackService.kt:69`; three bundled packs at `src/main/resources/rules/packs/{kotlin-coroutines,node-security,react-strict}.yml` | **Gated** under `RULE_PACKS`. Recorded as "implemented, no consumer" in `docs/IMPLEMENTATION_STATUS.md`. |
| 9 | External analyzer SDK | PARTIAL | `AnalysisEngine.kt:120`; `analysis/sdk/ExternalAnalyzerLoader.kt` — loader exists, no published SDK artifact or contract | **Gated** under `EXTERNAL_ANALYZERS`. |
| 10 | Problems tool window integration | PARTIAL | `ProblemsViewCoordinator.kt:38`; `wolf.reportProblems(...)` was being called from the EDT in a way that violates the platform's 2024.3 threading contract | **Gated** under `PROBLEMS_VIEW_EMIT`. |
| 11 | Debugger cross-check (live paused-session confirmation) | PARTIAL | `store/DebugObserver.kt`; `DebugSessionCoordinator.kt:153-154` | **Gated** under `DEBUGGER_CROSS_CHECK`. Test-suite cross-check (`TestRunObserver`) is a separate, ungated mechanism and remains active. See "the critical finding" below for how this gate was itself found broken during implementation. |
| 12 | Plugin action suite — 4 of 13 actions were silent no-ops | PARTIAL | `ShowInNeuroMapAction` used tool-window id `"Aegis Debug"` against a window registered as `"GhostDebugger"`; `ReanalyzeFileAction` called `analyzeProject()` instead of `reanalyzeFile(path)`; `SuppressFindingAction` recorded a dismissal instead of calling `suppressNow`, so the explicit "suppress" command still needed 3 dismissals; `ExportReportAction`'s success path called a null bridge instead of a notification | **Fixed in Task 7.** Now at `actions/ShowInNeuroMapAction.kt:15`, `actions/ReanalyzeFileAction.kt:14`, `actions/SuppressFindingAction.kt:21`, `ReportExporter.kt:61,74`. |
| 13 | Cloud-upload consent enforcement | PARTIAL | `settings.allowCloudUpload` was read in exactly one production site (`AnalysisEngine.kt:220`); both `resolveAiService()` methods (`UIEventRouter`, `AnalysisOrchestrator`) constructed `OpenAIService` without consulting it — the explain and fix paths could upload code to OpenAI without the consent gate the README advertises | **Fixed in Task 9.** Consent check centralized once, at the single chokepoint every resolver goes through: `ai/AIServiceFactory.kt:26-29`. |
| 14 | Ollama streaming-flag encoding | PARTIAL | `stream = false` was silently dropped from the request body because `kotlinx.serialization`'s `encodeDefaults` is `false` by default, so Ollama defaulted to streaming when the code asked it not to | **Fixed in Task 9.** `@EncodeDefault(EncodeDefault.Mode.ALWAYS)` at `ai/OllamaModels.kt:18-19`. |
| 15 | Settings panel | PARTIAL | Five controls had zero production read sites: `autoAnalyzeOnOpen`, `showInfoIssues`, `analyzeOnlyChangedFiles`, `coverageMode`, `nudgeShownOnce` | **Fixed in Task 8** — all five removed from `GhostDebuggerSettings.State` and their `Configurable` plumbing. `showUnreached` was investigated and found plumbed end-to-end (`bridge/JcefBridge.kt:133,146` → `webview/src/stores/appStore.ts`) with no leaf consumer — a different defect from having no read site — so it was kept and recorded as "implemented, no consumer" rather than deleted. |
| 16 | Documentation itself | PARTIAL | "Eleven deterministic analyzers" / "five deterministic fixers" in `README.md`, `plugin.xml`, `site/index.html` (registries hold 12 and 8); README's own self-contradiction ("eleven" vs. "Five core analyzers"); "Full static analysis" claimed for TS/JS despite `parser/SymbolExtractor.kt:6-14`'s own comment describing regex-only analysis; unregistered keyboard shortcuts; dead-UI features advertised in the changelog; `CHANGELOG.md` stuck at an untagged "[Unreleased] — 2.0.0"; `DATA_HANDLING.md` stamped 1.0.0; `AGENTS.md`'s repository layout stale (an `annotator/` package that no longer exists; `fix/engine/`, `store/`, `rules/`, `analysis/sdk/` missing); this document's predecessor recording all-PASS | **Fixed in Task 11** (this task) — the corrections in this commit range, plus this document and `docs/IMPLEMENTATION_STATUS.md`. |

## The critical finding: a gate that gated nothing

The single highest-value finding of the whole release-gate effort surfaced during implementation,
not during the original audit, and is worth recording here because it is the same failure mode
the audit exists to catch, found *inside the mechanism built to fix it*.

`DebugObserver`'s capability guard was originally placed as the first line of `start()`. But
`start()` only logs; the platform subscription that actually starts watching debug sessions runs
inside `init { }`, at service construction — which happens *before* `start()` is ever called
(`GhostDebuggerService.kt:123` calls `getInstance(project).start()`, and `getInstance` constructs
the service, running `init` first). The guard was suppressing one log line while
`SessionWatcher.sessionPaused()` → `evaluateRelevantFindingsAtCurrentFrame()` →
`RuntimeEvidenceStore.record()` ran unconditionally underneath it — exactly the behavior
`DEBUGGER_CROSS_CHECK` exists to stop. Fixed by moving the guard to the first line of
`evaluateRelevantFindingsAtCurrentFrame()` (`store/DebugObserver.kt:73-74`), the method every path
into the pipeline converges on. See the project ledger for the full trace; recorded here because a
capability gate that gates only a log line is a documentation lie wearing a gate's clothes, and
this release's entire premise is that such things get caught.

## Non-findings — explicitly out of scope

Recorded so the omissions are decisions, not oversights (unchanged from the design spec):

- Repairing the gated features themselves — `DependencyResolver`'s relative-imports-only edge
  resolution, the compile-gate shadowing of two Kotlin rules, the three unmatchable rule packs,
  the absent external SDK, the Problems-view threading contract, and `RuntimeEvidenceStore`
  persistence all stay gated and unfixed. Each is a project in its own right.
- Analyzer precision work (false positives in `NullSafetyAnalyzer`'s lookback window and in
  Kotlin scope-function / generic-type cases) — documented, not fixed.
- Publishing to the JetBrains Marketplace — `release.yml` deliberately omits `publishPlugin`.
