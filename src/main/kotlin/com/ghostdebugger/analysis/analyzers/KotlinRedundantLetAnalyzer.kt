package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.*
import com.ghostdebugger.parser.effectiveType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import java.util.UUID

/**
 * Flags `x?.let { ... }` calls where the receiver `x` already has a non-nullable type.
 *
 * Conservative gates:
 *   - receiver type unresolved -> don't flag (F3)
 *   - receiver type is genuinely nullable -> don't flag (the let is doing real work)
 *   - lambda body has multiple statements -> don't flag (autofixable form is single-statement)
 *
 * Severity is WARNING — stylistic, not a correctness bug.
 */
class KotlinRedundantLetAnalyzer : KotlinAnalyzer() {

    override val name = "KotlinRedundantLetAnalyzer"
    override val ruleId = "AEG-REDUNDANT-LET-KT-001"
    override val defaultSeverity = IssueSeverity.WARNING
    override val description =
        "Flags `x?.let { ... }` where `x` is already non-nullable, making the safe-call + let block redundant."

    override fun analyzeKtFile(
        ktFile: KtFile,
        parsedFile: ParsedFile,
        context: AnalysisContext,
        session: KaSession
    ): List<Issue> {
        val document = PsiDocumentManager.getInstance(ktFile.project).getDocument(ktFile)
        fun lineOf(offset: Int): Int = document?.getLineNumber(offset)?.plus(1) ?: 1

        val findings = mutableListOf<Issue>()
        for (safeCall in PsiTreeUtil.findChildrenOfType(ktFile, KtSafeQualifiedExpression::class.java)) {
            val call = safeCall.selectorExpression as? KtCallExpression ?: continue
            val callee = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            if (callee != "let") continue

            val lambda = call.lambdaArguments.singleOrNull()?.getLambdaExpression() ?: continue
            if (!isSingleStatementLambda(lambda)) continue

            with(session) {
                val recvType = effectiveType(safeCall.receiverExpression) ?: return@with
                if (recvType is KaErrorType) return@with
                if (recvType.isMarkedNullable) return@with

                findings.add(
                    Issue(
                        id = UUID.randomUUID().toString(),
                        type = IssueType.NULL_SAFETY,
                        severity = IssueSeverity.WARNING,
                        title = "Redundant `?.let` on non-nullable receiver",
                        description = "The receiver of `?.let` is already non-nullable here. The safe call and let block can be replaced with a direct call.",
                        filePath = parsedFile.path,
                        line = lineOf(safeCall.textOffset),
                        codeSnippet = extractSnippet(parsedFile.content, lineOf(safeCall.textOffset)),
                        affectedNodes = listOf(parsedFile.path),
                        ruleId = ruleId,
                        sources = listOf(IssueSource.STATIC),
                        providers = listOf(EngineProvider.STATIC),
                        confidence = 0.85
                    )
                )
            }
        }
        return findings
    }

    private fun isSingleStatementLambda(lambda: KtLambdaExpression): Boolean {
        val body = lambda.bodyExpression as? KtBlockExpression ?: return true
        return body.statements.size == 1
    }

    private fun extractSnippet(content: String, line: Int): String {
        val lines = content.lines()
        val start = maxOf(0, line - 3)
        val end = minOf(lines.size, line + 2)
        return lines.subList(start, end).joinToString("\n")
    }
}
