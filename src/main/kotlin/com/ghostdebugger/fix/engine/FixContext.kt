package com.ghostdebugger.fix.engine

import com.intellij.psi.PsiFile

/**
 * Inputs an operation may need to compute its edit: the current file [content] (always available)
 * and the file's PSI, resolved lazily and memoized (Kotlin/Java ops need the AST + types; content-only
 * ops like ReplaceRange never trigger resolution). Construct PSI access inside a read action.
 */
class FixContext(val content: String, private val psiProvider: () -> PsiFile?) {
    val psiFile: PsiFile? by lazy(psiProvider)
}
