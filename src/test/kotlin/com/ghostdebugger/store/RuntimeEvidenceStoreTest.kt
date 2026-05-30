package com.ghostdebugger.store

import com.ghostdebugger.model.Confidence
import com.ghostdebugger.model.ConfidenceCalculator
import com.ghostdebugger.model.EvidenceOutcome
import com.ghostdebugger.model.EvidenceSource
import com.ghostdebugger.model.RuntimeEvidence
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RuntimeEvidenceStoreTest : BasePlatformTestCase() {

    // ── Layer 1: Pure-logic Unit Tests ─────────────────────────────────────

    fun testConfidenceCalculator() {
        // Empty evidence -> UNCONFIRMED
        Assert.assertEquals(Confidence.UNCONFIRMED, ConfidenceCalculator.calculate(emptyList()))

        // Single CONFIRMED -> CONFIRMED
        val evidence1 = RuntimeEvidence("f1", EvidenceSource.TEST_FAILURE, EvidenceOutcome.CONFIRMED, 1000)
        Assert.assertEquals(Confidence.CONFIRMED, ConfidenceCalculator.calculate(listOf(evidence1)))

        // CONFIRMED + DEMOTED -> CONFIRMED (confirmed always wins)
        val evidence2 = RuntimeEvidence("f1", EvidenceSource.DEBUG_OBSERVATION, EvidenceOutcome.DEMOTED, 1000)
        Assert.assertEquals(Confidence.CONFIRMED, ConfidenceCalculator.calculate(listOf(evidence1, evidence2)))

        // DEMOTED alone -> DEMOTED
        Assert.assertEquals(Confidence.DEMOTED, ConfidenceCalculator.calculate(listOf(evidence2)))

        // LIKELY -> LIKELY
        val evidence3 = RuntimeEvidence("f1", EvidenceSource.TEST_COVERAGE, EvidenceOutcome.LIKELY, 1000)
        Assert.assertEquals(Confidence.LIKELY, ConfidenceCalculator.calculate(listOf(evidence3)))

        // All UNREACHED -> UNREACHED
        val evidence4 = RuntimeEvidence("f1", EvidenceSource.TEST_COVERAGE, EvidenceOutcome.UNREACHED, 1000)
        Assert.assertEquals(Confidence.UNREACHED, ConfidenceCalculator.calculate(listOf(evidence4)))
    }

    fun testStackTraceParser() {
        // JVM style
        val jvmTrace = "at com.acme.Foo.bar(Foo.kt:42)"
        val jvmFrames = StackTraceParser.parse(jvmTrace)
        Assert.assertEquals(1, jvmFrames.size)
        Assert.assertEquals("Foo.kt", jvmFrames[0].fileName)
        Assert.assertEquals(42, jvmFrames[0].line)

        // JS webpack style
        val jsTrace = "at bar (webpack:///./src/Foo.ts:125:7)"
        val jsFrames = StackTraceParser.parse(jsTrace)
        Assert.assertEquals(1, jsFrames.size)
        Assert.assertEquals("Foo.ts", jsFrames[0].fileName)
        Assert.assertEquals(125, jsFrames[0].line)

        // Raw path style
        val pathTrace = "at /home/user/project/src/sub/Baz.js:88:12"
        val pathFrames = StackTraceParser.parse(pathTrace)
        Assert.assertEquals(1, pathFrames.size)
        Assert.assertEquals("Baz.js", pathFrames[0].fileName)
        Assert.assertEquals(88, pathFrames[0].line)
    }

    // ── Layer 2: Persistence and PSC Serialization Tests ───────────────────

    fun testRuntimeEvidenceStoreSerialization() {
        val store = RuntimeEvidenceStore(project)
        
        val evidence = RuntimeEvidence(
            fingerprint = "rule:file.kt:42",
            source = EvidenceSource.TEST_FAILURE,
            outcome = EvidenceOutcome.CONFIRMED,
            timestamp = System.currentTimeMillis(),
            context = "Some Test"
        )
        
        store.record(evidence)
        
        // Retrieve state
        val state = store.state
        Assert.assertNotNull(state)
        Assert.assertEquals(1, state.evidenceList.size)
        Assert.assertEquals("rule:file.kt:42", state.evidenceList[0].fingerprint)

        // Round-trip loading state
        val newStore = RuntimeEvidenceStore(project)
        newStore.loadState(state)
        val lookupResult = newStore.lookup("rule:file.kt:42")
        Assert.assertEquals(1, lookupResult.size)
        Assert.assertEquals(EvidenceOutcome.CONFIRMED, lookupResult[0].outcome)
    }

    fun testStoreOrphanGc() {
        val store = RuntimeEvidenceStore(project)
        
        val evA = RuntimeEvidence("fingerprintA", EvidenceSource.TEST_FAILURE, EvidenceOutcome.CONFIRMED, 1000)
        val evB = RuntimeEvidence("fingerprintB", EvidenceSource.TEST_FAILURE, EvidenceOutcome.CONFIRMED, 1000)
        
        store.record(evA)
        store.record(evB)
        
        Assert.assertEquals(1, store.lookup("fingerprintA").size)
        Assert.assertEquals(1, store.lookup("fingerprintB").size)
        
        // GC everything except A
        store.clearOrphans(setOf("fingerprintA"))
        
        Assert.assertEquals(1, store.lookup("fingerprintA").size)
        Assert.assertEquals(0, store.lookup("fingerprintB").size)
    }

    fun testSuppressionMemoryService() {
        val suppression = SuppressionMemoryService.getInstance(project)
        suppression.resetAll()
        
        val fingerprint = "null-safety:file.kt:10"
        
        Assert.assertFalse(suppression.shouldAutoHide(fingerprint))
        
        // Default threshold is 3. Let's record 3 dismissals.
        suppression.recordDismissal(fingerprint)
        suppression.recordDismissal(fingerprint)
        suppression.recordDismissal(fingerprint)
        
        Assert.assertTrue(suppression.shouldAutoHide(fingerprint))
        
        // Reset dismissal
        suppression.reset(fingerprint)
        Assert.assertFalse(suppression.shouldAutoHide(fingerprint))
    }

    fun testStoreDebouncedListener() {
        val store = RuntimeEvidenceStore(project)
        var affectedKeys = emptySet<String>()

        store.addListener({ affected ->
            affectedKeys = affected
        }, testRootDisposable)

        val ev = RuntimeEvidence("key123", EvidenceSource.DEBUG_OBSERVATION, EvidenceOutcome.CONFIRMED, 1000)
        store.record(ev)

        // Pump EDT event queue to allow debounced scheduler and invokeLater to execute
        val start = System.currentTimeMillis()
        while (!affectedKeys.contains("key123") && System.currentTimeMillis() - start < 3000) {
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        
        Assert.assertTrue(affectedKeys.contains("key123"))
    }
}
