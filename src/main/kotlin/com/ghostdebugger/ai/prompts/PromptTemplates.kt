package com.ghostdebugger.ai.prompts

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.ProjectGraph

object PromptTemplates {

    fun detectIssues(
        filePath: String,
        fileContent: String,
        functions: List<com.ghostdebugger.model.FunctionSymbol> = emptyList()
    ): String {
        val signaturesBlock = if (functions.isEmpty()) "" else buildString {
            appendLine()
            appendLine("Function Signatures:")
            functions.forEach { fn ->
                val sig = buildString {
                    append(fn.name)
                    append('(')
                    append(fn.paramTypes.joinToString(", "))
                    append(')')
                    fn.returnType?.let { append(": ").append(it) }
                }
                appendLine("- $sig  (line ${fn.line})")
            }
        }
        return """
        You are an expert software engineer reviewing the following source code file for bugs, memory leaks, missing error handling, circular dependencies, state bugs, and edge cases.

        File Path: $filePath
        $signaturesBlock
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

        Worked examples (follow this exact output shape):

        ${PromptExamples.DETECT_ISSUES_EXAMPLES}
        """.trimIndent()
    }

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

        Worked examples (follow this exact output shape):

        ${PromptExamples.JOINT_FIX_EXAMPLES}
    """.trimIndent()

    /**
     * Prompt asking the model to act as a planner: emit a [com.ghostdebugger.fix.engine.FixPlan]
     * composed only of deterministic catalog operations as JSON. [feedback] (when non-null) is the
     * verify gate's reason for rejecting the previous attempt, so the model can revise.
     * Built with StringBuilder to avoid the trimIndent() multi-line-interpolation gotcha.
     */
    fun planFix(issue: Issue, fileContent: String, feedback: String? = null): String {
        val sb = StringBuilder()
        sb.append("You repair code by composing deterministic edit operations into a plan. ")
        sb.append("You do NOT write free-form fixed code; you only choose operations from the catalog below.\n\n")
        sb.append("Issue to fix:\n")
        sb.append("- ruleId: ").append(issue.ruleId ?: issue.type.name).append('\n')
        sb.append("- title: ").append(issue.title).append('\n')
        sb.append("- line: ").append(issue.line).append('\n')
        sb.append("- description: ").append(issue.description).append('\n')
        if (feedback != null) {
            sb.append("\nYour previous plan was REJECTED by the verifier: ").append(feedback)
            sb.append("\nProduce a corrected plan that resolves the issue without introducing new ones.\n")
        }
        sb.append("\nReturn ONLY a JSON object of this exact shape (no prose):\n")
        sb.append("{\"issueId\":\"").append(issue.id).append("\",\"operations\":[ <operation>, ... ]}\n\n")
        sb.append("Each <operation> is exactly one of:\n")
        for (entry in com.ghostdebugger.fix.engine.FixOperationCatalog.entries) {
            sb.append("- ").append(entry).append('\n')
        }
        sb.append("\nUnless an op's note says otherwise, `line`/`startLine`/`endLine` are 1-based and any char offsets are 0-based, into the file content below.\n")
        sb.append("\n--- FILE CONTENT START ---\n")
        sb.append(fileContent)
        sb.append("\n--- FILE CONTENT END ---\n")
        return sb.toString()
    }
}
