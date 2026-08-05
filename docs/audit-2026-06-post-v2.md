# Post-V2 Audit — 2026-06 (fix engine + store correlation + no-regression gate)

**Date:** 2026-06-16
**Scope:** `ec91efd..HEAD` (post–May-31-audit surface). 52 files audited.
**Lenses:** (1) CLAUDE.md invariants; (2) JetBrains platform-API misuse.

## Scope (files audited)

```
src/main/kotlin/com/ghostdebugger/AegisTestStatusListener.kt
src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt
src/main/kotlin/com/ghostdebugger/DebugSessionCoordinator.kt
src/main/kotlin/com/ghostdebugger/FileChangeWatcher.kt
src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt
src/main/kotlin/com/ghostdebugger/ProblemsViewCoordinator.kt
src/main/kotlin/com/ghostdebugger/ReportExporter.kt
src/main/kotlin/com/ghostdebugger/UIEventRouter.kt
src/main/kotlin/com/ghostdebugger/actions/
src/main/kotlin/com/ghostdebugger/ai/
src/main/kotlin/com/ghostdebugger/analysis/
src/main/kotlin/com/ghostdebugger/bridge/
src/main/kotlin/com/ghostdebugger/fix/
src/main/kotlin/com/ghostdebugger/fix/engine/
src/main/kotlin/com/ghostdebugger/inspections/
src/main/kotlin/com/ghostdebugger/intentions/
src/main/kotlin/com/ghostdebugger/model/
src/main/kotlin/com/ghostdebugger/parser/
src/main/kotlin/com/ghostdebugger/store/
src/main/kotlin/com/ghostdebugger/toolwindow/
```

## Coverage Tooling

`kover` plugin wired as report-only coverage measurement (`org.jetbrains.kotlinx.kover:0.9.1` in `build.gradle.kts`).

## Audit Findings

### Lens 1 — CLAUDE.md Invariants

| # | Severity | File:line | Invariant | Finding | Disposition |
|---|---|---|---|---|---|
| L1-1 | PASS | `fix/engine/*` | PCE handling | ProcessCanceledException explicitly rethrown before general Exception catch in FixPlanApplicator and FixEngine | FIXED / VERIFIED |
| L1-2 | PASS | `fix/engine/FixPlanApplicator.kt` | Single-writer facade state | All facade state reads/writes pass through `GhostDebuggerService.getInstance(project)` | VERIFIED |
| L1-3 | PASS | `parser/KotlinAnalysisHelpers.kt` | Analysis API chokepoint | All K2 analysis calls route through `withKtAnalysis` | VERIFIED |
| L1-4 | PASS | `fix/engine/FixOperation.kt` | PSI validity | All operations construct valid PSI elements or return null on failure | VERIFIED |

### Lens 2 — JetBrains Platform-API Misuse

| # | Severity | File:line | Category | Finding | Disposition |
|---|---|---|---|---|---|
| L2-1 | PASS | `AnalysisOrchestrator.kt` | EDT / Read Actions | Read actions and background processing use proper Application.runReadAction / Task.Backgroundable bounds | VERIFIED |
| L2-2 | PASS | `store/SuppressionMemoryService.kt` | Storage & Disposer | Memory service correctly bound to Project lifecycle | VERIFIED |

## Dispositions Legend
- **FIXED** — regression test first, then fix.
- **HARDENED** — defensive change.
- **REJECTED** — not a real defect; rationale recorded.
