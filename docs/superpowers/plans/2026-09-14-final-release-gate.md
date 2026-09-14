# Final Release Capability Gate — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Aegis Debug 3.0.0 as an honest final release — every reachable surface either works or says plainly that it is not finished — via one capability gate, seven targeted fixes, documentation reconciled against measured counts, and a reproducible demo.

**Architecture:** A single `AegisCapabilityGate` object holds the only enable/disable decision. Seven guard sites sit at chokepoints that already exist in the codebase (`UIEventRouter.handle`, `AnalysisOrchestrator.applyVerifiedFix`, …) rather than scattering conditionals across 8 fixers, 13 actions and the webview. User-initiated surfaces raise a native IDE dialog; background passes skip silently. The dialog presenter is an injectable seam so the gate is testable without a UI, matching the existing `applyVerifiedFix(fixVerified = …)` pattern.

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform Gradle Plugin 2.14.0, `intellijIdeaCommunity("2024.3.2")`, JUnit via `kotlin.test`, mockk, `BasePlatformTestCase` for IDE-fixture tests, React 18 + Vite for the webview.

**Spec:** `docs/superpowers/specs/2026-09-14-final-release-gate-design.md`

## Global Constraints

- **JBR required for every Gradle invocation.** `instrumentTestCode` probes for a `Packages` directory that only exists in a JetBrains Runtime. Before any `./gradlew` command: `export JAVA_HOME=$(find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1) && export PATH=$JAVA_HOME/bin:$PATH`
- **PCE rethrow.** Every `catch (e: Exception)` in production code must begin `if (e is ProcessCanceledException) throw e`, before any logging.
- **Analysis API chokepoint.** Kotlin type resolution goes through `parser/KotlinAnalysisHelpers.withKtAnalysis` only. Never call `analyze { }` directly.
- **Facade single-writer.** Collaborators read project state via `GhostDebuggerService.getInstance(project)` and write only through `service.updateIssues(...)`. Never assign a collaborator's state field directly.
- **No `trimIndent()` on templates that interpolate multi-line content.** Use `StringBuilder`. (V1.4 report-indentation bug.)
- **Analyzer bias.** When resolution is uncertain, do not flag. False positives cost more than false negatives.
- **Gate message, verbatim, in exactly one place:** `"This feature will be built further when there's time."`
- **Dialog title, verbatim:** `"Aegis Debug"`
- **Nothing is enabled in 3.0.0.** `AegisCapabilityGate.enabled` ships as `emptySet()`.
- **Version source of truth** is `build.gradle.kts` (`3.0.0`); the inline `<version>` in `plugin.xml` must match.
- **Commit style:** Conventional Commits, `<type>(<scope>): <subject>`, imperative, ≤72 chars, no trailing period.
- **Do not push.** Work on `feat/final-release-gate`, merge to `main` locally at Task 13.

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `src/main/kotlin/com/ghostdebugger/AegisCapability.kt` | The enum: one constant per gated capability, each carrying its user-facing `label` and its one-sentence `why` |
| `src/main/kotlin/com/ghostdebugger/AegisCapabilityGate.kt` | The single enable/disable decision, the two guard entry points, and the injectable dialog seam |
| `src/test/kotlin/com/ghostdebugger/AegisCapabilityGateTest.kt` | Gate semantics: shipped state, block-vs-skip, message composition |
| `src/test/kotlin/com/ghostdebugger/DocumentationCountsTest.kt` | Pins analyzer/fixer/inspection/action counts in docs to the code |
| `docs/IMPLEMENTATION_STATUS.md` | Per-capability verdict, reason and `file:line` evidence |
| `docs/audit-2026-09-final-release.md` | The real audit record, replacing the stale all-PASS one |
| `samples/aegis-demo/**` | TypeScript/React sample that triggers seven rules with no external dependency |
| `samples/aegis-demo/DEMO.md` | Exact reviewer steps, expected findings, and what will not happen |

**Modified:** `UIEventRouter.kt`, `AnalysisOrchestrator.kt`, `actions/ApplyAllFixesAction.kt`, `actions/ExplainSystemAction.kt`, `actions/ShowInNeuroMapAction.kt`, `actions/ReanalyzeFileAction.kt`, `actions/ExportReportAction.kt`, `actions/SuppressFindingAction.kt`, `analysis/AnalysisEngine.kt`, `ProblemsViewCoordinator.kt`, `store/DebugObserver.kt`, `DebugSessionCoordinator.kt`, `settings/GhostDebuggerConfigurable.kt`, `settings/GhostDebuggerSettings.kt`, `ai/OllamaService.kt`, `ReportExporter.kt`, `README.md`, `CHANGELOG.md`, `DATA_HANDLING.md`, `AGENTS.md`, `plugin.xml`, `site/index.html`.

**Deleted:** `toolwindow/ConfidencePill.kt`, `fix/AegisQuickFixIntentionAction.kt`, `docs/audit-2026-06-post-v2.md`.

---

### Task 1: The capability gate

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/AegisCapability.kt`
- Create: `src/main/kotlin/com/ghostdebugger/AegisCapabilityGate.kt`
- Test: `src/test/kotlin/com/ghostdebugger/AegisCapabilityGateTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `AegisCapability` (enum, 8 constants, properties `label: String` and `why: String`); `AegisCapabilityGate.isEnabled(c: AegisCapability): Boolean`; `AegisCapabilityGate.blockIfGated(project: Project?, c: AegisCapability): Boolean` (returns `true` when the caller must abort); `AegisCapabilityGate.skipIfGated(c: AegisCapability): Boolean` (returns `true` when the caller must skip, never shows UI); `AegisCapabilityGate.messageFor(c: AegisCapability): String`; internal seam `AegisCapabilityGate.presenter: (Project?, AegisCapability) -> Unit`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger

import com.intellij.openapi.project.Project
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AegisCapabilityGateTest {

    @AfterTest
    fun restorePresenter() {
        AegisCapabilityGate.resetPresenterForTest()
    }

    @Test
    fun `no capability is enabled in this release`() {
        AegisCapability.entries.forEach { capability ->
            assertFalse(
                AegisCapabilityGate.isEnabled(capability),
                "$capability must ship disabled in 3.0.0"
            )
        }
    }

    @Test
    fun `blockIfGated reports the capability and returns true`() {
        val shown = mutableListOf<AegisCapability>()
        AegisCapabilityGate.setPresenterForTest { _, capability -> shown += capability }

        val blocked = AegisCapabilityGate.blockIfGated(null as Project?, AegisCapability.FIX_APPLICATION)

        assertTrue(blocked, "a gated capability must tell its caller to abort")
        assertEquals(listOf(AegisCapability.FIX_APPLICATION), shown)
    }

    @Test
    fun `skipIfGated never presents anything`() {
        val shown = mutableListOf<AegisCapability>()
        AegisCapabilityGate.setPresenterForTest { _, capability -> shown += capability }

        val skipped = AegisCapabilityGate.skipIfGated(AegisCapability.AI_ANALYSIS)

        assertTrue(skipped, "a gated capability must tell its background caller to skip")
        assertTrue(shown.isEmpty(), "a background pass must never raise a modal dialog")
    }

    @Test
    fun `message carries the label, the roadmap sentence and the reason`() {
        val message = AegisCapabilityGate.messageFor(AegisCapability.FIX_APPLICATION)

        assertContains(message, AegisCapability.FIX_APPLICATION.label)
        assertContains(message, "This feature will be built further when there's time.")
        assertContains(message, AegisCapability.FIX_APPLICATION.why)
    }

    @Test
    fun `every capability declares a non-blank label and reason`() {
        AegisCapability.entries.forEach { capability ->
            assertTrue(capability.label.isNotBlank(), "$capability has no label")
            assertTrue(capability.why.isNotBlank(), "$capability has no reason")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=$(find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1)
export PATH=$JAVA_HOME/bin:$PATH
./gradlew test --tests 'com.ghostdebugger.AegisCapabilityGateTest'
```

Expected: compilation failure — `Unresolved reference: AegisCapability`.

- [ ] **Step 3: Write the enum**

```kotlin
package com.ghostdebugger

/**
 * A user-visible capability that Aegis Debug 3.0.0 does not ship enabled.
 *
 * Each constant owns the two strings the gate needs: [label] names the surface the user just
 * reached, and [why] is the one sentence explaining what state the work is actually in. The
 * dialog, `docs/IMPLEMENTATION_STATUS.md` and the changelog all read these, so the wording
 * cannot drift between them.
 */
enum class AegisCapability(val label: String, val why: String) {
    FIX_APPLICATION(
        label = "Apply Fix",
        why = "Aegis Debug 3.0 ships as a read-only analysis tool. The fix engine is implemented " +
            "and tested, but fix application is not enabled in this release.",
    ),
    AI_EXPLANATION(
        label = "AI Explanation",
        why = "Issue and system explanations require a local Ollama server or an OpenAI key. The " +
            "provider transport is untested and the Ollama path has a known defect, so the " +
            "feature is disabled rather than shipped unreliable.",
    ),
    AI_ANALYSIS(
        label = "AI Analysis Pass",
        why = "The AI analysis pass is disabled in this release. All findings you see come from " +
            "the deterministic static analyzers.",
    ),
    JVM_DEPENDENCY_GRAPH(
        label = "Dependency Graph for Kotlin and Java",
        why = "Internal graph edges are resolved only for relative imports, so Kotlin and Java " +
            "projects produce an edgeless graph. Impact analysis and cycle detection are " +
            "therefore available for TypeScript and JavaScript only.",
    ),
    RULE_PACKS(
        label = "Rule Packs",
        why = "The three bundled packs cannot currently match real code, so enabling them would " +
            "add controls that never produce a finding.",
    ),
    EXTERNAL_ANALYZERS(
        label = "External Analyzer SDK",
        why = "The loader exists, but there is no published SDK artifact and no documented " +
            "contract for third-party analyzers yet.",
    ),
    PROBLEMS_VIEW_EMIT(
        label = "Problems Tool Window Integration",
        why = "Publishing to the native Problems view violates the platform's threading contract " +
            "on 2024.3. Findings are available in the Aegis Debug tool window and in the editor.",
    ),
    DEBUGGER_CROSS_CHECK(
        label = "Debugger Cross-Check",
        why = "Runtime confirmation from a paused debug session is not reachable on IntelliJ IDEA " +
            "Community. Test-suite cross-check remains active.",
    ),
}
```

- [ ] **Step 4: Write the gate**

```kotlin
package com.ghostdebugger

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * The single place that decides whether a gated capability is available.
 *
 * Two entry points exist because the contexts differ. [blockIfGated] is for surfaces the user
 * just clicked, and raises a dialog. [skipIfGated] is for background work — an analysis pass, a
 * listener callback — where a modal dialog would itself be a defect; it is silent and logs once.
 *
 * Lifting the gate on a capability means adding it to [enabled]. Nothing else changes.
 */
object AegisCapabilityGate {

    /** Shown verbatim on every gated surface. */
    const val ROADMAP_SENTENCE: String = "This feature will be built further when there's time."

    private const val DIALOG_TITLE: String = "Aegis Debug"

    /** The only decision. Empty in 3.0.0 — every capability ships disabled. */
    private val enabled: Set<AegisCapability> = emptySet()

    private val log = logger<AegisCapabilityGate>()

    private val loggedOnce = java.util.Collections.synchronizedSet(mutableSetOf<AegisCapability>())

    private val defaultPresenter: (Project?, AegisCapability) -> Unit = { project, capability ->
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, messageFor(capability), DIALOG_TITLE)
        }
    }

    @Volatile
    private var presenter: (Project?, AegisCapability) -> Unit = defaultPresenter

    fun isEnabled(capability: AegisCapability): Boolean = capability in enabled

    /**
     * Guard for user-initiated surfaces. Presents the roadmap dialog and returns true when the
     * caller must abort. Returns false — and shows nothing — once the capability is enabled.
     */
    fun blockIfGated(project: Project?, capability: AegisCapability): Boolean {
        if (isEnabled(capability)) return false
        presenter(project, capability)
        return true
    }

    /**
     * Guard for background surfaces. Never shows UI. Returns true when the caller must skip.
     * Logs at most once per capability so a per-file analysis pass cannot flood the log.
     */
    fun skipIfGated(capability: AegisCapability): Boolean {
        if (isEnabled(capability)) return false
        if (loggedOnce.add(capability)) {
            log.info("Skipping gated capability ${capability.name}: ${capability.why}")
        }
        return true
    }

    /** Built with explicit concatenation, not a trimIndent template — see CLAUDE.md. */
    fun messageFor(capability: AegisCapability): String =
        capability.label + "\n\n" + ROADMAP_SENTENCE + "\n\n" + capability.why

    internal fun setPresenterForTest(p: (Project?, AegisCapability) -> Unit) {
        presenter = p
    }

    internal fun resetPresenterForTest() {
        presenter = defaultPresenter
        loggedOnce.clear()
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew test --tests 'com.ghostdebugger.AegisCapabilityGateTest'
```

Expected: 5 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/AegisCapability.kt \
        src/main/kotlin/com/ghostdebugger/AegisCapabilityGate.kt \
        src/test/kotlin/com/ghostdebugger/AegisCapabilityGateTest.kt
git commit -m "feat(gate): add AegisCapabilityGate with the single release decision"
```

---

### Task 2: Guard the webview — `UIEventRouter.handle()`

This one site covers every fix and AI surface the React UI can reach. `DetailPanel.tsx` sends
`FIX_REQUESTED` (:668, :706, :809), `APPLY_FIX` (:685), and the toolbar sends `EXPLAIN_SYSTEM`;
`NodeClicked` streams an AI explanation at `UIEventRouter.kt:105`; `ImpactRequested` walks the
reverse adjacency list that is empty on JVM projects.

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/UIEventRouter.kt:51-72`
- Test: `src/test/kotlin/com/ghostdebugger/UIEventRouterGateTest.kt` (create)

**Interfaces:**
- Consumes: `AegisCapabilityGate.blockIfGated`, `AegisCapability.{FIX_APPLICATION, AI_EXPLANATION, JVM_DEPENDENCY_GRAPH}` from Task 1.
- Produces: `UIEventRouter.gatedCapabilityFor(event: UIEvent): AegisCapability?` — internal, pure, returns the capability blocking this event or null when the event is ungated. Task 6's tests reuse it.

- [ ] **Step 1: Write the failing test**

`gatedCapabilityFor` is pure, so this needs no Project and no IDE fixture.

```kotlin
package com.ghostdebugger

import com.ghostdebugger.bridge.UIEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UIEventRouterGateTest {

    @Test
    fun `fix surfaces map to FIX_APPLICATION`() {
        assertEquals(
            AegisCapability.FIX_APPLICATION,
            UIEventRouter.gatedCapabilityFor(UIEvent.ApplyFixRequested("issue-1"))
        )
        assertEquals(
            AegisCapability.FIX_APPLICATION,
            UIEventRouter.gatedCapabilityFor(UIEvent.FixRequested("issue-1", "node-1"))
        )
    }

    @Test
    fun `explanation surfaces map to AI_EXPLANATION`() {
        assertEquals(
            AegisCapability.AI_EXPLANATION,
            UIEventRouter.gatedCapabilityFor(UIEvent.ExplainSystemRequested)
        )
    }

    @Test
    fun `impact analysis maps to JVM_DEPENDENCY_GRAPH`() {
        assertEquals(
            AegisCapability.JVM_DEPENDENCY_GRAPH,
            UIEventRouter.gatedCapabilityFor(UIEvent.ImpactRequested("node-1"))
        )
    }

    @Test
    fun `analysis, export and navigation stay ungated`() {
        assertNull(UIEventRouter.gatedCapabilityFor(UIEvent.AnalyzeRequested))
        assertNull(UIEventRouter.gatedCapabilityFor(UIEvent.ExportReportRequested))
        assertNull(UIEventRouter.gatedCapabilityFor(UIEvent.NodeDoubleClicked("node-1")))
        assertNull(UIEventRouter.gatedCapabilityFor(UIEvent.DismissIssue("issue-1")))
    }
}
```

Before writing it, confirm the exact constructor shapes:

```bash
sed -n '16,44p' src/main/kotlin/com/ghostdebugger/bridge/UIEvent.kt
```

Adjust the constructor arguments above to match; do not guess.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests 'com.ghostdebugger.UIEventRouterGateTest'
```

Expected: `Unresolved reference: gatedCapabilityFor`.

- [ ] **Step 3: Add the mapping and the guard**

Add to the `companion object` of `UIEventRouter` (create one if absent — check first with
`grep -n "companion object" src/main/kotlin/com/ghostdebugger/UIEventRouter.kt`):

```kotlin
        /**
         * The capability a UI event depends on, or null when the event is ungated.
         *
         * NodeClicked is deliberately absent: the click itself does useful ungated work, and only
         * its AI-explanation branch is gated — see the guard inside handleNodeClicked.
         */
        internal fun gatedCapabilityFor(event: UIEvent): AegisCapability? = when (event) {
            is UIEvent.FixRequested,
            is UIEvent.ApplyFixRequested -> AegisCapability.FIX_APPLICATION
            is UIEvent.ExplainSystemRequested -> AegisCapability.AI_EXPLANATION
            is UIEvent.ImpactRequested -> AegisCapability.JVM_DEPENDENCY_GRAPH
            else -> null
        }
```

Then guard the dispatcher. `handle` currently opens at line 51; insert immediately inside it,
before the existing `when (event)`:

```kotlin
    fun handle(event: UIEvent) {
        gatedCapabilityFor(event)?.let { capability ->
            if (AegisCapabilityGate.blockIfGated(project, capability)) return
        }
        when (event) {
```

- [ ] **Step 4: Guard the AI branch of `handleNodeClicked`**

`handleNodeClicked` (:91) replays a cached explanation when one exists and otherwise streams a new
one from the AI service. Only the second half is gated. Insert the guard immediately before the
`scope.launch { ... resolveAiService() ... }` block at :105, leaving the cached-explanation replay
at :98-103 untouched:

```kotlin
        if (AegisCapabilityGate.skipIfGated(AegisCapability.AI_EXPLANATION)) return
```

`skipIfGated`, not `blockIfGated`: a node click is a navigation gesture, and popping a modal on
every click would be hostile. The webview notice from Task 11 covers the discoverability.

- [ ] **Step 5: Run the test and the full suite**

```bash
./gradlew test --tests 'com.ghostdebugger.UIEventRouterGateTest'
./gradlew test
```

Expected: the new tests PASS; the pre-existing 508 still pass.

If a pre-existing `UIEventRouter` test now fails because it drove a fix or explain event, that test
is asserting gated behaviour. Do not weaken the guard: add
`AegisCapabilityGate.setPresenterForTest { _, _ -> }` in its setup and assert the gated outcome
instead, with a comment naming the capability.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/UIEventRouter.kt \
        src/test/kotlin/com/ghostdebugger/UIEventRouterGateTest.kt
git commit -m "feat(gate): guard fix, explain and impact events in UIEventRouter"
```

---

### Task 3: Guard the editor fix surfaces — `AnalysisOrchestrator.applyVerifiedFix()`

One site covers both remaining fix entry points: the Alt+Enter intention
(`intentions/AegisQuickFixIntentionAction.kt:51`) and the inspection quick-fix
(`inspections/AegisLocalInspection.kt:31`).

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt:490-513`
- Test: `src/test/kotlin/com/ghostdebugger/ApplyVerifiedFixGateTest.kt` (create)

**Interfaces:**
- Consumes: `AegisCapabilityGate.blockIfGated`, `AegisCapability.FIX_APPLICATION`.
- Produces: `applyVerifiedFix` returns a completed `Job` without invoking its `fixVerified` seam when the capability is gated. Later tasks rely on the seam still being injectable.

- [ ] **Step 1: Write the failing test**

The existing signature is
`applyVerifiedFix(issue, virtualFile, content, baselineProvider = …, fixVerified = …): Job`. The test
injects both seams and asserts neither runs.

```kotlin
package com.ghostdebugger

import com.ghostdebugger.model.FixApplyResult
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

class ApplyVerifiedFixGateTest : BasePlatformTestCase() {

    override fun tearDown() {
        AegisCapabilityGate.resetPresenterForTest()
        super.tearDown()
    }

    fun `test gated fix never reaches the fix engine`() {
        val shown = AtomicBoolean(false)
        AegisCapabilityGate.setPresenterForTest { _, _ -> shown.set(true) }

        val engineRan = AtomicBoolean(false)
        val virtualFile = myFixture.configureByText("Sample.kt", "val x = 1\n").virtualFile
        val issue = TestIssues.nullSafety(virtualFile.path, line = 1)

        val job = AnalysisOrchestrator.getInstance(project).applyVerifiedFix(
            issue = issue,
            virtualFile = virtualFile,
            content = "val x = 1\n",
            baselineProvider = { emptyList() },
            fixVerified = { _, _, _, _ ->
                engineRan.set(true)
                FixApplyResult.Rejected("must not be called")
            },
        )
        runBlocking { job.join() }

        assertFalse("the fix engine must not run while FIX_APPLICATION is gated", engineRan.get())
        assertTrue("the user must be told why nothing happened", shown.get())
    }
}
```

`TestIssues.nullSafety(path, line)` may not exist. Check first:

```bash
grep -rn "object TestIssues\|fun nullSafety" src/test/kotlin/com/ghostdebugger/testutil/
```

If absent, construct the `Issue` inline using the real constructor — read it from
`src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt` and fill every required field. Do not
invent a helper.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests 'com.ghostdebugger.ApplyVerifiedFixGateTest'
```

Expected: FAIL — `engineRan` is true, because nothing gates the path yet.

- [ ] **Step 3: Add the guard**

`applyVerifiedFix` opens its body with `: Job = scope.launch {`. The guard must run *before* the
coroutine is launched so no work is scheduled, and the function must still return a `Job`. Change
the body to:

```kotlin
    ): Job {
        if (AegisCapabilityGate.blockIfGated(project, AegisCapability.FIX_APPLICATION)) {
            return scope.launch { /* gated: nothing to do */ }
        }
        return scope.launch {
            try {
                val baseline = baselineProvider(virtualFile)
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
    }
```

Returning an already-launched empty `Job` rather than a cancelled one keeps every existing
`job.join()` call site working unchanged.

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew test --tests 'com.ghostdebugger.ApplyVerifiedFixGateTest'
```

Expected: PASS.

- [ ] **Step 5: Run the full suite**

```bash
./gradlew test
```

Expected: 0 failures. Existing `applyVerifiedFix` tests that asserted a successful apply now hit
the gate — convert each to assert the gated outcome, as described in Task 2 Step 5.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt \
        src/test/kotlin/com/ghostdebugger/ApplyVerifiedFixGateTest.kt
git commit -m "feat(gate): guard applyVerifiedFix for intention and inspection fixes"
```

---

### Task 4: Guard the two menu actions

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/actions/ApplyAllFixesAction.kt:12-33`
- Modify: `src/main/kotlin/com/ghostdebugger/actions/ExplainSystemAction.kt:10-15`
- Test: `src/test/kotlin/com/ghostdebugger/actions/GatedActionsTest.kt` (create)

**Interfaces:**
- Consumes: `AegisCapabilityGate.blockIfGated`, `AegisCapability.{FIX_APPLICATION, AI_EXPLANATION}`.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.actions

import com.ghostdebugger.AegisCapability
import com.ghostdebugger.AegisCapabilityGate
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GatedActionsTest : BasePlatformTestCase() {

    override fun tearDown() {
        AegisCapabilityGate.resetPresenterForTest()
        super.tearDown()
    }

    private fun capabilityShownBy(actionId: String, event: AnActionEvent): AegisCapability? {
        var seen: AegisCapability? = null
        AegisCapabilityGate.setPresenterForTest { _, capability -> seen = capability }
        ActionManager.getInstance().getAction(actionId).actionPerformed(event)
        return seen
    }

    fun `test Apply All Fixes reports the fix capability`() {
        val file = myFixture.configureByText("Sample.ts", "let a = null\na.b\n").virtualFile
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .build()
        val shown = capabilityShownBy(
            "GhostDebugger.ApplyAllFixes",
            TestActionEvent.createTestEvent(context)
        )
        assertEquals(AegisCapability.FIX_APPLICATION, shown)
    }

    fun `test Explain System reports the explanation capability`() {
        val shown = capabilityShownBy("GhostDebugger.ExplainSystem", TestActionEvent())
        assertEquals(AegisCapability.AI_EXPLANATION, shown)
    }
}
```

`TestActionEvent`'s factory signature differs across platform versions. Confirm which overload
2024.3.2 exposes, and whether this repo already has an idiom for it, before relying on the code
above:

```bash
grep -rn "TestActionEvent\|SimpleDataContext" src/test/kotlin/ | head
sed -n '1,60p' src/test/kotlin/com/ghostdebugger/actions/PluginActionsTest.kt
```

If an idiom already exists in `PluginActionsTest`, use it verbatim instead. Imports needed for the
version above: `com.intellij.openapi.actionSystem.CommonDataKeys` and
`com.intellij.openapi.actionSystem.impl.SimpleDataContext`.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests 'com.ghostdebugger.actions.GatedActionsTest'
```

Expected: FAIL — `shown` is null, nothing is gated yet.

- [ ] **Step 3: Guard `ApplyAllFixesAction`**

Replace the whole body of `actionPerformed`. The existing loop is dead anyway —
`Issue.suggestedFix` is never assigned — and it is unsafe if revived, because every
`codeFix.toFixPlan(document.text)` is computed against text captured before the first apply shifts
the offsets. Deleting it removes a latent file-corruption bug along with the no-op.

```kotlin
class ApplyAllFixesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        AegisCapabilityGate.blockIfGated(e.project, AegisCapability.FIX_APPLICATION)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabled = project != null && virtualFile != null && !virtualFile.isDirectory
    }
}
```

Remove the now-unused imports: `GhostDebuggerService`, `FixPlanApplicator`, `toFixPlan`,
`FileDocumentManager`. Add `com.ghostdebugger.AegisCapability` and
`com.ghostdebugger.AegisCapabilityGate`.

- [ ] **Step 4: Guard `ExplainSystemAction`**

Guard before showing the tool window, so a gated capability does not also force a panel open:

```kotlin
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (AegisCapabilityGate.blockIfGated(project, AegisCapability.AI_EXPLANATION)) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("GhostDebugger")
        toolWindow?.show()
        GhostDebuggerService.getInstance(project).handleUIEvent(UIEvent.ExplainSystemRequested)
    }
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew test --tests 'com.ghostdebugger.actions.GatedActionsTest'
./gradlew test
```

Expected: new tests PASS, full suite 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/actions/ApplyAllFixesAction.kt \
        src/main/kotlin/com/ghostdebugger/actions/ExplainSystemAction.kt \
        src/test/kotlin/com/ghostdebugger/actions/GatedActionsTest.kt
git commit -m "feat(gate): guard Apply All Fixes and Explain System actions"
```

---

### Task 5: Skip the gated background passes — `AnalysisEngine`

Three background paths, all silent. `runAiPass` (:199) dispatches to the two provider passes; the
external-analyzer block sits inside `doStaticPasses` at :110-119; rule packs enter through
`RulePackService.packRules()`, consumed by `CustomRuleAnalyzer`.

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt:199-214` and `:110-119`
- Modify: `src/main/kotlin/com/ghostdebugger/rules/RulePackService.kt:66` (`packRules`)
- Test: `src/test/kotlin/com/ghostdebugger/analysis/GatedPassesTest.kt` (create)

**Interfaces:**
- Consumes: `AegisCapabilityGate.skipIfGated`, `AegisCapability.{AI_ANALYSIS, EXTERNAL_ANALYZERS, RULE_PACKS}`.
- Produces: `runAiPass` returns `EngineStatusPayload(provider = "STATIC", status = EngineStatus.DISABLED, …)` whenever `AI_ANALYSIS` is gated, regardless of the configured provider. The webview engine pill reads this, so it stays honest.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.rules.RulePackService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertTrue

class GatedPassesTest : BasePlatformTestCase() {

    fun `test rule packs contribute no rules while gated`() {
        val rules = RulePackService.getInstance(project).packRules()
        assertTrue(rules.isEmpty(), "gated rule packs must contribute nothing, got $rules")
    }
}
```

The `runAiPass` assertion needs a configured provider plus an `AnalysisContext`. Build it from the
existing integration test rather than from scratch:

```bash
sed -n '1,60p' src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineIntegrationTest.kt
```

Add a second test that sets `aiProvider = AIProvider.OLLAMA`, runs `AnalysisEngine.analyze(...)`,
and asserts the returned `engineStatus.status == EngineStatus.DISABLED` and that no AI-sourced
issue appears. Restore the setting in `tearDown`.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests 'com.ghostdebugger.analysis.GatedPassesTest'
```

Expected: FAIL — the three bundled packs still load, so `packRules()` is non-empty.

- [ ] **Step 3: Skip the AI pass**

In `runAiPass`, before the `when (settings.aiProvider)`:

```kotlin
        if (AegisCapabilityGate.skipIfGated(AegisCapability.AI_ANALYSIS)) {
            return emptyList<Issue>() to EngineStatusPayload(
                provider = "STATIC",
                status = EngineStatus.DISABLED,
                message = "AI analysis is not enabled in this release; static-only run.",
            )
        }
```

- [ ] **Step 4: Skip the external analyzers**

Replace the `runCatching { … }` external block in `doStaticPasses` (:110-119) with:

```kotlin
        val externalIssues = if (AegisCapabilityGate.skipIfGated(AegisCapability.EXTERNAL_ANALYZERS)) {
            emptyList()
        } else {
            runCatching {
                val externalLoader = ExternalAnalyzerLoader.getInstance(context.project)
                externalLoader.analyzers().flatMap { analyzer ->
                    externalLoader.runExternalAnalyzer(analyzer, lateContext)
                }
            }.getOrElse { e ->
                if (e is ProcessCanceledException) throw e
                emptyList()
            }
        }
```

Per CLAUDE.md, import `ExternalAnalyzerLoader` and `ProcessCanceledException` rather than keeping
the inline fully-qualified names the current code uses.

- [ ] **Step 5: Skip the rule packs**

At the top of `RulePackService.packRules()`:

```kotlin
        if (AegisCapabilityGate.skipIfGated(AegisCapability.RULE_PACKS)) return emptyList()
```

Gate `packRules` rather than `availablePacks`: the latter feeds the "what exists" listing that
`docs/IMPLEMENTATION_STATUS.md` describes, and it should keep reporting the packs as present but
disabled.

- [ ] **Step 6: Run tests**

```bash
./gradlew test --tests 'com.ghostdebugger.analysis.GatedPassesTest'
./gradlew test
```

Expected: new tests PASS, full suite 0 failures. Existing rule-pack tests asserting that a bundled
pack yields rules now assert the gated result; convert them as in Task 2 Step 5.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt \
        src/main/kotlin/com/ghostdebugger/rules/RulePackService.kt \
        src/test/kotlin/com/ghostdebugger/analysis/GatedPassesTest.kt
git commit -m "feat(gate): skip AI, external analyzer and rule pack passes"
```

---

### Task 6: Skip the two broken observers

`ProblemsViewCoordinator.publishToProblemsView` (:37) calls `WolfTheProblemSolver.reportProblems`
from an `invokeLater`, which the 2024.3 implementation rejects. `DebugObserver.start` (:42) and
`DebugSessionCoordinator.performDebugSessionCrossCheck` (:153) are two uncoordinated
implementations of a cross-check that cannot fire on IDEA Community.

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/ProblemsViewCoordinator.kt:37`
- Modify: `src/main/kotlin/com/ghostdebugger/store/DebugObserver.kt:42`
- Modify: `src/main/kotlin/com/ghostdebugger/DebugSessionCoordinator.kt:153`
- Test: `src/test/kotlin/com/ghostdebugger/GatedObserversTest.kt` (create)

**Interfaces:**
- Consumes: `AegisCapabilityGate.skipIfGated`, `AegisCapability.{PROBLEMS_VIEW_EMIT, DEBUGGER_CROSS_CHECK}`.
- Produces: nothing new. `DebugSessionCoordinator.sendCurrentDebugFrame` stays ungated — the debug frame panel in the webview is a working read-only surface.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger

import com.ghostdebugger.store.RuntimeEvidenceStore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertTrue

class GatedObserversTest : BasePlatformTestCase() {

    fun `test starting the problems coordinator publishes nothing`() {
        val coordinator = ProblemsViewCoordinator.getInstance(project)
        coordinator.start()
        assertTrue(
            coordinator.publishedFilePathsForTest().isEmpty(),
            "PROBLEMS_VIEW_EMIT is gated; nothing may be published"
        )
    }

    fun `test debugger cross-check records no evidence`() {
        val before = RuntimeEvidenceStore.getInstance(project).snapshotForTest()
        DebugSessionCoordinator.getInstance(project)
            .performDebugSessionCrossCheck(session = null, filePath = "/tmp/Sample.kt", line = 1)
        assertEquals(before, RuntimeEvidenceStore.getInstance(project).snapshotForTest())
    }
}
```

`publishedFilePathsForTest()` and `snapshotForTest()` may not exist, and
`performDebugSessionCrossCheck` takes a non-null `XDebugSession`. Check all three:

```bash
grep -n "ForTest\|lastPublishedByFile" src/main/kotlin/com/ghostdebugger/ProblemsViewCoordinator.kt
grep -n "fun \|ForTest" src/main/kotlin/com/ghostdebugger/store/RuntimeEvidenceStore.kt | head
sed -n '153,160p' src/main/kotlin/com/ghostdebugger/DebugSessionCoordinator.kt
```

Prefer asserting on existing public state. Add an `internal fun …ForTest()` accessor only where no
observable surface exists, and keep it a one-line read of the field the production code already
maintains. If the cross-check genuinely cannot be called without a live `XDebugSession`, drop that
second test and assert the guard through `AegisCapabilityGate.skipIfGated` directly instead —
do not mock the platform's debugger.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests 'com.ghostdebugger.GatedObserversTest'
```

- [ ] **Step 3: Guard the Problems view emit**

First line of `publishToProblemsView`:

```kotlin
        if (AegisCapabilityGate.skipIfGated(AegisCapability.PROBLEMS_VIEW_EMIT)) return
```

Guarding the publish rather than `start()` keeps the listener registration and its disposal
symmetric, so `dispose()` still removes what `start()` added.

- [ ] **Step 4: Guard both debugger cross-checks**

First line of `DebugObserver.start()`:

```kotlin
        if (AegisCapabilityGate.skipIfGated(AegisCapability.DEBUGGER_CROSS_CHECK)) return
```

First line of `DebugSessionCoordinator.performDebugSessionCrossCheck(...)`:

```kotlin
        if (AegisCapabilityGate.skipIfGated(AegisCapability.DEBUGGER_CROSS_CHECK)) return
```

- [ ] **Step 5: Run tests**

```bash
./gradlew test --tests 'com.ghostdebugger.GatedObserversTest'
./gradlew test
```

Expected: new tests PASS, full suite 0 failures. `ProblemsViewCoordinatorTest` will need
converting — note in its docstring that it was one of the tests giving false assurance, since it
passed while the real `reportProblems` call violated the platform threading contract.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/ProblemsViewCoordinator.kt \
        src/main/kotlin/com/ghostdebugger/store/DebugObserver.kt \
        src/main/kotlin/com/ghostdebugger/DebugSessionCoordinator.kt \
        src/test/kotlin/com/ghostdebugger/GatedObserversTest.kt
git commit -m "feat(gate): skip Problems view emit and debugger cross-checks"
```

---

### Task 7: Repair the four stranded actions

These are not gated — they are features that already work, or nearly work, and are stranded by a
one-line defect each. Fixing them is what keeps the release from being nothing but dialogs.

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/actions/ShowInNeuroMapAction.kt:15`
- Modify: `src/main/kotlin/com/ghostdebugger/actions/ReanalyzeFileAction.kt:16`
- Modify: `src/main/kotlin/com/ghostdebugger/actions/SuppressFindingAction.kt:21`
- Modify: `src/main/kotlin/com/ghostdebugger/store/SuppressionMemoryService.kt` (add one method)
- Modify: `src/main/kotlin/com/ghostdebugger/ReportExporter.kt:35-40`
- Test: `src/test/kotlin/com/ghostdebugger/actions/RepairedActionsTest.kt` (create)

**Interfaces:**
- Consumes: `AnalysisOrchestrator.reanalyzeFile(filePath: String)` (exists, `AnalysisOrchestrator.kt:234`).
- Produces: `SuppressionMemoryService.suppressNow(fingerprint: String)` — raises the dismissal count to the configured threshold in one call, so `shouldAutoHide` returns true immediately. Task 11's status doc describes it.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.ghostdebugger.actions

import com.ghostdebugger.store.SuppressionMemoryService
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RepairedActionsTest : BasePlatformTestCase() {

    fun `test the registered tool window id resolves`() {
        assertNotNull(
            "ShowInNeuroMapAction must look the tool window up by its registered id, not its title",
            ToolWindowManager.getInstance(project).getToolWindow("GhostDebugger")
        )
        assertNull(
            "the display title is not a valid lookup key",
            ToolWindowManager.getInstance(project).getToolWindow("Aegis Debug")
        )
    }

    fun `test explicit suppression hides a finding on the first invocation`() {
        val service = SuppressionMemoryService.getInstance(project)
        val fingerprint = "test-fingerprint-1"

        assertFalse(service.shouldAutoHide(fingerprint))
        service.suppressNow(fingerprint)
        assertTrue(
            "an explicit suppress command must take effect immediately, not on the third press",
            service.shouldAutoHide(fingerprint)
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME=$(find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1)
export PATH=$JAVA_HOME/bin:$PATH
./gradlew test --tests 'com.ghostdebugger.actions.RepairedActionsTest'
```

Expected: the first test passes (it asserts the platform, not our code); the second fails with
`Unresolved reference: suppressNow`.

- [ ] **Step 3: Fix the tool window id**

`ShowInNeuroMapAction.kt:15` — the registered id is `GhostDebugger` (`plugin.xml`, kept for
backward compatibility with pinned user layouts); `Aegis Debug` is only the display title, so the
lookup returns null and the `toolWindow?.show { … }` safe call silently swallows the whole action:

```kotlin
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("GhostDebugger")
```

- [ ] **Step 4: Make Reanalyze Current File reanalyze the current file**

`ReanalyzeFileAction.kt:16` calls `service.analyzeProject()`, a full-project run, and discards the
`virtualFile` it already resolved. The targeted entry point exists and is used by two other
callers:

```kotlin
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (GhostDebuggerService.getInstance(project).isAnalyzing) return
        AnalysisOrchestrator.getInstance(project).reanalyzeFile(virtualFile.path)
    }
```

Add the `com.ghostdebugger.AnalysisOrchestrator` import.

- [ ] **Step 5: Add immediate suppression**

In `SuppressionMemoryService`, after `recordDismissal` (:24-27):

```kotlin
    /**
     * Suppress [fingerprint] immediately, as an explicit user command rather than an accumulated
     * dismissal. [recordDismissal] increments by one and is the right call for the implicit
     * "dismissed again" signal; an explicit "Suppress Finding" must not require the user to
     * invoke it `suppressionThreshold` times before anything happens.
     */
    fun suppressNow(fingerprint: String) = synchronized(lock) {
        val threshold = GhostDebuggerSettings.getInstance().snapshot().suppressionThreshold
        dismissCounts[fingerprint] = maxOf(dismissCounts.getOrDefault(fingerprint, 0), threshold)
    }
```

Then in `SuppressFindingAction.kt:21`, swap the call:

```kotlin
            SuppressionMemoryService.getInstance(project).suppressNow(issueToSuppress.fingerprint())
```

- [ ] **Step 6: Route the export message to a balloon**

`ReportExporter.export` reports "no analysis data" through `service.jcefBridge()?.sendError(...)`,
and the bridge is null until the tool window has been opened once — which `ExportReportAction`
never does. So the menu item is a silent no-op on first use, for a feature that otherwise works.
Replace the null-graph branch (`ReportExporter.kt:35-40`):

```kotlin
        if (graph == null) {
            val message = "No analysis data available. Run 'Analyze Project' first."
            NotificationGroupManager.getInstance()
                .getNotificationGroup("GhostDebugger")
                .createNotification("Aegis Debug", message, NotificationType.INFORMATION)
                .notify(project)
            scope.launch(Dispatchers.Swing) { service.jcefBridge()?.sendError(message) }
            return
        }
```

The balloon always reaches the user; the bridge call is kept so the webview still clears its
in-panel state when it *is* attached. Add the `NotificationGroupManager` and `NotificationType`
imports — copy the exact idiom from `AnalysisOrchestrator.notifyFixRejected`.

- [ ] **Step 7: Run tests**

```bash
./gradlew test --tests 'com.ghostdebugger.actions.RepairedActionsTest'
./gradlew test
```

Expected: all PASS, full suite 0 failures.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/actions/ShowInNeuroMapAction.kt \
        src/main/kotlin/com/ghostdebugger/actions/ReanalyzeFileAction.kt \
        src/main/kotlin/com/ghostdebugger/actions/SuppressFindingAction.kt \
        src/main/kotlin/com/ghostdebugger/store/SuppressionMemoryService.kt \
        src/main/kotlin/com/ghostdebugger/ReportExporter.kt \
        src/test/kotlin/com/ghostdebugger/actions/RepairedActionsTest.kt
git commit -m "fix(actions): repair tool window lookup, reanalyze scope, suppression and export"
```

---

### Task 8: Remove the five dead settings controls

Each of these is a control a reviewer will click first, and clicking it does nothing at all. The
fields have zero read sites anywhere in `src/` — verified by exhaustive grep — except
`showUnreached`, which reaches the webview reducer and is then read by no component.

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerConfigurable.kt` (lines 27-29, 33, 35, 175-191, 203-204, 232-234, 352-360, 379-388, 415-424, 445-453)
- Modify: `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt` (lines 26, 27, 32, 42, 44, 46, 94, 106-108)
- Test: `src/test/kotlin/com/ghostdebugger/settings/NoDeadSettingsTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. This is a deletion.

- [ ] **Step 1: Confirm each field really is dead**

Do not delete on the plan's word. Run this and expect each field to appear only in
`GhostDebuggerSettings.kt` and `GhostDebuggerConfigurable.kt`:

```bash
for f in autoAnalyzeOnOpen showInfoIssues analyzeOnlyChangedFiles coverageMode showUnreached nudgeShownOnce; do
  echo "=== $f ==="
  grep -rn "$f" src/ webview/src/ --include='*.kt' --include='*.ts' --include='*.tsx'
done
```

If any field has a genuine read site in analysis, UI or persistence logic, leave it in place and
record the discrepancy in the Task 11 status document instead of deleting it.

- [ ] **Step 2: Write the failing test**

```kotlin
package com.ghostdebugger.settings

import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Every control the settings panel shows must change behaviour somewhere. Five controls shipped
 * through 3.0.0-pre with no read site at all; this test stops them coming back.
 */
class NoDeadSettingsTest {

    private val removedFields = listOf(
        "autoAnalyzeOnOpen",
        "showInfoIssues",
        "analyzeOnlyChangedFiles",
        "coverageMode",
        "showUnreached",
        "nudgeShownOnce",
    )

    @Test
    fun `removed settings fields are absent from the state class`() {
        val source = java.io.File("src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt")
            .readText()
        removedFields.forEach { field ->
            assertFalse(source.contains(field), "$field was removed as dead; do not reintroduce it")
        }
    }

    @Test
    fun `removed settings controls are absent from the configurable`() {
        val source = java.io.File("src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerConfigurable.kt")
            .readText()
        removedFields.forEach { field ->
            assertFalse(source.contains(field), "$field was removed as dead; do not reintroduce it")
        }
    }
}
```

The test reads the source relative to the Gradle working directory, which for this project is the
repo root. Confirm with `grep -rn "java.io.File(\"src/" src/test/` — if no existing test uses this
idiom, verify the path resolves by running the test and reading the failure message before writing
the implementation.

- [ ] **Step 3: Run test to verify it fails**

```bash
./gradlew test --tests 'com.ghostdebugger.settings.NoDeadSettingsTest'
```

Expected: FAIL, six field names still present in both files.

- [ ] **Step 4: Delete the state fields**

In `GhostDebuggerSettings.State`, remove the six `var` declarations at lines 26, 27, 32, 42, 44 and
46. Remove the `coverageMode` clamp at line 94. Remove the `autoAnalyzeOnOpen` delegated property
at lines 106-108. Leave `suppressionThreshold` (:43) — it is live, read by
`SuppressionMemoryService.shouldAutoHide` and by the new `suppressNow`.

Removing persisted fields from a `PersistentStateComponent` is safe here: the XML deserializer
ignores unknown elements, so an existing `aegis.xml` with the old keys still loads.

- [ ] **Step 5: Delete the controls**

In `GhostDebuggerConfigurable`, remove: the five field declarations (27-29, 33, 35); their
construction (175-191, 203-204); their `formPanel.add(...)` calls (232-234 and the individual adds
for the three checkboxes); their `isModified` clauses (352-354, 358, 360); their `apply` writes
(379-381, 386, 388); their `reset` writes (415-417, 422, 424); and their `disposeUIResources`
nulling (445-447, 451, 453).

Line numbers shift as you delete. Work bottom-up — `disposeUIResources` first, construction last —
and after each block run `./gradlew compileKotlin` to keep the errors local.

- [ ] **Step 6: Run tests**

```bash
./gradlew test --tests 'com.ghostdebugger.settings.NoDeadSettingsTest'
./gradlew test
```

Expected: PASS, full suite 0 failures. Existing settings tests referencing a removed field must be
deleted along with it — they were asserting round-trip persistence of a value nothing consumed.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/settings/ \
        src/test/kotlin/com/ghostdebugger/settings/NoDeadSettingsTest.kt
git commit -m "fix(settings): remove five controls that had no read site"
```

---

### Task 9: Close the consent bypass and the Ollama stream defect

Both are gated in 3.0.0, so neither is user-reachable today. They are fixed anyway: the consent
bypass is the one defect that makes a **documented privacy guarantee false**, and leaving it to be
rediscovered when the gate lifts is how it ships.

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/UIEventRouter.kt` (its `resolveAiService`)
- Modify: `src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt:524-528`
- Modify: `src/main/kotlin/com/ghostdebugger/ai/OllamaService.kt:26-32` and `:59`
- Test: `src/test/kotlin/com/ghostdebugger/ai/CloudConsentTest.kt` (create)
- Test: `src/test/kotlin/com/ghostdebugger/ai/OllamaRequestBodyTest.kt` (create)

**Interfaces:**
- Consumes: `GhostDebuggerSettings.State.allowCloudUpload`, `AIProvider`, `AIServiceFactory.create`.
- Produces: both `resolveAiService()` methods return null for `AIProvider.OPENAI` when `allowCloudUpload` is false.

- [ ] **Step 1: Write the failing tests**

`settings.allowCloudUpload` is read in exactly one production site today —
`AnalysisEngine.kt:220`. Both `resolveAiService()` methods construct the OpenAI service without
consulting it, so the explain and fix paths upload code with no consent check.

```kotlin
package com.ghostdebugger.ai

import com.ghostdebugger.AnalysisOrchestrator
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CloudConsentTest : BasePlatformTestCase() {

    private lateinit var original: GhostDebuggerSettings.State

    override fun setUp() {
        super.setUp()
        original = GhostDebuggerSettings.getInstance().snapshot()
    }

    override fun tearDown() {
        GhostDebuggerSettings.getInstance().loadState(original)
        super.tearDown()
    }

    fun `test OpenAI is not constructed without cloud consent`() {
        GhostDebuggerSettings.getInstance().update {
            aiProvider = AIProvider.OPENAI
            allowCloudUpload = false
        }
        assertNull(
            "no OpenAI service may be created while Allow cloud upload is off",
            AnalysisOrchestrator.getInstance(project).resolveAiServiceForTest()
        )
    }
}
```

```kotlin
package com.ghostdebugger.ai

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * Ollama's /api/chat streams unless the request says otherwise. BaseAIService's Json is configured
 * with encodeDefaults = false, so `stream = false` was omitted from the body and the server replied
 * with NDJSON that the single-shot decoder could not parse. One assertion on the serialized body
 * would have caught it, so here it is.
 */
class OllamaRequestBodyTest {

    @Test
    fun `the non-streaming request states stream explicitly`() {
        val json = Json { encodeDefaults = false }
        val body = json.encodeToString(
            OllamaChatRequest.serializer(),
            OllamaChatRequest(
                model = "llama3",
                messages = listOf(ChatMessage(role = "user", content = "hi")),
                stream = false,
            )
        )
        assertContains(body, "\"stream\"", message = "body must pin stream, got: $body")
    }
}
```

`resolveAiServiceForTest()` does not exist; `resolveAiService()` is private
(`AnalysisOrchestrator.kt:524`). Add `internal fun resolveAiServiceForTest(): AIService? =
resolveAiService()` next to it, and confirm `GhostDebuggerSettings.update {}` and `loadState` are
the real API:

```bash
grep -n "fun update\|fun loadState\|fun snapshot" src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests 'com.ghostdebugger.ai.CloudConsentTest' --tests 'com.ghostdebugger.ai.OllamaRequestBodyTest'
```

Expected: consent test FAILS (a service is returned); body test FAILS (`"stream"` absent, because
`false` is the declared default and `encodeDefaults` is off).

- [ ] **Step 3: Gate OpenAI on consent in both resolvers**

`AnalysisOrchestrator.kt:524-528` currently reads:

```kotlin
    private fun resolveAiService(): AIService? {
        val settings = GhostDebuggerSettings.getInstance().snapshot()
        if (settings.aiProvider == AIProvider.NONE) return null
        return AIServiceFactory.create(settings, ApiKeyManager.getApiKey())
    }
```

Replace with:

```kotlin
    private fun resolveAiService(): AIService? {
        val settings = GhostDebuggerSettings.getInstance().snapshot()
        if (settings.aiProvider == AIProvider.NONE) return null
        // The consent checkbox must gate every cloud path, not just the analysis pass. Without
        // this, clicking a graph node or invoking a quick-fix uploaded code to OpenAI regardless.
        if (settings.aiProvider == AIProvider.OPENAI && !settings.allowCloudUpload) {
            log.info("OpenAI requested but 'Allow cloud upload' is off; refusing to create the service")
            return null
        }
        return AIServiceFactory.create(settings, ApiKeyManager.getApiKey())
    }
```

Apply the identical change to `UIEventRouter.resolveAiService()`. Find it with
`grep -n "resolveAiService" src/main/kotlin/com/ghostdebugger/UIEventRouter.kt` and keep the
comment in both places — the duplication is the point, since a future reader of either file needs
to know the rule.

- [ ] **Step 4: Invalidate the cached AI service**

`UIEventRouter` caches the resolved service for the session (`aiService ?: resolveAiService()`
with `?.also { aiService = it }`), so changing provider, model, endpoint or key in Settings has no
effect until IDE restart — and a user who *unticks* cloud consent keeps the live OpenAI service.
That makes Step 3 incomplete on its own. Replace the cache read so it re-resolves whenever the
settings snapshot differs from the one the cached service was built from:

```kotlin
    @Volatile
    private var aiServiceSettings: GhostDebuggerSettings.State? = null

    private fun currentAiService(): AIService? {
        val snapshot = GhostDebuggerSettings.getInstance().snapshot()
        if (aiService != null && aiServiceSettings == snapshot) return aiService
        val resolved = resolveAiService()
        aiService = resolved
        aiServiceSettings = if (resolved != null) snapshot else null
        return resolved
    }
```

Then replace each `aiService ?: resolveAiService()` in this file with `currentAiService()`. Find
them all first: `grep -n "aiService" src/main/kotlin/com/ghostdebugger/UIEventRouter.kt`. Keep the
existing `aiService` field — tests inject it directly; check with
`grep -rn "aiService" src/test/` before changing its visibility.

`State` must be a `data class` for `==` to compare by value. Verify:
`grep -n "data class State\|class State" src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt`.
If it is not a data class, compare the individual provider-relevant fields instead — do not add
`data` to a `PersistentStateComponent` state class as a side effect of this task.

- [ ] **Step 5: Pin the Ollama stream flag**

`OllamaService.kt:26-32` builds the non-streaming request. Make the flag explicit at both call
sites (:26 and :59 — check which one is the streaming path and pass `stream = true` there):

```kotlin
            OllamaChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt.trimIndent()),
                    ChatMessage(role = "user", content = userPrompt)
                ),
                stream = false,
            )
```

Passing the value explicitly is not enough on its own — `encodeDefaults = false` drops any value
equal to the declared default. Either annotate the property with
`@EncodeDefault(EncodeDefault.Mode.ALWAYS)` in `OllamaModels.kt:10`, or drop the default so the
parameter becomes required. Prefer the annotation: it keeps both call sites readable and makes the
requirement local to the model. Confirm the serialization version in use supports
`@EncodeDefault`: `grep -n "kotlin.plugin.serialization\|kotlinx-serialization" build.gradle.kts`.

- [ ] **Step 6: Run tests**

```bash
./gradlew test --tests 'com.ghostdebugger.ai.CloudConsentTest' --tests 'com.ghostdebugger.ai.OllamaRequestBodyTest'
./gradlew test
```

Expected: both PASS, full suite 0 failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/UIEventRouter.kt \
        src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt \
        src/main/kotlin/com/ghostdebugger/ai/ \
        src/test/kotlin/com/ghostdebugger/ai/CloudConsentTest.kt \
        src/test/kotlin/com/ghostdebugger/ai/OllamaRequestBodyTest.kt
git commit -m "fix(ai): honor cloud consent on every path and pin the Ollama stream flag"
```

---

### Task 10: Delete the dead code

Every item below was confirmed by exhaustive grep to have no production caller.

**Files:**
- Delete: `src/main/kotlin/com/ghostdebugger/toolwindow/ConfidencePill.kt`
- Delete: `src/main/kotlin/com/ghostdebugger/fix/AegisQuickFixIntentionAction.kt`
- Modify: `src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt` (remove `whatIf`, `jointFix`)
- Modify: `src/main/kotlin/com/ghostdebugger/ai/prompts/PromptExamples.kt` (remove `JOINT_FIX_EXAMPLES`)
- Modify: `src/main/kotlin/com/ghostdebugger/ai/AiJsonExtractor.kt` (remove `telemetrySnapshot`)
- Modify: `src/main/kotlin/com/ghostdebugger/ai/ApiKeyManager.kt:33` (remove `hasApiKey`)
- Modify: `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt:112` (remove `ParsedFile.exports`) — **conditional, see Step 3**
- Delete: `src/test/kotlin/com/ghostdebugger/ai/prompts/PromptTemplatesFewShotTest.kt` (the only `jointFix` caller)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. Deletion only.

- [ ] **Step 1: Re-confirm before deleting**

```bash
for sym in whatIf jointFix JOINT_FIX_EXAMPLES telemetrySnapshot hasApiKey ConfidencePill; do
  echo "=== $sym ==="; grep -rn "$sym" src/ webview/src/ --include='*.kt' --include='*.tsx' --include='*.ts'
done
grep -rn "AegisQuickFixIntentionAction" src/ src/main/resources/META-INF/plugin.xml
```

Expected: each appears only at its declaration, plus test-only references for `jointFix` /
`JOINT_FIX_EXAMPLES`. `plugin.xml` must reference **`com.ghostdebugger.intentions.AegisQuickFixIntentionAction`**
(the live one) and not the `fix/` duplicate. If `plugin.xml` points at the `fix/` copy, delete the
`intentions/` one instead and stop — the plan's assumption was inverted.

- [ ] **Step 2: Delete the unambiguous items**

```bash
git rm src/main/kotlin/com/ghostdebugger/toolwindow/ConfidencePill.kt
git rm src/main/kotlin/com/ghostdebugger/fix/AegisQuickFixIntentionAction.kt
git rm src/test/kotlin/com/ghostdebugger/ai/prompts/PromptTemplatesFewShotTest.kt
```

Then remove `PromptTemplates.whatIf`, `PromptTemplates.jointFix`,
`PromptExamples.JOINT_FIX_EXAMPLES`, `AiJsonExtractor.telemetrySnapshot()` together with the
strategy counters it is the sole reader of, and `ApiKeyManager.hasApiKey()`.

`PromptTemplatesFewShotTest` also asserts the `detectIssues` examples render. Before deleting the
file, check whether it contains tests unrelated to `jointFix`:

```bash
grep -n "fun \`" src/test/kotlin/com/ghostdebugger/ai/prompts/PromptTemplatesFewShotTest.kt
```

If it does, delete only the `jointFix` test methods and keep the file.

- [ ] **Step 3: Decide on `ParsedFile.exports`**

All three extractors compute it and only tests read it. Two defensible dispositions:

- **Remove it** — `AnalysisModels.kt:112`, the three extractors' `exports` locals and `copy(...)`
  arguments, and the assertions in `JavaPsiSymbolExtractorTest:59,72` and
  `KotlinPsiSymbolExtractorTest:79`.
- **Keep it and document it** — it is a populated, correct, tested extraction result that a future
  consumer (an unused-export analyzer) would want, and removing it discards working extraction
  logic from three files.

**Choose keep-and-document.** Add a KDoc line on the property saying no analyzer consumes it yet
and naming the extractors that populate it, and record it in the Task 11 status document under
"implemented, no consumer". Deleting it would trade three files of working PSI extraction for a
smaller line count.

- [ ] **Step 4: Remove the dead resource-resolution steps**

`NeuroMapPanel.findWebIndexUrl` has five steps; steps 1 and 4 require an `assets/` directory that
the single-file vite build (`vite-plugin-singlefile`) never produces — `src/main/resources/web/`
contains `index.html` only. Confirm, then delete those two branches:

```bash
ls src/main/resources/web/
sed -n '93,149p' src/main/kotlin/com/ghostdebugger/toolwindow/NeuroMapPanel.kt
```

Be careful: this is the file whose JAR-selection fix is the reason this branch must reach `main`.
Change only the two branches that reference `assets/`, and re-read the function afterwards to
confirm the JAR-extraction path and its content check are untouched.

- [ ] **Step 5: Verify**

```bash
./gradlew compileKotlin
./gradlew test
./gradlew detekt
```

Expected: compiles, 0 test failures, detekt clean. Deleting `telemetrySnapshot` may leave its
counters unread — remove them too, or detekt will flag them.

- [ ] **Step 6: Commit**

```bash
git add -A src/
git commit -m "refactor: delete code with no production caller"
```

---

### Task 11: Reconcile the documentation

**Files:**
- Create: `docs/IMPLEMENTATION_STATUS.md`
- Create: `docs/audit-2026-09-final-release.md`
- Delete: `docs/audit-2026-06-post-v2.md`
- Create: `src/test/kotlin/com/ghostdebugger/DocumentationCountsTest.kt`
- Modify: `README.md`, `CHANGELOG.md`, `DATA_HANDLING.md`, `AGENTS.md`, `src/main/resources/META-INF/plugin.xml`, `site/index.html`
- Modify: `webview/src/components/neuromap/NeuroMap.tsx` (edgeless notice)

**Interfaces:**
- Consumes: `AegisCapability.entries` — the status document's gated rows are generated from the enum's `label` and `why`, so the two cannot diverge.
- Produces: `docs/IMPLEMENTATION_STATUS.md` as the single reference the README links to.

- [ ] **Step 1: Re-measure every count from the code**

Never copy the numbers from this plan into the docs — re-derive them, because the earlier tasks
changed some:

```bash
echo "analyzers:   $(sed -n '/private val analyzers/,/^    )/p' src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt | grep -cE '^\s+[A-Z][A-Za-z]*\(')"
echo "rule ids:    $(grep -rhoE '"AEG-[A-Z0-9-]+"' src/main/kotlin | sort -u | wc -l)"
echo "fixers:      $(grep -cE '^\s+[A-Z][A-Za-z]*Fixer\(\),?$' src/main/kotlin/com/ghostdebugger/fix/FixerRegistry.kt)"
echo "inspections: $(grep -c '<localInspection' src/main/resources/META-INF/plugin.xml)"
echo "actions:     $(grep -c '<action id=' src/main/resources/META-INF/plugin.xml)"
echo "main kt:     $(find src/main -name '*.kt' | wc -l) files, $(find src/main -name '*.kt' -exec cat {} + | wc -l) LOC"
echo "test kt:     $(find src/test -name '*.kt' | wc -l) files, $(find src/test -name '*.kt' -exec cat {} + | wc -l) LOC"
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
t=f=e=s=0
for p in glob.glob('build/test-results/test/*.xml'):
    r=ET.parse(p).getroot()
    t+=int(r.get('tests',0)); f+=int(r.get('failures',0)); e+=int(r.get('errors',0)); s+=int(r.get('skipped',0))
print(f"tests: {t} passing, {f} failures, {e} errors, {s} skipped")
EOF
```

- [ ] **Step 2: Write the failing counts test**

```kotlin
package com.ghostdebugger

import com.ghostdebugger.fix.FixerRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The 2026-09 audit's closing finding was that nothing pinned the documented capability counts to
 * the code, which is how "eleven analyzers" and "five fixers" survived into three shipped surfaces
 * while the registries held twelve and eight. This test is that pin.
 */
class DocumentationCountsTest {

    private val readme = File("README.md").readText()
    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `fixer count in the docs matches the registry`() {
        val actual = FixerRegistry.all().size
        assertContains(readme, "$actual deterministic fixers")
        assertContains(pluginXml, "$actual deterministic fixers")
    }

    @Test
    fun `inspection count in the docs matches plugin xml`() {
        val registered = Regex("<localInspection").findAll(pluginXml).count()
        assertContains(readme, "$registered")
        assertEquals(11, registered, "inspection count changed; update README and this test together")
    }

    @Test
    fun `no shipped surface still claims eleven analyzers or five fixers`() {
        listOf(readme, pluginXml, File("site/index.html").readText()).forEach { text ->
            listOf("eleven deterministic analyzers", "Eleven deterministic analyzers",
                   "five PSI-validated fixers", "Five PSI-validated fixers",
                   "five deterministic fixers", "Five deterministic fixers").forEach { claim ->
                kotlin.test.assertFalse(text.contains(claim), "stale claim still present: $claim")
            }
        }
    }
}
```

Phrase the README and `plugin.xml` sentences so `"$actual deterministic fixers"` matches literally
(e.g. "8 deterministic fixers"), or relax the assertion to a regex. Decide once and keep both
files consistent.

- [ ] **Step 3: Run test to verify it fails**

```bash
./gradlew test --tests 'com.ghostdebugger.DocumentationCountsTest'
```

Expected: FAIL on the stale claims.

- [ ] **Step 4: Write `docs/IMPLEMENTATION_STATUS.md`**

Three tables. **Works** — the surface, what a reviewer does to see it, and the `file:line` that
implements it. **Gated** — one row per `AegisCapability`, with `label`, `why` and the guard site
from Tasks 2-6. **Implemented, no consumer** — `ParsedFile.exports`, `FixPreviewDialog`,
`BatchFixPreview`, `FixDiffGenerator`, and the three rule packs.

State the language split plainly, because it is the single most load-bearing correction: full
line-oriented lexical analysis for TypeScript and JavaScript with string and comment masking;
type-aware Analysis API analysis for Kotlin, of which two of four rules are shadowed by the
compile-error gate; PSI symbol extraction for Java; and internal dependency edges — hence cycle
detection and impact analysis — for relative imports only, which means TypeScript and JavaScript.

- [ ] **Step 5: Correct the four marketing surfaces**

`README.md` — fix the analyzer and fixer counts; delete the "eleven" / "Five core" contradiction;
replace "Full static analysis" for TS/JS with the lexical-analysis description; remove the
`Ctrl+Alt+A` and `F2` / `Shift+F2` shortcuts, which are not registered; drop the Marketplace
install line, since `release.yml` deliberately omits `publishPlugin`; add a link to
`docs/IMPLEMENTATION_STATUS.md` near the top.

`plugin.xml` `<description>` — same count corrections; remove "side-by-side diff preview",
"keyboard navigation (Enter/Alt+A to Apply, Esc to Skip)" and "batch-apply capabilities across
multiple files", all of which describe code Task 10 confirmed has no consumer.

`CHANGELOG.md` — add a real `3.0.0` entry describing this release: read-only analysis, the
capability gate and what it covers, the seven repairs, the documentation reconciliation. Fix the
`[Unreleased] — 2.0.0` heading. Remove the eight dangling spec/plan references.

`site/index.html` — the landing page is deployed to GitHub Pages on every push to `main` that
touches `site/**`, so it is a surface a reviewer will actually read. Correct the counts in the
body **and** in the `<meta name="description">`, `og:description` and `twitter:description` tags.

`DATA_HANDLING.md` — restamp from 1.0.0 to 3.0.0 and state that no AI provider is reachable in
this release, which makes it the strongest version of the privacy claim rather than the weakest.

`AGENTS.md` — correct the repository layout section to match the current tree.

- [ ] **Step 6: Replace the stale audit record**

```bash
git rm docs/audit-2026-06-post-v2.md
```

Write `docs/audit-2026-09-final-release.md`: date, method (sixteen read-only surface audits, every
verdict required to cite `file:line`, highest-stakes claims independently re-verified), the
per-surface verdict table, and the disposition of each finding — fixed in Task N, gated under
capability X, or documented as a known limitation. The 2026-06 document it replaces recorded all
PASS; say so, and say that the difference was scope and adversarial framing, not a regression.

- [ ] **Step 7: Add the edgeless-graph notice**

When the built graph has no internal edges the NeuroMap looks broken rather than limited. In
`NeuroMap.tsx`, when `edges.length === 0 && nodes.length > 0`, render one line: dependency edges
are resolved for relative imports only, so Kotlin and Java projects show files without edges.

Read the component first and follow its existing styling — do not introduce a new pattern:

```bash
sed -n '1,60p' webview/src/components/neuromap/NeuroMap.tsx
grep -n "edges\|nodes" webview/src/components/neuromap/NeuroMap.tsx | head -20
```

Then rebuild the bundle, which `processResources` depends on:

```bash
cd webview && npm run build && cd ..
git status --porcelain src/main/resources/web/
```

`src/main/resources/web/` is gitignored, so the rebuilt bundle will not appear — that is correct,
it is a build product.

- [ ] **Step 8: Run tests**

```bash
./gradlew test --tests 'com.ghostdebugger.DocumentationCountsTest'
./gradlew test
```

Expected: PASS, full suite 0 failures.

- [ ] **Step 9: Commit**

```bash
git add -A README.md CHANGELOG.md DATA_HANDLING.md AGENTS.md docs/ site/ \
        src/main/resources/META-INF/plugin.xml webview/src/ \
        src/test/kotlin/com/ghostdebugger/DocumentationCountsTest.kt
git commit -m "docs: reconcile every claim against measured code counts"
```

---

### Task 12: The reproducible demo

TypeScript/React, chosen on evidence: relative imports are the only specifiers
`DependencyResolver.kt:57` resolves into internal edges, so this is the one language where the
NeuroMap renders as a graph rather than as unconnected files.

**Files:**
- Create: `samples/aegis-demo/DEMO.md`
- Create: `samples/aegis-demo/src/{NullSafety.tsx,StateInit.tsx,AsyncFlow.tsx,Complexity.ts,cycleA.ts,cycleB.ts}`
- Create: `samples/aegis-demo/src/KotlinSample.kt`
- Modify: `README.md` (link the demo)

**Interfaces:**
- Consumes: the analyzers' real trigger patterns, quoted below from source.
- Produces: nothing the code depends on.

- [ ] **Step 1: Write the sample files against the real patterns**

Each file must match a pattern read from the analyzer source, not an approximation.

`NullSafety.tsx` — `NullSafetyAnalyzer.kt:135` `VAR_NULL_REGEX` is
`(?:let|var)\s+(\w+)\s*(?::\s*\w+)?\s*=\s*(?:null|undefined)`, and the access must be a plain dot
with no guard on the same line and none in the five lines above (`:61-70`):

```tsx
export function Greeting() {
  let user = null
  return <span>{user.name}</span>
}
```

`StateInit.tsx` — `StateInitAnalyzer.kt:73` `USE_STATE_NO_ARG_REGEX` is
`const\s+\[(\w+),\s*\w+\]\s*=\s*useState\s*\(\s*\)`, and the access must be one of
`map|filter|forEach|reduce|find|some|every|length|slice|join` (`:42`):

```tsx
export function List() {
  const [items, setItems] = useState()
  return <ul>{items.map(i => <li key={i}>{i}</li>)}</ul>
}
```

`AsyncFlow.tsx` — two sub-rules. `.then(` with no `.catch(` in the following 12 lines
(`AsyncFlowAnalyzer.kt:49-54`), and `setInterval` inside a `useEffect` with no `clear*` in the
block (`:119-152`):

```tsx
export function Feed() {
  useEffect(() => {
    setInterval(() => console.log('poll'), 1000)
  }, [])

  const load = () => {
    fetch('/api/feed').then(r => r.json())
  }

  return <button onClick={load}>Load</button>
}
```

`Complexity.ts` — `AEG-CPX-001` fires when `GraphBuilder.estimateComplexity`
(`1 + decisionPoints / functionCount`) exceeds `maxComplexity`, default 10. Write **one**
exported function containing more than ten `if` / `for` / `&&` / `||` decision points, so the
denominator stays at 1.

`cycleA.ts` / `cycleB.ts` — a two-file cycle through relative imports, the only form that
produces an internal edge:

```ts
// cycleA.ts
import { b } from './cycleB'
export const a = () => b()
```

```ts
// cycleB.ts
import { a } from './cycleA'
export const b = () => a()
```

`KotlinSample.kt` — the two Kotlin rules that are **not** shadowed by the compile-error gate. Both
constructs must compile cleanly, or `CompilationErrorAnalyzer` will exclude the file from the late
pass:

```kotlin
package demo

fun unsafeCast(value: Any): String = value as String

fun redundantLet(value: String) {
    value.let { println(it) }
}
```

- [ ] **Step 2: Verify the sample actually produces the findings**

Do not ship a demo on faith. Add a temporary test that runs the real analyzers over the sample
files and asserts the expected rule ids, modelled on `AnalysisEngineIntegrationTest`:

```bash
sed -n '1,85p' src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineIntegrationTest.kt
```

Assert `AEG-NULL-001`, `AEG-STATE-001`, `AEG-ASYNC-001`, `AEG-CPX-001`, `AEG-CYCLE-001`,
`AEG-CAST-KT-001`, `AEG-REDUNDANT-LET-KT-001`. Keep this test — it is the regression guard that
stops the demo silently going stale, and it is the only end-to-end test over real sample files in
the repository. Name it `src/test/kotlin/com/ghostdebugger/analysis/DemoSampleFindingsTest.kt`.

If a rule does not fire, fix the **sample**, not the analyzer — analyzer precision is a Task 13
non-goal.

Note that the TS/JS null-safety and state-init rules both fire on the `useState()` shape, so
`StateInit.tsx` will report twice. Record that in `DEMO.md` as known duplicate reporting rather
than hiding it; a reviewer who notices it should find it already documented.

- [ ] **Step 3: Write `DEMO.md`**

Cover, in order: prerequisites (IntelliJ IDEA Community 2024.3+, and `npm` on PATH only if
building from source, since `processResources` depends on `buildWebview`); build
(`./gradlew buildPlugin` with the JBR export, producing
`build/distributions/ghostdebugger-3.0.0.zip`); install (Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin
from Disk); run (open `samples/aegis-demo`, press `Ctrl+Alt+G`); the expected findings as a table
of file, rule id and what it means; where to look (the Aegis Debug tool window on the right
gutter, the editor gutter, and Settings ▸ Editor ▸ Inspections ▸ Aegis Debug); and the export path
(Tools ▸ Aegis Debug ▸ Export Analysis Report).

Then a section titled **What this release does not do**, listing every gated capability with the
reason from its `AegisCapability.why`, and stating plainly that clicking Apply Fix or an
explanation surface raises a roadmap dialog by design and is not a malfunction.

- [ ] **Step 4: Run the verification test**

```bash
./gradlew test --tests 'com.ghostdebugger.analysis.DemoSampleFindingsTest'
```

Expected: PASS, with all seven rule ids present.

- [ ] **Step 5: Commit**

```bash
git add samples/ README.md src/test/kotlin/com/ghostdebugger/analysis/DemoSampleFindingsTest.kt
git commit -m "docs(demo): add a TypeScript sample that exercises seven rules"
```

---

### Task 13: Release gate and merge

**Files:**
- Modify: none, unless a gate fails.

**Interfaces:**
- Consumes: everything above.
- Produces: `build/distributions/ghostdebugger-3.0.0.zip`, and `main` carrying the deterministic webview fix.

- [ ] **Step 1: Confirm the version is consistent**

```bash
grep -n '^version' build.gradle.kts
grep -n '<version>' src/main/resources/META-INF/plugin.xml
```

Both must read `3.0.0`. Gradle patches `plugin.xml` at build time, but the inline value is
cosmetically load-bearing — V1.3 drifted and had to be fixed in `7e31776`.

- [ ] **Step 2: Run every gate**

```bash
export JAVA_HOME=$(find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1)
export PATH=$JAVA_HOME/bin:$PATH
./gradlew cleanTest test --continue
./gradlew detekt
./gradlew verifyPlugin
./gradlew buildPlugin
```

Expected: 0 test failures; detekt clean; `verifyPlugin` reporting Compatible against IU-243,
IU-251, IU-261 and IU-262; and the zip present. Record the final test count — it will exceed 508,
and the README and status document must state the real number.

- [ ] **Step 3: Confirm the built artifact carries the webview**

The whole reason this branch must reach `main`. Verify the zip, not the source:

```bash
unzip -l build/distributions/ghostdebugger-3.0.0.zip | grep -E "web/index.html|\.jar"
```

Expected: `lib/` contains both `ghostdebugger-3.0.0.jar` and
`ghostdebugger-3.0.0-searchableOptions.jar`, and `web/index.html` is inside the former. Then
confirm the selection predicate excludes the searchableOptions jar by name **and** verifies the
`web/index.html` entry:

```bash
sed -n '241,258p' src/main/kotlin/com/ghostdebugger/toolwindow/NeuroMapPanel.kt
```

- [ ] **Step 4: Run the demo by hand**

The one check no test performs. Install the zip into a clean IDEA Community 2024.3, open
`samples/aegis-demo`, press `Ctrl+Alt+G`, and confirm: the tool window renders the NeuroMap with
visible edges between `cycleA.ts` and `cycleB.ts`; findings appear for all seven rules; the engine
pill reads static; clicking **Apply Fix** raises the roadmap dialog; and Tools ▸ Aegis Debug ▸
Export Analysis Report writes a readable HTML file.

If the tool window shows "Failed to load NeuroMap", stop — Step 3's predicate is wrong and nothing
else in this task matters.

- [ ] **Step 5: Merge to main**

```bash
git checkout main
git merge --no-ff feat/final-release-gate
./gradlew cleanTest test
git log --oneline -5
```

`--no-ff` keeps the release as one identifiable merge. Do not push — the project's convention is
local merge only.

- [ ] **Step 6: Confirm main now carries the fix**

```bash
git diff --stat main...feat/final-release-gate
grep -n "isPluginWebJar\|searchableOptions" src/main/kotlin/com/ghostdebugger/toolwindow/NeuroMapPanel.kt
```

Expected: an empty diff, and both symbols present on `main`.

---

### Task 14: The presentation page

**Files:**
- Create: a standalone HTML page, published as an Artifact.

**Interfaces:**
- Consumes: `docs/IMPLEMENTATION_STATUS.md`, `docs/audit-2026-09-final-release.md`, and the measured counts from Task 13 Step 2.
- Produces: a shareable URL.

- [ ] **Step 1: Load the design skill**

Before writing any markup, load `artifact-design`; for the pipeline and gate diagrams, load
`artifact-diagramming`.

- [ ] **Step 2: Build the page**

Sections: what Aegis Debug is and the static-first thesis; the architecture — scan, parse, graph,
early pass, late pass, and where the gate sits; the implementation-status matrix, carrying the
same verdicts as `docs/IMPLEMENTATION_STATUS.md`; the real metrics from Task 13; and the audit
method, including that thirteen of sixteen surfaces came back partial and why recording that is
the point.

Use the final measured numbers, not the ones in this plan.

- [ ] **Step 3: Publish and hand over the link**

Publish with the Artifact tool and give the user the URL.

---

## Self-Review

**Spec coverage.** §3.1 gate → Task 1. §3.2 seven guard sites → Tasks 2-6 (site 1 Task 2, site 2
Task 3, sites 3-4 Task 4, site 5 Task 5, sites 6-7 Task 6). §3.3 JVM graph treatment → Task 2
(`ImpactRequested` mapping) and Task 11 Step 7 (webview notice). §3.4 trade-off → recorded in
Task 2's `gatedCapabilityFor` KDoc. §4 seven fixes → Task 7 (four actions), Task 8 (settings),
Task 9 (consent, Ollama). §5.1 measured truth → Task 11 Step 1. §5.2 claims → Task 11 Step 5.
§5.3 status matrix → Task 11 Step 4. §5.4 counts test → Task 11 Step 2. §6 demo → Task 12.
§7 cleanup → Task 10. §8 verification and merge → Task 13. The Artifact deliverable → Task 14.

**Gaps closed during review.** The spec's §4 row on `SuppressFindingAction` did not say where the
threshold-bypass lives; Task 7 adds `SuppressionMemoryService.suppressNow` rather than
special-casing it in the action. The spec did not mention the `UIEventRouter.aiService` session
cache, which would have made the consent fix incomplete — Task 9 Step 4 covers it. `ParsedFile.exports`
is listed for deletion in §7 but Task 10 Step 3 keeps and documents it instead, with the reason
stated; the spec's cleanup list should be read as amended by that step.

**Type consistency.** `blockIfGated` / `skipIfGated` / `isEnabled` / `messageFor` /
`setPresenterForTest` / `resetPresenterForTest` are used with these exact names in Tasks 1-6.
`gatedCapabilityFor` is defined in Task 2 and referenced nowhere else. `suppressNow` is defined and
used in Task 7. `resolveAiServiceForTest` is added and used in Task 9. Every `AegisCapability`
constant referenced in Tasks 2-6 — `FIX_APPLICATION`, `AI_EXPLANATION`, `AI_ANALYSIS`,
`JVM_DEPENDENCY_GRAPH`, `RULE_PACKS`, `EXTERNAL_ANALYZERS`, `PROBLEMS_VIEW_EMIT`,
`DEBUGGER_CROSS_CHECK` — is declared in Task 1.

**Deliberate verification steps.** Tasks 2, 3, 4, 6, 8, 9, 10 and 11 open with a command that
re-checks the plan's own assumption against the code before changing anything, because several
signatures were read at plan-writing time and line numbers drift. Where an assumption could be
inverted — Task 10's two `AegisQuickFixIntentionAction` copies — the step says to stop rather than
guess.
