package com.ghostdebugger.rules

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

@Service(Service.Level.PROJECT)
class RulePackService(private val project: Project) : Disposable {

    private val LOG = Logger.getInstance(RulePackService::class.java)

    @Volatile
    private var cachedPacks: List<RulePack>? = null
    
    private val disabledPackIds = mutableSetOf<String>()

    init {
        Disposer.register(project, this)
        VirtualFileManager.getInstance().addAsyncFileListener(
            { events ->
                if (events.any { isAegisPackEvent(it) }) {
                    cachedPacks = null
                }
                null
            },
            this
        )
    }

    private fun isAegisPackEvent(event: VFileEvent): Boolean {
        val path = event.path
        return path.contains(".aegis/packs/") && (path.endsWith(".yml") || path.endsWith(".yaml"))
    }

    fun availablePacks(): List<RulePack> {
        val cached = cachedPacks
        if (cached != null) return cached

        val loaded = loadAllPacks()
        cachedPacks = loaded
        return loaded
    }

    fun isPackEnabled(packId: String): Boolean {
        val pack = availablePacks().firstOrNull { it.id == packId } ?: return false
        if (disabledPackIds.contains(packId)) return false
        return pack.enabledByDefault
    }

    fun setPackEnabled(packId: String, enabled: Boolean) {
        if (enabled) {
            disabledPackIds.remove(packId)
        } else {
            disabledPackIds.add(packId)
        }
    }

    fun packRules(): List<CustomRule> {
        val rulesList = mutableListOf<CustomRule>()
        val seenIds = mutableSetOf<String>()

        for (pack in availablePacks()) {
            if (isPackEnabled(pack.id)) {
                for (rule in pack.rules) {
                    if (!seenIds.contains(rule.id)) {
                        seenIds.add(rule.id)
                        rulesList.add(rule)
                    }
                }
            }
        }
        return rulesList
    }

    private fun loadAllPacks(): List<RulePack> {
        val packs = mutableListOf<RulePack>()
        val seenPackIds = mutableSetOf<String>()

        // 1. Load bundled packs from classpath resources with hardcoded fallback strings
        val bundledPacksWithFallback = listOf(
            "/rules/packs/react-strict.yml" to BUNDLED_REACT_STRICT_YAML,
            "/rules/packs/kotlin-coroutines.yml" to BUNDLED_KOTLIN_COROUTINES_YAML,
            "/rules/packs/node-security.yml" to BUNDLED_NODE_SECURITY_YAML
        )
        for ((path, fallbackYaml) in bundledPacksWithFallback) {
            val relPath = path.removePrefix("/")
            val stream = javaClass.getResourceAsStream(path)
                ?: javaClass.getResourceAsStream(relPath)
                ?: javaClass.classLoader?.getResourceAsStream(relPath)
                ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(relPath)
                ?: RulePackService::class.java.classLoader?.getResourceAsStream(relPath)

            val content = stream?.bufferedReader()?.use { it.readText() } ?: fallbackYaml
            val pack = RulePackCodec.decode(content)
            if (pack != null && !seenPackIds.contains(pack.id)) {
                seenPackIds.add(pack.id)
                packs.add(pack)
            }
        }

        // 2. Load custom packs from .aegis/packs/*.yml in project
        val baseDir = project.guessProjectDir()
        if (baseDir != null) {
            val packsDir = baseDir.findFileByRelativePath(".aegis/packs")
            if (packsDir != null && packsDir.isDirectory) {
                ApplicationManager.getApplication().runReadAction {
                    for (file in packsDir.children) {
                        parsePackFile(file, packs, seenPackIds)
                    }
                }
            }
        }

        return packs
    }

    private fun parsePackFile(file: VirtualFile, packs: MutableList<RulePack>, seenPackIds: MutableSet<String>) {
        if (file.isDirectory || (!file.name.endsWith(".yml") && !file.name.endsWith(".yaml"))) return
        val content = runCatching { String(file.contentsToByteArray()) }.getOrNull() ?: return
        val pack = RulePackCodec.decode(content)
        if (pack == null) {
            LOG.warn("Skipping malformed Aegis rule pack file: ${file.path}")
            return
        }
        if (!seenPackIds.contains(pack.id)) {
            seenPackIds.add(pack.id)
            packs.add(pack)
        }
    }

    override fun dispose() {
        cachedPacks = null
        disabledPackIds.clear()
    }

    companion object {
        fun getInstance(project: Project): RulePackService = project.service()

        private val BUNDLED_REACT_STRICT_YAML = """
            id: react-strict
            name: "React Strict Practices"
            description: "Rules for enforcing clean React lifecycle, hooks discipline, and state hygiene."
            enabledByDefault: true
            rules:
              - id: react-no-eval
                language: typescript
                severity: error
                message: "Avoid using eval() or dynamic code execution in React components."
                match:
                  element: call-expression
                  name-matches: "^eval$"
              - id: react-no-direct-mutation
                language: typescript
                severity: warning
                message: "Do not mutate state properties directly; use setter functions."
                match:
                  element: call-expression
                  name-matches: "^setState$"
        """.trimIndent()

        private val BUNDLED_KOTLIN_COROUTINES_YAML = """
            id: kotlin-coroutines
            name: "Kotlin Coroutines Safety"
            description: "Rules for preventing common coroutine pitfalls and cancellation swallowing."
            enabledByDefault: true
            rules:
              - id: pce-rethrow-missing
                language: kotlin
                severity: warning
                message: "catch (e: Exception) must rethrow ProcessCanceledException first"
                match:
                  element: catch-clause
                  parameter-type: java.lang.Exception
                  unless:
                    contains-text: "is ProcessCanceledException) throw"
        """.trimIndent()

        private val BUNDLED_NODE_SECURITY_YAML = """
            id: node-security
            name: "Node.js Security Guard"
            description: "Security rules preventing dangerous calls and unsafe child process executions."
            enabledByDefault: true
            rules:
              - id: node-no-child-process-exec
                language: javascript
                severity: error
                message: "exec() can lead to command injection; prefer execFile or spawn with fixed arguments."
                match:
                  element: call-expression
                  name-matches: "^exec$"
        """.trimIndent()
    }
}
