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

        // 1. Load bundled packs from classpath resources
        val bundledPaths = listOf(
            "/rules/packs/react-strict.yml",
            "/rules/packs/kotlin-coroutines.yml",
            "/rules/packs/node-security.yml"
        )
        for (path in bundledPaths) {
            val stream = javaClass.getResourceAsStream(path)
            if (stream != null) {
                val content = stream.bufferedReader().use { it.readText() }
                val pack = RulePackCodec.decode(content)
                if (pack != null && !seenPackIds.contains(pack.id)) {
                    seenPackIds.add(pack.id)
                    packs.add(pack)
                }
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
    }
}
