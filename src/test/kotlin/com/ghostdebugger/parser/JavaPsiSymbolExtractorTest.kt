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

    fun testCanary_javaReturnTypeCaptured() {
        val src = """
            public class Sample {
                public String greet() { return "hi"; }
            }
        """.trimIndent()
        val extractor = JavaPsiSymbolExtractor(project)
        val vf = myFixture.configureByText("Sample.java", src).virtualFile
        val pf = com.ghostdebugger.model.ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "java",
            content = src
        )
        val out = extractor.extract(pf)
        val greet = out.functions.first { it.name == "greet" }
        assertEquals("String", greet.returnType)
    }

    fun testCanary_javaParamTypesCaptured() {
        val src = """
            public class Sample {
                public int add(int a, int b) { return a + b; }
            }
        """.trimIndent()
        val extractor = JavaPsiSymbolExtractor(project)
        val vf = myFixture.configureByText("Sample.java", src).virtualFile
        val pf = com.ghostdebugger.model.ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "java",
            content = src
        )
        val out = extractor.extract(pf)
        val add = out.functions.first { it.name == "add" }
        assertEquals(listOf("int", "int"), add.paramTypes)
    }

    fun testCanary_javaVoidReturnCaptured() {
        val src = """
            public class Sample {
                public void shout(String msg) { System.out.println(msg); }
            }
        """.trimIndent()
        val extractor = JavaPsiSymbolExtractor(project)
        val vf = myFixture.configureByText("Sample.java", src).virtualFile
        val pf = com.ghostdebugger.model.ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "java",
            content = src
        )
        val out = extractor.extract(pf)
        val shout = out.functions.first { it.name == "shout" }
        // Java void via PSI: presentableText returns "void"; we capture it as-is.
        assertEquals("void", shout.returnType)
    }

    fun testCanary_javaRegexFallbackEnrichment() {
        val src = """
            public class Sample {
                public Foo doSomething(int x, String y) { return null; }
            }
        """.trimIndent()
        val extractor = JavaPsiSymbolExtractor(project)
        val pf = com.ghostdebugger.model.ParsedFile(
            virtualFile = myFixture.configureByText("Sample.java", src).virtualFile,
            path = "Sample.java",
            extension = "java",
            content = src
        )
        val out = extractor.extractWithRegex(pf)
        val fn = out.functions.first { it.name == "doSomething" }
        assertEquals(
            "Regex fallback should capture return type",
            "Foo", fn.returnType
        )
        assertEquals(
            "Regex fallback should split params on comma and take type token",
            listOf("int", "String"), fn.paramTypes
        )
    }
}
