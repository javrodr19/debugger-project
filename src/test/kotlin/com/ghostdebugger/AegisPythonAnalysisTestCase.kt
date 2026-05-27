package com.ghostdebugger

import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.ParsedFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerServiceInstance
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
abstract class AegisPythonAnalysisTestCase : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        com.intellij.openapi.application.WriteAction.runAndWait<Throwable> {
            val fileTypeManager = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
            val pythonFileType = com.jetbrains.python.PythonFileType.INSTANCE
            fileTypeManager.associateExtension(pythonFileType, "py")

            val pythonLanguage = com.jetbrains.python.PythonLanguage.INSTANCE
            val pythonParserDefinition = com.jetbrains.python.PythonParserDefinition()
            com.intellij.lang.LanguageParserDefinitions.INSTANCE.addExplicitExtension(pythonLanguage, pythonParserDefinition)

            try {
                val area = com.intellij.openapi.extensions.Extensions.getRootArea() as com.intellij.openapi.extensions.impl.ExtensionsAreaImpl
                if (!area.hasExtensionPoint("Pythonid.dialectsTokenSetContributor")) {
                    val epClass = Class.forName("com.jetbrains.python.PythonDialectsTokenSetContributor")
                    area.registerExtensionPoint(
                        "Pythonid.dialectsTokenSetContributor",
                        epClass.name,
                        com.intellij.openapi.extensions.ExtensionPoint.Kind.INTERFACE,
                        true
                    )
                }
                if (!area.hasExtensionPoint("Pythonid.typeProvider")) {
                    val epClass = Class.forName("com.jetbrains.python.psi.impl.PyTypeProvider")
                    area.registerExtensionPoint(
                        "Pythonid.typeProvider",
                        epClass.name,
                        com.intellij.openapi.extensions.ExtensionPoint.Kind.INTERFACE,
                        true
                    )
                }
            } catch (e: Exception) {
                println("ERROR REGISTERING EXTENSION POINT: ${e.message}")
                e.printStackTrace()
            }

            try {
                val dummyModuleService = io.mockk.mockk<com.jetbrains.python.module.PyModuleService>(relaxed = true)
                val dummyRuntimeService = io.mockk.mockk<com.jetbrains.python.PythonRuntimeService>(relaxed = true)
                val realFacade = com.jetbrains.python.PyElementTypesFacadeImpl()

                io.mockk.every { dummyRuntimeService.getLanguageLevelForSdk(any()) } returns com.jetbrains.python.psi.LanguageLevel.getDefault()

                val application = com.intellij.openapi.application.ApplicationManager.getApplication()
                application.registerServiceInstance(com.jetbrains.python.module.PyModuleService::class.java as Class<Any>, dummyModuleService)
                application.registerServiceInstance(com.jetbrains.python.PythonRuntimeService::class.java as Class<Any>, dummyRuntimeService)
                application.registerServiceInstance(com.jetbrains.python.PyElementTypesFacade::class.java as Class<Any>, realFacade)

                val cacheClass = Class.forName("com.jetbrains.python.psi.types.TypeEvalContextCacheImpl")
                val constructor = cacheClass.getDeclaredConstructor(com.intellij.openapi.project.Project::class.java)
                constructor.isAccessible = true
                val dummyContextCache = constructor.newInstance(project) as com.jetbrains.python.psi.types.TypeEvalContextCache
                project.registerServiceInstance(com.jetbrains.python.psi.types.TypeEvalContextCache::class.java as Class<Any>, dummyContextCache)
            } catch (e: Exception) {
                println("ERROR IN SETUP REGISTER SERVICES: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    override fun tearDown() {
        com.intellij.openapi.application.WriteAction.runAndWait<Throwable> {
            val pythonLanguage = com.jetbrains.python.PythonLanguage.INSTANCE
            val pythonParserDefinition = com.intellij.lang.LanguageParserDefinitions.INSTANCE.forLanguage(pythonLanguage)
            if (pythonParserDefinition != null) {
                com.intellij.lang.LanguageParserDefinitions.INSTANCE.removeExplicitExtension(pythonLanguage, pythonParserDefinition)
            }
        }
        super.tearDown()
    }

    protected fun analyze(
        source: String,
        analyzerFactory: () -> com.ghostdebugger.analysis.Analyzer
    ): List<Issue> {
        val vf = myFixture.configureByText("Sample.py", source).virtualFile
        var ftName = ""
        var psiClassName = ""
        com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction {
            val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("py")
            ftName = fileType.name + " (" + fileType.javaClass.name + ")"
            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf)
            psiClassName = psiFile?.javaClass?.name ?: "null"
        }
        val pluginsPath = System.getProperty("idea.plugins.path")
        println("DEBUG: idea.plugins.path = $pluginsPath")
        if (pluginsPath != null) {
            val pDir = java.io.File(pluginsPath)
            if (pDir.exists()) {
                println("DEBUG: sandbox plugins directory exists; files: ${pDir.list()?.toList()}")
            } else {
                println("DEBUG: sandbox plugins directory does NOT exist")
            }
        }
        println("DEBUG: file extension = py, fileType = $ftName")
        println("DEBUG: psiFile class = $psiClassName")
        val pf = ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "py",
            content = source
        )
        val ctx = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(pf)
        )
        return analyzerFactory().analyze(ctx)
    }

    protected fun expectFinding(
        source: String,
        ruleId: String,
        line: Int,
        analyzerFactory: () -> com.ghostdebugger.analysis.Analyzer
    ) {
        val issues = analyze(source, analyzerFactory)
        val matching = issues.filter { it.ruleId == ruleId && it.line == line }
        assertTrue(
            "Expected at least one finding with ruleId=$ruleId on line $line; got: ${issues.map { it.ruleId to it.line }}",
            matching.isNotEmpty()
        )
    }

    protected fun expectNoFinding(
        source: String,
        ruleId: String,
        analyzerFactory: () -> com.ghostdebugger.analysis.Analyzer
    ) {
        val issues = analyze(source, analyzerFactory).filter { it.ruleId == ruleId }
        assertTrue(
            "Expected no findings with ruleId=$ruleId; got: $issues",
            issues.isEmpty()
        )
    }
}
