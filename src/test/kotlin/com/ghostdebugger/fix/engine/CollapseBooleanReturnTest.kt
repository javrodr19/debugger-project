package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CollapseBooleanReturnTest : BasePlatformTestCase() {
    private fun ktCtx(content: String): FixContext {
        val psi = myFixture.configureByText("A.kt", content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    // Pure content ctx for the JS/TS regex path (no PSI).
    private fun tsCtx(content: String) = FixContext(content) { null }

    private fun applied(content: String, ctx: FixContext, line: Int): String? {
        val edit = runReadAction { CollapseBooleanReturn(line).toEdit(ctx) } ?: return null
        return content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
    }

    fun testKtPositiveInline() {
        val c = "fun f(a: Boolean): Boolean {\n    if (a) return true else return false\n}\n"
        assertEquals(
            "fun f(a: Boolean): Boolean {\n    return a\n}\n",
            applied(c, ktCtx(c), 2)
        )
    }

    fun testKtNegatedInline() {
        val c = "fun f(a: Boolean): Boolean {\n    if (a) return false else return true\n}\n"
        assertEquals(
            "fun f(a: Boolean): Boolean {\n    return !a\n}\n",
            applied(c, ktCtx(c), 2)
        )
    }

    fun testKtNegatedCompoundConditionGetsParens() {
        val c = "fun f(a: Boolean, b: Boolean): Boolean {\n    if (a && b) return false else return true\n}\n"
        assertEquals(
            "fun f(a: Boolean, b: Boolean): Boolean {\n    return !(a && b)\n}\n",
            applied(c, ktCtx(c), 2)
        )
    }

    fun testKtBlockBodies() {
        val c = "fun f(a: Boolean): Boolean {\n    if (a) {\n        return true\n    } else {\n        return false\n    }\n}\n"
        assertEquals(
            "fun f(a: Boolean): Boolean {\n    return a\n}\n",
            applied(c, ktCtx(c), 2)
        )
    }

    fun testKtDeclinesWhenNotBooleanReturns() {
        val c = "fun f(a: Boolean): Int {\n    if (a) return 1 else return 2\n}\n"
        assertNull(runReadAction { CollapseBooleanReturn(2).toEdit(ktCtx(c)) })
    }

    fun testKtDeclinesWhenNoElse() {
        val c = "fun f(a: Boolean): Boolean {\n    if (a) return true\n    return false\n}\n"
        assertNull(runReadAction { CollapseBooleanReturn(2).toEdit(ktCtx(c)) })
    }

    fun testSitesFindsEveryCollapsibleLine() {
        val c = "fun f(a: Boolean, b: Boolean) {\n" +
            "    if (a) return true else return false\n" +
            "    println(1)\n" +
            "    if (b) return false else return true\n" +
            "}\n"
        val sites = runReadAction { BooleanReturnCollapse.sites(ktCtx(c)) }
        assertEquals(listOf(2, 4), sites)
    }

    fun testTsInlinePositive() {
        val c = "function f(a) {\n  if (a) return true; else return false;\n}\n"
        assertEquals(
            "function f(a) {\n  return a;\n}\n",
            applied(c, tsCtx(c), 2)
        )
    }
}
