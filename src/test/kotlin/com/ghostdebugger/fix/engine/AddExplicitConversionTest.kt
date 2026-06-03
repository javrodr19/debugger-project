package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AddExplicitConversionTest : BasePlatformTestCase() {
    private fun ctxFor(fileName: String, content: String): FixContext {
        val psi = myFixture.configureByText(fileName, content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testSuffixFormAppendsConversion() {
        val content = "fun f(x: Int): Long {\n    val y: Long = x\n}\n"
        val edit = runReadAction { AddExplicitConversion(line = 2, expr = "x", conversion = ".toLong()").toEdit(ctxFor("A.kt", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("val y: Long = x.toLong()"))
    }

    fun testWrapperFormWrapsExpression() {
        val content = "function f(n) {\n  const s = n;\n}\n"
        val edit = runReadAction { AddExplicitConversion(line = 2, expr = "n", conversion = "String").toEdit(ctxFor("a.ts", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("String(n)"))
    }

    fun testNullWhenExprAbsentOnLine() {
        val content = "fun f(): Long {\n    val x: Long = 1\n}\n"
        assertNull(runReadAction { AddExplicitConversion(line = 2, expr = "missing", conversion = ".toLong()").toEdit(ctxFor("A.kt", content)) })
    }
}
