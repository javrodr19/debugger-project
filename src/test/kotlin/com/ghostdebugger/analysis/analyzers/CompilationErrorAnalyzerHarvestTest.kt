package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.testutil.FixtureFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers `CompilationErrorAnalyzer.harvestAll`'s per-file fan-out shape and the
 * [DaemonHarvestBudget] truncation paths it drives.
 *
 * These exist because the bug this branch fixes — `harvestAll` silently degrading from a
 * parallel `map { async { … } } .awaitAll()` fan-out to a sequential
 * `flatMap { withContext(…) { … } }` — passed code review once already precisely because nothing
 * exercised the fan-out shape. `harvestFile` itself calls
 * `DaemonCodeAnalyzerImpl.runMainPasses`, which needs a live IDE fixture and is impractical to
 * drive from a latch-based concurrency test, so these tests go through the `harvestOverride` seam
 * on [CompilationErrorAnalyzer] instead of a real highlighting pass.
 */
class CompilationErrorAnalyzerHarvestTest {

    private fun sampleIssue(path: String): Issue = Issue(
        id = UUID.randomUUID().toString(),
        type = IssueType.COMPILATION_ERROR,
        severity = IssueSeverity.ERROR,
        title = "fake compile error",
        description = "fake",
        filePath = path,
    )

    @Test
    fun `harvestAll runs the per-file harvest in parallel, not sequentially`() {
        // DAEMON_CONCURRENCY (private in CompilationErrorAnalyzer, see its KDoc) is 4: the
        // semaphore there admits at most 4 in-flight harvests at once. Keep N at or below that —
        // a latch sized above the permit count would deadlock even under genuinely parallel,
        // correct code, because the (N - DAEMON_CONCURRENCY) excess fake harvests could never be
        // in flight (and thus counting the latch down) at the same time as the rest.
        val n = 4
        val latch = CountDownLatch(n)
        val files = (1..n).map { FixtureFactory.parsedFile("/f$it.kt", "kt", "") }
        val ranCount = AtomicInteger(0)

        val analyzer = CompilationErrorAnalyzer(
            settingsProvider = { GhostDebuggerSettings.State() },
            harvestOverride = { _, _ ->
                ranCount.incrementAndGet()
                latch.countDown()
                // Under genuine parallelism, all N fake harvests are in flight at once, so the
                // latch reaches zero almost immediately and this returns true right away. Under
                // the sequential `flatMap { withContext(…) { … } }` regression, only the first
                // file's harvest is running when this executes — the other N-1 counts never
                // happen until it returns — so this blocks for the full timeout and comes back
                // false, which is exactly the shape of the bug this test targets.
                val ranTogether = latch.await(5, TimeUnit.SECONDS)
                assertTrue(ranTogether, "harvestAll did not run the per-file harvest in parallel")
                emptyList()
            }
        )

        val issues = analyzer.analyze(FixtureFactory.context(files))

        assertEquals(n, ranCount.get(), "every file should have been harvested")
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `daemonFileBudget caps how many files are harvested`() {
        val files = (1..10).map { FixtureFactory.parsedFile("/f$it.kt", "kt", "") }
        val invocationCount = AtomicInteger(0)

        val analyzer = CompilationErrorAnalyzer(
            settingsProvider = { GhostDebuggerSettings.State(daemonFileBudget = 3, daemonTimeBudgetMs = 60_000) },
            harvestOverride = { _, _ ->
                invocationCount.incrementAndGet()
                emptyList()
            }
        )

        val issues = analyzer.analyze(FixtureFactory.context(files))

        assertEquals(3, invocationCount.get(), "only daemonFileBudget files should be harvested")
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `expired daemonTimeBudgetMs truncates the harvest but keeps issues already collected`() {
        // 5 files against a DAEMON_CONCURRENCY of 4: the first 4 acquire a semaphore permit
        // immediately (well inside the 200ms budget) and each "harvests" a real issue. The 5th
        // has to wait for one of those 4 to release its permit, which only happens after the
        // 400ms fake harvest completes — by then the 200ms budget has already expired, so the
        // 5th file is truncated (never reaches harvestOverride) rather than losing the 4 issues
        // already collected.
        val files = (1..5).map { FixtureFactory.parsedFile("/f$it.kt", "kt", "") }
        val invocationCount = AtomicInteger(0)

        val analyzer = CompilationErrorAnalyzer(
            settingsProvider = { GhostDebuggerSettings.State(daemonFileBudget = 5, daemonTimeBudgetMs = 200) },
            harvestOverride = { file, _ ->
                invocationCount.incrementAndGet()
                Thread.sleep(400)
                listOf(sampleIssue(file.path))
            }
        )

        val issues = analyzer.analyze(FixtureFactory.context(files))

        assertEquals(4, invocationCount.get(), "only the first concurrency wave should run before the budget expires")
        assertEquals(4, issues.size, "issues collected before the deadline must not be discarded")
    }
}
