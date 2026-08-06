package com.ghostdebugger.rules

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CustomRuleServiceTest : BasePlatformTestCase() {
    private val validYaml = """
        version: 1
        rules:
          - id: pce-rethrow-missing
            language: kotlin
            severity: WARNING
            message: "catch (e: Exception) must rethrow ProcessCanceledException first"
            match: { element: catch-clause, parameter-type: java.lang.Exception }
    """.trimIndent()

    fun testLoadsValidRulesAndSkipsMalformedFiles() {
        myFixture.tempDirFixture.createFile(".aegis/rules/ok.yml", validYaml)
        myFixture.tempDirFixture.createFile(".aegis/rules/bad.yml", "rules: [ : :")
        val rules = CustomRuleService.getInstance(project).rules()
        val okRule = rules.firstOrNull { it.id == "pce-rethrow-missing" }
        assertNotNull(okRule)
        assertEquals("pce-rethrow-missing", okRule?.id)
    }
}
