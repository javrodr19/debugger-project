package com.ghostdebugger.parser

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.bridge.renderShort
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

class KotlinAnalysisHelpersTest : AegisKotlinAnalysisTestCase() {

    /** Returns the rendered short form of `effectiveTypeWithStructuralSmartCast` at the
     *  N-th `targetName` reference in the source (0-indexed). */
    private fun typeAtNthRef(source: String, targetName: String, n: Int = 0): String? {
        val vf = myFixture.configureByText("Sample.kt", source).virtualFile
        return ApplicationManager.getApplication().runReadAction<String?> {
            val ktFile = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: return@runReadAction null
            val refs = PsiTreeUtil.findChildrenOfType(ktFile, KtNameReferenceExpression::class.java)
                .filter { it.getReferencedName() == targetName }
            val target = refs.getOrNull(n) ?: return@runReadAction null
            withKtAnalysis(ktFile) {
                val type = effectiveTypeWithStructuralSmartCast(target)
                if (type == null || type is KaErrorType) null else renderShort(type)
            }
        }
    }

    fun testIsCheckThenBranchNarrowsType() {
        val src = """
            fun run(a: Any) {
                if (a is String) {
                    println(a)
                }
            }
        """.trimIndent()
        // n=0 is `a` in `is String`; n=1 is `println(a)`.
        val type = typeAtNthRef(src, "a", n = 1)
        assertEquals("String", type)
    }

    fun testNotNullCheckThenBranchNarrowsType() {
        val src = """
            fun run(a: String?) {
                if (a != null) {
                    println(a)
                }
            }
        """.trimIndent()
        val type = typeAtNthRef(src, "a", n = 1)
        assertEquals("String", type)
    }

    fun testNotNullCheckSymmetricForm() {
        val src = """
            fun run(a: String?) {
                if (null != a) {
                    println(a)
                }
            }
        """.trimIndent()
        val type = typeAtNthRef(src, "a", n = 1)
        assertEquals("String", type)
    }

    fun testIsCheckElseBranchDoesNotNarrow() {
        val src = """
            fun run(a: Any) {
                if (a is String) {
                    val ignored = 0
                } else {
                    println(a)
                }
            }
        """.trimIndent()
        val type = typeAtNthRef(src, "a", n = 1)
        // In else-branch — walker should not narrow.
        assertEquals("Any", type)
    }

    fun testNarrowingDoesNotCrossNestedFunctionBoundary() {
        val src = """
            fun outer(a: Any) {
                if (a is String) {
                    val nested = fun() {
                        println(a)
                    }
                }
            }
        """.trimIndent()
        val type = typeAtNthRef(src, "a", n = 1)
        // Across a nested `fun() { ... }` lambda — walker should stop at the boundary.
        assertEquals("Any", type)
    }

    fun testElvisReturnPriorStatementNarrows() {
        val src = """
            fun run(x: String?): Int {
                val s = x ?: return 0
                return x.length
            }
        """.trimIndent()
        // n=0: `x` in `?:`, n=1: `x.length`.
        val type = typeAtNthRef(src, "x", n = 1)
        assertEquals("String", type)
    }

    fun testReassignmentBetweenGuardAndUseInvalidates() {
        val src = """
            fun run(a: String?) {
                if (a != null) {
                    var b: Any = a
                    b = 42
                    println(b)
                }
            }
        """.trimIndent()
        // `b` was reassigned to 42 (Int). The Analysis API's smartCastInfo channel reports
        // the narrowed type as Int at the println(b) site (last-assignment smart-cast).
        // The walker itself has no `if (b != null)` guard for `b`, so it cannot add further
        // structural narrowing — the type it returns is whatever effectiveType(b) reports,
        // which is "Int" here (K2 smart-cast from the `b = 42` assignment). Both "Int" and
        // "Any" are acceptable; the point of the test is that the walker does NOT wrongly
        // widen to String or produce an error type.
        val type = typeAtNthRef(src, "b", n = 1)
        assertNotNull("Walker should resolve a type for `b`", type)
        // "Int" is the smart-cast from the last assignment; "Any" would be the declared type.
        // Either is correct; the disallowed outcomes are null or "String" (no leakthrough from `a`).
        assertTrue(
            "Expected 'Int' or 'Any' for `b` after reassignment; got: $type",
            type == "Int" || type == "Any"
        )
    }
}
