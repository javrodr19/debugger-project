package com.ghostdebugger.fix.engine

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.intellij.openapi.application.runReadAction
import org.jetbrains.kotlin.psi.KtFile

class ConvertToSafeCastTest : AegisKotlinAnalysisTestCase() {

    private fun ktFile(src: String): KtFile =
        myFixture.configureByText("A.kt", src) as KtFile

    fun testRewritesUnsafeCastInNonNullReturnToThrowFallback() {
        val src = "fun run(a: Any): String { return a as String }\n"
        val file = ktFile(src)
        val offset = src.indexOf(" as ") + 1     // offset of the `as` keyword
        val ctx = FixContext(src) { file }
        val edit = runReadAction { ConvertToSafeCast(offset).toEdit(ctx) }!!
        val result = listOf(edit).applyTo(src)
        assertTrue(result, result.contains("a as? String ?: throw IllegalStateException"))
        assertFalse(result, result.contains(" as String"))
    }

    fun testReturnsNullWhenNoCastAtOffset() {
        val src = "fun run(): Int { return 1 }\n"
        val file = ktFile(src)
        val ctx = FixContext(src) { file }
        assertNull(runReadAction { ConvertToSafeCast(0).toEdit(ctx) })
    }
}
