package com.ghostdebugger.ai.prompts

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.ProjectGraph

object PromptTemplates {

    fun detectIssues(filePath: String, fileContent: String): String = """
        You are an expert software engineer reviewing the following source code file for bugs, memory leaks, missing error handling, circular dependencies, state bugs, and edge cases.
        
        File Path: $filePath
        
        ```
        $fileContent
        ```
        
        If you find any issues, return a JSON array of objects representing the issues.
        Return ONLY valid JSON.
        
        JSON schema for each array item:
        {
          "type": "<a string from the following exact list: NULL_SAFETY, CIRCULAR_DEPENDENCY, ASYNC_FLOW, UNHANDLED_PROMISE, STATE_BEFORE_INIT, HIGH_COMPLEXITY, MISSING_ERROR_HANDLING, DEAD_CODE, RESOURCE_LEAK, MEMORY_LEAK, ARCHITECTURE>",
          "severity": "<either ERROR or WARNING>",
          "title": "<A concise title for the issue in English>",
          "description": "<A detailed explanation of the problem, max 2 sentences in Spanish>",
          "line": <Integer, the 1-indexed line number where the issue exists>
        }
        
        If no issues are found, return an empty array [].
    """.trimIndent()

    fun explainIssue(issue: Issue, codeSnippet: String, projectContext: String = ""): String = """
        You are a senior software developer explaining a bug to a teammate.

        ## Issue
        Type: ${issue.type}
        Severity: ${issue.severity}
        Title: ${issue.title}
        File: ${issue.filePath.substringAfterLast("/")}
        Line: ${issue.line}

        ## Code
        ```
        ${codeSnippet.take(500)}
        ```

        ${if (projectContext.isNotBlank()) "## Project Context\n$projectContext\n" else ""}

        Explain this issue in simple, clear language. Include:
        1. WHAT is happening (describe the bug)
        2. WHY it happens (root cause)
        3. WHEN it manifests (in which scenario it breaks)
        4. IMPACT (what parts of the app are affected)

        Respond in Spanish. Be concise (max 150 words). Use simple language, no jargon.
    """.trimIndent()

    fun suggestFix(issue: Issue, codeSnippet: String, impactContext: String = ""): String = """
        You are a world-class senior developer. Fix the bug in the provided source code.
        
        $impactContext

        ## Issue to Fix
        Type: ${issue.type}
        Title: ${issue.title}
        File: ${issue.filePath.substringAfterLast("/")}
        Line: ${issue.line}

        ## Current Code
        ```
        ${codeSnippet.take(800)}
        ```

        Provide a COMPLETE file replacement. Return ONLY:
        1. A brief explanation (1-2 sentences in Spanish)
        2. The ENTIRE fixed source file content.

        Format:
        EXPLANATION: <1-2 sentences in Spanish>

        FIXED_CODE:
        ```
        <entire fixed source file content here>
        ```

        Do NOT change exported function signatures, component props names, or shared types unless it is the ONLY way to fix the bug.
        Other modules depend on these signatures. If you must change them, explain why in the EXPLANATION.
        
        Return the FULL file content in the FIXED_CODE section.
    """.trimIndent()

    fun explainSystem(graph: ProjectGraph): String = """
        You are a software architect analyzing a project structure.

        ## Project Structure
        - Total files/modules: ${graph.nodes.size}
        - Files with errors: ${graph.nodes.count { it.status.name == "ERROR" }}
        - Files with warnings: ${graph.nodes.count { it.status.name == "WARNING" }}
        - Total dependencies: ${graph.edges.size}

        ## Key Modules
        ${graph.nodes.take(15).joinToString("\n") { "- ${it.name} (${it.type}, ${it.status})" }}

        Provide a concise project overview in Spanish (max 200 words):
        1. What this project seems to do
        2. Main components and their purpose
        3. Key architectural concerns
        4. Most critical areas to address

        Be specific and actionable.
    """.trimIndent()

    fun whatIf(question: String, graph: ProjectGraph): String = """
        You are a CTO analyzing a software project.

        ## Project Status
        - Modules: ${graph.nodes.size}
        - Health: ${graph.metadata.healthScore.toInt()}%
        - Total issues: ${graph.metadata.totalIssues}

        ## Modules with Issues
        ${graph.nodes.filter { it.issues.isNotEmpty() }.take(10)
            .joinToString("\n") { "- ${it.name}: ${it.issues.size} issues (${it.status})" }}

        ## Question from the developer
        "$question"

        Answer this question as a CTO in Spanish. Be direct, specific, and actionable.
        Max 200 words. Focus on technical risks and concrete recommendations.
    """.trimIndent()
}
