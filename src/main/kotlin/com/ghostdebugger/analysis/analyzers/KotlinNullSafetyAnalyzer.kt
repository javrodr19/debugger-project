package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.*
import com.ghostdebugger.parser.effectiveType
import com.ghostdebugger.parser.effectiveTypeWithStructuralSmartCast
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import java.util.UUID

/**
 * Type-aware null-safety analyzer for Kotlin (Analysis API).
 *
 * Flags an access whose receiver has a *nullable* type at the use site. Because the
 * Analysis API exposes smart-cast and inferred types, the same check covers:
 *   - declared nullables (`val x: String? = ...`)
 *   - type-inferred nullables (`val x = repo.find()` where `find(): T?`)
 *   - generic nullable bounds (`Box<T>(val value: T?)` accessed via `b.value.length`)
 *   - smart-cast windows that narrow the type to non-null (no flag inside the window)
 *   - reassignment flow (`var x: String? = null; x = "hi"; x.length` is non-null)
 *
 * Conservative-miss bias: when the type is `KaErrorType` (unresolved symbol or broken
 * sibling file), don't flag — F3 in the V1.3 design spec.
 *
 * Why we iterate `KtDotQualifiedExpression` only: safe calls (`x?.foo`) are
 * `KtSafeQualifiedExpression`, a sibling AST node, and `!!`-unwrapped receivers
 * (`x!!.foo`) carry the post-unwrap non-null type. Both cases are correctly
 * skipped by virtue of the AST shape — no syntactic guard logic needed.
 */
class KotlinNullSafetyAnalyzer : KotlinAnalyzer() {

    override val name = "KotlinNullSafetyAnalyzer"
    override val ruleId = "AEG-NULL-KT-001"
    override val defaultSeverity = IssueSeverity.ERROR
    override val description =
        "Flags access on Kotlin nullable expressions without a safe call, null guard, !!, or Elvis fallback."

    override fun analyzeKtFile(
        ktFile: KtFile,
        parsedFile: ParsedFile,
        context: AnalysisContext,
        session: KaSession
    ): List<Issue> {
        val document = PsiDocumentManager.getInstance(ktFile.project).getDocument(ktFile)
        fun lineOf(offset: Int): Int = document?.getLineNumber(offset)?.plus(1) ?: 1

        val findings = mutableListOf<Issue>()
        for (access in PsiTreeUtil.findChildrenOfType(ktFile, KtDotQualifiedExpression::class.java)) {
            val receiver = access.receiverExpression

            with(session) {
                val type = effectiveTypeWithStructuralSmartCast(receiver) ?: return@with
                if (type is KaErrorType) return@with        // F3 — type unknown, don't flag
                if (!type.isMarkedNullable) return@with     // smart-cast or non-nullable
                findings.add(buildIssue(parsedFile, receiver, lineOf(access.textOffset)))
            }
        }
        return findings
    }

    private fun buildIssue(parsedFile: ParsedFile, receiver: KtExpression, line: Int): Issue {
        val name = (receiver as? KtNameReferenceExpression)?.getReferencedName() ?: receiver.text
        return Issue(
            id = UUID.randomUUID().toString(),
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.ERROR,
            title = "Nullable '$name' accessed without a null check",
            description = "Expression '$name' has a nullable type at this access. Use ?., a null guard, !!, or an Elvis fallback.",
            filePath = parsedFile.path,
            line = line,
            codeSnippet = extractSnippet(parsedFile.content, line),
            affectedNodes = listOf(parsedFile.path),
            ruleId = ruleId,
            sources = listOf(IssueSource.STATIC),
            providers = listOf(EngineProvider.STATIC),
            confidence = 0.95
        )
    }

    private fun extractSnippet(content: String, line: Int): String {
        val lines = content.lines()
        val start = maxOf(0, line - 3)
        val end = minOf(lines.size, line + 2)
        return lines.subList(start, end).joinToString("\n")
    }
}
