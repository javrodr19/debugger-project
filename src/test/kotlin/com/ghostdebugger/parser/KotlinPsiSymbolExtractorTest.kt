package com.ghostdebugger.parser

import com.ghostdebugger.model.ParsedFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KotlinPsiSymbolExtractorTest : BasePlatformTestCase() {

    private fun parseFile(source: String): ParsedFile {
        val vf = myFixture.configureByText("Sample.kt", source).virtualFile
        return ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "kt",
            content = source
        )
    }

    fun testExtractsTopLevelFunction() {
        val src = """
            package example
            fun greet(name: String): String = "hi, ${'$'}name"
        """.trimIndent()

        val out = KotlinPsiSymbolExtractor(project).extract(parseFile(src))

        assertTrue(out.functions.any { it.name == "greet" })
        assertEquals(2, out.functions.single { it.name == "greet" }.line)
    }

    fun testExtractsSuspendFunctionAsAsync() {
        val src = """
            package example
            suspend fun load(): Unit {}
        """.trimIndent()

        val out = KotlinPsiSymbolExtractor(project).extract(parseFile(src))

        val load = out.functions.single { it.name == "load" }
        assertTrue(load.isAsync)
    }

    fun testExtractsImports() {
        val src = """
            package example
            import java.util.UUID
            import kotlin.collections.List
            fun foo() {}
        """.trimIndent()

        val out = KotlinPsiSymbolExtractor(project).extract(parseFile(src))

        val sources = out.imports.map { it.source }.toSet()
        assertTrue(sources.containsAll(setOf("java.util.UUID", "kotlin.collections.List")))
    }

    fun testExtractsPropertiesAsVariables() {
        val src = """
            package example
            val x: Int = 1
            var y: String = "ok"
        """.trimIndent()

        val out = KotlinPsiSymbolExtractor(project).extract(parseFile(src))

        val names = out.variables.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("x", "y")))
    }

    fun testExtractsClassAndObjectAsExports() {
        val src = """
            package example
            class Widget
            object Singleton
        """.trimIndent()

        val out = KotlinPsiSymbolExtractor(project).extract(parseFile(src))

        val names = out.exports.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("Widget", "Singleton")))
    }

    fun testBrokenFileFallsBackToRegex() {
        val src = """
            package example
            fun survive() {
        """.trimIndent()

        val out = KotlinPsiSymbolExtractor(project).extract(parseFile(src))

        assertTrue(out.functions.any { it.name == "survive" })
    }
}
