package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AddElvisDefaultTest : BasePlatformTestCase() {
    private fun ctxFor(fileName: String, content: String): FixContext {
        val psi = myFixture.configureByText(fileName, content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testKotlinAppendsElvis() {
        val content = "fun f(): String {\n    return name\n}\n"
        val edit = runReadAction { AddElvisDefault(line = 2, expr = "name", default = "\"\"").toEdit(ctxFor("A.kt", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("name ?: \"\""))
    }

    fun testTypeScriptUsesNullishCoalescing() {
        val content = "function f() {\n  return name;\n}\n"
        val edit = runReadAction { AddElvisDefault(line = 2, expr = "name", default = "''").toEdit(ctxFor("a.ts", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("name ?? ''"))
    }

    fun testNullWhenExprAbsent() {
        val content = "fun f(): String {\n    return name\n}\n"
        assertNull(runReadAction { AddElvisDefault(line = 2, expr = "missing", default = "\"\"").toEdit(ctxFor("A.kt", content)) })
    }
}
