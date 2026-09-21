---
title: "UIEventRouter"
type: "architecture"
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
- Caches the *resolved `AIService` instance* (`currentAiService()`, `UIEventRouter.kt:102-109`), re-resolving it only when the live settings snapshot changes. This avoids re-resolving the provider/API key on every call — it is not response caching. Per-response caching (issue/system explanations) is owned by [[BaseAIService]]'s `AICache`, not by this router.
- Routes commands from the user clicking on the [[NeuroMap_Webview]] back into IntelliJ platform actions (e.g., opening a file). Applying a fixer, requesting an AI explanation, and impact analysis are gated in 3.0.0 — see below.
- Delegates to `service().jcefBridge()` / `bridgeChannel()` to reach the browser. Serialization (`kotlinx.serialization`, patched for JS-injection safety in V1.4.1) is implemented in [[JcefBridge_and_BridgeChannel]] — this router has no `kotlinx.serialization` usage of its own; it only calls the `send*` methods that class implements.

## Gated in 3.0.0
`handle()` blocks before dispatch via `gatedCapabilityFor` (`UIEventRouter.kt:452-458`): `FixRequested`/`ApplyFixRequested` need `AegisCapability.FIX_APPLICATION`, `ExplainSystemRequested` needs `AI_EXPLANATION`, and `ImpactRequested` needs `JVM_DEPENDENCY_GRAPH` — all three ship disabled in 3.0.0, so a user hits the roadmap dialog instead of the handler. `NodeClicked` itself is deliberately ungated — `handleNodeClicked` can still surface an issue's already-cached explanation with no gate check — but calling the AI to produce a *new* explanation is separately gated inline, later in the same function (`UIEventRouter.kt:135`). Opening a file is a different event, `NodeDoubleClicked` → `handleNodeDoubleClicked`, which is not in `gatedCapabilityFor` at all and is fully ungated.
