package com.ghostdebugger.fix.engine

import com.ghostdebugger.graph.GraphBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Per-function complexity for Kotlin content: each [KtNamedFunction]'s own complexity, computed as
 * `GraphBuilder.estimateComplexity(body, 1) = 1 + decisionPointsInBody` (the file-level metric at
 * function granularity — single-sourced in [GraphBuilder]). Keyed by `"name/arity"` so a function
 * matches itself across an edit (extraction does not change the source's signature).
 *
 * Parses [content] into an in-memory [KtFile] inside a read action — structural PSI only, no Analysis
 * API, so it is safe off-EDT and never resolves types. Consumed by `ExtractMethodVerifier` (C2) to
 * compare original vs candidate.
 */
object PerFunctionComplexity {
    /** Per-function complexities by `name/arity`; [collision] is true if two functions shared a key. */
    data class Result(val byKey: Map<String, Int>, val collision: Boolean)

    fun measure(project: Project, content: String): Result {
        return ApplicationManager.getApplication().runReadAction<Result> {
            try {
                val ktFile = PsiFileFactory.getInstance(project)
                    .createFileFromText("temp.kt", KotlinLanguage.INSTANCE, content) as? KtFile
                    ?: return@runReadAction Result(emptyMap(), collision = false)
                val graphBuilder = GraphBuilder()
                val map = HashMap<String, Int>()
                var collision = false
                for (fn in PsiTreeUtil.findChildrenOfType(ktFile, KtNamedFunction::class.java)) {
                    val body = fn.bodyExpression ?: continue
                    val key = "${fn.name ?: "?"}/${fn.valueParameters.size}"
                    if (map.containsKey(key)) collision = true
                    map[key] = graphBuilder.estimateComplexity(body.text, 1)
                }
                Result(map, collision)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                Result(emptyMap(), collision = false)
            }
        }
    }
}
