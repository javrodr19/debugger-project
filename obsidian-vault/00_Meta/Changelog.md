---
title: "Changelog"
type: "meta"
status: "active"
related_components: []
aliases: []
tags:
  - aegis-debug
---

# Changelog

A high-level summary of Aegis Debug releases. For full details, see the raw `CHANGELOG.md` in the project root.

## 2.0.0-beta.1 — Dynamic Validation, Rule Packs, Fix Preview & External SDK
- **Dynamic Validation Pass**: `RUNTIME_CONFIRMED` source tag, debugger cross-check, test runner cross-check, suppression memory service.
- **AI-Supervised Fix Engine**: Supervises deterministic fix operations with verification gate.
- **Custom Rules & Rule Packs (V3.1 & V3.2)**: Declarative `.aegis/rules/*.yml` custom rules and curated `.aegis/packs/*.yml` rule packs.
- **Fix-Preview UX (V3.3)**: Line/hunk diff previews and interactive Swing diff preview dialog.
- **External Analyzer SDK (V3.4)**: Dynamic `.jar` analyzer plugins loaded from `.aegis/analyzers/` with isolated classloader and PCE protection.
- **Plugin Actions (Batches 1–3)**: `ReanalyzeFile`, `ApplyAllFixes`, `NavigateFinding`, `SuppressFinding`, `ToggleRule`, `ShowInNeuroMap`, `ExportReport`, `CopyFindingForAI`, `ConfirmDenyFinding`.

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
