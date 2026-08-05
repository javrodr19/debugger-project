---
title: "NeuroMap Webview"
type: "architecture"
status: "active"
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
- Provides visual badges distinguishing between engine-verified and AI-suggested results (Provenance Tracking).
- Hotspots get highlighted live as the debugger steps through them (V4 feature).
