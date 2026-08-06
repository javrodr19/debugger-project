package com.ghostdebugger.fix.engine

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class FixPreviewDialog(
    project: Project,
    private val diffs: List<FileFixDiff>,
    private val isBatch: Boolean = false
) : DialogWrapper(project, true) {

    var wasApplied: Boolean = false
        private set

    init {
        title = if (isBatch) "Aegis Debug — Batch Fix Preview (${diffs.size} files)" else "Aegis Debug — Fix Preview"
        setOKButtonText(if (isBatch) "Apply All (${diffs.size})" else "Apply Fix")
        setCancelButtonText("Skip")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 10))
        mainPanel.preferredSize = JBUI.size(720, 480)

        val headerText = if (isBatch) {
            "Review the verified changes below before applying to ${diffs.size} file(s):"
        } else {
            val first = diffs.firstOrNull()
            "Fix: ${first?.description ?: "Proposed code fix"} (${first?.filePath ?: ""})"
        }
        val headerLabel = JBLabel(headerText)
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 13f)
        mainPanel.add(headerLabel, BorderLayout.NORTH)

        val textPane = JTextPane()
        textPane.isEditable = false
        textPane.font = Font("Monospaced", Font.PLAIN, 12)

        val doc = textPane.styledDocument

        val attrNormal = SimpleAttributeSet()
        StyleConstants.setForeground(attrNormal, Color.GRAY)

        val attrAdded = SimpleAttributeSet()
        StyleConstants.setForeground(attrAdded, Color(46, 160, 67)) // Green
        StyleConstants.setBackground(attrAdded, Color(46, 160, 67, 30))

        val attrDeleted = SimpleAttributeSet()
        StyleConstants.setForeground(attrDeleted, Color(248, 81, 73)) // Red
        StyleConstants.setBackground(attrDeleted, Color(248, 81, 73, 30))

        val attrHunk = SimpleAttributeSet()
        StyleConstants.setForeground(attrHunk, Color(88, 166, 255)) // Blue
        StyleConstants.setBold(attrHunk, true)

        for (diff in diffs) {
            doc.insertString(doc.length, "--- ${diff.filePath}\n+++ ${diff.filePath}\n", attrHunk)
            for (hunk in diff.hunks) {
                doc.insertString(doc.length, "${hunk.header}\n", attrHunk)
                for (line in hunk.lines) {
                    when (line.type) {
                        DiffLineType.ADDED -> {
                            val lineStr = "+ ${line.newLineNumber ?: ""}\t${line.text}\n"
                            doc.insertString(doc.length, lineStr, attrAdded)
                        }
                        DiffLineType.DELETED -> {
                            val lineStr = "- ${line.oldLineNumber ?: ""}\t${line.text}\n"
                            doc.insertString(doc.length, lineStr, attrDeleted)
                        }
                        DiffLineType.UNCHANGED -> {
                            val lineStr = "  ${line.oldLineNumber ?: ""}\t${line.text}\n"
                            doc.insertString(doc.length, lineStr, attrNormal)
                        }
                    }
                }
            }
            doc.insertString(doc.length, "\n", attrNormal)
        }

        val scrollPane = JBScrollPane(textPane)
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        return mainPanel
    }

    override fun doOKAction() {
        wasApplied = true
        super.doOKAction()
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction, cancelAction)
    }
}
