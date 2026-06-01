# Fix Engine Phase 2c-i — Wire the Verify Gate into Live Fix Paths Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route the three live fix-application sites (editor intention, IDE inspection quick-fix, webview "apply fix") through the Phase-2b `FixEngine.fixVerified` Tier-2 gate so the verify gate actually runs in production, with the file's current findings as the baseline and rejections surfaced to the user.

**Architecture:** Add one shared async helper `AnalysisOrchestrator.applyVerifiedFix(issue, virtualFile, content)` — the orchestrator already owns the coroutine `scope`, the `service()` facade (source of the baseline), and `reanalyzeFile`. It computes a file-scoped baseline via a pure `baselineFor(issues, filePath)` helper, calls `fixVerified` off-EDT, re-analyzes on success, and surfaces a balloon Notification (reusing the existing `GhostDebugger` notification group) on rejection. The editor intention and the inspection quick-fix delegate to it; the webview path (`UIEventRouter`, already async with its own bridge messaging) calls `fixVerified` directly with the baseline.

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform (IPGP 2.14.0, intellijIdeaCommunity 2024.3.2), kotlinx-coroutines, JUnit3-style `BasePlatformTestCase`.

---

## Prerequisites (test prelude)

Tests require a JetBrains Runtime, and the shell env does NOT persist between Bash commands. Prefix EVERY gradlew invocation inline:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt` — `@Service(Service.Level.PROJECT) internal class`; has `private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())`, `private fun service(): GhostDebuggerService`, `fun reanalyzeFile(filePath: String)`, and a `getInstance(project)` companion. Imports already include `kotlinx.coroutines.launch`, `Dispatchers`, `withContext`, `swing.Swing`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt` — `suspend fun fixVerified(issue, virtualFile, content, baselineForFile, reanalyze=…, edtContext=…): FixApplyResult` (Phase 2b).
- `src/main/kotlin/com/ghostdebugger/fix/FixApplicator.kt` — `sealed class FixApplyResult { data object Success; data class Rejected(val reason: String); data class Failed(val throwable: Throwable) }`.
- `src/main/kotlin/com/ghostdebugger/intentions/AegisQuickFixIntentionAction.kt:40-54` — `invoke()` (already `startInWriteAction()=false`); currently `FixEngine(project).fix(...)` then `reanalyzeFile`.
- `src/main/kotlin/com/ghostdebugger/inspections/AegisLocalInspection.kt:27-40` — `AegisLocalQuickFix.applyFix()`; currently `FixEngine(project).apply(plan, vf)` then `reanalyzeFile`.
- `src/main/kotlin/com/ghostdebugger/UIEventRouter.kt:264-285` — already inside `scope.launch`; currently `FixEngine(project).fix(issue, vf, content)` with Success→`sendFixApplied`+`reanalyzeFile`, else→`sendError`.
- `src/main/resources/META-INF/plugin.xml:304` — existing `<notificationGroup id="GhostDebugger" …>`; used in `ReportExporter.kt:72` via `NotificationGroupManager.getInstance().getNotificationGroup("GhostDebugger")`.
- `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` — `currentIssues` (read), `internal fun updateIssues(...)`, `jcefBridge()`, `bridgeChannel()`.

## File structure

- **Modify** `src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt` — add top-level `internal fun baselineFor(...)`, member `applyVerifiedFix(...)`, private `notifyFixRejected(...)`, and imports.
- **Modify** `src/main/kotlin/com/ghostdebugger/intentions/AegisQuickFixIntentionAction.kt` — `invoke` delegates to `applyVerifiedFix`.
- **Modify** `src/main/kotlin/com/ghostdebugger/inspections/AegisLocalInspection.kt` — `applyFix` delegates to `applyVerifiedFix`.
- **Modify** `src/main/kotlin/com/ghostdebugger/UIEventRouter.kt` — swap `fix`→`fixVerified` + baseline.
- **Create** `src/test/kotlin/com/ghostdebugger/BaselineForTest.kt`
- **Create** `src/test/kotlin/com/ghostdebugger/AnalysisOrchestratorApplyVerifiedFixTest.kt`
- **Modify** `docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md` — §9 mark 2c-i done.

---

### Task 1: `baselineFor` — pure file-scoped baseline helper

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt` (add a top-level `internal fun`)
- Test: `src/test/kotlin/com/ghostdebugger/BaselineForTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.Assert.assertEquals
import org.junit.Test

class BaselineForTest {
    private fun issue(id: String, path: String) = Issue(
        id = id, type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
        title = "t", description = "", filePath = path, line = 1, ruleId = "AEG-CAST-KT-001"
    )

    @Test fun keepsOnlyIssuesForTheGivenFile() {
        val here = issue("a", "/proj/A.kt")
        val elsewhere = issue("b", "/proj/B.kt")
        assertEquals(listOf(here), baselineFor(listOf(here, elsewhere), "/proj/A.kt"))
    }

    @Test fun normalizesBackslashesOnBothSides() {
        val here = issue("a", "C:\\proj\\A.kt")
        assertEquals(listOf(here), baselineFor(listOf(here), "C:/proj/A.kt"))
    }

    @Test fun returnsEmptyWhenNoneMatch() {
        assertEquals(emptyList<Issue>(), baselineFor(listOf(issue("a", "/proj/A.kt")), "/proj/Z.kt"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.BaselineForTest"`
Expected: FAIL — `baselineFor` unresolved.

- [ ] **Step 3: Add the helper**

In `AnalysisOrchestrator.kt`, add this top-level function (outside the class, e.g. just below the imports or below the class):

```kotlin
/**
 * The file-scoped baseline for the Tier-2 verify gate: the issues already known for [filePath].
 * Path comparison normalizes `\` to `/` on both sides so Windows and POSIX paths match. Top-level
 * and pure so it is unit-testable without constructing the @Service.
 */
internal fun baselineFor(issues: List<com.ghostdebugger.model.Issue>, filePath: String): List<com.ghostdebugger.model.Issue> {
    val normalized = filePath.replace("\\", "/")
    return issues.filter { it.filePath.replace("\\", "/") == normalized }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.BaselineForTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt src/test/kotlin/com/ghostdebugger/BaselineForTest.kt
git commit -m "feat(fix-engine): baselineFor file-scoped baseline helper for verify gate"
```

---

### Task 2: `AnalysisOrchestrator.applyVerifiedFix` — shared async verified-fix entry point

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt`
- Test: `src/test/kotlin/com/ghostdebugger/AnalysisOrchestratorApplyVerifiedFixTest.kt`

- [ ] **Step 1: Write the failing test**

The test injects a fake `fixVerified` (records the baseline it received, returns `Rejected` so the heavy `reanalyzeFile` path is not taken) and awaits the returned `Job`. It proves the helper computes a file-scoped baseline from `service().currentIssues` and routes through `fixVerified`.

```kotlin
package com.ghostdebugger

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

class AnalysisOrchestratorApplyVerifiedFixTest : BasePlatformTestCase() {

    private fun issue(id: String, path: String) = Issue(
        id = id, type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
        title = "t", description = "", filePath = path, line = 1, ruleId = "AEG-CAST-KT-001"
    )

    fun testRoutesThroughFixVerifiedWithFileScopedBaseline() {
        val psi = myFixture.configureByText("A.kt", "fun f(): Int { return 1 }\n")
        val vf = psi.virtualFile
        val here = issue("t", vf.path)
        val elsewhere = issue("o", "/other/B.kt")
        GhostDebuggerService.getInstance(project).updateIssues(listOf(here, elsewhere))

        var receivedBaseline: List<Issue>? = null
        val content = runReadAction { myFixture.getDocument(psi).text }
        val orch = AnalysisOrchestrator.getInstance(project)

        runBlocking {
            orch.applyVerifiedFix(
                here, vf, content,
                fixVerified = { _, _, _, baseline ->
                    receivedBaseline = baseline
                    FixApplyResult.Rejected("verification declined (test)")
                },
            ).join()
        }

        // File-scoped: the issue in /other/B.kt must be excluded from the baseline.
        assertEquals(listOf(here), receivedBaseline)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.AnalysisOrchestratorApplyVerifiedFixTest"`
Expected: FAIL — `applyVerifiedFix` unresolved.

- [ ] **Step 3: Implement `applyVerifiedFix` + `notifyFixRejected`**

Add these imports to `AnalysisOrchestrator.kt`:

```kotlin
import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.fix.engine.FixEngine
import com.ghostdebugger.model.Issue
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import kotlinx.coroutines.Job
```

Add these members to the `AnalysisOrchestrator` class:

```kotlin
    /**
     * Apply a deterministic fix for [issue] through the Phase-2b Tier-2 verify gate
     * ([FixEngine.fixVerified]) off the EDT, using the file's current findings as the baseline.
     * On success, re-analyzes the file so the resolved issue clears; on rejection, surfaces a
     * balloon notification. The [fixVerified] seam is injectable for tests. Returns the launched
     * [Job] so callers/tests can await completion.
     */
    internal fun applyVerifiedFix(
        issue: Issue,
        virtualFile: VirtualFile,
        content: String,
        fixVerified: suspend (Issue, VirtualFile, String, List<Issue>) -> FixApplyResult =
            { i, v, c, b -> FixEngine(project).fixVerified(i, v, c, b) },
    ): Job = scope.launch {
        try {
            val baseline = baselineFor(service().currentIssues, virtualFile.path)
            when (val result = fixVerified(issue, virtualFile, content, baseline)) {
                is FixApplyResult.Success -> reanalyzeFile(virtualFile.path)
                is FixApplyResult.Rejected -> notifyFixRejected(issue, result.reason)
                is FixApplyResult.Failed ->
                    notifyFixRejected(issue, result.throwable.message ?: "Fix failed unexpectedly.")
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.warn("Verified fix failed for ${issue.filePath}", e)
            notifyFixRejected(issue, e.message ?: "Fix failed unexpectedly.")
        }
    }

    /** Surface a verify-gate rejection to the user via the existing GhostDebugger balloon group. */
    private fun notifyFixRejected(issue: Issue, reason: String) {
        log.warn("Verified fix rejected for ${issue.fingerprint()}: $reason")
        NotificationGroupManager.getInstance()
            .getNotificationGroup("GhostDebugger")
            .createNotification("Aegis Debug couldn't apply the fix", reason, NotificationType.WARNING)
            .notify(project)
    }
```

> Implementer note: `notifyFixRejected` must NOT wrap `notify(...)` in `withContext(Dispatchers.Swing)`. `notify` schedules its balloon on the EDT internally (non-blocking); adding an explicit Swing hop would deadlock the test, which awaits the Job under `runBlocking` on the EDT. If the test hangs, an EDT-blocking call in this path is the cause.

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.AnalysisOrchestratorApplyVerifiedFixTest"`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt src/test/kotlin/com/ghostdebugger/AnalysisOrchestratorApplyVerifiedFixTest.kt
git commit -m "feat(fix-engine): AnalysisOrchestrator.applyVerifiedFix async verify-gate entry point"
```

---

### Task 3: Route the editor intention through the verify gate

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/intentions/AegisQuickFixIntentionAction.kt:40-54`

- [ ] **Step 1: Replace the synchronous body of `invoke`**

Replace the current body (lines 48-53, the `findFixableIssue` + `FixEngine(...).fix(...)` + `if (Success) reanalyzeFile`) with delegation to the orchestrator. The method becomes:

```kotlin
    @Throws(IncorrectOperationException::class)
    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        if (editor == null) return
        val psiFile = element.containingFile ?: return
        val virtualFile = psiFile.virtualFile ?: return

        // Recompute from the current caret rather than reading a shared field. This intention is a
        // singleton reused across editors/carets, and isAvailable() runs frequently on background
        // threads — a mutable activeIssue field raced and could apply the wrong fix or none. BUG-23.
        val issue = findFixableIssue(project, editor, element) ?: return
        val content = psiFile.text
        // Tier-2 verify gate runs off-EDT; the orchestrator re-analyzes on success and notifies on reject.
        AnalysisOrchestrator.getInstance(project).applyVerifiedFix(issue, virtualFile, content)
    }
```

(The `com.ghostdebugger.fix.engine.FixEngine` reference is gone; `AnalysisOrchestrator` is already imported. Leave the rest of the file — `findFixableIssue`, `startInWriteAction()=false`, `displayTitle` — unchanged.)

- [ ] **Step 2: Verify it compiles (no behavior test — mechanical delegation covered by Task 2)**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/intentions/AegisQuickFixIntentionAction.kt
git commit -m "feat(fix-engine): route editor quick-fix intention through verify gate"
```

---

### Task 4: Route the inspection quick-fix through the verify gate

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/inspections/AegisLocalInspection.kt:27-40`

The pre-derived `fix: CodeFix` in `AegisLocalQuickFix` stays — `buildVisitor` still uses `FixDeriver` to decide whether to *offer* the quick-fix. Only `applyFix` changes: instead of applying the plan via Tier-1 `FixEngine.apply`, delegate to the verify gate (which re-derives the deterministic plan internally and verifies it).

- [ ] **Step 1: Replace the body of `applyFix`**

```kotlin
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val vf = descriptor.psiElement?.containingFile?.virtualFile ?: return
        val content = com.intellij.openapi.application.ApplicationManager.getApplication()
            .runReadAction<String?> { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)?.text }
            ?: return
        // Route through the Tier-2 verify gate (off-EDT). The orchestrator re-analyzes on success
        // and surfaces a notification on rejection, matching the other two fix paths. BUG-24.
        AnalysisOrchestrator.getInstance(project).applyVerifiedFix(issue, vf, content)
    }
```

Then remove now-unused imports from the file: `com.ghostdebugger.fix.FixApplyResult`, `com.ghostdebugger.fix.engine.FixEngine`, `com.ghostdebugger.fix.engine.toFixPlan` — **but keep** `com.ghostdebugger.fix.FixDeriver` and `com.ghostdebugger.model.CodeFix` (still used by `buildVisitor`/the `AegisLocalQuickFix` constructor). Verify with the compiler which imports are unused and remove exactly those.

- [ ] **Step 2: Verify it compiles**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (no unused-import or unresolved-reference errors).

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/inspections/AegisLocalInspection.kt
git commit -m "feat(fix-engine): route inspection quick-fix through verify gate"
```

---

### Task 5: Route the webview "apply fix" path through the verify gate

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/UIEventRouter.kt:264-285`

This site is already inside `scope.launch` (off-EDT) with Success/Reject bridge handling. Only the engine call changes: compute the baseline and call `fixVerified`. The richer `Rejected.reason` from the gate flows into the existing `sendError`.

- [ ] **Step 1: Swap `fix` → `fixVerified` with baseline**

In the `scope.launch { … }` block (around line 265-277), replace the `val applied = …` assignment. The block becomes:

```kotlin
        svc.suppressUntil = System.currentTimeMillis() + 3000
        scope.launch {
            val content = try {
                java.io.File(issue.filePath).readText()
            } catch (e: Exception) {
                if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                null
            }
            val vf = LocalFileSystem.getInstance().findFileByPath(issue.filePath)
            val applied = if (vf != null && content != null) {
                val baseline = baselineFor(svc.currentIssues, vf.path)
                com.ghostdebugger.fix.engine.FixEngine(project).fixVerified(issue, vf, content, baseline)
            } else {
                FixApplyResult.Rejected("Could not read file for fix: ${issue.filePath}")
            }
            if (applied is FixApplyResult.Success) {
                withContext(Dispatchers.Swing) { svc.jcefBridge()?.sendFixApplied(issueId) }
                AnalysisOrchestrator.getInstance(project).reanalyzeFile(issue.filePath)
            } else {
                val msg = (applied as? FixApplyResult.Rejected)?.reason ?: "Fix application failed for issue $issueId."
                withContext(Dispatchers.Swing) { svc.jcefBridge()?.sendError(msg) }
            }
        }
```

(`baselineFor` is top-level in the `com.ghostdebugger` package — same package as `UIEventRouter` — so it resolves without import. `svc.currentIssues` is the facade state. The rest of the block is unchanged.)

- [ ] **Step 2: Verify it compiles**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/UIEventRouter.kt
git commit -m "feat(fix-engine): route webview apply-fix through verify gate"
```

---

### Task 6: Document 2c-i as done

**Files:**
- Modify: `docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md`

- [ ] **Step 1: Update the phasing section (§9)**

Split Phase 2c into 2c-i (done) and 2c-ii (remaining):
- **Phase 2c-i (DONE):** the verify gate is wired into all three live fix paths — editor intention, inspection quick-fix, and webview — via `AnalysisOrchestrator.applyVerifiedFix` (and `fixVerified` directly in `UIEventRouter`). Baseline = the file's current findings (`baselineFor`). Rejections surface through the existing `GhostDebugger` notification group.
- **Phase 2c-ii (remaining):** `FixPlanner` (AI emits a `FixPlan` from the op catalog as JSON via `AiJsonExtractor`), the bounded AI review loop, and retiring the free-form `suggestFix`/`parseFixResponse` path (`BaseAIService` / `AIService` / `PromptTemplates`, called at `UIEventRouter`).

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md
git commit -m "docs(spec): Phase 2c-i verify-gate wire-in complete"
```

---

## Final verification

- [ ] **Run the touched test packages + a compile of the whole module**

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.BaselineForTest" --tests "com.ghostdebugger.AnalysisOrchestratorApplyVerifiedFixTest" --tests "com.ghostdebugger.fix.engine.*"
```
Expected: all green.

- [ ] **Run the full suite** (catches any caller that depended on the old synchronous `fix()` return at these sites)

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test
```
Expected: all green. In particular confirm `AegisQuickFixIntentionActionTest` (if present) still passes — `invoke` is now fire-and-forget async; if a test asserted synchronous post-conditions on `invoke`, it must await re-analysis or be updated to reflect the async contract. If such a test fails, adapt it to await the effect (it is testing the new async behavior), not by reverting the wire-in.

---

## Self-Review (completed during planning)

- **Spec coverage:** wire-in of all three live fix sites → Tasks 3,4,5; baseline from current findings → Task 1 + used in Tasks 2,5; rejection surfacing → Task 2 (`notifyFixRejected`, reusing the existing `GhostDebugger` group); the AI planner/review + legacy-path removal are explicitly Phase 2c-ii (Task 6), not in scope here.
- **Type consistency:** `baselineFor(issues: List<Issue>, filePath: String): List<Issue>`; `applyVerifiedFix(issue, virtualFile, content, fixVerified=…): Job`; `fixVerified` seam type `suspend (Issue, VirtualFile, String, List<Issue>) -> FixApplyResult` matches `FixEngine.fixVerified(issue, virtualFile, content, baselineForFile)` (defaults cover `reanalyze`/`edtContext`). `FixApplyResult.Failed.throwable` confirmed against source.
- **Threading:** `applyVerifiedFix` launches on the orchestrator's `Dispatchers.Default` scope; `fixVerified` does its own write-safe EDT hops (Phase 2b). `notifyFixRejected` uses non-blocking `Notification.notify` (no Swing hop) so the Task-2 test can await the Job under `runBlocking` on the EDT without deadlock. The success path calls `reanalyzeFile` (itself a fire-and-forget `scope.launch`), so the Task-2 test deliberately uses the reject path to avoid the heavy re-analysis.
- **PCE:** `applyVerifiedFix` rethrows `ProcessCanceledException` before its `Exception` catch, per project convention.
- **Placeholders:** none. Tasks 3–5 are mechanical call-site rewires verified by compilation + the full suite (the behavior they invoke — `fixVerified` — is unit- and integration-tested in Phase 2b; the shared helper is unit-tested in Task 2).
