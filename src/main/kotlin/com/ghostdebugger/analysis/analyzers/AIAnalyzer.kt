package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.ai.AIService
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class AIAnalyzer(
    private val service: AIService,
    private val progress: ProgressIndicator? = null,
    private val concurrency: Int = DEFAULT_CONCURRENCY_CLOUD,
    private val labelPrefix: String = "AI: "
) {
    private val log = logger<AIAnalyzer>()
    private val semaphore = Semaphore(concurrency)

    suspend fun analyze(context: AnalysisContext): List<Issue> {
        val analyzableFiles = context.parsedFiles.filter {
            it.extension in setOf("ts", "tsx", "js", "jsx", "kt", "java") &&
                it.lines.size < 2000
        }
        val results = mutableListOf<Issue>()
        log.info("Starting AI Analysis on ${analyzableFiles.size} files with concurrency limit $concurrency...")

        coroutineScope {
            val deferred = analyzableFiles.map { file ->
                async {
                    semaphore.withPermit {
                        progress?.checkCanceled()
                        progress?.text2 = "$labelPrefix${file.path.substringAfterLast('/')}"
                        try {
                            log.info("Sending ${file.path} to AI for deep review...")
                            service.detectIssues(file.path, file.content)
                        } catch (e: Exception) {
                            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                            log.warn("AI pass failed for ${file.path}", e)
                            emptyList()
                        }
                    }
                }
            }
            deferred.awaitAll().forEach { results.addAll(it) }
        }

        log.info("AI Analysis completed. Found ${results.size} deep issues.")
        return results
    }

    companion object {
        const val DEFAULT_CONCURRENCY_CLOUD = 3
        const val DEFAULT_CONCURRENCY_LOCAL = 4
    }
}
