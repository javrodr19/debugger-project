---
title: "InMemoryGraph and Parser"
type: "architecture"
status: "active"
related_components:
  - "[[NeuroMap_Webview]]"
  - "[[AnalysisOrchestrator]]"
aliases:
  - "InMemoryGraph"
tags:
  - aegis-debug
  - graph
  - parser
---

# InMemoryGraph and Parser

`InMemoryGraph` represents the in-memory dependency graph of the project, used to drive NeuroMap visualizations and circular dependency detection.

## Concurrency & Performance Updates (V1.4.1)
- **Thread Safety**: Adjacency lists use `ConcurrentHashMap.newKeySet()` to prevent race conditions during concurrent edge insertions by multiple analyzer threads.
- **Cycle Detection**: `findCycles` was rewritten as an iterative DFS with an explicit stack to prevent `StackOverflowError` when analyzing large monorepos with deep dependency chains.

## Symbol Parsing
- Uses language-specific PSI parsers (`KotlinPsiSymbolExtractor`, `JavaPsiSymbolExtractor`) for JVM languages, and a hardened regex scanner for TS/JS files.
