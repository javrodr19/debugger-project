# Analysis hang + docs truth pass — design

**Date:** 2026-08-07
**Status:** approved
**Scope:** `CompilationErrorAnalyzer`, `AnalysisEngine`, `AnalysisOrchestrator`, `FileScanner`,
`GhostDebuggerSettings`; `CLAUDE.md`, `AGENTS.md`, `obsidian-vault/`.

## Problem

Running a full project analysis appears to hang indefinitely. The build compiles and the test
suite exists; the defect is runtime behaviour introduced by uncommitted working-tree edits.

### Root cause (certain)

`CompilationErrorAnalyzer.analyze` was rewritten from a parallel fan-out into a sequential loop:

```kotlin
context.parsedFiles.flatMap { file ->
    withContext(Dispatchers.Default) { harvestFile(file, context.project) }
}
```

`flatMap` with a suspending body awaits each element before starting the next, so the
`withContext` adds a thread hand-off per file without any concurrency. It replaced
`async` + `awaitAll` gated by `Semaphore(DAEMON_CONCURRENCY = 4)`.

Each iteration calls `DaemonCodeAnalyzerImpl.runMainPasses` — the full IntelliJ highlighting
daemon for one file, inside a read action. The default budget is `maxFilesToAnalyze = 500`
(`GhostDebuggerSettings.kt:24`). At a realistic 0.3–2 s per Kotlin file, serialized, a run takes
roughly 3–17 minutes.

### Why it reads as "stuck" rather than "slow"

`AnalysisEngine.runStaticPass` sets `indicator.text2` once per analyzer, before the work starts
(`AnalysisEngine.kt:181`). A multi-minute serial loop inside one analyzer therefore renders as a
frozen `Analyzer: CompilationErrorAnalyzer` with a static progress bar — indistinguishable from a
deadlock. Long-running per-item work must report per-item progress.

### Aggravating factors

1. **Per-file read actions.** `AnalysisOrchestrator.kt:147` dropped the single outer
   `runReadAction`; `FileScanner.parsedFiles` now takes one read action per file, each calling
   `fdm.getDocument(vf)` (forcing document creation). 500 separate acquisitions contend with
   every write action, keystroke and indexing pass.
2. **Unexplained throttle.** `AnalysisEngine.kt:169` added a hard-coded `Semaphore(4)` capping
   all 10 late analyzers, with no stated rationale.
3. **Latent starvation.** The `runBlocking` at `CompilationErrorAnalyzer.kt:71` executes on a
   `Dispatchers.Default` thread supplied by `AnalysisEngine.kt:171`'s `async(Dispatchers.Default)`.
   `runBlocking` blocks that pooled thread while its body needs another thread from the same
   bounded pool (`max(2, ncores)`). Survivable today because only one analyzer does this, but it
   is a landmine that must not be replicated.

### Documentation drift

| Defect | Evidence |
|---|---|
| `CLAUDE.md:81,139,140` and `AGENTS.md:22,23,154,196,315` reference `docs/superpowers/` | Tree deleted in `b8185c0` / `cadcdc9`; agent instruction files cite a nonexistent path |
| `Vault_Index.md:61` links `[[30_Specs_and_Plans/]]` | Folder does not exist (vault has `00/10/20/40/99`) |
| `Vault_Index.md` duplicates its "Meta & History" section | Lines 22–24 and 36–39 |
| `Static_Analyzers.md` claims "11 deterministic analyzers" | 12 registered at `AnalysisEngine.kt:32-45` |
| No note covers `fix/engine/` | 24 files, the V3 centrepiece, undocumented |
| `status:` frontmatter carries no signal | All 30 notes say `"active"` |

## Non-goals

- No new V3 features. The AI-supervised fix engine is out of scope for this work.

  **Correction, made during the truth pass:** this section originally said Phase 2 "needs its own
  spec", implying it was unbuilt — repeating the claim in `CLAUDE.md`. It is in fact shipped.
  `FixEngine.fixSupervised` (`fix/engine/FixEngine.kt:77`) is wired into production at
  `AnalysisOrchestrator.kt:497`. The instruction files have been corrected accordingly. Recorded
  here rather than silently edited, because the wrong belief is what the pass existed to find.
- No change to analyzer detection logic, severities, or the conservative-miss bias.
- No rewrite of vault prose that is already accurate.

## Design

### 1. `DaemonHarvestBudget` (new)

`analysis/analyzers/DaemonHarvestBudget.kt`. Pure policy with no platform dependencies and an
injectable clock, so the regression that caused this hang is testable without an IDE fixture.

```kotlin
class DaemonHarvestBudget(
    private val maxFiles: Int,
    private val timeBudgetMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun select(files: List<ParsedFile>): List<ParsedFile>
    fun startDeadline(): Long
    fun expired(deadline: Long): Boolean
}
```

`select` caps the fan-out at `maxFiles`. `startDeadline` returns `clock() + timeBudgetMs`.
`expired` compares against the current clock.

Contract for non-positive inputs, defined here rather than assumed from the caller: `maxFiles <= 0`
means **no cap** (`select` returns every file) and `timeBudgetMs <= 0` means **never expires**.
Settings validation independently clamps both to their defaults, so these paths should be
unreachable in production — but the class is a standalone unit with its own tested behaviour, not
one that relies on a caller's invariant.

### 2. `CompilationErrorAnalyzer`

- Restore the `private val semaphore = Semaphore(DAEMON_CONCURRENCY)` field the edit deleted.
- Restore parallel fan-out: `files.map { async(Dispatchers.Default) { semaphore.withPermit { … } } }.awaitAll().flatten()`.
- Apply `DaemonHarvestBudget` to select files and to abort remaining work past the deadline.
- Accept a constructor-injected `progress: ProgressIndicator?` (default `null`) and a
  `settingsProvider`, matching the existing `AIAnalyzer(service, progress, concurrency, labelPrefix)`
  convention.
- Report per-file progress: `progress?.text2 = "Compile check: $done/$total — ${file.name}"`,
  incremented through an `AtomicInteger` as each file completes.
- `ProgressManager.checkCanceled()` stays inside the permit, before harvesting.

**Truncation is reported, not silent.** When the deadline expires, the analyzer returns partial
results, logs at INFO, and sets `text2` to say the harvest was capped. This project's stated bias
accepts false negatives, but an *invisible* truncation would leave the user believing a run was
complete when it was not.

### 3. `AnalysisEngine`

- Delete the `Semaphore(4)` throttle in `runStaticPass`; restore plain
  `async(Dispatchers.Default) { runOne(analyzer, context, indicator) }`. `Dispatchers.Default` is
  already bounded by core count; the extra semaphore only serialized further.
- Construct `CompilationErrorAnalyzer(progress)` in the default analyzer list. Legal because
  `progress` is declared before `analyzers` in the constructor parameter list.
- Drop the now-unused `Semaphore` / `withPermit` imports.

### 4. `AnalysisOrchestrator` + `FileScanner`

- Restore the single outer `runReadAction` around `FileScanner(project).parsedFiles(virtualFiles)`
  in `AnalysisOrchestrator`.
- Remove the per-file `app.runReadAction` from `FileScanner.parsedFiles`.
- **Keep** the `ProgressManager.checkCanceled()` calls the edit added to `FileScanner.parsedFiles`
  and `KotlinAnalyzer.analyze` — they make both loops cancellable and are a genuine improvement.
  Convert the inline fully-qualified `com.intellij.openapi.progress.ProgressManager` references
  into proper imports.

### 5. Settings

Add to `GhostDebuggerSettings.State`:

```kotlin
var daemonFileBudget: Int = 150,
var daemonTimeBudgetMs: Long = 60_000,
```

Clamp in `validate()` beside the existing rules: `if (daemonFileBudget <= 0) daemonFileBudget = 150`,
`if (daemonTimeBudgetMs <= 0) daemonTimeBudgetMs = 60_000`. Expose both in
`GhostDebuggerConfigurable` following the existing field pattern.

### 6. Retained from the working tree

`NeuroMapPanel`'s JAR-selection fix (choose the jar that actually contains `web/index.html`, skip
`searchableOptions`) and its extraction cache, plus `untilBuild` 261→262 in `build.gradle.kts` and
`plugin.xml`. These are correct and stay.

## Testing

- Unit tests for `DaemonHarvestBudget`: caps at `maxFiles`, returns all when under the cap,
  `expired` flips at the deadline using an injected fake clock, zero/negative budgets behave.
- Unit test that `AnalysisEngine` passes its indicator through to `CompilationErrorAnalyzer`.
- Full `./gradlew test` under JBR (`JAVA_HOME` must point at the bundled JetBrains Runtime — a
  generic JDK fails `instrumentTestCode` with `Packages does not exist`).
- `./gradlew detekt` to confirm no new complexity violations.

## Documentation work

1. Repoint the dead fix-engine spec reference in `CLAUDE.md:81` and `AGENTS.md:315` at the new
   `obsidian-vault/10_Architecture/FixEngine.md` note.
2. Recreate `docs/superpowers/specs/` — this document restores the convention both instruction
   files describe.
3. Vault: verify all 31 notes claim-by-claim against source; add
   `10_Architecture/FixEngine.md`; dedupe `Vault_Index` and remove the dead
   `30_Specs_and_Plans` link; correct 11→12 analyzers; drop the `status:` frontmatter field.

## Risks

- Capping the daemon harvest at 150 files means large projects no longer surface compilation
  errors for every file in a cold full-project run. Mitigated by making it a setting and by
  reporting truncation.
- Removing the `Semaphore(4)` raises analyzer concurrency back to core count. This is the
  pre-regression behaviour and is bounded by `Dispatchers.Default`.
