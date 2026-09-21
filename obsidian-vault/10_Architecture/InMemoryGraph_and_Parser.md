---
title: "InMemoryGraph and Parser"
type: "architecture"
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

## Dependency Resolution (edges the graph actually gets)
- `DependencyResolver.resolve` only turns an import into an internal graph edge when `isRelativeImport` (`DependencyResolver.kt:56-58`) is true — the source starts with `./` or `../`. Everything else (including every fully-qualified Kotlin/Java import) becomes an `ext:`-prefixed external node with no edge back into the project graph.
- Practical effect: internal edges resolve for TypeScript/JavaScript only. A Kotlin- or Java-only project produces a graph with nodes but zero internal edges, so `findCycles`/`calculateImpact` above have nothing to walk for those languages. This is why `JVM_DEPENDENCY_GRAPH` ships gated in 3.0.0 — the graph and cycle-detection code themselves are correct and unchanged; only their reach for JVM languages is limited.
