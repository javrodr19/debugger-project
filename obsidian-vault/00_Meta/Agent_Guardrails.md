---
title: "Agent Guardrails"
type: "meta"
status: "active"
related_components: []
aliases:
  - "AGENTS.md"
tags:
  - aegis-debug
---

# Agent Guardrails

These are cross-agent guardrails for any AI assistant working on this repository (extracted from `AGENTS.md`).

## 1. Guiding Principles
Agents must strictly follow the [[Project_Principles]].

## 2. Repository Structure
- `build.gradle.kts` - Version source-of-truth.
- `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` - Facade — single writer of project state.
- `AnalysisOrchestrator.kt`, `UIEventRouter.kt`, `FileChangeWatcher.kt`, `DebugSessionCoordinator.kt` - Collaborators.
- `parser/KotlinAnalysisHelpers.withKtAnalysis(...)` - The **only** entry point for Kotlin Analysis API.

## 3. Versioning and Branch Discipline
- **Versions**: `build.gradle.kts` is the source-of-truth. `plugin.xml`'s `<version>` must be synced manually.
- **Branches**: `v<version>-<short-kebab-topic>`. Direct commits to `main` are reserved for trivial tweaks.
- **Tags**: `v.<version>` (e.g., `v.1.5.0`).

## 4. Documenting Changes
Every non-trivial change needs a **Spec** and a **Plan** (see `30_Specs_and_Plans/`).
- Specs live in `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`.
- Plans live in `docs/superpowers/plans/YYYY-MM-DD-<topic>.md`.
- Changes must be documented in `plugin.xml`'s `<change-notes>` section.

## 5. Code Conventions
- **Error handling**: `catch (e: Exception)` must immediately rethrow `ProcessCanceledException` (PCE).
- **Facade state ownership**: Only [[GhostDebuggerService]] writes to `currentIssues`/`issuesByFile`.
- **Analyzers**: Inherit from `Analyzer`. Apply conservative-miss bias.
- **Fixers**: Inherit from `Fixer`. Must guarantee PSI validity. Fix application routes through `FixEngine`.
- **Tests**: Mirror `src/main` layout. Use `AegisKotlinAnalysisTestCase` for Analysis API tests.

## 6. Commit and PR Conventions
- **Commits**: Conventional commits (`feat(scope): summary`).
- **Squashing**: Don't squash refactor-class branches (ordered commits). Squash churn branches.

## 7. Things Not To Do
- Don't create new top-level docs like README/CHANGELOG without request.
- Don't swallow `ProcessCanceledException`.
- Don't introduce new permissions, telemetry, or cloud calls.
- Don't edit `src/main/resources/web/` directly (use `webview/src/`).
