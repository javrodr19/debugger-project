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

        // Jest standard parenthesized trace
        val jestTrace = "at Object.<anonymous> (/home/user/src/utils/math.test.ts:42:12)"
        val jestFrames = StackTraceParser.parse(jestTrace)
        Assert.assertEquals(1, jestFrames.size)
        Assert.assertEquals("math.test.ts", jestFrames[0].fileName)
        Assert.assertEquals(42, jestFrames[0].line)

        // Vitest spec trace
        val vitestTrace = "at Context.<anonymous> (test/index.spec.js:15:10)"
        val vitestFrames = StackTraceParser.parse(vitestTrace)
        Assert.assertEquals(1, vitestFrames.size)
        Assert.assertEquals("index.spec.js", vitestFrames[0].fileName)
        Assert.assertEquals(15, vitestFrames[0].line)

        // Raw suffix traces (Mocha/Karma style)
        val mochaTrace = "test/index.spec.js:15:10"
        val mochaFrames = StackTraceParser.parse(mochaTrace)
        Assert.assertEquals(1, mochaFrames.size)
        Assert.assertEquals("index.spec.js", mochaFrames[0].fileName)
        Assert.assertEquals(15, mochaFrames[0].line)

        // Native ESM file URLs
        val esmTrace = "at file:///home/user/project/src/main.ts:88:12"
        val esmFrames = StackTraceParser.parse(esmTrace)
        Assert.assertEquals(1, esmFrames.size)
        Assert.assertEquals("main.ts", esmFrames[0].fileName)
        Assert.assertEquals(88, esmFrames[0].line)

        // Paths with spaces in directories
        val spacesTrace = "at Object.run (/Users/user/My Projects/src/main.ts:88:12)"
        val spacesFrames = StackTraceParser.parse(spacesTrace)
        Assert.assertEquals(1, spacesFrames.size)
        Assert.assertEquals("main.ts", spacesFrames[0].fileName)
        Assert.assertEquals(88, spacesFrames[0].line)

        // Webpack nested source-maps
        val webpackTrace = "at Object.webpackContext [as keys] (webpack:///./src/utils/math.ts:25:9)"
        val webpackFrames = StackTraceParser.parse(webpackTrace)
        Assert.assertEquals(1, webpackFrames.size)
        Assert.assertEquals("math.ts", webpackFrames[0].fileName)
        Assert.assertEquals(25, webpackFrames[0].line)

        // Deduplication: overlapping matches on a single frame
        val overlappingTrace = "at Object.<anonymous> (/src/main.ts:42:12)"
        val overlappingFrames = StackTraceParser.parse(overlappingTrace)
        Assert.assertEquals(1, overlappingFrames.size)
        Assert.assertEquals("main.ts", overlappingFrames[0].fileName)
        Assert.assertEquals(42, overlappingFrames[0].line)

        // Windows absolute paths: backslash separators, no '/'. The simple filename must still be
        // extracted so it matches slash-normalized issue paths in the cross-checker. Regression for BUG-18.
        val windowsTrace = "at Aegis.run (C:\\Users\\dev\\project\\src\\Widget.ts:42:7)"
        val windowsFrames = StackTraceParser.parse(windowsTrace)
        Assert.assertEquals(1, windowsFrames.size)
        Assert.assertEquals("Widget.ts", windowsFrames[0].fileName)
        Assert.assertEquals(42, windowsFrames[0].line)
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
        Assert.assertEquals(0, suppression.getDismissCounts().size)
        
        // Default threshold is 3. Let's record 3 dismissals.
        suppression.recordDismissal(fingerprint)
        Assert.assertEquals(1, suppression.getDismissCounts()[fingerprint])
        suppression.recordDismissal(fingerprint)
        suppression.recordDismissal(fingerprint)
        
        Assert.assertTrue(suppression.shouldAutoHide(fingerprint))
        Assert.assertEquals(3, suppression.getDismissCounts()[fingerprint])
        
        // Reset dismissal
        suppression.reset(fingerprint)
        Assert.assertFalse(suppression.shouldAutoHide(fingerprint))
        Assert.assertFalse(suppression.getDismissCounts().containsKey(fingerprint))
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
