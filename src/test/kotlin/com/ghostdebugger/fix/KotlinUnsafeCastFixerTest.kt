package com.ghostdebugger.fix

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.analysis.analyzers.KotlinUnsafeCastAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile

class KotlinUnsafeCastFixerTest : AegisKotlinAnalysisTestCase() {

    private fun applyFix(source: String): String? {
        val vf = myFixture.configureByText("Sample.kt", source).virtualFile
        val pf = com.ghostdebugger.model.ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "kt",
            content = source
        )
        val ctx = com.ghostdebugger.model.AnalysisContext(
            graph = com.ghostdebugger.graph.InMemoryGraph(),
            project = project,
            parsedFiles = listOf(pf)
        )
        val issue = KotlinUnsafeCastAnalyzer().analyze(ctx).firstOrNull() ?: return null
        return ApplicationManager.getApplication().runReadAction<String?> {
            val ktFile = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: return@runReadAction null
            KotlinUnsafeCastFixer().generateFixFromPsi(issue, ktFile)?.fixedCode
        }
    }

    fun testReturnNullableUsesElvisReturnNull() {
        val src = """
            open class A
            class B : A()
            fun run(a: A): B? {
                return a as B
            }
        """.trimIndent()
        val fixed = applyFix(src)
        assertNotNull("Expected a fix to be derived for the unsafe cast", fixed)
        assertTrue(
            "Expected `as? B ?: return null` in fixed line; got: $fixed",
            fixed!!.contains("a as? B ?: return null")
        )
    }

    fun testReturnNonNullableUsesElvisThrow() {
        val src = """
            open class A
            class B : A()
            fun run(a: A): B {
                return a as B
            }
        """.trimIndent()
        val fixed = applyFix(src)
        assertNotNull(fixed)
        assertTrue(
            "Expected Elvis throw in fixed line; got: $fixed",
            fixed!!.contains("a as? B ?: throw IllegalStateException")
        )
    }

    fun testReturnUnitUsesElvisReturn() {
        val src = """
            open class A
            class B : A()
            fun run(a: A): Unit {
                return a as B
            }
        """.trimIndent()
        val fixed = applyFix(src)
        assertNotNull(fixed)
        assertTrue(
            "Expected Elvis return (no value) in fixed line; got: $fixed",
            fixed!!.contains("a as? B ?: return")
        )
    }

    fun testCastInVariableInitializerIsDeclined() {
        val src = """
            open class A
            class B : A()
            fun run(a: A) {
                val b: B = a as B
            }
        """.trimIndent()
        val fixed = applyFix(src)
        assertNull(
            "Expected fixer to decline (return null) for cast in non-return position; got: $fixed",
            fixed
        )
    }

    fun testCastInsideExpressionStatementIsDeclined() {
        val src = """
            open class A
            class B : A()
            fun run(a: A) {
                println(a as B)
            }
        """.trimIndent()
        val fixed = applyFix(src)
        assertNull(
            "Expected fixer to decline for cast inside println expression",
            fixed
        )
    }

    fun testSmartCastCoveredCastNotFlagged() {
        val src = """
            open class A
            class B : A()
            fun run(a: A): B? {
                if (a is B) {
                    return a as B
                }
                return null
            }
        """.trimIndent()
        // After Commit 4's smart-cast walker, the cast inside `if (a is B)` is no longer
        // flagged (walker narrows `a` to B before the cast). No issue -> no fix.
        val fixed = applyFix(src)
        assertNull(
            "Expected no issue (and therefore no fix) when smart-cast walker covers the receiver",
            fixed
        )
    }
}
