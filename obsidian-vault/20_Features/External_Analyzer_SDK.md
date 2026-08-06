---
title: "External Analyzer SDK (V3.4)"
type: "feature"
status: "active"
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

## Core Design
- **Isolated Loading**: `ExternalAnalyzerLoader` loads external JARs via an isolated `URLClassLoader`.
- **Fault Isolation**: Runtime exceptions in third-party analyzers are safely caught and logged without aborting project analysis.
- **PCE Protection**: `ProcessCanceledException` is explicitly rethrown to satisfy platform cancellation invariants.
- **Provenance Stamping**: Findings produced by external SDK analyzers carry the `IssueSource.EXTERNAL_SDK` tag.
