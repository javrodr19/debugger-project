---
title: "Roadmap V2 to V5"
type: "meta"
related_components: []
aliases:
  - "Roadmap"
tags:
  - aegis-debug
---

# Roadmap: V2 to V5

This is the high-level north star for Aegis Debug.

## V2: Dynamic validation + language breadth + IDE-native integration `[Landed in 3.0.0, mostly gated — see IMPLEMENTATION_STATUS.md]`
Theme: Prove that the static findings are real.

V2 was never tagged and shipped on its own — per `CHANGELOG.md`'s 3.0.0 entry, this content
reaches a user for the first time in 3.0.0, and most of it ships disabled behind
`AegisCapabilityGate`.

- **Dynamic validation pass**: correlate static findings with runtime paths; adds the
  `RUNTIME_CONFIRMED` provenance tier. The test-runner cross-check (`TestRunObserver`) runs
  unconditionally; the debug-session cross-check is **gated** under `DEBUGGER_CROSS_CHECK`
  (runtime confirmation from a paused debug session isn't reachable on IntelliJ IDEA Community —
  test-suite cross-check remains the active path).
- **IntelliJ Problems tool window integration** — **gated** under `PROBLEMS_VIEW_EMIT`
  (publishing to the native Problems view violates the platform's threading contract on 2024.3).
- **Quick-fix intention actions** (`Alt+Enter`) — implemented (`AegisQuickFixIntentionAction`),
  but its `invoke()` routes through the same `AnalysisOrchestrator.applyVerifiedFix` gate as
  "Apply Fix" (`AnalysisOrchestrator.kt:499`), so it's unreachable while `FIX_APPLICATION` is
  gated.
- **Streaming AI responses** — implemented at the transport layer
  (`BaseAIService`/`OllamaService`/`OpenAIService.callModelStreaming`), but unreachable in 3.0.0
  because both capabilities that would call it, `AI_ANALYSIS` and `AI_EXPLANATION`, are gated (no
  AI provider is reachable in this release).
- *(Explicit non-goals: team sync, cross-repo validation).*

## V3: Fixer breadth + custom rule authoring `[Landed in 3.0.0, mostly gated — see IMPLEMENTATION_STATUS.md]`
Theme: Extend the fix catalog and let power users define rules.

Same caveat as V2: this is 3.0.0-dev content that reached a user for the first time in the
3.0.0 release, not a release of its own.

- **AI-supervised fix engine**: `FixEngine` + `FixPlanApplicator` supervising deterministic
  operations, wired into production at `AnalysisOrchestrator.kt:497` — **gated** under
  `FIX_APPLICATION` (implemented and tested; fix application itself is not enabled in 3.0.0).
- **Custom rule authoring**: YAML definition of rules in `.aegis/rules/*.yml` (V3.1) — **not
  gated**, the one V3 item that ships live: it runs unconditionally as one of the registered
  analyzers.
- **Rule packs**: Curated & project packs in `.aegis/packs/*.yml` (V3.2) — **gated** under
  `RULE_PACKS` (the three bundled packs can't currently match real code).
- **Fix Preview UX**: Line/hunk diffs & interactive Swing dialog (V3.3) — not gated, but has
  zero callers outside its own declaration ("implemented, no consumer" in
  `docs/IMPLEMENTATION_STATUS.md`) — a different status from being turned off.
- **Analyzer author SDK**: Dynamic `.jar` analyzer plugins in `.aegis/analyzers/` (V3.4) —
  **gated** under `EXTERNAL_ANALYZERS` (the loader exists; no published SDK artifact or
  documented third-party contract yet).

## V4: Debug-time UX
Theme: A debug session that actively teaches the user where to look.
- **Breakpoint-aware relevance ranking** in the detail panel.
- **Variable-at-breakpoint AI explanations**.
- **Call-stack hotspot overlay** on NeuroMap.
- **Profiler correlation**.

## V5: Team / multi-repo scale
Theme: Organizations running Aegis across many repos (without centralizing source code).
- **Cross-repo graph**.
- **Shared rule configs**.
- **Audit log export**.
- **CLI / CI runner**.

## Not on Roadmap
- Web-based version.
- Mobile language support.
- Auto-apply fixes.
- Cloud hosting of customer code.
