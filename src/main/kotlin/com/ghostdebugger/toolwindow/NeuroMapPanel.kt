package com.ghostdebugger.toolwindow

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.bridge.JcefBridge
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.jar.JarFile
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.browser.CefBrowser

class NeuroMapPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log = logger<NeuroMapPanel>()
    private var browser: JBCefBrowser? = null

    init {
        if (JBCefApp.isSupported()) {
            initJcef()
        } else {
            add(JLabel("JCEF is not supported in this environment.", SwingConstants.CENTER), BorderLayout.CENTER)
        }
    }

    private fun initJcef() {
        try {
            val indexUrl = findWebIndexUrl()
            log.info("NeuroMapPanel: Loading JCEF browser with URL: $indexUrl")
            val jbBrowser = JBCefBrowser(indexUrl)
            browser = jbBrowser

            val service = GhostDebuggerService.getInstance(project)
            val bridge = JcefBridge(jbBrowser) { event ->
                service.handleUIEvent(event)
            }
            service.setBridge(bridge)

            jbBrowser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                override fun onConsoleMessage(
                    browser: CefBrowser?,
                    level: org.cef.CefSettings.LogSeverity?,
                    message: String?,
                    source: String?,
                    line: Int
                ): Boolean {
                    log.warn("JCEF Console [${level?.name}]: $message - $source:$line")
                    return false
                }
            }, jbBrowser.cefBrowser)

            add(jbBrowser.component, BorderLayout.CENTER)
            log.info("NeuroMapPanel: JCEF browser initialized successfully")
        } catch (e: Exception) {
            log.error("Failed to initialize JCEF browser", e)
            add(
                JLabel("<html><center>Failed to load NeuroMap.<br>${e.message}</center></html>", SwingConstants.CENTER),
                BorderLayout.CENTER
            )
        }
    }

    private fun findWebIndexUrl(): String {
        // 1. Try the plugin's own directory (sandbox mode: build/idea-sandbox/.../plugins/ghostdebugger/)
        val pluginDir = getPluginWebDir()
        if (pluginDir != null) {
            val indexFile = File(pluginDir, "index.html")
            if (indexFile.exists() && File(pluginDir, "assets").isDirectory) {
                log.info("Found complete web dir at plugin path: ${pluginDir.absolutePath}")
                return indexFile.toURI().toString()
            }
        }

        // 2. Try source resources directory (development — when running ./gradlew runIde from project root)
        val srcResourceWeb = File("src/main/resources/web/index.html")
        if (srcResourceWeb.exists()) {
            log.info("Found web resources at source path: ${srcResourceWeb.canonicalPath}")
            return srcResourceWeb.canonicalFile.toURI().toString()
        }

        // 3. Try build output directory
        val buildResourceWeb = File("build/resources/main/web/index.html")
        if (buildResourceWeb.exists()) {
            log.info("Found web resources at build path: ${buildResourceWeb.canonicalPath}")
            return buildResourceWeb.canonicalFile.toURI().toString()
        }

        // 4. Try classpath — if protocol is "file://", use it directly
        val resource = javaClass.classLoader.getResource("web/index.html")
        if (resource != null) {
            if (resource.protocol == "file") {
                val resourceFile = File(resource.toURI())
                val assetsDir = File(resourceFile.parentFile, "assets")
                if (assetsDir.isDirectory) {
                    log.info("Found web resources on classpath (file): ${resourceFile.absolutePath}")
                    return resource.toExternalForm()
                }
            }

            // 5. Resources are inside a JAR — extract everything to temp
            log.info("Web resources are inside JAR, extracting to temp directory...")
            return extractAllWebResources()
        }

        throw IllegalStateException(
            "Cannot find web/index.html. " +
            "Please build the webview first: cd webview && npm run build"
        )
    }

    private fun getPluginWebDir(): File? {
        try {
            val pluginId = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                com.intellij.openapi.extensions.PluginId.getId("com.ghostdebugger")
            ) ?: return null

            val pluginPath = pluginId.pluginPath

            // Check common locations in the plugin directory
            for (subPath in listOf("classes/web", "web", "lib/classes/web", "lib/web")) {
                val candidate = pluginPath.resolve(subPath).toFile()
                if (candidate.exists() && candidate.isDirectory) {
                    return candidate
                }
            }
        } catch (e: Exception) {
            log.warn("Could not determine plugin path", e)
        }
        return null
    }

    /**
     * Extract all web resources (index.html + assets/) from the classpath/JAR
     * to a temporary directory that JCEF can load via file:// URLs.
     */
    private fun extractAllWebResources(): String {
        val tempDir = Files.createTempDirectory("ghostdebugger-web").toFile()
        tempDir.deleteOnExit()

        val classLoader = javaClass.classLoader

        // Find the JAR or directory containing "web/index.html"
        val webIndexUrl = classLoader.getResource("web/index.html")
            ?: throw IllegalStateException("web/index.html not found on classpath")

        if (webIndexUrl.protocol == "jar") {
            // Extract from JAR file
            val jarPath = webIndexUrl.path.substringAfter("file:").substringBefore("!")
            val jarFile = JarFile(File(jarPath))

            jarFile.use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("web/") && !entry.isDirectory) {
                        val relativePath = entry.name.removePrefix("web/")
                        val targetFile = File(tempDir, relativePath)
                        targetFile.parentFile.mkdirs()

                        jar.getInputStream(entry).use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        log.info("Extracted: ${entry.name} → ${targetFile.absolutePath}")
                    }
                }
            }
        } else {
            // Resources are in a directory (e.g., exploded classes)
            val webDir = File(webIndexUrl.toURI()).parentFile
            webDir.copyRecursively(tempDir, overwrite = true)
            log.info("Copied web directory: ${webDir.absolutePath} → ${tempDir.absolutePath}")
        }

        val indexFile = File(tempDir, "index.html")
        if (!indexFile.exists()) {
            throw IllegalStateException("Failed to extract web resources: index.html not found in $tempDir")
        }

        // Verify assets were extracted
        val assetsDir = File(tempDir, "assets")
        if (assetsDir.isDirectory) {
            val assetFiles = assetsDir.listFiles()?.map { it.name } ?: emptyList()
            log.info("Extracted assets: $assetFiles")
        } else {
            log.warn("No assets directory found after extraction!")
        }

        log.info("Web resources extracted to: ${tempDir.absolutePath}")
        return indexFile.toURI().toString()
    }
}
