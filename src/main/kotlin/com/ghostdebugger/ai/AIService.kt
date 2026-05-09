package com.ghostdebugger.ai

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.FunctionSymbol
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.ProjectGraph

interface AIService {
    /**
     * Detect issues in [fileContent].
     *
     * [functions] is an optional list of pre-extracted function symbols with rendered
     * `returnType` and `paramTypes`. When non-empty, the prompt builder distils them
     * into a "Function Signatures" block to ground the LLM. Default empty for callers
     * that don't have parsed-file info (e.g., test mocks).
     */
    suspend fun detectIssues(
        filePath: String,
        fileContent: String,
        functions: List<FunctionSymbol> = emptyList()
    ): List<Issue>

    suspend fun explainIssue(issue: Issue, codeSnippet: String): String
    suspend fun suggestFix(issue: Issue, codeSnippet: String): CodeFix
    suspend fun explainSystem(graph: ProjectGraph): String
}
