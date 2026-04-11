package com.ghostdebugger.service

import com.ghostdebugger.ai.ApiKeyManager
import com.ghostdebugger.ai.OpenAIService
import com.ghostdebugger.analysis.AnalysisEngine
import com.ghostdebugger.graph.GraphBuilder
import com.ghostdebugger.model.*
import com.ghostdebugger.parser.DependencyResolver
import com.ghostdebugger.parser.FileScanner
import com.ghostdebugger.parser.SymbolExtractor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AnalysisPipeline(private val project: Project) {
    private val log = logger<AnalysisPipeline>()

    suspend fun run(): AnalysisResult? {
        return try {
            log.info("Starting project analysis pipeline...")

            val virtualFiles = ApplicationManager.getApplication().runReadAction<List<com.intellij.openapi.vfs.VirtualFile>> {
                FileScanner(project).scanFiles()
            }

            val rawFiles = ApplicationManager.getApplication().runReadAction<List<ParsedFile>> {
                FileScanner(project).parsedFiles(virtualFiles)
            }

            val extractor = SymbolExtractor()
            val parsedFiles = rawFiles.map { extractor.extract(it) }

            val resolver = DependencyResolver(project.basePath ?: "")
            val dependencies = resolver.resolve(parsedFiles)

            val graphBuilder = GraphBuilder()
            val inMemoryGraph = graphBuilder.build(parsedFiles, dependencies)

            val analysisContext = AnalysisContext(
                graph = inMemoryGraph,
                project = project,
                parsedFiles = parsedFiles
            )
            val result = AnalysisEngine().analyze(analysisContext)
            
            graphBuilder.applyIssues(inMemoryGraph, result.issues)
            
            val projectGraph = inMemoryGraph.toProjectGraph(project.name)

            AnalysisResult(projectGraph, result)
        } catch (e: Exception) {
            log.error("Pipeline failed", e)
            null
        }
    }

    data class AnalysisResult(
        val graph: ProjectGraph,
        val engineResult: com.ghostdebugger.model.AnalysisResult
    )
}
