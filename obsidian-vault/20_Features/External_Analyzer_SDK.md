---
title: "External Analyzer SDK (V3.4)"
type: "feature"
related_components:
  - "[[Static_Analyzers]]"
  - "[[GhostDebuggerService]]"
tags:
  - feature
  - external-sdk
  - aegis-debug
---

# External Analyzer SDK (V3.4)

The External Analyzer SDK enables third-party developers to package custom `Analyzer` implementations into `.jar` files and drop them into a project's `.aegis/analyzers/` directory.

> **Gating status (3.0.0):** gated by `AegisCapability.EXTERNAL_ANALYZERS`. `AnalysisEngine.doStaticPasses`
> checks `AegisCapabilityGate.skipIfGated(AegisCapability.EXTERNAL_ANALYZERS)` (`AnalysisEngine.kt:120`)
> and short-circuits to an empty list before `externalAnalyzerRunner` — and therefore
> `ExternalAnalyzerLoader` — ever runs. The loader and its isolation mechanics below are real and
> exercised by tests, but no external analyzer JAR is loaded during a normal "Analyze Project" pass
> in this release. Verbatim from `AegisCapability.kt`: "The loader exists, but there is no published
> SDK artifact and no documented contract for third-party analyzers yet."

## Core Design
- **Isolated Loading**: `ExternalAnalyzerLoader` loads external JARs via an isolated `URLClassLoader`.
- **Fault Isolation**: Runtime exceptions in third-party analyzers are safely caught and logged without aborting project analysis.
- **PCE Protection**: `ProcessCanceledException` is explicitly rethrown to satisfy platform cancellation invariants.
- **Provenance Stamping**: Findings produced by external SDK analyzers carry the `IssueSource.EXTERNAL_SDK` tag.
