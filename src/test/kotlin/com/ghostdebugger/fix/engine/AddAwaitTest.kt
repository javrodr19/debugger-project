package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AddAwaitTest : BasePlatformTestCase() {
    private fun ctxFor(fileName: String, content: String): FixContext {
        val psi = myFixture.configureByText(fileName, content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testPrefixesCallWithAwait() {
        val content = "async function f() {\n  const r = fetch(url);\n}\n"
        val edit = runReadAction { AddAwait(line = 2, call = "fetch(").toEdit(ctxFor("a.ts", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("await fetch("))
    }

    fun testDeclinesWhenAlreadyAwaited() {
        val content = "async function f() {\n  const r = await fetch(url);\n}\n"
        assertNull(runReadAction { AddAwait(line = 2, call = "fetch(").toEdit(ctxFor("a.ts", content)) })
    }

    fun testNullOnKotlin() {
        val content = "fun f() {\n    val r = fetch()\n}\n"
        assertNull(runReadAction { AddAwait(line = 2, call = "fetch(").toEdit(ctxFor("A.kt", content)) })
    }
}
