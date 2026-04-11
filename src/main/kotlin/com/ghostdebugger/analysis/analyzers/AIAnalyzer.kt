package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.ai.OpenAIService
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class AIAnalyzer(private val apiKey: String) {
    private val log = logger<AIAnalyzer>()
    
    // Limit to 3 concurrent requests to avoid aggressive rate limits
    private val concurrencySemaphore = Semaphore(3)
    
    suspend fun analyze(context: AnalysisContext): List<Issue> {
        val openAIService = OpenAIService(apiKey)
        val aiIssues = mutableListOf<Issue>()
        
        // Only analyze source code files, ignore large minified files or assets
        val analyzableFiles = context.parsedFiles.filter { 
            it.extension in setOf("ts", "tsx", "js", "jsx", "kt", "java") && it.content.lines().size < 2000
        }
        
        log.info("Starting AI Analysis on ${analyzableFiles.size} files with concurrency limit 3...")
        
        coroutineScope {
            val deferredResults = analyzableFiles.map { file ->
                async {
                    concurrencySemaphore.withPermit {
                        try {
                            log.info("Sending ${file.path} to OpenAI for deep review...")
                            openAIService.detectIssues(file.path, file.content)
                        } catch (e: Exception) {
                            log.warn("AI Analysis failed for file ${file.path}", e)
                            emptyList<Issue>()
                        }
                    }
                }
            }
            
            deferredResults.awaitAll().forEach { aiIssues.addAll(it) }
        }
        
        log.info("AI Analysis completed. Found ${aiIssues.size} deep issues.")
        return aiIssues
    }
}
