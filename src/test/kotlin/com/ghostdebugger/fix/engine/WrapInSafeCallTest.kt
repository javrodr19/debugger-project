package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class WrapInSafeCallTest : BasePlatformTestCase() {

    private fun ctxFor(fileName: String, content: String): FixContext {
        val psi = myFixture.configureByText(fileName, content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testKotlinRewritesDotAccessToSafeCall() {
        val content = "fun f(user: User?) {\n    val n = user.name\n}\n"
        val edit = runReadAction { WrapInSafeCall(line = 2, receiver = "user").toEdit(ctxFor("A.kt", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("user?.name"))
    }

    fun testTypeScriptInsertsOptionalChaining() {
        val content = "function f(user) {\n  const n = user.name;\n}\n"
        val edit = runReadAction { WrapInSafeCall(line = 2, receiver = "user").toEdit(ctxFor("a.ts", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("user?.name"))
    }

    fun testNullWhenReceiverNotOnLine() {
        val content = "fun f(user: User?) {\n    val n = user.name\n}\n"
        assertNull(runReadAction { WrapInSafeCall(line = 1, receiver = "user").toEdit(ctxFor("A.kt", content)) })
    }
}
