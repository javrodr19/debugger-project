package com.ghostdebugger.analysis

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue

fun interface AiPassRunner {
    suspend fun run(context: AnalysisContext, apiKey: String): List<Issue>
}
