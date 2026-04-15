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
          "description": "<A detailed explanation of the problem, max 2 sentences in English>",
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

        Respond in English. Be concise (max 150 words). Use simple language, no jargon.
    """.trimIndent()

    fun suggestFix(issue: Issue, codeSnippet: String, impactContext: String = ""): String = """
        You are a world-class senior developer. Provide a fix for the following bug.

        ## Issue
        Type: ${issue.type}
        Title: ${issue.title}
        File: ${issue.filePath.substringAfterLast("/")}
        Line: ${issue.line}

        ## Buggy Code (snippet)
        ```
        ${codeSnippet.take(800)}
        ```

        Return EXACTLY this format — nothing else:

        EXPLANATION: <1-2 sentences in English describing what you changed and why>

        FIXED_CODE:
        ```
        <ONLY the fixed version of the snippet above — same scope, minimal changes>
        ```

        Rules:
        - Only fix the shown snippet, do not rewrite the whole file
        - Keep the same indentation style
        - Preserve function/variable names unless the name itself is the bug
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

        Provide a concise project overview in English (max 200 words):
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

        Answer this question as a CTO in English. Be direct, specific, and actionable.
        Max 200 words. Focus on technical risks and concrete recommendations.
    """.trimIndent()
    fun jointFix(issue: Issue, brokenFiles: Map<String, String>, healthyContext: Map<String, String>): String = """
        You are a world-class senior developer fixing a bug that might span multiple related files.
        
        ## Primary Issue
        Type: ${issue.type}
        Title: ${issue.title}
        Primary File: ${issue.filePath.substringAfterLast("/")}
        
        ## BROKEN NEIGHBORHOOD (Files that need fixing or review)
        ${brokenFiles.entries.joinToString("\n\n") { (path, content) -> 
            "### File: ${path.substringAfterLast("/")}\n```\n$content\n```" 
        }}
        
        ## HEALTHY CONTEXT (Reference these to ensure type/signature compatibility)
        ${healthyContext.entries.joinToString("\n\n") { (path, content) -> 
            "### File: ${path.substringAfterLast("/")}\n```\n$content\n```" 
        }}
        
        Provide a JOINT FIX plan. 
        Your goal is to fix the issues while ensuring compatibility between all involved files.
        Return ONLY a JSON object with this exact structure:
        {
          "explanation": "<1-2 sentences in English explaining the global fix>",
          "fixes": [
            {
              "filePath": "<full path of the file>",
              "fixedCode": "<the entire new content of that file>"
            }
          ]
        }
        
        Include a fix entry for EVERY file in the BROKEN NEIGHBORHOOD section, even if no changes were needed (in that case, return the original code).
        Return ONLY valid JSON.
    """.trimIndent()
}
