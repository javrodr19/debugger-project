---
title: "NeuroMap Webview"
type: "architecture"
related_components:
  - "[[UIEventRouter]]"
aliases:
  - "NeuroMap"
tags:
  - aegis-debug
  - frontend
  - react
---

# NeuroMap Webview

The NeuroMap is a visual project graph that highlights hotspots, circular dependencies, and complex architecture.

## Responsibilities & Stack
- Built using React + TypeScript under the `webview/` directory.
- Runs in the JetBrains Chromium Embedded Framework (JCEF).
- Interacts with the backend via the `bridge/` logic, handled on the Kotlin side by [[UIEventRouter]].
- Provides visual badges distinguishing between engine-verified and AI-suggested results (Provenance Tracking) — `ProvenanceBadge` in `webview/src/components/detail-panel/DetailPanel.tsx` switches on `IssueSource` (`STATIC`, `AI_CLOUD`, `AI_LOCAL`, `RUNTIME_CONFIRMED`, ...). In 3.0.0 the `AI_CLOUD`/`AI_LOCAL` states are unreachable in practice: `AI_ANALYSIS` is gated off (`AnalysisEngine.kt:215`), so the deterministic pipeline never emits an AI-sourced issue. `STATIC` (always), `CUSTOM` (from user-authored `.aegis/rules/*.yml`, ungated — `CustomRuleAnalyzer.kt:69`), and `RUNTIME_CONFIRMED` (via the still-active test-suite cross-check — see [[DebugSessionCoordinator]]) remain reachable. The component itself is unchanged and would render the AI badge correctly if that gate ever lifts.
- Hotspots get highlighted live as the debugger steps through them (V4 feature).
