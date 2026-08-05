---
title: "JcefBridge and BridgeChannel"
type: "architecture"
status: "active"
related_components:
  - "[[UIEventRouter]]"
  - "[[NeuroMap_Webview]]"
aliases:
  - "JcefBridge"
  - "BridgeChannel"
tags:
  - aegis-debug
  - bridge
  - jcef
---

# JcefBridge and BridgeChannel

`JcefBridge` manages bi-directional communication between the Kotlin backend and the React webview running inside JCEF.

## Key Properties & Changes
- **`BridgeChannel` Interface**: Extracted in V1.2 to allow headless unit tests to substitute a recording stub without launching a real JCEF browser instance.
- **Serialization Security**: Hand-built JSON construction was replaced in V1.4.1 with `kotlinx.serialization` across all payload senders. This prevents backslashes, newlines, and control characters from causing JavaScript syntax errors or code injection vectors inside the browser engine.
