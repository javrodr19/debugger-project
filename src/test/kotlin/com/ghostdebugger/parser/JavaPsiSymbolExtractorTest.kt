package com.ghostdebugger.parser

import com.ghostdebugger.model.ParsedFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JavaPsiSymbolExtractorTest : BasePlatformTestCase() {

    private fun parseFile(source: String): ParsedFile {
        val vf = myFixture.configureByText("Sample.java", source).virtualFile
        return ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "java",
            content = source
        )
    }

    fun testExtractsImports() {
        val src = """
            package example;
            import java.util.List;
            import java.util.Map;
            public class Sample {}
        """.trimIndent()

        val out = JavaPsiSymbolExtractor(project).extract(parseFile(src))

        val sources = out.imports.map { it.source }.toSet()
        assertTrue(sources.containsAll(setOf("java.util.List", "java.util.Map")))
    }

    fun testExtractsMethodsButNotConstructors() {
        val src = """
            package example;
            public class Sample {
                public Sample() {}
                public void greet() {}
                public int add(int a, int b) { return a + b; }
            }
        """.trimIndent()

        val out = JavaPsiSymbolExtractor(project).extract(parseFile(src))

        val names = out.functions.map { it.name }.toSet()
        assertTrue(names.contains("greet"))
        assertTrue(names.contains("add"))
        assertFalse(names.contains("Sample"))
    }

    fun testExtractsClassAsExport() {
        val src = """
            package example;
            public class Widget {}
            class Helper {}
        """.trimIndent()

        val out = JavaPsiSymbolExtractor(project).extract(parseFile(src))

        val names = out.exports.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("Widget", "Helper")))
    }

    fun testBrokenFileFallsBackToRegex() {
        val src = """
            package example;
            public class Broken {
                public void missing
        """.trimIndent()

        val out = JavaPsiSymbolExtractor(project).extract(parseFile(src))

        assertTrue(out.exports.any { it.name == "Broken" })
    }
}
