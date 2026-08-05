package com.ghostdebugger.rules

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

@Service(Service.Level.PROJECT)
class CustomRuleService(private val project: Project) : Disposable {

    private val LOG = Logger.getInstance(CustomRuleService::class.java)

    @Volatile
    private var cachedRules: List<CustomRule>? = null

    init {
        Disposer.register(project, this)
        VirtualFileManager.getInstance().addAsyncFileListener(
            { events ->
                if (events.any { isAegisRuleEvent(it) }) {
                    cachedRules = null
                }
                null
            },
            this
        )
    }

    private fun isAegisRuleEvent(event: VFileEvent): Boolean {
        val path = event.path
        return path.contains(".aegis/rules/") && (path.endsWith(".yml") || path.endsWith(".yaml"))
    }

    fun rules(): List<CustomRule> {
        val cached = cachedRules
        if (cached != null) return cached

        val loaded = loadRules()
        cachedRules = loaded
        return loaded
    }

    private fun loadRules(): List<CustomRule> {
        val baseDir = project.baseDir ?: return emptyList()
        val rulesDir = baseDir.findFileByRelativePath(".aegis/rules") ?: return emptyList()
        if (!rulesDir.isDirectory) return emptyList()

        val rulesList = mutableListOf<CustomRule>()
        val seenIds = mutableSetOf<String>()

        for (file in rulesDir.children) {
            if (file.isDirectory || (!file.name.endsWith(".yml") && !file.name.endsWith(".yaml"))) continue

            val content = runCatching { String(file.contentsToByteArray()) }.getOrNull() ?: continue
            val decodedFile = CustomRuleCodec.decode(content)
            if (decodedFile == null) {
                LOG.warn("Skipping malformed Aegis custom rule file: ${file.path}")
                continue
            }

            for (rule in decodedFile.rules) {
                if (seenIds.contains(rule.id)) {
                    LOG.warn("Skipping duplicate custom rule id '${rule.id}' in ${file.path}")
                    continue
                }
                seenIds.add(rule.id)
                rulesList.add(rule)
            }
        }

        return rulesList
    }

    override fun dispose() {
        cachedRules = null
    }

    companion object {
        fun getInstance(project: Project): CustomRuleService = project.service()
    }
}
