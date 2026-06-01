# Fix Engine Phase 2c-ii-a — AI Planner + Bounded Supervised Apply Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the AI act as a *planner/supervisor of the engine*: it proposes a `FixPlan` composed of deterministic catalog operations (as JSON), the engine applies each candidate through the existing Tier-2 verify gate, and a bounded loop feeds gate rejections back to the AI to revise — deterministic fixers always tried first, AI strictly optional.

**Architecture:** Three layers. (1) `FixPlanCodec` decodes raw model text into a `FixPlan` (reusing `AiJsonExtractor` + kotlinx closed-polymorphic decoding of the sealed `FixOperation`). (2) `AIService.proposeFixPlan` (implemented in `BaseAIService` via `callModel` + a new `PromptTemplates.planFix` describing the op catalog) returns a candidate plan, or null on any failure. (3) `FixEngine.fixSupervised` runs the loop: try the deterministic plan via `applyVerified`; if absent or rejected, ask the AI up to N times, feeding each rejection reason back as feedback, applying every candidate through the same gate; return the first `Success` or the last rejection. Acceptance remains **fully deterministic** (the count-based gate) — the AI only proposes and revises; there is intentionally no subjective AI accept-gate.

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform (IPGP 2.14.0, 2024.3.2), kotlinx.serialization (sealed-class polymorphism, discriminator `type`), kotlinx-coroutines, JUnit4 `BasePlatformTestCase` + JUnit5 (`org.junit.jupiter`) for the AI-service tests (matching `BaseAIServiceTest`).

---

## Prerequisites (test prelude)

Tests require a JetBrains Runtime; shell env does NOT persist between Bash commands. Prefix EVERY gradlew invocation inline:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` — `@Serializable sealed class FixOperation` with subclasses `@SerialName("replaceRange") ReplaceRange(startOffset,endOffset,text)`, `@SerialName("insertImport") InsertImport(fqName)`, `@SerialName("convertToSafeCast") ConvertToSafeCast(asOffset)`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlan.kt` — `@Serializable data class FixPlan(val issueId: String, val operations: List<FixOperation>)`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt` — `planFor(issue, vf, content): FixPlan?`, `private val applicator`, `suspend fun fixVerified(...)`. Has `AegisWriteSafeEdt` (in `FixPlanApplicator.kt`) as the write-safe EDT dispatcher.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicator.kt` — `suspend fun applyVerified(plan, virtualFile, project, target, baselineForFile, reanalyze, verifier=…, edtContext=AegisWriteSafeEdt): FixApplyResult`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/SingleFileStaticReanalysis.kt` — default reanalyze provider.
- `src/main/kotlin/com/ghostdebugger/ai/AiJsonExtractor.kt` — `extract(raw): Result` (Ok(element)/Empty), DIRECT/FENCED/BALANCED.
- `src/main/kotlin/com/ghostdebugger/ai/AIService.kt` — interface; `suspend fun suggestFix(...)` (legacy, retired in 2c-ii-b — leave it here).
- `src/main/kotlin/com/ghostdebugger/ai/BaseAIService.kt` — `protected abstract suspend fun callModel(systemPrompt, userPrompt, jsonMode): String`; `SystemPrompts.DEBUGGER`; `suggestFix` at line 110 shows the `callModel` usage pattern.
- `src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt` — `suggestFix(...)`, `detectIssues(...)`; **NOTE the CLAUDE.md `trimIndent()` gotcha**: do not interpolate multi-line content (file text) into a `"""…"""".trimIndent()` template — build with `StringBuilder`.
- `src/test/kotlin/com/ghostdebugger/ai/BaseAIServiceTest.kt` — JUnit5; private `RecordingService : BaseAIService(...)` overrides `callModel` to return a canned `response`. Reuse this pattern.
- `src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt` — `applyVerifiedFix(...)` (2c-i) currently calls an injected `fixVerified` seam defaulting to `FixEngine(project).fixVerified(...)`. `AIServiceFactory.create(GhostDebuggerSettings.getInstance().snapshot(), ApiKeyManager.getApiKey()): AIService?` resolves the service.

## File structure

- **Create** `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanCodec.kt`
- **Modify** `src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt` — add `planFix(...)`.
- **Modify** `src/main/kotlin/com/ghostdebugger/ai/AIService.kt` — add `proposeFixPlan(...)` (default null).
- **Modify** `src/main/kotlin/com/ghostdebugger/ai/BaseAIService.kt` — override `proposeFixPlan(...)`.
- **Modify** `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt` — add `fixSupervised(...)`.
- **Modify** `src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt` — `applyVerifiedFix` routes through `fixSupervised`; add a private `resolveAiService()`.
- **Create** tests: `FixPlanCodecTest.kt`, `PromptTemplatesPlanFixTest.kt`, a `proposeFixPlan` case in `BaseAIServiceTest.kt`, `FixEngineSupervisedTest.kt`.
- **Modify** `docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md` — §9.

---

### Task 1: `FixPlanCodec` — decode model text into a `FixPlan`

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanCodec.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanCodecTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixPlanCodecTest {

    @Test fun decodesADirectJsonPlan() {
        val raw = """{"issueId":"i1","operations":[{"type":"replaceRange","startOffset":0,"endOffset":3,"text":"x"}]}"""
        val plan = FixPlanCodec.decode(raw)!!
        assertEquals("i1", plan.issueId)
        assertEquals(1, plan.operations.size)
        assertTrue(plan.operations[0] is ReplaceRange)
        assertEquals(ReplaceRange(0, 3, "x"), plan.operations[0])
    }

    @Test fun decodesAFencedJsonPlanWithMultipleOps() {
        val raw = """
            Here is the plan:
            ```json
            {"issueId":"i2","operations":[
              {"type":"insertImport","fqName":"a.b.C"},
              {"type":"convertToSafeCast","asOffset":42}
            ]}
            ```
        """.trimIndent()
        val plan = FixPlanCodec.decode(raw)!!
        assertEquals("i2", plan.issueId)
        assertEquals(listOf(InsertImport("a.b.C"), ConvertToSafeCast(42)), plan.operations)
    }

    @Test fun returnsNullOnGarbage() {
        assertNull(FixPlanCodec.decode("I could not produce a plan."))
    }

    @Test fun returnsNullOnUnknownOperationType() {
        val raw = """{"issueId":"i","operations":[{"type":"frobnicate","x":1}]}"""
        assertNull(FixPlanCodec.decode(raw))
    }

    @Test fun returnsNullOnEmpty() {
        assertNull(FixPlanCodec.decode(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanCodecTest"`
Expected: FAIL — `FixPlanCodec` unresolved.

- [ ] **Step 3: Implement `FixPlanCodec.kt`**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.ai.AiJsonExtractor
import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.Json

/**
 * Decodes raw model output into a [FixPlan]. Reuses [AiJsonExtractor] for robust extraction
 * (direct / fenced / balanced), then kotlinx closed-polymorphic decoding of the sealed
 * [FixOperation] (discriminator `type`, e.g. `replaceRange`). Returns null on any failure —
 * empty output, malformed JSON, or an unknown/unsupported operation type — so a bad AI proposal
 * is simply discarded (the verify gate is the safety net for plans that do decode).
 */
object FixPlanCodec {
    private val log = logger<FixPlanCodec>()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "type"
    }

    fun decode(raw: String): FixPlan? {
        val element = when (val r = AiJsonExtractor.extract(raw)) {
            is AiJsonExtractor.Result.Ok -> r.element
            AiJsonExtractor.Result.Empty -> return null
        }
        return runCatching { json.decodeFromJsonElement(FixPlan.serializer(), element) }
            .onFailure { e -> log.info("FixPlan decode failed: ${e.message}") }
            .getOrNull()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanCodecTest"`
Expected: PASS (5 tests). If `decodesADirectJsonPlan` fails to resolve the discriminator, confirm the sealed `FixOperation` subclasses carry `@SerialName("replaceRange")` etc. (they do) and that `classDiscriminator = "type"` is set.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanCodec.kt src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanCodecTest.kt
git commit -m "feat(fix-engine): FixPlanCodec decodes model output into a FixPlan"
```

---

### Task 2: `PromptTemplates.planFix` — describe the op catalog to the AI

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt`
- Test: `src/test/kotlin/com/ghostdebugger/ai/PromptTemplatesPlanFixTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.ai

import com.ghostdebugger.ai.prompts.PromptTemplates
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplatesPlanFixTest {
    private fun issue() = Issue(
        id = "ISSUE-7", type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
        title = "Unsafe cast", description = "cast may fail", filePath = "A.kt", line = 4,
        ruleId = "AEG-CAST-KT-001"
    )

    @Test fun describesCatalogAndEmbedsIssueAndContent() {
        val p = PromptTemplates.planFix(issue(), "fun f(a: Any) = a as String\n", feedback = null)
        assertTrue(p.contains("replaceRange"))
        assertTrue(p.contains("insertImport"))
        assertTrue(p.contains("convertToSafeCast"))
        assertTrue(p.contains("ISSUE-7"))             // issueId for the envelope
        assertTrue(p.contains("AEG-CAST-KT-001"))     // rule identity
        assertTrue(p.contains("fun f(a: Any) = a as String")) // file content embedded
        assertFalse(p.contains("REJECTED"))           // no feedback section when feedback == null
    }

    @Test fun includesFeedbackSectionWhenProvided() {
        val p = PromptTemplates.planFix(issue(), "x", feedback = "Fix did not resolve the target issue.")
        assertTrue(p.contains("REJECTED"))
        assertTrue(p.contains("Fix did not resolve the target issue."))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.ai.PromptTemplatesPlanFixTest"`
Expected: FAIL — `planFix` unresolved.

- [ ] **Step 3: Add `planFix` to `PromptTemplates`**

Add this function inside `object PromptTemplates`. Built with `StringBuilder` (not a `trimIndent()` template) because `fileContent` is multi-line — see the CLAUDE.md `trimIndent()` interpolation gotcha.

```kotlin
    /**
     * Prompt asking the model to act as a planner: emit a [com.ghostdebugger.fix.engine.FixPlan]
     * composed only of deterministic catalog operations as JSON. [feedback] (when non-null) is the
     * verify gate's reason for rejecting the previous attempt, so the model can revise.
     * Built with StringBuilder to avoid the trimIndent() multi-line-interpolation gotcha.
     */
    fun planFix(issue: Issue, fileContent: String, feedback: String? = null): String {
        val sb = StringBuilder()
        sb.append("You repair code by composing deterministic edit operations into a plan. ")
        sb.append("You do NOT write free-form fixed code; you only choose operations from the catalog below.\n\n")
        sb.append("Issue to fix:\n")
        sb.append("- ruleId: ").append(issue.ruleId ?: issue.type.name).append('\n')
        sb.append("- title: ").append(issue.title).append('\n')
        sb.append("- line: ").append(issue.line).append('\n')
        sb.append("- description: ").append(issue.description).append('\n')
        if (feedback != null) {
            sb.append("\nYour previous plan was REJECTED by the verifier: ").append(feedback)
            sb.append("\nProduce a corrected plan that resolves the issue without introducing new ones.\n")
        }
        sb.append("\nReturn ONLY a JSON object of this exact shape (no prose):\n")
        sb.append("{\"issueId\":\"").append(issue.id).append("\",\"operations\":[ <operation>, ... ]}\n\n")
        sb.append("Each <operation> is exactly one of:\n")
        sb.append("- {\"type\":\"replaceRange\",\"startOffset\":<int>,\"endOffset\":<int>,\"text\":\"<replacement>\"} ")
        sb.append("// replace the half-open character range [startOffset, endOffset) with text\n")
        sb.append("- {\"type\":\"insertImport\",\"fqName\":\"<fully.qualified.Name>\"} // add an import if absent\n")
        sb.append("- {\"type\":\"convertToSafeCast\",\"asOffset\":<int>} ")
        sb.append("// asOffset = the 0-based character offset where the unsafe `as` keyword starts\n")
        sb.append("\nAll offsets are 0-based character indices into the file content below.\n")
        sb.append("\n--- FILE CONTENT START ---\n")
        sb.append(fileContent)
        sb.append("\n--- FILE CONTENT END ---\n")
        return sb.toString()
    }
```

Ensure `com.ghostdebugger.model.Issue` is imported in `PromptTemplates.kt` (other functions already use `Issue`, so it is).

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.ai.PromptTemplatesPlanFixTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt src/test/kotlin/com/ghostdebugger/ai/PromptTemplatesPlanFixTest.kt
git commit -m "feat(fix-engine): planFix prompt describing the op catalog"
```

---

### Task 3: `AIService.proposeFixPlan` + `BaseAIService` implementation

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/ai/AIService.kt`
- Modify: `src/main/kotlin/com/ghostdebugger/ai/BaseAIService.kt`
- Test: `src/test/kotlin/com/ghostdebugger/ai/BaseAIServiceTest.kt` (add a case, reusing `RecordingService`)

- [ ] **Step 1: Write the failing test (add into `BaseAIServiceTest`)**

`RecordingService`'s constructor takes a `response` string. Add a test that constructs one returning a FixPlan JSON and asserts `proposeFixPlan` decodes it. Add this method inside `class BaseAIServiceTest`:

```kotlin
    @Test
    fun proposeFixPlanDecodesModelJsonIntoAFixPlan() {
        val planJson = """{"issueId":"i9","operations":[{"type":"insertImport","fqName":"a.b.C"}]}"""
        val service = RecordingService(cacheEnabled = false, response = planJson)
        val plan = runBlocking { service.proposeFixPlan(issue(), "some file content", feedback = null) }
        assertNotNull(plan)
        assertEquals("i9", plan!!.issueId)
        assertEquals(1, plan.operations.size)
        assertTrue(plan.operations[0] is com.ghostdebugger.fix.engine.InsertImport)
        assertTrue("planning must request JSON mode", service.lastJsonMode)
    }

    @Test
    fun proposeFixPlanReturnsNullWhenModelEmitsNoJson() {
        val service = RecordingService(cacheEnabled = false, response = "Sorry, I cannot help.")
        val plan = runBlocking { service.proposeFixPlan(issue(), "x", feedback = null) }
        assertNull(plan)
    }
```

(`issue()` already exists in `BaseAIServiceTest`; `assertNotNull`/`assertNull`/`assertEquals`/`assertTrue` come from the existing `org.junit.jupiter.api.Assertions.*` import.)

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.ai.BaseAIServiceTest"`
Expected: FAIL — `proposeFixPlan` unresolved.

- [ ] **Step 3: Add `proposeFixPlan` to the interface and implement it**

In `AIService.kt`, add the import `import com.ghostdebugger.fix.engine.FixPlan` and this interface method (with a null default so mocks/non-supporting impls need not override):

```kotlin
    /**
     * Propose a deterministic [FixPlan] (catalog operations as JSON) that fixes [issue] in
     * [fileContent]. [feedback] is the verify gate's reason for rejecting a prior attempt, if any.
     * Returns null when the model produces no decodable plan. Default: unsupported (null).
     */
    suspend fun proposeFixPlan(issue: Issue, fileContent: String, feedback: String? = null): FixPlan? = null
```

In `BaseAIService.kt`, add the imports `import com.ghostdebugger.fix.engine.FixPlan` and `import com.ghostdebugger.fix.engine.FixPlanCodec`, and override (place near `suggestFix`). NOT cached — each attempt with different feedback must re-ask:

```kotlin
    override suspend fun proposeFixPlan(issue: Issue, fileContent: String, feedback: String?): FixPlan? {
        val raw = try {
            callModel(SystemPrompts.DEBUGGER, PromptTemplates.planFix(issue, fileContent, feedback), jsonMode = true)
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            log.warn("proposeFixPlan callModel failed for ${issue.id}", e)
            return null
        }
        return FixPlanCodec.decode(raw)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.ai.BaseAIServiceTest"`
Expected: PASS (existing cases + 2 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/ai/AIService.kt src/main/kotlin/com/ghostdebugger/ai/BaseAIService.kt src/test/kotlin/com/ghostdebugger/ai/BaseAIServiceTest.kt
git commit -m "feat(fix-engine): AIService.proposeFixPlan emits a FixPlan via planFix + FixPlanCodec"
```

---

### Task 4: `FixEngine.fixSupervised` — the bounded supervised loop

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineSupervisedTest.kt`

- [ ] **Step 1: Write the failing test**

The loop is unit-tested with fakes via two seams: the `applyVerified` step is injected as a lambda (returns scripted results, records the plans it received), and a fake `AIService` returns scripted plans (recording the feedback it got). `deriveCodeFix` is injected to return null so the deterministic step is skipped and the AI path is exercised.

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.ai.AIService
import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.model.ProjectGraph
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class FixEngineSupervisedTest : BasePlatformTestCase() {

    private fun issue() = Issue(
        id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
        title = "t", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-CAST-KT-001"
    )

    /** Fake AIService that returns a scripted plan per attempt and records the feedback received. */
    private class FakeAI(private val plans: List<FixPlan?>) : AIService {
        val feedbacks = mutableListOf<String?>()
        private var i = 0
        override suspend fun detectIssues(filePath: String, fileContent: String, functions: List<com.ghostdebugger.model.FunctionSymbol>) = emptyList<Issue>()
        override suspend fun explainIssue(issue: Issue, codeSnippet: String) = ""
        override suspend fun suggestFix(issue: Issue, codeSnippet: String) = throw UnsupportedOperationException()
        override suspend fun explainSystem(graph: ProjectGraph) = ""
        override suspend fun proposeFixPlan(issue: Issue, fileContent: String, feedback: String?): FixPlan? {
            feedbacks += feedback
            return plans.getOrNull(i++)
        }
    }

    fun testAiRetriesWithFeedbackUntilGateAccepts() {
        val vf = myFixture.configureByText("A.kt", "fun f() {}\n").virtualFile
        val p1 = FixPlan("i1", listOf(InsertImport("a.X")))
        val p2 = FixPlan("i1", listOf(InsertImport("a.Y")))
        val applied = mutableListOf<FixPlan>()
        val results = ArrayDeque(listOf<FixApplyResult>(FixApplyResult.Rejected("nope-1"), FixApplyResult.Success))

        val engine = FixEngine(project, deriveCodeFix = { _, _, _ -> null })  // no deterministic plan
        val ai = FakeAI(listOf(p1, p2))

        val result = runBlocking {
            engine.fixSupervised(
                issue(), vf, "content", baselineForFile = emptyList(), aiService = ai,
                reanalyze = { emptyList() },
                applyVerified = { plan -> applied += plan; results.removeFirst() },
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Success)
        assertEquals(listOf(p1, p2), applied)                       // both candidates were applied
        assertEquals(listOf(null, "nope-1"), ai.feedbacks)          // 2nd attempt got the 1st rejection
    }

    fun testReturnsRejectedWhenNoAiAndNoDeterministicPlan() {
        val vf = myFixture.configureByText("A.kt", "fun f() {}\n").virtualFile
        val engine = FixEngine(project, deriveCodeFix = { _, _, _ -> null })
        val result = runBlocking {
            engine.fixSupervised(
                issue(), vf, "content", baselineForFile = emptyList(), aiService = null,
                reanalyze = { emptyList() },
                applyVerified = { FixApplyResult.Success },  // never called (no plan source)
            )
        }
        assertTrue(result.toString(), result is FixApplyResult.Rejected)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixEngineSupervisedTest"`
Expected: FAIL — `fixSupervised` unresolved.

- [ ] **Step 3: Implement `fixSupervised`**

Add these imports to `FixEngine.kt`:

```kotlin
import com.ghostdebugger.ai.AIService
import kotlin.coroutines.CoroutineContext
```

Add this method to the `FixEngine` class (the `applyVerified` default closes over the real `applicator`; `AegisWriteSafeEdt` is already accessible from `FixPlanApplicator.kt` in the same package):

```kotlin
    /**
     * AI-supervised fix loop. Tries the deterministic plan first; if absent or rejected by the
     * verify gate, asks [aiService] (when non-null) for a [FixPlan] up to [maxAiAttempts] times,
     * feeding each rejection reason back as planner feedback and applying every candidate through
     * the same Tier-2 gate. Returns the first [FixApplyResult.Success] or the last rejection.
     * AI-optional: with [aiService] == null this is exactly the deterministic verified path.
     *
     * Acceptance is fully deterministic (the gate). The AI only proposes and revises.
     */
    suspend fun fixSupervised(
        issue: Issue,
        virtualFile: VirtualFile,
        content: String,
        baselineForFile: List<Issue>,
        aiService: AIService?,
        reanalyze: suspend () -> List<Issue> = { SingleFileStaticReanalysis(project).issuesFor(virtualFile) },
        maxAiAttempts: Int = 2,
        edtContext: CoroutineContext = AegisWriteSafeEdt,
        applyVerified: suspend (FixPlan) -> FixApplyResult = { plan ->
            applicator.applyVerified(plan, virtualFile, project, issue, baselineForFile, reanalyze, edtContext = edtContext)
        },
    ): FixApplyResult {
        var lastReason = "No deterministic fix available for ${issue.ruleId}."

        // 1) Deterministic plan first.
        planFor(issue, virtualFile, content)?.let { plan ->
            when (val r = applyVerified(plan)) {
                is FixApplyResult.Success -> return r
                is FixApplyResult.Rejected -> lastReason = r.reason
                is FixApplyResult.Failed -> lastReason = r.throwable.message ?: lastReason
            }
        }

        // 2) AI-supervised attempts with rejection feedback.
        if (aiService != null) {
            var feedback: String? = null
            repeat(maxAiAttempts) {
                val plan = aiService.proposeFixPlan(issue, content, feedback)
                if (plan != null) {
                    when (val r = applyVerified(plan)) {
                        is FixApplyResult.Success -> return r
                        is FixApplyResult.Rejected -> { feedback = r.reason; lastReason = r.reason }
                        is FixApplyResult.Failed -> {
                            feedback = r.throwable.message ?: "Fix failed."
                            lastReason = feedback ?: lastReason
                        }
                    }
                }
            }
        }
        return FixApplyResult.Rejected(lastReason)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixEngineSupervisedTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Run the existing engine suite (regression)**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.*"`
Expected: PASS (all engine tests, including FixEngineTest / FixPlanApplicator* / Codec / Supervised).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineSupervisedTest.kt
git commit -m "feat(fix-engine): FixEngine.fixSupervised bounded AI-planner loop over the verify gate"
```

---

### Task 5: Route the live apply path through `fixSupervised`

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt`

`applyVerifiedFix` (added in 2c-i) takes an injected `fixVerified` seam defaulting to `FixEngine(project).fixVerified(...)`. Change the **default** to `fixSupervised` with a resolved AIService, keeping the seam signature identical so the 2c-i test (which injects its own fake) is unaffected.

- [ ] **Step 1: Add a private `resolveAiService()` and re-point the default**

Add the imports to `AnalysisOrchestrator.kt`:

```kotlin
import com.ghostdebugger.ai.AIService
import com.ghostdebugger.ai.AIServiceFactory
import com.ghostdebugger.ai.ApiKeyManager
import com.ghostdebugger.settings.AIProvider
```

Add this private helper to the class (mirrors `UIEventRouter.resolveAiService`):

```kotlin
    /** Resolve the configured AIService, or null when AI is disabled / unconfigured. */
    private fun resolveAiService(): AIService? {
        val settings = GhostDebuggerSettings.getInstance().snapshot()
        if (settings.aiProvider == AIProvider.NONE) return null
        return AIServiceFactory.create(settings, ApiKeyManager.getApiKey())
    }
```

Then, in `applyVerifiedFix`, change ONLY the default value of the `fixVerified` seam parameter from:

```kotlin
        fixVerified: suspend (Issue, VirtualFile, String, List<Issue>) -> FixApplyResult =
            { i, v, c, b -> FixEngine(project).fixVerified(i, v, c, b) },
```

to:

```kotlin
        fixVerified: suspend (Issue, VirtualFile, String, List<Issue>) -> FixApplyResult =
            { i, v, c, b -> FixEngine(project).fixSupervised(i, v, c, b, resolveAiService()) },
```

(The parameter name stays `fixVerified` for minimal churn; its body now routes through the supervised loop, which itself falls back to the deterministic verified path when `resolveAiService()` is null. The 2c-i test injects this seam explicitly, so it is unaffected.)

- [ ] **Step 2: Verify it compiles**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the orchestrator wire-in test (regression — its injected seam still works)**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.AnalysisOrchestratorApplyVerifiedFixTest"`
Expected: PASS (1 test).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt
git commit -m "feat(fix-engine): live apply path uses fixSupervised (AI-optional)"
```

---

### Task 6: Document 2c-ii-a as done

**Files:**
- Modify: `docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md`

- [ ] **Step 1: Update §9 phasing**

Under Phase 2c-ii, split into:
- **Phase 2c-ii-a (DONE):** the AI is a planner/supervisor. `AIService.proposeFixPlan` emits a `FixPlan` of catalog operations as JSON (`PromptTemplates.planFix` + `FixPlanCodec`); `FixEngine.fixSupervised` tries the deterministic plan first, then asks the AI up to N times, feeding each verify-gate rejection back as feedback and applying every candidate through the **deterministic** Tier-2 gate (no subjective AI accept-gate). Wired into the live apply path via `AnalysisOrchestrator.applyVerifiedFix`; AI-optional (falls back to the deterministic verified path).
- **Phase 2c-ii-b (remaining):** migrate the AI fix **suggestion/preview** path (`UIEventRouter` `sendFixSuggestion`) to render a `fixSupervised`/planner result, then retire the free-form `suggestFix` / `parseFixResponse` (`AIService` / `BaseAIService` / `PromptTemplates.suggestFix`).

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md
git commit -m "docs(spec): Phase 2c-ii-a AI planner + supervised loop complete"
```

---

## Final verification

- [ ] **Run the touched packages**

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.*" --tests "com.ghostdebugger.ai.*"
```
Expected: all green.

- [ ] **Run the full suite**

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test
```
Expected: all green.

---

## Self-Review (completed during planning)

- **Spec coverage:** FixPlanner (AI→FixPlan via op catalog + AiJsonExtractor) → Tasks 1–3; bounded supervised loop with rejection feedback → Task 4; wire-in → Task 5. The AI **preview** migration + retiring `suggestFix`/`parseFixResponse` are explicitly Phase 2c-ii-b (Task 6 note), not in scope.
- **Determinism ethos:** acceptance is the count-based gate only; the AI proposes/revises but never decides acceptance. Documented in `fixSupervised` and the spec.
- **Type consistency:** `FixPlanCodec.decode(raw): FixPlan?`; `AIService.proposeFixPlan(issue, fileContent, feedback=null): FixPlan?` (default null); `BaseAIService` override matches; `FixEngine.fixSupervised(issue, virtualFile, content, baselineForFile, aiService, reanalyze=…, maxAiAttempts=2, edtContext=AegisWriteSafeEdt, applyVerified=…): FixApplyResult`; `FixApplyResult.Failed.throwable` used. `FixPlan(issueId, operations)` and ops `ReplaceRange(start,end,text)`/`InsertImport(fqName)`/`ConvertToSafeCast(asOffset)` match their definitions.
- **kotlinx decoding:** sealed `FixOperation` gives closed polymorphism; `classDiscriminator = "type"` aligns with the `@SerialName` values; unknown types throw → caught → null (conservative).
- **trimIndent gotcha:** `planFix` uses `StringBuilder`, not an interpolated `"""…""".trimIndent()`, because file content is multi-line (per CLAUDE.md).
- **PCE:** `proposeFixPlan` rethrows `ProcessCanceledException` before its `Exception` catch.
- **Threading:** `fixSupervised` is `suspend`; it delegates EDT-safe writes to `applyVerified` (default closes over the real applicator using `AegisWriteSafeEdt`). The Task-4 test injects a fake `applyVerified` + fake `AIService` so it runs deterministically on `BasePlatformTestCase` with no real EDT hop or AI.
- **Placeholders:** none. Task 5 is a one-line default-value change verified by compile + the existing 2c-i test; all logic-bearing code is unit-tested (Tasks 1–4).
