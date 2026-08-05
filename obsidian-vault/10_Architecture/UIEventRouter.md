---
title: "UIEventRouter"
type: "architecture"
status: "active"
related_components:
  - "[[GhostDebuggerService]]"
  - "[[NeuroMap_Webview]]"
aliases: []
tags:
  - aegis-debug
  - kotlin
---

# UIEventRouter

`UIEventRouter` is one of the four main collaborators managed by [[GhostDebuggerService]].

## Responsibilities
- Manages UI event dispatching between the IDE backend and the frontend JCEF bridge.
- Provides caching for the AI service responses to prevent redundant requests.
- Routes commands from the user clicking on the [[NeuroMap_Webview]] back into IntelliJ platform actions (e.g., opening a file, applying a fixer).
- Serializes payloads using `kotlinx.serialization` (to prevent JS injection vectors that were patched in V1.4.1).
