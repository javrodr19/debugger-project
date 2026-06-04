# Catalog Breadth — Batch 5: AI Operation Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a single-source `FixOperationCatalog` describing all 14 operations, regenerate the AI `planFix` prompt from it (so the planner can compose every op, not just the original 3), and add a coverage test that fails if any `FixOperation` lacks a catalog entry.

**Architecture:** `FixOperationCatalog.entries` is an authored list of one-line JSON schemas (one per op); `PromptTemplates.planFix` renders the ops section from it instead of hardcoding. `FixOperationCatalogTest` asserts `FixOperationCatalog.serialNames()` exactly equals the set of registered `FixOperation` sealed-subclass `@SerialName`s (via each subclass's serializer descriptor), so a new op can never silently miss the prompt. `FixPlanCodec` is unchanged (kotlinx already decodes the full sealed hierarchy). Spec: `docs/superpowers/specs/2026-06-02-fix-engine-catalog-breadth-design.md` §3.4.

**Tech Stack:** Kotlin 2.0.21, kotlinx.serialization, plain JUnit4.

---

## Prerequisites (test prelude)

Tests need a JBR; shell env does NOT persist between Bash commands. Prefix EVERY gradlew call inline:
```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` — 14 `@Serializable @SerialName(...)` subclasses (`replaceRange`, `insertImport`, `convertToSafeCast`, `wrapInSafeCall`, `addElvisDefault`, `surroundWithNullCheck`, `addAwait`, `addPromiseCatch`, `addExplicitConversion`, `surroundWithTryCatch`, `removeRange`, `replaceExpression`, `insertStatementBefore`, `insertStatementAfter`).
- `src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt:166-192` — `planFix(...)`; lines 182-187 hardcode the original 3 ops + the "All offsets" guidance line. That block is what Task 2 replaces.
- `src/test/kotlin/com/ghostdebugger/ai/PromptTemplatesPlanFixTest.kt` — existing prompt test (asserts the 3 original ops + issue/content/feedback). Extend in Task 2.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanCodec.kt` — unchanged (decodes any catalog op).

## File structure

- Create `fix/engine/FixOperationCatalog.kt` + `src/test/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalogTest.kt`.
- Modify `ai/prompts/PromptTemplates.kt` (planFix → catalog-driven) + extend `ai/PromptTemplatesPlanFixTest.kt`.

---

### Task 1: `FixOperationCatalog` + coverage test

**Files:** Create `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalog.kt`; Test `src/test/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalogTest.kt`

- [ ] **Step 1: Write the failing coverage test**

```kotlin
package com.ghostdebugger.fix.engine

import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.full.starProjectedType

class FixOperationCatalogTest {
    @Test fun everySealedOperationHasExactlyOneCatalogEntry() {
        val registered = FixOperation::class.sealedSubclasses
            .map { serializer(it.starProjectedType).descriptor.serialName }
            .toSet()
        assertEquals(registered, FixOperationCatalog.serialNames())
    }

    @Test fun entriesAreNonEmptyAndTypePrefixed() {
        assertEquals(14, FixOperationCatalog.entries.size)
        FixOperationCatalog.entries.forEach { assertEquals(true, it.trimStart().startsWith("{\"type\":\"")) }
    }
}
```

- [ ] **Step 2: Run → FAIL** (`FixOperationCatalog` unresolved).

Run: `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.engine.FixOperationCatalogTest"`

- [ ] **Step 3: Implement `FixOperationCatalog.kt`**

```kotlin
package com.ghostdebugger.fix.engine

/**
 * Single source of truth for the AI planner's operation catalog. Each entry is a one-line JSON schema
 * (plus a short `//` note) for one [FixOperation]; `PromptTemplates.planFix` renders these, so adding an
 * op here is all that exposes it to the AI. `FixOperationCatalogTest` enforces that every sealed
 * [FixOperation] subclass has exactly one entry.
 */
object FixOperationCatalog {
    val entries: List<String> = listOf(
        """{"type":"replaceRange","startOffset":<int>,"endOffset":<int>,"text":"<str>"} // replace chars [startOffset,endOffset); 0-based offsets""",
        """{"type":"insertImport","fqName":"<fully.qualified.Name>"} // add an import if absent""",
        """{"type":"convertToSafeCast","asOffset":<int>} // Kotlin: `x as T` -> `x as? T ?: ...`; asOffset = 0-based offset of the `as` keyword""",
        """{"type":"wrapInSafeCall","line":<int>,"receiver":"<id>"} // receiver.member -> receiver?.member""",
        """{"type":"addElvisDefault","line":<int>,"expr":"<expr>","default":"<value>"} // expr -> expr ?: default (Kotlin) / expr ?? default (JS/TS)""",
        """{"type":"surroundWithNullCheck","line":<int>,"variable":"<id>"} // wrap the line's statement in if (variable != null) { ... }""",
        """{"type":"addAwait","line":<int>,"call":"<call(>"} // JS/TS only: prefix the call with await""",
        """{"type":"addPromiseCatch","line":<int>,"handler":"<expr>"} // JS/TS only: append .catch(handler) to a ...); chain (handler optional)""",
        """{"type":"addExplicitConversion","line":<int>,"expr":"<expr>","conversion":"<.toX()|Wrapper>"} // expr.toLong() (suffix) or String(expr) (wrapper)""",
        """{"type":"surroundWithTryCatch","startLine":<int>,"endLine":<int>,"catchBody":"<stmt>"} // wrap lines in try/catch (catchBody optional; Kotlin uses a typed catch)""",
        """{"type":"removeRange","startLine":<int>,"endLine":<int>} // delete whole lines startLine..endLine""",
        """{"type":"replaceExpression","line":<int>,"find":"<text>","replacement":"<text>"} // replace the first `find` on the line""",
        """{"type":"insertStatementBefore","line":<int>,"statement":"<stmt>"} // insert a statement line before the target line""",
        """{"type":"insertStatementAfter","line":<int>,"statement":"<stmt>"} // insert a statement line after the target line""",
    )

    private val TYPE_RE = Regex(""""type":"([a-zA-Z]+)"""")

    /** The `type` discriminator of every entry; must match the registered [FixOperation] subclasses. */
    fun serialNames(): Set<String> = entries.mapNotNull { TYPE_RE.find(it)?.groupValues?.get(1) }.toSet()
}
```

- [ ] **Step 4: Run → PASS** (2 tests).

If `everySealedOperationHasExactlyOneCatalogEntry` fails, the assertion diff shows exactly which `@SerialName` is missing from / extra in the catalog — fix the `entries` list to match. (If `serializer(it.starProjectedType)` raises at runtime, substitute the sealed-parent descriptor navigation: `FixOperation.serializer().descriptor.getElementDescriptor(1).elementDescriptors.map { it.serialName }.toSet()` — but the per-subclass form is preferred.)

- [ ] **Step 5: Commit** `git commit -m "feat(fix-engine): FixOperationCatalog single source for the AI op catalog"`

---

### Task 2: Wire `planFix` to the catalog + extend the prompt test

**Files:** Modify `src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt`; extend `src/test/kotlin/com/ghostdebugger/ai/PromptTemplatesPlanFixTest.kt`

- [ ] **Step 1: Extend the prompt test (add cases)**

Add these to `PromptTemplatesPlanFixTest` (it already has `issue()` and tests the 3 original ops):

```kotlin
    @Test fun listsTheFullOperationCatalogIncludingNewOps() {
        val p = PromptTemplates.planFix(issue(), "x", feedback = null)
        // originals still present
        assertTrue(p.contains("replaceRange"))
        assertTrue(p.contains("convertToSafeCast"))
        // new ops from batches 1-4 are now exposed
        assertTrue(p.contains("wrapInSafeCall"))
        assertTrue(p.contains("surroundWithTryCatch"))
        assertTrue(p.contains("addExplicitConversion"))
        assertTrue(p.contains("removeRange"))
        assertTrue(p.contains("insertStatementAfter"))
    }

    @Test fun everyCatalogOpAppearsInThePrompt() {
        val p = PromptTemplates.planFix(issue(), "x", feedback = null)
        com.ghostdebugger.fix.engine.FixOperationCatalog.serialNames().forEach { op ->
            assertTrue("prompt missing op: $op", p.contains(op))
        }
    }
```

- [ ] **Step 2: Run → FAIL** (`everyCatalogOpAppearsInThePrompt` fails — the prompt still only lists 3 ops).

Run: `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.ai.PromptTemplatesPlanFixTest"`

- [ ] **Step 3: Make `planFix` catalog-driven**

In `PromptTemplates.planFix`, replace the hardcoded ops block — the six `sb.append(...)` calls for `replaceRange`/`insertImport`/`convertToSafeCast` and the following `"\nAll offsets are 0-based character indices into the file content below.\n"` line (currently lines ~182-187) — with:

```kotlin
        for (entry in com.ghostdebugger.fix.engine.FixOperationCatalog.entries) {
            sb.append("- ").append(entry).append('\n')
        }
        sb.append("\nUnless an op's note says otherwise, `line`/`startLine`/`endLine` are 1-based and any char offsets are 0-based, into the file content below.\n")
```

Leave the rest of `planFix` (issue block, feedback, the `{"issueId":...}` shape line, the `--- FILE CONTENT ---` section) unchanged.

- [ ] **Step 4: Run → PASS**

Run: `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.ai.PromptTemplatesPlanFixTest"` → all green (the original-3 assertions still hold; the new ops now appear; `everyCatalogOpAppearsInThePrompt` passes for all 14).

- [ ] **Step 5: Commit** `git commit -m "feat(fix-engine): planFix renders the full op catalog (AI can compose all ops)"`

---

## Final verification

- [ ] `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.engine.*" --tests "com.ghostdebugger.ai.*"` → green.
- [ ] `JAVA_HOME=... ./gradlew test 2>&1 | tail -6; echo EXIT=${PIPESTATUS[0]}` → full suite green, `EXIT=0`.

## Self-Review (completed during planning)

- **Spec coverage:** `FixOperationCatalog` self-describing single source (spec §3.4) → Task 1; `planFix` regenerated from it + coverage test → Tasks 1-2. This is the batch that makes all 14 ops AI-composable.
- **Coverage guarantee:** `FixOperationCatalogTest` compares the catalog's `type`s to the actual sealed-subclass `@SerialName`s — a new op without a catalog entry fails the test; `everyCatalogOpAppearsInThePrompt` proves the prompt renders them all.
- **Type consistency:** the 14 entries' `type` values exactly match the `@SerialName`s in `FixOperation.kt`; `FixOperationCatalog.{entries,serialNames}`; `planFix` iterates `entries`.
- **No behavior risk:** `FixPlanCodec` already decodes all ops (sealed polymorphism); only the *prompt text* changes. Decoding/applying is unchanged and gate-verified.
- **Placeholders:** none — complete catalog + tests; the one runtime-API fallback (sealed-parent descriptor) is noted for the coverage test.
