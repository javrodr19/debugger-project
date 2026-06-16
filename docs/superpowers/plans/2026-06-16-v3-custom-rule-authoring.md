# V3.1 Custom Rule Authoring — Implementation Plan (Stream B)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or
> superpowers:executing-plans. Read `plans/2026-06-16-parallel-execution-coordination.md` first — this
> is **Stream B**. Work on branch `stream/v3-custom-rules`. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let a developer add a `.aegis/rules/*.yml` file that flags matching code (labelled `CUSTOM`
+ rule id) and optionally applies a deterministic, verify-gated fix — reusing the shipped analyzer
pipeline and fix engine.

**Architecture:** One new `Analyzer` (`CustomRuleAnalyzer`) added to `AnalysisEngine.analyzers`; a
project `CustomRuleService` loads + caches YAML rules; a bounded matcher evaluates them fail-closed;
a rule's `fix:` decodes to a `FixPlan` via the existing `FixPlan.serializer()` and applies through
`FixPlanApplicator`'s verify gate. Spec: `specs/2026-06-16-v3-custom-rule-authoring-design.md`.

**Tech Stack:** Kotlin, `com.charleskorn.kaml` (YAML for `kotlinx.serialization`), the Analysis-API
chokepoint `withKtAnalysis`, the `fix/engine/` `FixPlan` path.

---

## ⚠️ Stream-B owned regions (do NOT edit outside these in shared files)

| Shared file | B edits ONLY | Owner of the rest |
|---|---|---|
| `build.gradle.kts` | the `dependencies { }` block (add YAML lib near L46) | A (plugins block) |
| `analysis/AnalysisEngine.kt` | the `analyzers = listOf(...)` (L30–42): append one entry | A (gate/late-pass) |
| `model/AnalysisModels.kt` | the `IssueSource` enum: append `CUSTOM` | read-only elsewhere |
| `src/main/resources/META-INF/plugin.xml` | the `<extensions>` projectService block (~L154–198) | D (`<actions>`) |

All other Stream-B files are **new** (no conflict surface).

---

## File map

| File | Create/Modify | Responsibility |
|---|---|---|
| `build.gradle.kts` | Modify (deps block) | add `com.charleskorn.kaml` |
| `model/AnalysisModels.kt` | Modify (enum) | `IssueSource.CUSTOM` |
| `rules/CustomRule.kt` | Create | `@Serializable` rule + file model (matcher, severity, msg, optional `fix`) |
| `rules/CustomRuleService.kt` | Create | `@Service(PROJECT)` loader + cache + fail-closed parse |
| `rules/RuleMatcher.kt` | Create | bounded-vocabulary evaluator; fail-closed on `KaErrorType` |
| `analysis/analyzers/CustomRuleAnalyzer.kt` | Create | `Analyzer` that emits `Issue`s with `IssueSource.CUSTOM` + rule id |
| `fix/engine/RuleAnchorResolver.kt` | Create | resolves named anchors → offsets for the `FixPlan` |
| `analysis/AnalysisEngine.kt` | Modify (list) | register `CustomRuleAnalyzer` |
| `plugin.xml` | Modify (extensions) | register `CustomRuleService` |
| `src/test/.../rules/*Test.kt` | Create | loader, matcher (incl. `KaErrorType` canary), fix, e2e |

---

## Task 1: Add the YAML dependency (owned region: deps block)

**Files:** Modify `build.gradle.kts` (the `dependencies { }` block only)

- [ ] **Step 1: Add the lib** directly under the existing serialization-json line (~L46):

```kotlin
    // Kotlin Serialization (JSON) + YAML front-end for custom rules
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.charleskorn.kaml:kaml:0.61.0")
```

- [ ] **Step 2: Verify it resolves** (JBR env exported per CLAUDE.md):

Run: `./gradlew dependencies --configuration runtimeClasspath` (look for `kaml`)
Expected: `com.charleskorn.kaml:kaml:0.61.0` present.

- [ ] **Step 3: Commit.**

```bash
git add build.gradle.kts
git commit -m "build(v3.1): add kaml (YAML for kotlinx.serialization) for custom rules"
```

## Task 2: Add `IssueSource.CUSTOM` (owned region: enum)

**Files:** Modify `model/AnalysisModels.kt:6`

- [ ] **Step 1: Append the enum value.** Change:

```kotlin
enum class IssueSource { STATIC, AI_LOCAL, AI_CLOUD, RUNTIME_CONFIRMED }
```
to:
```kotlin
enum class IssueSource { STATIC, AI_LOCAL, AI_CLOUD, RUNTIME_CONFIRMED, CUSTOM }
```

- [ ] **Step 2: Compile** to confirm no exhaustive-`when` breakage.

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. (If a `when (source)` is now non-exhaustive, add a `CUSTOM` branch
mirroring `STATIC` behavior — custom findings sort like static unless runtime-confirmed.)

- [ ] **Step 3: Commit.**

```bash
git add src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt
git commit -m "feat(v3.1): add IssueSource.CUSTOM provenance tier"
```

## Task 3: The rule model + YAML decode

**Files:** Create `rules/CustomRule.kt`; Test `src/test/.../rules/CustomRuleDecodeTest.kt`

- [ ] **Step 1: Write the failing decode test.**

```kotlin
class CustomRuleDecodeTest {
    private val yaml = """
        version: 1
        rules:
          - id: pce-rethrow-missing
            language: kotlin
            severity: warning
            message: "catch (e: Exception) must rethrow ProcessCanceledException first"
            match: { element: catch-clause, parameter-type: java.lang.Exception }
    """.trimIndent()

    @Test fun `decodes a single rule from YAML`() {
        val file = CustomRuleCodec.decode(yaml)
        assertEquals(1, file!!.rules.size)
        assertEquals("pce-rethrow-missing", file.rules[0].id)
        assertEquals(RuleSeverity.WARNING, file.rules[0].severity)
        assertEquals("catch-clause", file.rules[0].match.element)
    }

    @Test fun `malformed YAML decodes to null, not a throw`() {
        assertNull(CustomRuleCodec.decode("rules: [ : : :"))
    }
}
```

- [ ] **Step 2: Run, confirm FAIL** (`CustomRuleCodec` undefined).

Run: `./gradlew test --tests "*CustomRuleDecodeTest*"` → FAIL.

- [ ] **Step 3: Implement the model + codec** in `rules/CustomRule.kt`:

```kotlin
package com.ghostdebugger.rules

import com.charleskorn.kaml.Yaml
import com.ghostdebugger.fix.engine.FixPlan
import kotlinx.serialization.Serializable

enum class RuleSeverity { ERROR, WARNING, WEAK_WARNING, INFO }

@Serializable data class RuleMatch(
    val element: String,
    val `name-matches`: String? = null,
    val `text-matches`: String? = null,
    val `parameter-type`: String? = null,
    val `receiver-type`: String? = null,
    val `argument-type`: String? = null,
    val inside: String? = null,
    val `annotated-with`: String? = null,
    val `contains-text`: String? = null,
    val unless: RuleMatch? = null,
)

@Serializable data class CustomRule(
    val id: String,
    val language: String,
    val severity: RuleSeverity,
    val message: String,
    val match: RuleMatch,
    val fix: FixPlan? = null,   // reuses the engine's @Serializable FixPlan
)

@Serializable data class CustomRuleFile(val version: Int, val rules: List<CustomRule>)

object CustomRuleCodec {
    private val yaml = Yaml.default
    fun decode(raw: String): CustomRuleFile? =
        runCatching { yaml.decodeFromString(CustomRuleFile.serializer(), raw) }.getOrNull()
}
```

> If `FixPlan`'s constructor isn't directly YAML-shaped, decode `fix` into the engine's existing
> serializable op-list type and adapt — confirm `FixPlan.serializer()`'s field names at execution
> (see `fix/engine/FixPlanCodec.kt`, which already deserializes a `FixPlan`).

- [ ] **Step 4: Run, confirm PASS.** `./gradlew test --tests "*CustomRuleDecodeTest*"` → PASS.

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/com/ghostdebugger/rules/CustomRule.kt src/test/kotlin/com/ghostdebugger/rules/CustomRuleDecodeTest.kt
git commit -m "feat(v3.1): @Serializable custom-rule model + YAML codec (fail-closed)"
```

## Task 4: `CustomRuleService` — load + cache `.aegis/rules/*.yml`

**Files:** Create `rules/CustomRuleService.kt`; Modify `plugin.xml` (extensions); Test `…/CustomRuleServiceTest.kt`

- [ ] **Step 1: Failing test** — a temp `.aegis/rules/` with one good + one malformed file yields one
  rule, logs the bad one, never throws.

```kotlin
class CustomRuleServiceTest : BasePlatformTestCase() {
    fun `test loads valid rules and skips malformed files`() {
        myFixture.tempDirFixture.createFile(".aegis/rules/ok.yml", VALID_YAML)
        myFixture.tempDirFixture.createFile(".aegis/rules/bad.yml", "rules: [ : :")
        val rules = CustomRuleService.getInstance(project).rules()
        assertEquals(1, rules.size)            // bad file skipped, not fatal
        assertEquals("pce-rethrow-missing", rules[0].id)
    }
}
```

- [ ] **Step 2: Run → FAIL.** `./gradlew test --tests "*CustomRuleServiceTest*"`

- [ ] **Step 3: Implement** `rules/CustomRuleService.kt` as `@Service(Service.Level.PROJECT)`:
  read `.aegis/rules/*.yml` under the project base, `CustomRuleCodec.decode` each, drop nulls (log a
  warning), drop rules with duplicate `id` (log), cache the result, invalidate on VFS change under
  `.aegis/`. Register `Disposer.register(project, this)` in `init {}` (CLAUDE.md facade rules).

- [ ] **Step 4: Register the service** in `plugin.xml` — inside the existing
  `<extensions defaultExtensionNs="com.intellij">` block (~L190, alongside the other `<projectService>`
  entries — **Stream B's owned region**):

```xml
        <projectService serviceImplementation="com.ghostdebugger.rules.CustomRuleService"/>
```

- [ ] **Step 5: Run → PASS.** Then **commit** (service + plugin.xml + test).

```bash
git add src/main/kotlin/com/ghostdebugger/rules/CustomRuleService.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/ghostdebugger/rules/CustomRuleServiceTest.kt
git commit -m "feat(v3.1): CustomRuleService loads/caches .aegis/rules/*.yml (skip-on-malformed)"
```

## Task 5: `RuleMatcher` — bounded vocabulary, fail-closed

**Files:** Create `rules/RuleMatcher.kt`; Test `…/RuleMatcherTest.kt` (extends `AegisKotlinAnalysisTestCase`)

- [ ] **Step 1: Failing tests — one positive, one negative, and the MANDATORY `KaErrorType` canary.**

```kotlin
class RuleMatcherTest : AegisKotlinAnalysisTestCase() {
    fun `test matches a non-compliant catch clause`() { /* asserts the PCE rule matches */ }
    fun `test does NOT match when unless-guard already present`() { /* compliant catch → no match */ }
    fun `test fails closed on unresolved type (KaErrorType canary)`() {
        // a catch whose parameter type cannot be resolved must NOT match (conservative-miss)
        assertTrue(matchOn("catch (e: Unresolvable) { }", pceRule).isEmpty())
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** `rules/RuleMatcher.kt`: evaluate a `RuleMatch` against a PSI element inside
  `withKtAnalysis` (Kotlin) / the JS-TS path. Implement the v1 vocabulary from the spec §4 (`element`,
  `name-matches`, `text-matches`, `*-type` via `effectiveType`, `inside`, `annotated-with`,
  `contains-text`, `unless`). **Any type predicate that resolves to `KaErrorType`/unresolved returns
  no match** (spec D4). Rethrow `ProcessCanceledException` in any catch.

- [ ] **Step 4: Run → PASS** (all three, especially the canary).

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/com/ghostdebugger/rules/RuleMatcher.kt src/test/kotlin/com/ghostdebugger/rules/RuleMatcherTest.kt
git commit -m "feat(v3.1): bounded rule matcher, fail-closed on KaErrorType (canary covered)"
```

## Task 6: `CustomRuleAnalyzer` + wire into `AnalysisEngine` (owned line)

**Files:** Create `analysis/analyzers/CustomRuleAnalyzer.kt`; Modify `AnalysisEngine.kt:30-42`; Test `…/CustomRuleAnalyzerTest.kt`

- [ ] **Step 1: Failing test** — analyzer emits an `Issue` with `IssueSource.CUSTOM` and the rule id.

```kotlin
fun `test emits CUSTOM issue carrying the rule id`() {
    val issues = CustomRuleAnalyzer().analyze(ctxWith(pceRule, nonCompliantCatchFile))
    assertEquals(IssueSource.CUSTOM, issues.single().sources.single())
    assertTrue(issues.single().ruleId == "pce-rethrow-missing")
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** `CustomRuleAnalyzer : Analyzer` — pull rules from
  `CustomRuleService.getInstance(project)`, run `RuleMatcher` per element, emit `Issue`s with
  `sources = listOf(IssueSource.CUSTOM)` and the rule id threaded through (add a `ruleId: String?`
  field to `Issue` if absent — additive, in `model/AnalysisModels.kt` enum-adjacent, still Stream-B
  owned). Honor the conservative-miss + PCE-rethrow conventions.

- [ ] **Step 4: Register** in `AnalysisEngine.kt` — append to the `analyzers` list (L30–42, the
  **owned line**), keeping `ComplexityAnalyzer()` last-but-one formatting:

```kotlin
        ComplexityAnalyzer(),
        CustomRuleAnalyzer()
```

- [ ] **Step 5: Run → PASS.** **Commit** (analyzer + AnalysisEngine line + any Issue.ruleId + test).

```bash
git add src/main/kotlin/com/ghostdebugger/analysis/analyzers/CustomRuleAnalyzer.kt src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt src/test/kotlin/com/ghostdebugger/analysis/analyzers/CustomRuleAnalyzerTest.kt
git commit -m "feat(v3.1): CustomRuleAnalyzer emits CUSTOM findings, wired into AnalysisEngine"
```

## Task 7: The fix path — YAML `fix:` → verified `FixPlan`

**Files:** Create `fix/engine/RuleAnchorResolver.kt` (new file — additive in fix/engine, no edit to A-owned files); Test `…/CustomFixApplyTest.kt`

- [ ] **Step 1: Failing tests** — a rule's `fix:` applies and is PSI-valid; a deliberately
  PSI-breaking `fix:` is **refused** by the verify gate.

```kotlin
fun `test custom fix applies and is PSI-valid`() {
    val result = applyCustomFix(pceRule.fix!!, nonCompliantCatchFile)
    assertNotNull(result); assertTrue(result.isPsiValid)
    assertTrue(result.text.contains("is ProcessCanceledException) throw e"))
}
fun `test PSI-breaking custom fix is refused (returns null)`() {
    assertNull(applyCustomFix(planThatUnbalancesBraces, anyFile))
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** `RuleAnchorResolver` (named anchors like `catch-block-open-brace` →
  concrete offsets) and the glue that hands the rule's `FixPlan` to the existing
  `FixPlanApplicator` + verify gate. **No new apply/verify code** — reuse `fix/engine/`'s public API.

- [ ] **Step 4: Run → PASS** (apply works; PSI-breaking refused). **Commit.**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/RuleAnchorResolver.kt src/test/kotlin/com/ghostdebugger/fix/engine/CustomFixApplyTest.kt
git commit -m "feat(v3.1): custom fix path reuses FixPlanApplicator verify gate (refuses PSI-invalid)"
```

## Task 8: End-to-end dogfood + green bar

**Files:** Test `src/test/.../rules/CustomRuleE2ETest.kt`; Modify none (verification)

- [ ] **Step 1: E2E test** — drop the `pce-rethrow.yml` rule (spec §3) in a fixture project; a
  non-compliant `catch` is flagged `CUSTOM`; the fix inserts the rethrow as the first statement;
  re-analysis shows the finding resolved.

- [ ] **Step 2: Run → PASS.**

- [ ] **Step 3: Full green bar** (JBR env): `./gradlew test`, `./gradlew detekt`, `./gradlew verifyPlugin`.
  Expected: all green / Compatible.

- [ ] **Step 4: Commit + run Lens-1 convention check** (`aegis-convention-reviewer` over the branch
  diff — expect no HIGH findings: PCE rethrow present, fail-closed honored, facade single-writer kept).

```bash
git add src/test/kotlin/com/ghostdebugger/rules/CustomRuleE2ETest.kt
git commit -m "test(v3.1): e2e dogfood — PCE-rethrow custom rule flags + fixes"
```

---

## Self-review (against the spec)

- §3 schema → Task 3 ✅ · §4 matcher + fail-closed → Task 5 (canary) ✅ · §5 FixPlan fix path → Task 7 ✅
- §6 integration seams (AnalysisEngine, plugin.xml extensions, IssueSource) → Tasks 2/4/6 ✅
- §7 error handling (skip-on-malformed, dup-id drop, PCE rethrow) → Tasks 4/5 ✅
- §8 testing (loader, matcher canary, fix refuse, e2e) → Tasks 3–8 ✅
- **Partition honored:** deps block (T1), enum (T2), analyzers list (T6), extensions (T4); all else new files.

## Execution handoff

Stream-B branch `stream/v3-custom-rules`. Integrate at **Merge 3** of the coordination plan (rebase on
`main` first). Subagent-driven recommended (one subagent per task).
