package com.ghostdebugger.analysis

import com.ghostdebugger.analysis.analyzers.*
import com.ghostdebugger.model.IssueSeverity
import kotlin.test.Test
import kotlin.test.assertTrue

class AnalyzerContractTest {

    private val analyzers: List<Analyzer> = listOf(
        PsiSyntaxAnalyzer(),
        CompilationErrorAnalyzer(),
        NullSafetyAnalyzer(),
        StateInitAnalyzer(),
        AsyncFlowAnalyzer(),
        CircularDependencyAnalyzer(),
        ComplexityAnalyzer()
    )

    @Test
    fun `every V1 analyzer declares non-blank metadata`() {
        for (a in analyzers) {
            assertTrue(a.name.isNotBlank(), "name blank for $a")
            assertTrue(a.ruleId.isNotBlank(), "ruleId blank for ${a.name}")
            assertTrue(a.description.isNotBlank(), "description blank for ${a.name}")
            assertTrue(
                a.defaultSeverity in listOf(IssueSeverity.ERROR, IssueSeverity.WARNING, IssueSeverity.INFO),
                "invalid defaultSeverity for ${a.name}"
            )
        }
    }

    @Test
    fun `ruleIds are unique and match AEG pattern`() {
        val ids = analyzers.map { it.ruleId }
        assertTrue(ids.distinct().size == ids.size, "duplicate ruleId in $ids")
        val pattern = Regex("""^AEG-[A-Z]+-\d{3}$""")
        for (id in ids) {
            assertTrue(pattern.matches(id), "ruleId '$id' does not match AEG-<CATEGORY>-<NNN>")
        }
    }
}
