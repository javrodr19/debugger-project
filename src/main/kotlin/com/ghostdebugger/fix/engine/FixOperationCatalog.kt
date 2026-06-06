package com.ghostdebugger.fix.engine

/**
 * Single source of truth for the AI planner's operation catalog. Each entry is a one-line JSON schema
 * (plus a short `//` note) for one [FixOperation]; `PromptTemplates.planFix` renders these, so adding an
 * op here is all that exposes it to the AI. `FixOperationCatalogTest` enforces that every sealed
 * [FixOperation] subclass has exactly one entry.
 */
object FixOperationCatalog {
    val entries: List<String> = listOf(
        """{"type":"replaceRange","startOffset":<int>,"endOffset":<int>,"text":"<str>"} // replace chars [startOffset,endOffset); 0-based offsets""",
        """{"type":"insertImport","fqName":"<fully.qualified.Name>"} // add an import if absent""",
        """{"type":"convertToSafeCast","asOffset":<int>} // Kotlin: `x as T` -> `x as? T ?: ...`; asOffset = 0-based offset of the `as` keyword""",
        """{"type":"wrapInSafeCall","line":<int>,"receiver":"<id>"} // receiver.member -> receiver?.member""",
        """{"type":"addElvisDefault","line":<int>,"expr":"<expr>","default":"<value>"} // expr -> expr ?: default (Kotlin) / expr ?? default (JS/TS)""",
        """{"type":"surroundWithNullCheck","line":<int>,"variable":"<id>"} // wrap the line's statement in if (variable != null) { ... }""",
        """{"type":"addAwait","line":<int>,"call":"<call(>"} // JS/TS only: prefix the call with await""",
        """{"type":"addPromiseCatch","line":<int>,"handler":"<expr>"} // JS/TS only: append .catch(handler) to a ...); chain (handler optional)""",
        """{"type":"addExplicitConversion","line":<int>,"expr":"<expr>","conversion":"<.toX()|Wrapper>"} // expr.toLong() (suffix) or String(expr) (wrapper)""",
        """{"type":"surroundWithTryCatch","startLine":<int>,"endLine":<int>,"catchBody":"<stmt>"} // wrap lines in try/catch (catchBody optional; Kotlin uses a typed catch)""",
        """{"type":"removeRange","startLine":<int>,"endLine":<int>} // delete whole lines startLine..endLine""",
        """{"type":"replaceExpression","line":<int>,"find":"<text>","replacement":"<text>"} // replace the first `find` on the line""",
        """{"type":"insertStatementBefore","line":<int>,"statement":"<stmt>"} // insert a statement line before the target line""",
        """{"type":"insertStatementAfter","line":<int>,"statement":"<stmt>"} // insert a statement line after the target line""",
        """{"type":"collapseBooleanReturn","line":<int>} // if (C) return true else return false -> return C (negated -> return !C); simplification""",
        """{"type":"replaceLines","startLine":<int>,"endLine":<int>,"text":"<verbatim lines>"} // replace whole lines startLine..endLine (1-based) with text; e.g. swap an extracted block for its call""",
        """{"type":"insertLinesAfter","afterLine":<int>,"text":"<verbatim lines>"} // insert text as a blank-line-separated block after afterLine; e.g. define a newly extracted function""",
    )

    private val TYPE_RE = Regex(""""type":"([a-zA-Z]+)"""")

    /** The `type` discriminator of every entry; must match the registered [FixOperation] sealed subclasses. */
    fun serialNames(): Set<String> = entries.mapNotNull { TYPE_RE.find(it)?.groupValues?.get(1) }.toSet()
}
