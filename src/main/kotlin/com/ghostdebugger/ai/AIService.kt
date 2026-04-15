package com.ghostdebugger.ai

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.ProjectGraph

interface AIService {
    suspend fun detectIssues(filePath: String, fileContent: String): List<Issue>
    suspend fun explainIssue(issue: Issue, codeSnippet: String): String
    suspend fun suggestFix(issue: Issue, codeSnippet: String): CodeFix
    suspend fun explainSystem(graph: ProjectGraph): String
}
