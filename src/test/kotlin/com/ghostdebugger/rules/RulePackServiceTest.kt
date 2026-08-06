package com.ghostdebugger.rules

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RulePackServiceTest : BasePlatformTestCase() {

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
