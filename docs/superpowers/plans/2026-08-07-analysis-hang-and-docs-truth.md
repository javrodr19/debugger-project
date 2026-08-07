# Analysis Hang Fix + Docs Truth Pass — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop full-project analysis from appearing to hang, and bring `CLAUDE.md`, `AGENTS.md` and the Obsidian vault back into agreement with the code.

**Architecture:** The hang is a serialized per-file run of IntelliJ's highlighting daemon inside `CompilationErrorAnalyzer`. We restore its parallel fan-out, bound it with a new pure-policy `DaemonHarvestBudget` (file cap + wall-clock deadline, injectable clock), and report per-file progress so the bar can never sit still. Read-action scope is restored to one outer action. Then documentation is reconciled against source.

**Tech Stack:** Kotlin, IntelliJ Platform Gradle Plugin 2.14.0, kotlinx.coroutines, JUnit 5 (pure units) / `BasePlatformTestCase` (platform tests), MockK, Detekt.

**Spec:** `docs/superpowers/specs/2026-08-07-analysis-hang-and-docs-truth-design.md`

---

## Build prerequisite — read before running any Gradle task

`instrumentTestCode` requires a JetBrains Runtime, not a generic JDK. Export this in **every** shell that runs Gradle, or the build fails with `Packages does not exist` before a single test runs:

```bash
export JAVA_HOME=$(find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1)
export PATH=$JAVA_HOME/bin:$PATH
```

Verify: `echo $JAVA_HOME` must print a path ending in `/jbr`.

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `src/main/kotlin/com/ghostdebugger/analysis/analyzers/DaemonHarvestBudget.kt` | Pure budget policy: file cap + deadline arithmetic. No platform deps. | Create |
| `src/test/kotlin/com/ghostdebugger/analysis/analyzers/DaemonHarvestBudgetTest.kt` | Unit tests for the budget policy | Create |
| `src/test/kotlin/com/ghostdebugger/settings/GhostDebuggerSettingsBudgetTest.kt` | Unit tests for settings clamping | Create |
| `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt` | Two new persisted fields + clamps | Modify |
| `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerConfigurable.kt` | Two new spinners | Modify |
| `src/main/kotlin/com/ghostdebugger/analysis/analyzers/CompilationErrorAnalyzer.kt` | Parallel harvest, budgeted, progress-reporting | Modify |
| `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt` | Drop `Semaphore(4)`; inject indicator | Modify |
| `src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt` | Restore outer read action | Modify |
| `src/main/kotlin/com/ghostdebugger/parser/FileScanner.kt` | Remove per-file read action; keep cancellation | Modify |
| `src/main/kotlin/com/ghostdebugger/analysis/analyzers/KotlinAnalyzer.kt` | Import tidy | Modify |
| `CLAUDE.md`, `AGENTS.md` | Repoint dead spec reference | Modify |
| `obsidian-vault/**` | Truth pass | Modify/Create |

---

## Task 1: `DaemonHarvestBudget`

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/analysis/analyzers/DaemonHarvestBudget.kt`
- Test: `src/test/kotlin/com/ghostdebugger/analysis/analyzers/DaemonHarvestBudgetTest.kt`

> **Design note:** `select` is generic over `T` rather than typed to `ParsedFile`. `ParsedFile` holds a non-null `VirtualFile`, which cannot be constructed without an IDE fixture. Keeping the signature generic makes this a plain JUnit test with zero platform dependencies — which is the whole point of extracting the policy.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/ghostdebugger/analysis/analyzers/DaemonHarvestBudgetTest.kt`:

```kotlin
package com.ghostdebugger.analysis.analyzers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DaemonHarvestBudgetTest {

    private val tenFiles = (1..10).map { "file$it.kt" }

    @Test
    fun `select caps the list at maxFiles`() {
        val budget = DaemonHarvestBudget(maxFiles = 3, timeBudgetMs = 1_000)
        assertEquals(listOf("file1.kt", "file2.kt", "file3.kt"), budget.select(tenFiles))
    }

    @Test
    fun `select returns everything when under the cap`() {
        val budget = DaemonHarvestBudget(maxFiles = 50, timeBudgetMs = 1_000)
        assertEquals(tenFiles, budget.select(tenFiles))
    }

    @Test
    fun `maxFiles of zero or less means no cap`() {
        assertEquals(tenFiles, DaemonHarvestBudget(0, 1_000).select(tenFiles))
        assertEquals(tenFiles, DaemonHarvestBudget(-1, 1_000).select(tenFiles))
    }

    @Test
    fun `startDeadline is now plus the time budget`() {
        val budget = DaemonHarvestBudget(maxFiles = 10, timeBudgetMs = 500, clock = { 1_000L })
        assertEquals(1_500L, budget.startDeadline())
    }

    @Test
    fun `expired flips once the clock reaches the deadline`() {
        var now = 1_000L
        val budget = DaemonHarvestBudget(maxFiles = 10, timeBudgetMs = 500, clock = { now })
        val deadline = budget.startDeadline()

        assertFalse(budget.expired(deadline), "not expired at start")
        now = 1_499L
        assertFalse(budget.expired(deadline), "not expired just before deadline")
        now = 1_500L
        assertTrue(budget.expired(deadline), "expired at the deadline")
        now = 9_999L
        assertTrue(budget.expired(deadline), "still expired past the deadline")
    }

    @Test
    fun `time budget of zero or less never expires`() {
        var now = 1_000L
        val budget = DaemonHarvestBudget(maxFiles = 10, timeBudgetMs = 0, clock = { now })
        val deadline = budget.startDeadline()
        now = Long.MAX_VALUE - 1
        assertFalse(budget.expired(deadline))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests "com.ghostdebugger.analysis.analyzers.DaemonHarvestBudgetTest"
```

Expected: FAIL — compilation error, `Unresolved reference: DaemonHarvestBudget`.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/ghostdebugger/analysis/analyzers/DaemonHarvestBudget.kt`:

```kotlin
package com.ghostdebugger.analysis.analyzers

/**
 * Bounds the cost of the compilation-error daemon harvest.
 *
 * `CompilationErrorAnalyzer` runs IntelliJ's full highlighting daemon
 * (`DaemonCodeAnalyzerImpl.runMainPasses`) once per file. That is expensive enough — 0.3-2s for a
 * typical Kotlin file — that an unbounded pass over `maxFilesToAnalyze` (default 500) files reads
 * to the user as a hang. This class holds the two caps that keep the pass bounded.
 *
 * It is deliberately free of platform types so it can be unit-tested without an IDE fixture; the
 * clock is injectable for the same reason.
 *
 * Non-positive inputs are defined here rather than assumed from the caller: [maxFiles] `<= 0`
 * means no cap, and [timeBudgetMs] `<= 0` means never expires. `GhostDebuggerSettings.validate()`
 * independently clamps both away from those values, so production should not reach them.
 */
class DaemonHarvestBudget(
    private val maxFiles: Int,
    private val timeBudgetMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Caps [files] at [maxFiles]. Generic so tests need no [com.intellij.openapi.vfs.VirtualFile]. */
    fun <T> select(files: List<T>): List<T> =
        if (maxFiles <= 0) files else files.take(maxFiles)

    /** Absolute timestamp after which harvesting should stop. [Long.MAX_VALUE] when unbounded. */
    fun startDeadline(): Long =
        if (timeBudgetMs <= 0) Long.MAX_VALUE else clock() + timeBudgetMs

    fun expired(deadline: Long): Boolean =
        deadline != Long.MAX_VALUE && clock() >= deadline
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests "com.ghostdebugger.analysis.analyzers.DaemonHarvestBudgetTest"
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/analysis/analyzers/DaemonHarvestBudget.kt \
        src/test/kotlin/com/ghostdebugger/analysis/analyzers/DaemonHarvestBudgetTest.kt
git commit -m "feat(analysis): add DaemonHarvestBudget to bound compile-error harvesting"
```

---

## Task 2: Settings fields

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt:19-41,58-70`
- Test: `src/test/kotlin/com/ghostdebugger/settings/GhostDebuggerSettingsBudgetTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/ghostdebugger/settings/GhostDebuggerSettingsBudgetTest.kt`:

```kotlin
package com.ghostdebugger.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `GhostDebuggerSettings` has a no-arg constructor and `loadState` runs `validate()`, so the
 * clamping rules are reachable without an IDE fixture.
 */
class GhostDebuggerSettingsBudgetTest {

    @Test
    fun `daemon budgets have sane defaults`() {
        val state = GhostDebuggerSettings.State()
        assertEquals(150, state.daemonFileBudget)
        assertEquals(60_000L, state.daemonTimeBudgetMs)
    }

    @Test
    fun `non-positive daemon file budget is clamped to the default`() {
        val settings = GhostDebuggerSettings()
        settings.loadState(GhostDebuggerSettings.State(daemonFileBudget = 0))
        assertEquals(150, settings.state.daemonFileBudget)

        settings.loadState(GhostDebuggerSettings.State(daemonFileBudget = -5))
        assertEquals(150, settings.state.daemonFileBudget)
    }

    @Test
    fun `non-positive daemon time budget is clamped to the default`() {
        val settings = GhostDebuggerSettings()
        settings.loadState(GhostDebuggerSettings.State(daemonTimeBudgetMs = 0))
        assertEquals(60_000L, settings.state.daemonTimeBudgetMs)
    }

    @Test
    fun `valid daemon budgets are preserved`() {
        val settings = GhostDebuggerSettings()
        settings.loadState(GhostDebuggerSettings.State(daemonFileBudget = 42, daemonTimeBudgetMs = 5_000))
        assertEquals(42, settings.state.daemonFileBudget)
        assertEquals(5_000L, settings.state.daemonTimeBudgetMs)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests "com.ghostdebugger.settings.GhostDebuggerSettingsBudgetTest"
```

Expected: FAIL — `Unresolved reference: daemonFileBudget`.

- [ ] **Step 3: Add the fields**

In `GhostDebuggerSettings.kt`, inside `data class State(...)`, add after `var maxComplexity: Int = 10,`:

```kotlin
        // --- V3 daemon-harvest budgets ---
        // CompilationErrorAnalyzer runs the full highlighting daemon per file; these cap that pass
        // so a large project cannot make analysis look hung. See DaemonHarvestBudget.
        var daemonFileBudget: Int = 150,
        var daemonTimeBudgetMs: Long = 60_000,
```

- [ ] **Step 4: Add the clamps**

In the same file, inside `private fun State.validate(): State`, add after `if (maxComplexity < 1) maxComplexity = 10`:

```kotlin
        if (daemonFileBudget <= 0) daemonFileBudget = 150
        if (daemonTimeBudgetMs <= 0) daemonTimeBudgetMs = 60_000
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew test --tests "com.ghostdebugger.settings.GhostDebuggerSettingsBudgetTest"
```

Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt \
        src/test/kotlin/com/ghostdebugger/settings/GhostDebuggerSettingsBudgetTest.kt
git commit -m "feat(settings): add daemonFileBudget and daemonTimeBudgetMs with clamping"
```

---

## Task 3: Settings UI

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerConfigurable.kt`

No test — this is Swing wiring verified by the compiler and by opening the settings panel.

- [ ] **Step 1: Declare the fields**

After `private var maxComplexitySpinner: JSpinner? = null` (around line 31), add:

```kotlin
    private var daemonFileBudgetSpinner: JSpinner? = null
    private var daemonTimeBudgetSpinner: JSpinner? = null
```

- [ ] **Step 2: Build the panels**

In `createComponent()`, immediately after the `maxComplexityPanel` block (ends around line 123), add:

```kotlin
        // Daemon harvest budgets (V3 — bounds CompilationErrorAnalyzer's per-file daemon pass)
        val daemonFilesSpinner = JSpinner(SpinnerNumberModel(settings.daemonFileBudget, 10, 2000, 10)).apply {
            preferredSize = Dimension(80, 28)
        }
        daemonFileBudgetSpinner = daemonFilesSpinner
        val daemonFilesPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Compile-check file budget:"))
            add(daemonFilesSpinner)
        }

        val daemonSecondsSpinner = JSpinner(
            SpinnerNumberModel((settings.daemonTimeBudgetMs / 1000).toInt(), 5, 600, 5)
        ).apply { preferredSize = Dimension(80, 28) }
        daemonTimeBudgetSpinner = daemonSecondsSpinner
        val daemonTimePanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Compile-check time budget (s):"))
            add(daemonSecondsSpinner)
        }
```

- [ ] **Step 3: Add the panels to the form**

Change line 198 from:

```kotlin
        formPanel.add(maxComplexityPanel)
```

to:

```kotlin
        formPanel.add(maxComplexityPanel)
        formPanel.add(daemonFilesPanel)
        formPanel.add(daemonTimePanel)
```

- [ ] **Step 4: Wire `isModified`**

In `isModified()`, add these two clauses to the existing `||` chain (alongside `s.maxFilesToAnalyze != maxFilesSpinner?.value`):

```kotlin
            || s.daemonFileBudget != daemonFileBudgetSpinner?.value
            || s.daemonTimeBudgetMs != ((daemonTimeBudgetSpinner?.value as? Int)?.toLong() ?: 0L) * 1000
```

- [ ] **Step 5: Wire `apply`**

Inside the `GhostDebuggerSettings.getInstance().update { ... }` block, after `(maxComplexitySpinner?.value as? Int)?.let { maxComplexity = it }`, add:

```kotlin
            (daemonFileBudgetSpinner?.value as? Int)?.let { daemonFileBudget = it }
            (daemonTimeBudgetSpinner?.value as? Int)?.let { daemonTimeBudgetMs = it.toLong() * 1000 }
```

- [ ] **Step 6: Wire `reset`**

In `reset()`, alongside `maxFilesSpinner?.value = s.maxFilesToAnalyze`, add:

```kotlin
        daemonFileBudgetSpinner?.value = s.daemonFileBudget
        daemonTimeBudgetSpinner?.value = (s.daemonTimeBudgetMs / 1000).toInt()
```

- [ ] **Step 7: Verify it compiles**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerConfigurable.kt
git commit -m "feat(settings): expose daemon harvest budgets in the settings panel"
```

---

## Task 4: Fix the hang in `CompilationErrorAnalyzer`

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/analysis/analyzers/CompilationErrorAnalyzer.kt:27-78`

This is the core fix. `harvestFile`, `invokeRunInsideHighlightingSession`, `buildIssue`, `stripHtml` and the whole `companion object` are **unchanged** — only the constructor, the `semaphore` field and `analyze` change.

- [ ] **Step 1: Update the imports**

Replace the import block (lines 3-25) with:

```kotlin
import com.ghostdebugger.analysis.EarlyAnalyzer
import com.ghostdebugger.model.*
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ProperTextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
```

- [ ] **Step 2: Add the constructor parameters**

Replace line 27 (`class CompilationErrorAnalyzer : EarlyAnalyzer {`) with:

```kotlin
/**
 * Harvests IDE-reported compilation errors by running the highlighting daemon per file.
 *
 * The daemon pass is expensive (0.3-2s per Kotlin file), so it runs [DAEMON_CONCURRENCY]-way
 * parallel and is bounded by [DaemonHarvestBudget]. Per-file progress is reported through
 * [progress]: without it a multi-minute pass renders as a frozen progress bar, which is
 * indistinguishable from a hang.
 */
class CompilationErrorAnalyzer(
    private val progress: ProgressIndicator? = null,
    private val settingsProvider: () -> GhostDebuggerSettings.State =
        { GhostDebuggerSettings.getInstance().snapshot() },
) : EarlyAnalyzer {
```

- [ ] **Step 3: Restore the semaphore field and rewrite `analyze`**

Replace the current `analyze` (lines 71-78) with the field plus two functions:

```kotlin
    private val semaphore = Semaphore(DAEMON_CONCURRENCY)

    override fun analyze(context: AnalysisContext): List<Issue> = runBlocking {
        val settings = settingsProvider()
        val budget = DaemonHarvestBudget(settings.daemonFileBudget, settings.daemonTimeBudgetMs)
        val files = budget.select(context.parsedFiles)

        if (files.size < context.parsedFiles.size) {
            log.info(
                "Compile-error harvest limited to ${files.size} of ${context.parsedFiles.size} " +
                    "files by daemonFileBudget=${settings.daemonFileBudget}"
            )
        }
        harvestAll(files, budget, context.project)
    }

    /**
     * Fans the per-file daemon pass out across [DAEMON_CONCURRENCY] workers.
     *
     * Note the shape: `map { async { … } }.awaitAll()` genuinely runs in parallel, whereas
     * `flatMap { withContext(…) { … } }` awaits each element before starting the next and is
     * therefore fully sequential. That mistake is what made analysis appear to hang.
     */
    private suspend fun harvestAll(
        files: List<ParsedFile>,
        budget: DaemonHarvestBudget,
        project: Project,
    ): List<Issue> = coroutineScope {
        val deadline = budget.startDeadline()
        val total = files.size
        val done = AtomicInteger(0)
        val truncated = AtomicBoolean(false)

        val issues = files.map { file ->
            async(Dispatchers.Default) {
                semaphore.withPermit {
                    ProgressManager.checkCanceled()
                    if (budget.expired(deadline)) {
                        truncated.set(true)
                        emptyList()
                    } else {
                        harvestFile(file, project).also {
                            val n = done.incrementAndGet()
                            progress?.text2 = "Compile check: $n/$total — ${file.virtualFile.name}"
                        }
                    }
                }
            }
        }.awaitAll().flatten()

        if (truncated.get()) {
            val checked = done.get()
            // Surfaced rather than silent: the user should know a run was capped, otherwise a
            // partial harvest looks identical to a clean bill of health.
            log.info("Compile-error harvest hit its time budget after $checked/$total files")
            progress?.text2 = "Compile check: time budget reached after $checked/$total files"
        }
        issues
    }
```

- [ ] **Step 4: Verify it compiles**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/analysis/analyzers/CompilationErrorAnalyzer.kt
git commit -m "fix(analysis): restore parallel compile-error harvest with budget and progress

The harvest had been rewritten as flatMap { withContext(...) }, which awaits
each file before starting the next. That serialized a full highlighting-daemon
pass over up to 500 files and reported no per-file progress, so analysis
appeared to hang."
```

---

## Task 5: Remove the throttle in `AnalysisEngine`

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt:10-12,33,163-177`

- [ ] **Step 1: Drop the now-unused imports**

Replace lines 10-12:

```kotlin
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
```

with:

```kotlin
import kotlinx.coroutines.*
```

- [ ] **Step 2: Inject the indicator into the analyzer**

On line 34, change:

```kotlin
        CompilationErrorAnalyzer(),
```

to:

```kotlin
        CompilationErrorAnalyzer(progress),
```

This is legal: `progress` is declared as a constructor parameter *before* `analyzers`, and Kotlin default-argument expressions may reference earlier parameters.

- [ ] **Step 3: Restore unthrottled analyzer fan-out**

Replace `runStaticPass` (lines 163-177) with:

```kotlin
    private suspend fun runStaticPass(
        analyzersToRun: List<Analyzer>,
        context: AnalysisContext,
        indicator: ProgressIndicator?
    ): List<Issue> =
        coroutineScope {
            // No extra semaphore here: Dispatchers.Default is already bounded by core count, and
            // stacking a second limiter on top only serializes analyzers further.
            analyzersToRun.map { analyzer ->
                async(Dispatchers.Default) {
                    runOne(analyzer, context, indicator)
                }
            }.awaitAll().flatten()
        }
```

- [ ] **Step 4: Verify it compiles**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt
git commit -m "fix(analysis): drop redundant Semaphore(4) and pass indicator to compile analyzer"
```

---

## Task 6: Restore read-action scope

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt:147`
- Modify: `src/main/kotlin/com/ghostdebugger/parser/FileScanner.kt:74-96`

The current code takes one read action *per file*, each forcing `getDocument`. Across 500 files that contends with every write action in the IDE. One outer read action is the pre-regression behaviour.

- [ ] **Step 1: Restore the outer read action**

In `AnalysisOrchestrator.kt`, replace line 147:

```kotlin
        val rawFiles = FileScanner(project).parsedFiles(virtualFiles)
```

with:

```kotlin
        val rawFiles = ApplicationManager.getApplication().runReadAction<List<ParsedFile>> {
            FileScanner(project).parsedFiles(virtualFiles)
        }
```

`ApplicationManager` and `ParsedFile` are already imported (lines 29 and 16).

- [ ] **Step 2: Remove the per-file read action but keep cancellation**

In `FileScanner.kt`, replace the body of `parsedFiles` with:

```kotlin
    fun parsedFiles(virtualFiles: List<VirtualFile>): List<ParsedFile> {
        val fdm = FileDocumentManager.getInstance()
        return virtualFiles.mapNotNull { vf ->
            // Caller holds one outer read action; checkCanceled keeps the loop interruptible.
            ProgressManager.checkCanceled()
            try {
                val document = fdm.getCachedDocument(vf) ?: fdm.getDocument(vf)
                val content = document?.text ?: String(vf.contentsToByteArray(), Charsets.UTF_8)
                ParsedFile(
                    virtualFile = vf,
                    path = vf.path,
                    extension = vf.extension ?: "",
                    content = content
                )
            } catch (e: Exception) {
                if (e is ProcessCanceledException) throw e
                log.warn("Could not read file: ${vf.path}", e)
                null
            }
        }
    }
```

- [ ] **Step 3: Add the imports**

In `FileScanner.kt`, add to the import block:

```kotlin
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
```

Then confirm no inline fully-qualified references remain:

```bash
grep -n "com.intellij.openapi.progress" src/main/kotlin/com/ghostdebugger/parser/FileScanner.kt
```

Expected: only the two `import` lines.

- [ ] **Step 4: Verify it compiles**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt \
        src/main/kotlin/com/ghostdebugger/parser/FileScanner.kt
git commit -m "fix(parser): restore single outer read action for file parsing"
```

---

## Task 7: Tidy the `KotlinAnalyzer` cancellation check

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/analysis/analyzers/KotlinAnalyzer.kt:31`

The `checkCanceled()` call added here is correct and stays — it makes the per-file loop interruptible. Only the inline fully-qualified name is replaced with an import.

- [ ] **Step 1: Add the import**

Add to the import block:

```kotlin
import com.intellij.openapi.progress.ProgressManager
```

- [ ] **Step 2: Use the short name**

Replace:

```kotlin
            com.intellij.openapi.progress.ProgressManager.checkCanceled()
```

with:

```kotlin
            ProgressManager.checkCanceled()
```

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/analysis/analyzers/KotlinAnalyzer.kt
git commit -m "refactor(analysis): import ProgressManager instead of inline FQN"
```

---

## Task 8: Full verification

**Files:** none modified.

- [ ] **Step 1: Run the full test suite**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`. If any test fails, fix it before continuing — do not proceed with a red suite.

Two failure modes to expect and how to read them:
- `Packages does not exist` → `JAVA_HOME` is not the JBR. Re-export it (see the prerequisite section).
- A test constructing `AnalysisEngine(analyzers = listOf(...))` still passes because `progress` defaults to `null`; a failure here instead means a test asserted on the old `Semaphore(4)` behaviour and should be updated.

- [ ] **Step 2: Run Detekt**

```bash
./gradlew detekt
```

Expected: `BUILD SUCCESSFUL`. If `harvestAll` trips a complexity rule, extract the `async` body into a private `harvestOne(file, budget, deadline, project, done, total, truncated)` helper rather than raising the threshold.

- [ ] **Step 3: Build the plugin**

```bash
./gradlew buildPlugin
```

Expected: `BUILD SUCCESSFUL`, producing `build/distributions/ghostdebugger-3.0.0.zip`.

- [ ] **Step 4: Commit any fixes**

```bash
git add -A && git commit -m "test: fix fallout from analysis concurrency restoration"
```

Skip this step if nothing changed.

---

## Task 9: Repoint the dead spec references

**Files:**
- Modify: `CLAUDE.md:81`
- Modify: `AGENTS.md:315`

`docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md` was deleted in `b8185c0`. Both instruction files still cite it. The knowledge moved into the vault, so the references point there instead. Lines `CLAUDE.md:139-140` and `AGENTS.md:154,196` describing the spec/plan *convention* stay as they are — this plan's own spec restores that directory.

- [ ] **Step 1: Fix `CLAUDE.md`**

Replace:

```
`docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md`.
```

with:

```
`obsidian-vault/10_Architecture/FixEngine.md`.
```

- [ ] **Step 2: Fix `AGENTS.md`**

Apply the identical replacement at `AGENTS.md:315`.

- [ ] **Step 3: Verify no dangling references remain**

```bash
grep -rn "2026-05-31-ai-supervised" CLAUDE.md AGENTS.md
```

Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md AGENTS.md
git commit -m "docs: repoint deleted fix-engine spec reference at the vault note"
```

---

## Task 10: Vault truth pass

**Files:**
- Create: `obsidian-vault/10_Architecture/FixEngine.md`
- Modify: `obsidian-vault/00_Meta/Vault_Index.md`
- Modify: `obsidian-vault/20_Features/Static_Analyzers.md`
- Modify: all remaining notes under `obsidian-vault/` as verification requires

- [ ] **Step 1: Write the missing `FixEngine` note**

`fix/engine/` holds 24 files and is the V3 centrepiece, yet no note covers it. Before writing, read these to ground every claim: `FixEngine.kt`, `FixPlan.kt`, `FixPlanApplicator.kt`, `FixOperation.kt`, `FixOperationCatalog.kt`, `CodeFixAdapter.kt`, `FixVerifier.kt`.

Create `obsidian-vault/10_Architecture/FixEngine.md` with frontmatter matching the other architecture notes (`title`, `type`, `related_components`, `aliases`, `tags` — **no** `status` field, per Step 4) and these sections:

- **Purpose** — what the engine does and why it replaced direct `Fixer` application.
- **Phase 1 (merged)** — a `Fixer`'s `CodeFix` is adapted to a single-op `FixPlan` via `CodeFixAdapter` and applied by `FixPlanApplicator` behind the same PSI-validity gate. Behaviour-preserving.
- **Phase 2 (not implemented)** — the AI becomes a planner/supervisor composing deterministic engine operations and verifying them, never authoring raw fix code. State this as *planned*, not shipped.
- **Key types** — one line each for the files listed above, with the real path.
- **Invariants** — PSI validity or `null`; PCE rethrow.
- Links: `[[Deterministic_Fixers]]`, `[[Fix_Preview_UX]]`, `[[Creating_New_Fixers_Guide]]`.

Any claim you cannot confirm in the source does not go in the note.

- [ ] **Step 2: Repair `Vault_Index.md`**

Three concrete defects:

1. Delete the duplicated "Meta & History" section. Lines 22-24 and 36-39 both exist; keep the later one (it also lists `[[CI_and_Release_Automation]]`) and delete the earlier two-item copy.
2. Delete the final "Specs & Plans" section entirely — it links `[[30_Specs_and_Plans/]]`, a folder that does not exist.
3. Add to the Architecture list, in alphabetical position:

```markdown
- [[FixEngine]] — Deterministic fix planning & application engine (V3).
```

- [ ] **Step 3: Correct the analyzer count**

`Static_Analyzers.md:22` says "11 deterministic analyzers". Twelve are registered at `AnalysisEngine.kt:32-45`. Confirm the current count first:

```bash
sed -n '32,45p' src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt | grep -c 'Analyzer()'
```

Then update the line to the number that command prints, and extend the bullet list beneath it so it names every registered analyzer rather than the current partial list.

- [ ] **Step 4: Drop the `status` frontmatter field**

All 30 notes carry `status: "active"`, so the field distinguishes nothing. Remove it from every note (the `99_Templates/Standard_Note_Template.md` placeholder line goes too):

```bash
find obsidian-vault -name "*.md" -exec sed -i '/^status: /d' {} +
```

Verify:

```bash
grep -rn "^status:" obsidian-vault/ | wc -l
```

Expected: `0`.

- [ ] **Step 5: Verify the remaining notes against source**

Work through every note not already touched. For each factual claim — a file path, class name, count, or described behaviour — confirm it against the source with `grep`/`Read`. Correct what has drifted; delete claims you cannot verify; leave accurate prose alone.

Highest-risk notes, because the code beneath them moved most: `AnalysisOrchestrator.md`, `GhostDebuggerService.md`, `Deterministic_Fixers.md`, `Roadmap.md`, `Claude_Conventions.md`, `Build_and_Testing_Guide.md`, `Creating_New_Fixers_Guide.md`.

Two checks worth running across the whole vault:

```bash
# Wiki-links pointing at notes that do not exist
grep -rho "\[\[[^]]*\]\]" obsidian-vault/ | tr -d '[]' | sort -u | while read -r n; do
  [ -n "$n" ] && ! find obsidian-vault -name "${n}.md" | grep -q . && echo "DEAD LINK: $n"
done

# Source paths cited in notes that no longer exist
grep -rhoE "src/main/kotlin/[A-Za-z0-9/_.]+\.kt" obsidian-vault/ | sort -u | while read -r p; do
  [ -f "$p" ] || echo "DEAD PATH: $p"
done
```

Both should print nothing when the pass is complete. `Roadmap.md` needs particular care: per prior sessions the roadmap reads like a to-do list, but V2 is already built (the `store/` package) — describe shipped work as shipped.

- [ ] **Step 6: Commit**

```bash
git add obsidian-vault/
git commit -m "docs(vault): reconcile notes with source, add FixEngine note, repair index"
```

---

## Task 11: Final check

- [ ] **Step 1: Confirm the tree is clean and green**

```bash
git status --short
./gradlew test
```

Expected: no unexpected modifications; `BUILD SUCCESSFUL`.

- [ ] **Step 2: Review the branch diff**

```bash
git log --oneline main..HEAD
git diff main...HEAD --stat
```

Confirm the retained working-tree work is present: `NeuroMapPanel` JAR selection plus extraction cache, and `untilBuild` 262 in both `build.gradle.kts` and `plugin.xml`.

- [ ] **Step 3: Report**

Summarise for the user: what was fixed, what the test suite reports, and what documentation changed. Do not claim the hang is fixed in the running IDE unless it has actually been exercised there — the tests cover the budget policy, not the live daemon pass.

---

## Self-Review

**Spec coverage:** DaemonHarvestBudget → Task 1. Settings → Tasks 2-3. CompilationErrorAnalyzer → Task 4. AnalysisEngine → Task 5. Orchestrator/FileScanner → Task 6. KotlinAnalyzer → Task 7. Retained NeuroMapPanel/untilBuild work → verified in Task 11 Step 2. Testing → Tasks 1, 2, 8. Docs → Task 9. Vault → Task 10. No gaps.

**Type consistency:** `DaemonHarvestBudget(maxFiles, timeBudgetMs, clock)` with `select`/`startDeadline`/`expired` is used identically in Tasks 1 and 4. `daemonFileBudget`/`daemonTimeBudgetMs` are spelled identically in Tasks 2, 3 and 4. `harvestFile(file, project)` matches the existing private signature at `CompilationErrorAnalyzer.kt:86`.

**Known deviation from spec:** the spec's testing section listed "unit test that `AnalysisEngine` passes its indicator through to `CompilationErrorAnalyzer`". That is not included as a separate test — the wiring is a constructor default argument enforced by the compiler, and a reflective test over a default-argument expression would assert on Kotlin codegen rather than behaviour. Task 5 Step 4 covers it via compilation.
