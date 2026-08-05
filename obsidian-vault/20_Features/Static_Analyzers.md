---
title: "Static Analyzers"
type: "feature"
status: "active"
related_components:
  - "[[AnalysisOrchestrator]]"
aliases:
  - "Analyzers"
tags:
  - aegis-debug
---

# Static Analyzers

Aegis Debug is **Static-first**. Deterministic engines decide; AI augments.

## Core Rules
- **Conservative-miss bias:** If an analyzer can't decide (e.g., unresolved symbol, `KaErrorType`), it must NOT flag. False positives cost more trust than false negatives.
- Kotlin Analyzers MUST use `parser/KotlinAnalysisHelpers.withKtAnalysis` to safely interact with the Kotlin Analysis API.

## Implemented Analyzers
Aegis Debug currently provides 11 deterministic analyzers covering:
- Null Safety
- State Initialization
- Async Flow
- Circular Dependencies
- Complexity
- Compilation Error Harvesting
- Syntax Error Harvesting

See [[Project_Principles]] for how these analyzers operate under strict privacy and correctness rules.
