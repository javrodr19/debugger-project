package com.ghostdebugger.analysis

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue

interface Analyzer {
    val name: String
    fun analyze(context: AnalysisContext): List<Issue>
}
