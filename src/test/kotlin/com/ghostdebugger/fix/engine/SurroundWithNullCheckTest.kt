package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SurroundWithNullCheckTest : BasePlatformTestCase() {
    private fun ctxFor(fileName: String, content: String): FixContext {
        val psi = myFixture.configureByText(fileName, content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testWrapsLineInNullGuardPreservingIndent() {
        val content = "fun f(user: User?) {\n    println(user.name)\n}\n"
        val edit = runReadAction { SurroundWithNullCheck(line = 2, variable = "user").toEdit(ctxFor("A.kt", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("if (user != null) {"))
        assertTrue(after, after.contains("println(user.name)"))
    }

    fun testNullWhenLineBlank() {
        val content = "fun f() {\n\n}\n"
        assertNull(runReadAction { SurroundWithNullCheck(line = 2, variable = "x").toEdit(ctxFor("A.kt", content)) })
    }
}
