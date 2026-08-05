---
title: "Project Principles"
type: "concept"
status: "active"
related_components: []
aliases:
  - "Principles"
tags:
  - aegis-debug
---

# Project Principles

These are the guiding principles of the Aegis Debug project. They apply to every change and must never be violated without explicit user opt-in.

1. **Static-first, AI-optional.** AI never becomes load-bearing for correctness. Deterministic engines decide; AI augments.
2. **Privacy by default.** No telemetry. No cloud unless the user explicitly enabled it. No "anonymous" identifiers either — that's still telemetry.
3. **Deterministic fixes only.** If a fixer can't guarantee PSI validity, it returns `null` and the AI fallback handles it. No "best effort" fixes.
4. **Provenance always visible.** `STATIC` / `AI_LOCAL` / `AI_CLOUD` / `RUNTIME_CONFIRMED` stays labeled on every finding the user sees.
5. **Conservative-miss bias.** When an analyzer can't decide (unresolved symbol, `KaErrorType`, ambiguous type), it must *not* flag. False positives cost more trust than false negatives.

Any feature that breaks one of these needs an explicit, user-visible opt-in.
