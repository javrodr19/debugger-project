package com.ghostdebugger.settings

import com.ghostdebugger.ai.ApiKeyManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import javax.swing.*
import javax.swing.border.EmptyBorder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout

class GhostDebuggerConfigurable : Configurable {

    private var panel: JPanel? = null
    private var apiKeyField: JPasswordField? = null
    private var modelCombo: JComboBox<String>? = null
    private var maxFilesSpinner: JSpinner? = null

    override fun getDisplayName(): String = "GhostDebugger"

    override fun createComponent(): JComponent {
        val mainPanel = JPanel(BorderLayout(10, 10))
        mainPanel.border = EmptyBorder(10, 10, 10, 10)

        val formPanel = JPanel()
        formPanel.layout = BoxLayout(formPanel, BoxLayout.Y_AXIS)

        // API Key section
        val apiKeyLabel = JLabel("OpenAI API Key:")
        apiKeyField = JPasswordField(ApiKeyManager.getApiKey() ?: "", 40)
        apiKeyField!!.preferredSize = Dimension(400, 28)

        val apiKeyPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        apiKeyPanel.add(apiKeyLabel)
        apiKeyPanel.add(apiKeyField)

        val testButton = JButton("Test Connection")
        testButton.addActionListener {
            val key = String(apiKeyField!!.password)
            if (key.isBlank()) {
                Messages.showWarningDialog("Please enter an API key first.", "GhostDebugger")
            } else {
                Messages.showInfoMessage(
                    "API key saved. Connection will be tested on first analysis.",
                    "GhostDebugger"
                )
            }
        }
        apiKeyPanel.add(testButton)

        // Model selection
        val modelLabel = JLabel("Model:")
        modelCombo = JComboBox(arrayOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo"))
        modelCombo!!.selectedItem = GhostDebuggerSettings.getInstance().openAiModel

        val modelPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        modelPanel.add(modelLabel)
        modelPanel.add(modelCombo)

        // Max files
        val maxFilesLabel = JLabel("Max files to analyze:")
        maxFilesSpinner = JSpinner(SpinnerNumberModel(
            GhostDebuggerSettings.getInstance().maxFilesToAnalyze, 10, 2000, 50
        ))
        maxFilesSpinner!!.preferredSize = Dimension(80, 28)

        val maxFilesPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        maxFilesPanel.add(maxFilesLabel)
        maxFilesPanel.add(maxFilesSpinner)

        formPanel.add(Box.createVerticalStrut(10))
        formPanel.add(JLabel("<html><b>👻 GhostDebugger Configuration</b></html>"))
        formPanel.add(Box.createVerticalStrut(15))
        formPanel.add(JLabel("<html>Configure your OpenAI API key to enable AI-powered explanations and fixes.</html>"))
        formPanel.add(Box.createVerticalStrut(10))
        formPanel.add(apiKeyPanel)
        formPanel.add(modelPanel)
        formPanel.add(maxFilesPanel)

        mainPanel.add(formPanel, BorderLayout.NORTH)
        panel = mainPanel
        return mainPanel
    }

    override fun isModified(): Boolean {
        val currentKey = ApiKeyManager.getApiKey() ?: ""
        val enteredKey = String(apiKeyField?.password ?: charArrayOf())
        return currentKey != enteredKey ||
                GhostDebuggerSettings.getInstance().openAiModel != modelCombo?.selectedItem ||
                GhostDebuggerSettings.getInstance().maxFilesToAnalyze != maxFilesSpinner?.value
    }

    override fun apply() {
        val key = String(apiKeyField?.password ?: charArrayOf())
        if (key.isNotBlank()) {
            ApiKeyManager.setApiKey(key)
        }
        modelCombo?.selectedItem?.let {
            GhostDebuggerSettings.getInstance().openAiModel = it as String
        }
        maxFilesSpinner?.value?.let {
            GhostDebuggerSettings.getInstance().maxFilesToAnalyze = it as Int
        }
    }

    override fun reset() {
        apiKeyField?.text = ApiKeyManager.getApiKey() ?: ""
        modelCombo?.selectedItem = GhostDebuggerSettings.getInstance().openAiModel
        maxFilesSpinner?.value = GhostDebuggerSettings.getInstance().maxFilesToAnalyze
    }
}
