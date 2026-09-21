---
title: "Changelog"
type: "meta"
related_components: []
aliases: []
tags:
  - aegis-debug
---

# Changelog

A high-level summary of Aegis Debug releases. For full details, see the raw `CHANGELOG.md` in the project root.

## 3.0.0 — 2026-09-20 — Final release: capability gate, seven repairs, honest documentation
The first tagged, released version since 1.5.0. Everything under [[Roadmap|V2 and V3]] (dynamic
validation, fix engine, custom rules, rule packs, fix-preview UX, external SDK) is real and
tested, but reaches a user for the first time in this release — none of it was ever tagged and
shipped as its own 2.0.0 or 3.0.0-dev version — and most of it ships **disabled**.
- **Capability gate**: eight implemented, tested capabilities gated off behind one chokepoint,
  `AegisCapabilityGate` (`FIX_APPLICATION`, `AI_EXPLANATION`, `AI_ANALYSIS`,
  `JVM_DEPENDENCY_GRAPH`, `RULE_PACKS`, `EXTERNAL_ANALYZERS`, `PROBLEMS_VIEW_EMIT`,
  `DEBUGGER_CROSS_CHECK`) rather than shipped half-finished. See [[Roadmap]] and
  `docs/IMPLEMENTATION_STATUS.md`'s Gated table for the full list and why.
- **Seven repaired defects**: a cloud-upload consent bypass (both AI-resolver call sites could
  construct `OpenAIService` without checking `allowCloudUpload`; now centralized in
  `AIServiceFactory.create`); four silently-broken actions that each did something other than
  what their label promised (Show in NeuroMap, Reanalyze Current File, Suppress Finding, and the
  report-export success notification); the Ollama `stream = false` flag (silently dropped by
  `kotlinx.serialization`'s default `encodeDefaults = false`, so Ollama streamed even when asked
  not to); and a capability guard that gated a log line while the behavior it was meant to gate
  (`RuntimeEvidenceStore.record` on every debugger pause) kept running underneath it.
- **Dead code deleted**: five settings with zero production read sites removed
  (`autoAnalyzeOnOpen`, `showInfoIssues`, `analyzeOnlyChangedFiles`, `coverageMode`,
  `nudgeShownOnce`).
- **Documentation reconciled against measured counts**: analyzer/fixer counts corrected (12
  analyzers, 8 fixers — not the prior "eleven"/"five") across README, `plugin.xml`, and the
  site; a test now pins these counts to the registries so they can't drift apart again.

Custom rule authoring (V3.1) is the one V2/V3 item that ships live and ungated. For the complete
entry, see the raw `CHANGELOG.md` in the project root.

## 1.5.0 — Pre-V2 structural refactor
- Pure structural release (no user-visible change).
- `GhostDebuggerService` shrunk to a thin facade.
- Four new project-scoped services extracted: [[AnalysisOrchestrator]], `UIEventRouter`, `FileChangeWatcher`, `DebugSessionCoordinator`.
- `BaseAIService` extracted to reduce duplication.

## 1.4.1 — Audit-driven fixes
- Honored cancellation (`ProcessCanceledException`).
- JCEF tool-window payload serialization via `kotlinx.serialization` to avoid JS injection.
- Thread-safety fixes in `InMemoryGraph` and `findCycles` loop.
- `NullSafetyFixer` rewritten to use PSI instead of regex.

## 1.4.0 — Cleanup, report export rewrite
- Clean HTML report export.
- Smart-cast walker.
- AI prompts include function signatures.

## 1.3.0 — Kotlin K2 + Analysis API
- Fully supports Kotlin plugin in K2 mode (IDEA 2024.3+).
- Analyzers rewritten on Kotlin Analysis API.
- Three new Kotlin analyzers: `AEG-CAST-KT-001`, `AEG-TYPE-KT-001`, `AEG-REDUNDANT-LET-KT-001`.

## 1.2.0 — Hardening release
- `KotlinNullSafetyAnalyzer` added.
- Resilient AI JSON parsing.
- Static dependent cascade analysis on `reanalyzeFile`.
- `SymbolExtractor` acts as a language dispatcher to true PSI parsers.

## 1.1.0 & 1.1.1 & 1.1.2 — Syntax & Compilation detection
- Added `AEG-SYNTAX-001` and `AEG-COMPILE-001`.
- Introduced two static phases (early/late).
- Fixed stale content issues on re-analysis.
- Surfaced IDE-reported compilation errors correctly.

## 1.0.0 — V1 General Availability
- Five deterministic static analyzers, three deterministic fixers.
- NeuroMap visual graph.
- Ollama and OpenAI AI backends.
- PSI-validity check on fixer apply.
