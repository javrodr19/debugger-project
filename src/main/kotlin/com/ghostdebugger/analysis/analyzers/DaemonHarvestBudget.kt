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
