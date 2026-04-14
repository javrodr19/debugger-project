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
    private var providerCombo: JComboBox<String>? = null
    private var ollamaEndpointField: JTextField? = null
    private var ollamaModelField: JTextField? = null
    private var allowCloudUploadBox: JCheckBox? = null

    override fun getDisplayName(): String = "Aegis Debug"

    override fun createComponent(): JComponent {
        val settings = GhostDebuggerSettings.getInstance().snapshot()
        val mainPanel = JPanel(BorderLayout(10, 10))
        mainPanel.border = EmptyBorder(10, 10, 10, 10)

        val formPanel = JPanel()
        formPanel.layout = BoxLayout(formPanel, BoxLayout.Y_AXIS)

        // Provider dropdown
        val providerCombo = JComboBox(AIProvider.values().map { it.name }.toTypedArray()).apply {
            selectedItem = settings.aiProvider.name
        }
        this.providerCombo = providerCombo
        val providerPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("AI Provider:"))
            add(providerCombo)
        }

        // API Key section
        val field = JPasswordField(ApiKeyManager.getApiKey() ?: "", 40).apply {
            preferredSize = Dimension(400, 28)
        }
        apiKeyField = field
        val apiKeyPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("OpenAI API Key:"))
            add(field)
            val testButton = JButton("Test Connection").apply {
                addActionListener {
                    val key = String(field.password)
                    if (key.isBlank()) {
                        Messages.showWarningDialog("Please enter an API key first.", "Aegis Debug")
                    } else {
                        Messages.showInfoMessage(
                            "API key saved. Connection will be tested on first analysis.",
                            "Aegis Debug"
                        )
                    }
                }
            }
            add(testButton)
        }

        // Model
        val combo = JComboBox(arrayOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo")).apply {
            selectedItem = settings.openAiModel
        }
        modelCombo = combo
        val modelPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("OpenAI Model:"))
            add(combo)
        }

        // Ollama endpoint
        val ollamaEndpointField = JTextField(settings.ollamaEndpoint, 30)
        this.ollamaEndpointField = ollamaEndpointField
        val ollamaEndpointPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Ollama Endpoint:"))
            add(ollamaEndpointField)
        }

        // Ollama model
        val ollamaModelField = JTextField(settings.ollamaModel, 20)
        this.ollamaModelField = ollamaModelField
        val ollamaModelPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Ollama Model:"))
            add(ollamaModelField)
        }

        // Max files
        val spinner = JSpinner(SpinnerNumberModel(settings.maxFilesToAnalyze, 10, 2000, 50)).apply {
            preferredSize = Dimension(80, 28)
        }
        maxFilesSpinner = spinner
        val maxFilesPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Max files to analyze:"))
            add(spinner)
        }

        // Allow cloud upload
        val allowCloudBox = JCheckBox("Allow cloud upload (OpenAI)", settings.allowCloudUpload)
        this.allowCloudUploadBox = allowCloudBox

        formPanel.add(Box.createVerticalStrut(10))
        formPanel.add(JLabel("<html><b>Aegis Debug Configuration</b></html>"))
        formPanel.add(Box.createVerticalStrut(15))
        formPanel.add(JLabel("<html>Static-first analysis. AI is optional and off by default.</html>"))
        formPanel.add(Box.createVerticalStrut(10))
        formPanel.add(providerPanel)
        formPanel.add(apiKeyPanel)
        formPanel.add(modelPanel)
        formPanel.add(ollamaEndpointPanel)
        formPanel.add(ollamaModelPanel)
        formPanel.add(maxFilesPanel)
        formPanel.add(allowCloudBox)

        mainPanel.add(formPanel, BorderLayout.NORTH)
        panel = mainPanel
        return mainPanel
    }

    override fun isModified(): Boolean {
        val s = GhostDebuggerSettings.getInstance().snapshot()
        val currentKey = ApiKeyManager.getApiKey() ?: ""
        val enteredKey = String(apiKeyField?.password ?: charArrayOf())
        return currentKey != enteredKey
            || s.aiProvider.name != providerCombo?.selectedItem
            || s.openAiModel != modelCombo?.selectedItem
            || s.ollamaEndpoint != ollamaEndpointField?.text
            || s.ollamaModel != ollamaModelField?.text
            || s.maxFilesToAnalyze != maxFilesSpinner?.value
            || s.allowCloudUpload != allowCloudUploadBox?.isSelected
    }

    override fun apply() {
        val key = String(apiKeyField?.password ?: charArrayOf())
        if (key.isNotBlank()) ApiKeyManager.setApiKey(key)

        GhostDebuggerSettings.getInstance().update {
            aiProvider = AIProvider.valueOf(providerCombo?.selectedItem as? String ?: "NONE")
            (modelCombo?.selectedItem as? String)?.let { openAiModel = it }
            (ollamaEndpointField?.text)?.let { ollamaEndpoint = it }
            (ollamaModelField?.text)?.let { ollamaModel = it }
            (maxFilesSpinner?.value as? Int)?.let { maxFilesToAnalyze = it }
            allowCloudUpload = allowCloudUploadBox?.isSelected ?: false
        }
    }

    override fun reset() {
        val s = GhostDebuggerSettings.getInstance().snapshot()
        apiKeyField?.text = ApiKeyManager.getApiKey() ?: ""
        providerCombo?.selectedItem = s.aiProvider.name
        modelCombo?.selectedItem = s.openAiModel
        ollamaEndpointField?.text = s.ollamaEndpoint
        ollamaModelField?.text = s.ollamaModel
        maxFilesSpinner?.value = s.maxFilesToAnalyze
        allowCloudUploadBox?.isSelected = s.allowCloudUpload
    }
}
