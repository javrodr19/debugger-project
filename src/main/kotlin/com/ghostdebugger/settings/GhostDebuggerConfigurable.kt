package com.ghostdebugger.settings

import com.ghostdebugger.ai.ApiKeyManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableModel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout

class GhostDebuggerConfigurable : Configurable {

    private var panel: JPanel? = null
    private var apiKeyField: JPasswordField? = null
    private var modelCombo: JComboBox<String>? = null
    private var maxFilesSpinner: JSpinner? = null
    private var maxAiFilesSpinner: JSpinner? = null
    private var providerCombo: JComboBox<String>? = null
    private var ollamaEndpointField: JTextField? = null
    private var ollamaModelField: JTextField? = null
    private var allowCloudUploadBox: JCheckBox? = null
    private var cacheEnabledBox: JCheckBox? = null
    private var cacheTtlSpinner: JSpinner? = null
    private var aiTimeoutSpinner: JSpinner? = null
    private var autoAnalyzeOnOpenBox: JCheckBox? = null
    private var showInfoIssuesBox: JCheckBox? = null
    private var analyzeOnlyChangedFilesBox: JCheckBox? = null
    private var maxComplexitySpinner: JSpinner? = null
    private var coverageModeCombo: JComboBox<String>? = null
    private var suppressionThresholdSpinner: JSpinner? = null
    private var showUnreachedBox: JCheckBox? = null
    private var showSuppressedBox: JCheckBox? = null
    private var suppressionTable: JTable? = null
    private var suppressionModel: javax.swing.table.DefaultTableModel? = null
    private var noSuppressionsLabel: JLabel? = null

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

        // Max AI files
        val aiFilesSpinner = JSpinner(SpinnerNumberModel(settings.maxAiFiles, 0, 500, 5)).apply {
            preferredSize = Dimension(80, 28)
        }
        maxAiFilesSpinner = aiFilesSpinner
        val maxAiFilesPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Max AI files per pass:"))
            add(aiFilesSpinner)
        }

        // Complexity threshold (V1.4.1 — was hardcoded at 10 pre-1.4.1)
        val complexitySpinner = JSpinner(SpinnerNumberModel(settings.maxComplexity, 1, 100, 1)).apply {
            preferredSize = Dimension(80, 28)
        }
        maxComplexitySpinner = complexitySpinner
        val maxComplexityPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Complexity threshold:"))
            add(complexitySpinner)
        }

        // AI Timeout
        val timeoutSpinner = JSpinner(SpinnerNumberModel(settings.aiTimeoutMs.toInt(), 5000, 300000, 5000)).apply {
            preferredSize = Dimension(100, 28)
        }
        aiTimeoutSpinner = timeoutSpinner
        val timeoutPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("AI Timeout (ms):"))
            add(timeoutSpinner)
        }

        // Cache
        val cacheBox = JCheckBox("Enable AI response cache", settings.cacheEnabled)
        this.cacheEnabledBox = cacheBox
        val ttlSpinner = JSpinner(SpinnerNumberModel(settings.cacheTtlSeconds.toInt(), 60, 86400, 300)).apply {
            preferredSize = Dimension(100, 28)
        }
        cacheTtlSpinner = ttlSpinner
        val cachePanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(cacheBox)
            add(Box.createHorizontalStrut(20))
            add(JLabel("Cache TTL (s):"))
            add(ttlSpinner)
        }

        // Allow cloud upload
        val allowCloudBox = JCheckBox("Allow cloud upload (OpenAI)", settings.allowCloudUpload)
        this.allowCloudUploadBox = allowCloudBox

        // Other options
        val autoAnalyzeBox = JCheckBox("Auto-analyze project on open", settings.autoAnalyzeOnOpen)
        this.autoAnalyzeOnOpenBox = autoAnalyzeBox
        
        val showInfoBox = JCheckBox("Show INFO level issues", settings.showInfoIssues)
        this.showInfoIssuesBox = showInfoBox
        
        val changedFilesOnlyBox = JCheckBox("Only analyze changed files", settings.analyzeOnlyChangedFiles)
        this.analyzeOnlyChangedFilesBox = changedFilesOnlyBox

        // V2.0 Dynamic Validation settings
        val coverageModeCombo = JComboBox(arrayOf("Always", "Ask each time", "Never")).apply {
            selectedItem = settings.coverageMode
        }
        this.coverageModeCombo = coverageModeCombo
        val coverageModePanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Use coverage for test runs:"))
            add(coverageModeCombo)
        }

        val suppressionSpinner = JSpinner(SpinnerNumberModel(settings.suppressionThreshold, 1, 10, 1)).apply {
            preferredSize = Dimension(85, 28)
        }
        this.suppressionThresholdSpinner = suppressionSpinner
        val suppressionPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Suppression threshold (dismissals):"))
            add(suppressionSpinner)
        }

        val showUnreachedBox = JCheckBox("Show unreached findings (needs coverage)", settings.showUnreached)
        this.showUnreachedBox = showUnreachedBox

        val showSuppressedBox = JCheckBox("Show suppressed findings", settings.showSuppressed)
        this.showSuppressedBox = showSuppressedBox

        formPanel.add(Box.createVerticalStrut(10))
        formPanel.add(JLabel("<html><b>Aegis Debug Configuration</b></html>"))
        formPanel.add(Box.createVerticalStrut(15))
        formPanel.add(providerPanel)
        formPanel.add(apiKeyPanel)
        formPanel.add(modelPanel)
        formPanel.add(ollamaEndpointPanel)
        formPanel.add(ollamaModelPanel)
        formPanel.add(maxFilesPanel)
        formPanel.add(maxAiFilesPanel)
        formPanel.add(maxComplexityPanel)
        formPanel.add(timeoutPanel)
        formPanel.add(cachePanel)
        formPanel.add(allowCloudBox)
        formPanel.add(autoAnalyzeBox)
        formPanel.add(showInfoBox)
        formPanel.add(changedFilesOnlyBox)
        
        formPanel.add(Box.createVerticalStrut(15))
        formPanel.add(JLabel("<html><b>Dynamic Validation (V2.0)</b></html>"))
        formPanel.add(Box.createVerticalStrut(10))
        formPanel.add(coverageModePanel)
        formPanel.add(suppressionPanel)
        formPanel.add(showUnreachedBox)
        formPanel.add(showSuppressedBox)

        formPanel.add(Box.createVerticalStrut(20))
        formPanel.add(JLabel("<html><b>Active Suppressions & Dismissals Management</b></html>"))
        formPanel.add(Box.createVerticalStrut(10))

        val model = DefaultTableModel(
            arrayOf("Project", "File Path", "Rule/Type", "Line", "Dismissals", "fingerprint"), 0
        )
        suppressionModel = model

        val table = com.intellij.ui.table.JBTable(model).apply {
            setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            // Hide fingerprint column from view
            columnModel.getColumn(5).minWidth = 0
            columnModel.getColumn(5).maxWidth = 0
            columnModel.getColumn(5).width = 0
        }
        suppressionTable = table

        val scrollPane = com.intellij.ui.components.JBScrollPane(table).apply {
            preferredSize = java.awt.Dimension(600, 150)
            border = BorderFactory.createLineBorder(java.awt.Color.GRAY)
        }

        val noSuppressionsLabel = JLabel("No active dismissals or suppressions.").apply {
            font = font.deriveFont(java.awt.Font.ITALIC)
        }
        this.noSuppressionsLabel = noSuppressionsLabel

        val suppressionListPanel = JPanel(BorderLayout()).apply {
            add(scrollPane, BorderLayout.CENTER)
            add(noSuppressionsLabel, BorderLayout.NORTH)
        }
        formPanel.add(suppressionListPanel)
        formPanel.add(Box.createVerticalStrut(8))

        val resetSelectedBtn = JButton("Unsuppress Selected").apply {
            addActionListener {
                val selectedRows = suppressionTable?.selectedRows ?: return@addActionListener
                if (selectedRows.isEmpty()) {
                    Messages.showInfoMessage("Please select one or more items to unsuppress.", "No Selection")
                    return@addActionListener
                }
                val openProjects = com.intellij.openapi.project.ProjectManager.getInstance().openProjects
                val toReset = selectedRows.map { row ->
                    val modelRow = suppressionTable?.convertRowIndexToModel(row) ?: -1
                    if (modelRow >= 0) {
                        val projName = suppressionModel?.getValueAt(modelRow, 0) as? String
                        val fingerprint = suppressionModel?.getValueAt(modelRow, 5) as? String
                        Pair(projName, fingerprint)
                    } else null
                }.filterNotNull()

                toReset.forEach { (projName, fingerprint) ->
                    if (fingerprint != null) {
                        val project = openProjects.find { it.name == projName }
                        if (project != null && !project.isDisposed) {
                            val suppressionSvc = com.ghostdebugger.store.SuppressionMemoryService.getInstance(project)
                            suppressionSvc.reset(fingerprint)
                            com.ghostdebugger.GhostDebuggerService.getInstance(project).refreshIssuesUI()
                        }
                    }
                }
                loadActiveSuppressions()
            }
        }

        val resetAllBtn = JButton("Reset All").apply {
            addActionListener {
                val confirm = Messages.showYesNoDialog(
                    "Are you sure you want to reset all active issue dismissals/suppressions across all open projects?",
                    "Reset All Suppressions",
                    Messages.getQuestionIcon()
                )
                if (confirm == Messages.YES) {
                    com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach { project ->
                        if (!project.isDisposed) {
                            val suppressionSvc = com.ghostdebugger.store.SuppressionMemoryService.getInstance(project)
                            suppressionSvc.resetAll()
                            com.ghostdebugger.GhostDebuggerService.getInstance(project).refreshIssuesUI()
                        }
                    }
                    loadActiveSuppressions()
                }
            }
        }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(resetSelectedBtn)
            add(Box.createHorizontalStrut(10))
            add(resetAllBtn)
        }
        formPanel.add(buttonPanel)

        loadActiveSuppressions()

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
            || s.maxAiFiles != maxAiFilesSpinner?.value
            || s.aiTimeoutMs != (aiTimeoutSpinner?.value as? Int)?.toLong()
            || s.cacheEnabled != cacheEnabledBox?.isSelected
            || s.cacheTtlSeconds != (cacheTtlSpinner?.value as? Int)?.toLong()
            || s.allowCloudUpload != allowCloudUploadBox?.isSelected
            || s.autoAnalyzeOnOpen != autoAnalyzeOnOpenBox?.isSelected
            || s.showInfoIssues != showInfoIssuesBox?.isSelected
            || s.analyzeOnlyChangedFiles != analyzeOnlyChangedFilesBox?.isSelected
            || s.maxComplexity != maxComplexitySpinner?.value
            || s.coverageMode != coverageModeCombo?.selectedItem
            || s.suppressionThreshold != suppressionThresholdSpinner?.value
            || s.showUnreached != showUnreachedBox?.isSelected
            || s.showSuppressed != showSuppressedBox?.isSelected
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
            (maxAiFilesSpinner?.value as? Int)?.let { maxAiFiles = it }
            (aiTimeoutSpinner?.value as? Int)?.let { aiTimeoutMs = it.toLong() }
            cacheEnabled = cacheEnabledBox?.isSelected ?: true
            (cacheTtlSpinner?.value as? Int)?.let { cacheTtlSeconds = it.toLong() }
            allowCloudUpload = allowCloudUploadBox?.isSelected ?: false
            autoAnalyzeOnOpen = autoAnalyzeOnOpenBox?.isSelected ?: false
            showInfoIssues = showInfoIssuesBox?.isSelected ?: true
            analyzeOnlyChangedFiles = analyzeOnlyChangedFilesBox?.isSelected ?: false
            (maxComplexitySpinner?.value as? Int)?.let { maxComplexity = it }
            // V2.0 Settings
            (coverageModeCombo?.selectedItem as? String)?.let { coverageMode = it }
            (suppressionThresholdSpinner?.value as? Int)?.let { suppressionThreshold = it }
            showUnreached = showUnreachedBox?.isSelected ?: false
            showSuppressed = showSuppressedBox?.isSelected ?: false
        }

        // V2.0 live UI refresh on applying settings
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach { project ->
                if (!project.isDisposed) {
                    com.ghostdebugger.GhostDebuggerService.getInstance(project).refreshIssuesUI()
                }
            }
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
        maxAiFilesSpinner?.value = s.maxAiFiles
        aiTimeoutSpinner?.value = s.aiTimeoutMs.toInt()
        cacheEnabledBox?.isSelected = s.cacheEnabled
        cacheTtlSpinner?.value = s.cacheTtlSeconds.toInt()
        allowCloudUploadBox?.isSelected = s.allowCloudUpload
        autoAnalyzeOnOpenBox?.isSelected = s.autoAnalyzeOnOpen
        showInfoIssuesBox?.isSelected = s.showInfoIssues
        analyzeOnlyChangedFilesBox?.isSelected = s.analyzeOnlyChangedFiles
        maxComplexitySpinner?.value = s.maxComplexity
        // V2.0 Settings reset
        coverageModeCombo?.selectedItem = s.coverageMode
        suppressionThresholdSpinner?.value = s.suppressionThreshold
        showUnreachedBox?.isSelected = s.showUnreached
        showSuppressedBox?.isSelected = s.showSuppressed
        
        loadActiveSuppressions()
    }

    private fun loadActiveSuppressions() {
        val model = suppressionModel ?: return
        model.rowCount = 0
        var hasSuppressions = false
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach { project ->
            if (!project.isDisposed) {
                val suppressionSvc = com.ghostdebugger.store.SuppressionMemoryService.getInstance(project)
                suppressionSvc.getDismissCounts().forEach { (fingerprint, count) ->
                    val parts = fingerprint.split(":")
                    val rule = parts.getOrNull(0) ?: "UNKNOWN"
                    val filePath = parts.getOrNull(1) ?: ""
                    val line = parts.getOrNull(2) ?: "0"
                    model.addRow(arrayOf(project.name, filePath, rule, line, count, fingerprint))
                    hasSuppressions = true
                }
            }
        }
        
        suppressionTable?.isVisible = hasSuppressions
        noSuppressionsLabel?.isVisible = !hasSuppressions
        suppressionTable?.parent?.parent?.revalidate()
        suppressionTable?.parent?.parent?.repaint()
    }
}
