package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SurroundWithTryCatchTest : BasePlatformTestCase() {
    private fun ctxFor(fileName: String, content: String): FixContext {
        val psi = myFixture.configureByText(fileName, content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testWrapsTypeScriptLineWithTryCatch() {
        val content = "function f() {\n  return res.json();\n}\n"
        val edit = runReadAction { SurroundWithTryCatch(startLine = 2, endLine = 2).toEdit(ctxFor("a.ts", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("try {"))
        assertTrue(after, after.contains("} catch (e) {"))
        assertTrue(after, after.contains("console.error(e)"))
        assertTrue(after, after.contains("return res.json();"))
    }

    fun testWrapsKotlinLineWithTypedCatch() {
        val content = "fun f() {\n    risky()\n}\n"
        val edit = runReadAction { SurroundWithTryCatch(startLine = 2, endLine = 2).toEdit(ctxFor("A.kt", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("} catch (e: Exception) {"))
        assertTrue(after, after.contains("risky()"))
    }

    fun testNullWhenRangeBlank() {
        val content = "fun f() {\n\n}\n"
        assertNull(runReadAction { SurroundWithTryCatch(startLine = 2, endLine = 2).toEdit(ctxFor("A.kt", content)) })
    }
}
