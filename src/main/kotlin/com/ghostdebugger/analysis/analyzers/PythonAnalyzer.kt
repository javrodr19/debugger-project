package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.analysis.Analyzer
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.ParsedFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiManager
import com.jetbrains.python.psi.PyFile

abstract class PythonAnalyzer : Analyzer {

    private val log = logger<PythonAnalyzer>()

    final override fun analyze(context: AnalysisContext): List<Issue> {
        val out = mutableListOf<Issue>()
        for (file in context.parsedFiles) {
            if (file.extension != "py") continue
            try {
                out.addAll(analyzeFileSafely(file, context))
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                log.warn("$name failed for ${file.path}", e)
            }
        }
        return out
    }

    private fun analyzeFileSafely(file: ParsedFile, context: AnalysisContext): List<Issue> {
        val app = ApplicationManager.getApplication() ?: return emptyList()
        val project = context.project ?: return emptyList()
        val pyFile = app.runReadAction<PyFile?> {
            PsiManager.getInstance(project).findFile(file.virtualFile) as? PyFile
        }
        if (pyFile == null) {
            log.info("Python PSI unavailable for ${file.path}; skipping $name")
            return emptyList()
        }
        return app.runReadAction<List<Issue>> {
            analyzePyFile(pyFile, file, context)
        }
    }

    abstract fun analyzePyFile(
        pyFile: PyFile,
        parsedFile: ParsedFile,
        context: AnalysisContext
    ): List<Issue>
}
