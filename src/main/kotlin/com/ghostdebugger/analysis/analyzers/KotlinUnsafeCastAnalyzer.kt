package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.*
import com.ghostdebugger.parser.effectiveType
import com.ghostdebugger.parser.effectiveTypeWithStructuralSmartCast
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtFile
import java.util.UUID

/**
 * Flags `x as Foo` downcasts that aren't provably safe.
 *
 * Decision matrix (conservative-miss bias):
 *   - target type unresolved (KaErrorType) -> don't flag
 *   - receiver type unresolved (KaErrorType) -> don't flag
 *   - receiver type is a subtype of target (upcast / identity) -> don't flag
 *   - otherwise (downcast or unrelated) -> flag
 *
 * Operator filter: only `as` (KtTokens.AS_KEYWORD). Safe casts (`as?`) are
 * intentionally not flagged.
 */
class KotlinUnsafeCastAnalyzer : KotlinAnalyzer() {

    override val name = "KotlinUnsafeCastAnalyzer"
    override val ruleId = "AEG-CAST-KT-001"
    override val defaultSeverity = IssueSeverity.ERROR
    override val description =
        "Flags `as` downcasts that are not provably safe at compile time. Use `as?` with an Elvis fallback instead."

    override fun analyzeKtFile(
        ktFile: KtFile,
        parsedFile: ParsedFile,
        context: AnalysisContext,
        session: KaSession
    ): List<Issue> {
        val document = PsiDocumentManager.getInstance(ktFile.project).getDocument(ktFile)
        fun lineOf(offset: Int): Int = document?.getLineNumber(offset)?.plus(1) ?: 1

        val findings = mutableListOf<Issue>()
        for (cast in PsiTreeUtil.findChildrenOfType(ktFile, KtBinaryExpressionWithTypeRHS::class.java)) {
            if (cast.operationReference.getReferencedNameElementType() != KtTokens.AS_KEYWORD) continue
            val targetTypeRef = cast.right ?: continue

            with(session) {
                val targetType = targetTypeRef.type
                if (targetType is KaErrorType) return@with
                val receiverType = effectiveTypeWithStructuralSmartCast(cast.left) ?: return@with
                if (receiverType is KaErrorType) return@with

                // Upcast or identity: receiver is already a subtype of target -> safe.
                if (receiverType.isSubtypeOf(targetType)) return@with

                // Downcast or unrelated -> flag.
                findings.add(
                    Issue(
                        id = UUID.randomUUID().toString(),
                        type = IssueType.NULL_SAFETY,
                        severity = IssueSeverity.ERROR,
                        title = "Unsafe cast: '${cast.text}'",
                        description = "This `as` downcast will throw `ClassCastException` at runtime when the receiver is not of the target type. Use `as?` with an Elvis fallback (`?: return`, `?: throw …`).",
                        filePath = parsedFile.path,
                        line = lineOf(cast.textOffset),
                        codeSnippet = extractSnippet(parsedFile.content, lineOf(cast.textOffset)),
                        affectedNodes = listOf(parsedFile.path),
                        ruleId = ruleId,
                        sources = listOf(IssueSource.STATIC),
                        providers = listOf(EngineProvider.STATIC),
                        confidence = 0.9
                    )
                )
            }
        }
        return findings
    }

    private fun extractSnippet(content: String, line: Int): String {
        val lines = content.lines()
        val start = maxOf(0, line - 3)
        val end = minOf(lines.size, line + 2)
        return lines.subList(start, end).joinToString("\n")
    }
}
