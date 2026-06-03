package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AddPromiseCatchTest : BasePlatformTestCase() {
    private fun ctxFor(fileName: String, content: String): FixContext {
        val psi = myFixture.configureByText(fileName, content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testAppendsCatchBeforeTrailingSemicolon() {
        val content = "function f() {\n  doThing().then(handle);\n}\n"
        val edit = runReadAction { AddPromiseCatch(line = 2).toEdit(ctxFor("a.ts", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains(".then(handle).catch(console.error);"))
    }

    fun testNullWhenLineDoesNotEndWithCallSemicolon() {
        val content = "function f() {\n  const x = 1\n}\n"
        assertNull(runReadAction { AddPromiseCatch(line = 2).toEdit(ctxFor("a.ts", content)) })
    }

    fun testNullOnKotlin() {
        val content = "fun f() {\n    doThing().then(handle);\n}\n"
        assertNull(runReadAction { AddPromiseCatch(line = 2).toEdit(ctxFor("A.kt", content)) })
    }
}
