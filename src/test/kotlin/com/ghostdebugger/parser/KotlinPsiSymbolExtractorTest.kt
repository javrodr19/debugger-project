package com.ghostdebugger.parser

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.model.ParsedFile
import com.intellij.openapi.application.ApplicationManager

class KotlinPsiSymbolExtractorTest : AegisKotlinAnalysisTestCase() {

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

    fun testCanary_returnTypeIsRendered() {
        val src = """
            fun greet(): String = "hi"
        """.trimIndent()
        val extractor = KotlinPsiSymbolExtractor(project)
        val vf = myFixture.configureByText("Sample.kt", src).virtualFile
        val pf = com.ghostdebugger.model.ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "kt",
            content = src
        )
        val out = ApplicationManager.getApplication().runReadAction<ParsedFile> { extractor.extract(pf) }
        val greet = out.functions.first { it.name == "greet" }
        assertEquals("String", greet.returnType)
    }

    fun testCanary_nullableReturnTypeIsShortForm() {
        val src = """
            fun lookup(): String? = null
        """.trimIndent()
        val extractor = KotlinPsiSymbolExtractor(project)
        val vf = myFixture.configureByText("Sample.kt", src).virtualFile
        val pf = com.ghostdebugger.model.ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "kt",
            content = src
        )
        val out = ApplicationManager.getApplication().runReadAction<ParsedFile> { extractor.extract(pf) }
        val lookup = out.functions.first { it.name == "lookup" }
        assertEquals(
            "Renderer should produce 'String?' (short form). If you see 'kotlin.String?', the renderer drifted to FQN form.",
            "String?", lookup.returnType
        )
    }

    fun testCanary_paramTypesRendered() {
        val src = """
            fun add(a: Int, b: Int): Int = a + b
        """.trimIndent()
        val extractor = KotlinPsiSymbolExtractor(project)
        val vf = myFixture.configureByText("Sample.kt", src).virtualFile
        val pf = com.ghostdebugger.model.ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "kt",
            content = src
        )
        val out = ApplicationManager.getApplication().runReadAction<ParsedFile> { extractor.extract(pf) }
        val add = out.functions.first { it.name == "add" }
        assertEquals(listOf("Int", "Int"), add.paramTypes)
    }

    fun testCanary_unitReturnFiltered() {
        val src = """
            fun shout(msg: String) { println(msg) }
        """.trimIndent()
        val extractor = KotlinPsiSymbolExtractor(project)
        val vf = myFixture.configureByText("Sample.kt", src).virtualFile
        val pf = com.ghostdebugger.model.ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "kt",
            content = src
        )
        val out = ApplicationManager.getApplication().runReadAction<ParsedFile> { extractor.extract(pf) }
        val shout = out.functions.first { it.name == "shout" }
        assertNull(
            "Unit return should be filtered to null (don't clutter signatures with Unit)",
            shout.returnType
        )
    }
}
