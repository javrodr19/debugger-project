package com.ghostdebugger.parser

import com.ghostdebugger.model.ParsedFile
import com.intellij.openapi.vfs.VirtualFile
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TsJsRegexHardeningTest {

    private fun parsed(content: String, ext: String = "ts"): ParsedFile = ParsedFile(
        virtualFile = mockk<VirtualFile>(relaxed = true),
        path = "/fake.$ext",
        extension = ext,
        content = content
    )

    private val extractor = TsJsRegexSymbolExtractor()

    @Test
    fun `multi-line named imports are parsed into a single logical line`() {
        val src = """
            import {
              a,
              b as renamed,
              c
            } from 'lib';
        """.trimIndent()

        val out = extractor.extract(parsed(src))

        assertEquals(1, out.imports.size)
        val imp = out.imports.single()
        assertEquals("lib", imp.source)
        assertEquals(listOf("a", "b", "c"), imp.names)
    }

    @Test
    fun `function declaration inside a string literal is not extracted`() {
        val src = """
            const s = "function fake(x) {}";
            function real() {}
        """.trimIndent()

        val out = extractor.extract(parsed(src))

        assertTrue(out.functions.any { it.name == "real" })
        assertFalse(out.functions.any { it.name == "fake" })
    }

    @Test
    fun `function inside a line comment is not extracted`() {
        val src = """
            // function fakeInComment() {}
            function real() {}
        """.trimIndent()

        val out = extractor.extract(parsed(src))

        assertTrue(out.functions.any { it.name == "real" })
        assertFalse(out.functions.any { it.name == "fakeInComment" })
    }

    @Test
    fun `function inside a block comment is not extracted`() {
        val src = """
            /*
             * function fakeInBlock() {}
             */
            function real() {}
        """.trimIndent()

        val out = extractor.extract(parsed(src))

        assertTrue(out.functions.any { it.name == "real" })
        assertFalse(out.functions.any { it.name == "fakeInBlock" })
    }

    @Test
    fun `import inside a template literal is not extracted`() {
        val src = """
            const s = `import something from 'nothing';`;
            import real from 'real-lib';
        """.trimIndent()

        val out = extractor.extract(parsed(src))

        assertEquals(1, out.imports.size)
        assertEquals("real-lib", out.imports.single().source)
    }

    @Test
    fun `line numbers still reflect original source after multi-line collapse`() {
        val src = """
            import {
              a
            } from 'lib';
            function f() {}
        """.trimIndent()

        val out = extractor.extract(parsed(src))

        assertEquals(1, out.imports.single().line)
        assertEquals(4, out.functions.single { it.name == "f" }.line)
    }
}
