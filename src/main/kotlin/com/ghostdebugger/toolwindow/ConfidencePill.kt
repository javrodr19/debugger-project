package com.ghostdebugger.toolwindow

import com.ghostdebugger.model.Confidence
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

class ConfidencePill(confidence: Confidence) : JLabel(confidence.name, SwingConstants.CENTER) {

    private val pillBackground: JBColor
    private val pillForeground = JBColor.WHITE

    init {
        text = when (confidence) {
            Confidence.CONFIRMED -> "CONFIRMED"
            Confidence.LIKELY -> "LIKELY"
            Confidence.UNCONFIRMED -> "UNCONFIRMED"
            Confidence.UNREACHED -> "NEEDS COVERAGE"
            Confidence.DEMOTED -> "DEMOTED"
        }

        pillBackground = when (confidence) {
            Confidence.CONFIRMED -> JBColor(0x1F8B4C, 0x1F8B4C)
            Confidence.LIKELY -> JBColor(0x2E5BBA, 0x2E5BBA)
            Confidence.UNCONFIRMED -> JBColor(0x6B7280, 0x6B7280)
            Confidence.UNREACHED -> JBColor(0xB45309, 0xB45309)
            Confidence.DEMOTED -> JBColor(0xB91C1C, 0xB91C1C)
        }

        foreground = pillForeground
        border = EmptyBorder(2, 6, 2, 6)
        isOpaque = false

        if (confidence == Confidence.DEMOTED) {
            font = font.deriveFont(java.awt.Font.ITALIC)
        }
    }

    override fun paintComponent(g: Graphics) {
        // `as?` (not `as`): g.create() is always a Graphics2D during Swing painting, but a safe cast
        // avoids an unprovable downcast; if it ever weren't, we simply skip the rounded background.
        (g.create() as? Graphics2D)?.let { g2 ->
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = pillBackground
            g2.fillRoundRect(0, 0, width, height, 8, 8)
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
