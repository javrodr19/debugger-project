package com.ghostdebugger.rules

import com.ghostdebugger.AegisCapability
import com.ghostdebugger.AegisCapabilityGate
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * RULE_PACKS is gated in 3.0.0 (Task 5's `RulePackService.packRules` guard). Without lifting the
 * gate, no test here can reach the pack-enable/disable logic these tests were written to cover,
 * because `packRules()` short-circuits to empty before ever consulting `availablePacks()`.
 * [AegisCapabilityGate.setEnabledForTest] lifts the gate for just this capability, for just this
 * test class, without touching what actually ships; [AegisCapabilityGate.resetForTest] restores
 * the shipped (disabled) state in `tearDown` so no override leaks into other tests, including
 * `AegisCapabilityGateTest`'s "no capability is enabled in this release" assertion.
 */
class RulePackServiceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        AegisCapabilityGate.setEnabledForTest(setOf(AegisCapability.RULE_PACKS))
    }

    override fun tearDown() {
        AegisCapabilityGate.resetForTest()
        super.tearDown()
    }

    fun `test loads bundled rule packs`() {
        val service = RulePackService.getInstance(project)
        val packs = service.availablePacks()
        
        assertTrue(packs.isNotEmpty())
        val ids = packs.map { it.id }
        assertTrue(ids.contains("react-strict"))
        assertTrue(ids.contains("kotlin-coroutines"))
        assertTrue(ids.contains("node-security"))
    }

    fun `test pack rules are returned when enabled`() {
        val service = RulePackService.getInstance(project)
        val packRules = service.packRules()
        assertTrue(packRules.isNotEmpty())
        
        val pceRule = packRules.firstOrNull { it.id == "pce-rethrow-missing" }
        assertNotNull(pceRule)
    }

    fun `test disabling pack excludes its rules`() {
        val service = RulePackService.getInstance(project)
        service.setPackEnabled("kotlin-coroutines", false)
        
        val packRules = service.packRules()
        val pceRule = packRules.firstOrNull { it.id == "pce-rethrow-missing" }
        assertNull(pceRule)

        // Re-enable
        service.setPackEnabled("kotlin-coroutines", true)
        assertTrue(service.packRules().any { it.id == "pce-rethrow-missing" })
    }

    fun `test custom repo rule overrides pack rule with same ID`() {
        // Create custom rule with same ID in .aegis/rules/
        val overrideYaml = """
            version: 1
            rules:
              - id: pce-rethrow-missing
                language: kotlin
                severity: error
                message: "OVERRIDDEN REPO RULE MESSAGE"
                match: { element: catch-clause }
        """.trimIndent()

        myFixture.tempDirFixture.createFile(".aegis/rules/override.yml", overrideYaml)
        
        val customService = CustomRuleService.getInstance(project)
        val rules = customService.rules()
        
        val matched = rules.firstOrNull { it.id == "pce-rethrow-missing" }
        assertNotNull(matched)
        assertEquals("OVERRIDDEN REPO RULE MESSAGE", matched!!.message)
        assertEquals(RuleSeverity.ERROR, matched.severity)
    }
}
