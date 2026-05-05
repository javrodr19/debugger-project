package com.ghostdebugger.bridge

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.types.Variance

/**
 * Renders a [KaType] as a short, user-facing string.
 *
 * Defined as a top-level extension on [KaSession] (not a member of an object) so a
 * single `import com.ghostdebugger.bridge.renderShort` lets it be called directly
 * inside any `withKtAnalysis { ... }` block.
 *
 * Default `KaType.render()` produces FQN form (`kotlin.String?`); we want the
 * source-style short form (`String?`) for NeuroMap tooltips, AI prompts, and
 * exported symbol info.
 */
@OptIn(KaExperimentalApi::class)
fun KaSession.renderShort(type: KaType): String =
    type.render(
        renderer = org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource.WITH_SHORT_NAMES,
        position = Variance.INVARIANT
    )
