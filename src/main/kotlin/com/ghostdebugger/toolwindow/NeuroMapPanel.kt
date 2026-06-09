package com.ghostdebugger.toolwindow

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.bridge.JcefBridge
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.jar.JarFile
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.browser.CefBrowser

class NeuroMapPanel(
    private val project: Project,
    private val parentDisposable: Disposable
) : JPanel(BorderLayout()) {

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
            val jbBrowser = JBCefBrowser.createBuilder()
                .setUrl(indexUrl)
                .build()
            Disposer.register(parentDisposable, jbBrowser)
            browser = jbBrowser
            
            val service = GhostDebuggerService.getInstance(project)
            val bridge = JcefBridge(project, jbBrowser) { event ->
                service.handleUIEvent(event)
            }
            Disposer.register(parentDisposable, bridge)
            // initialize() is invoked by service.setBridge(bridge) below; calling it here too
            // created a second JBCefJSQuery (the first orphaned/leaked) plus a duplicate load
            // handler. See BUG-01. (initialize() is idempotent now as a backstop.)
            service.setBridge(bridge)

            val displayHandler = object : CefDisplayHandlerAdapter() {
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
            }
            jbBrowser.jbCefClient.addDisplayHandler(displayHandler, jbBrowser.cefBrowser)
            // Remove the handler deterministically on teardown. Registered after the browser, so
            // LIFO disposal removes it while the client is still alive. See BUG-04.
            Disposer.register(parentDisposable, Disposable {
                jbBrowser.jbCefClient.removeDisplayHandler(displayHandler, jbBrowser.cefBrowser)
            })

            add(jbBrowser.component, BorderLayout.CENTER)
            log.info("NeuroMapPanel: JCEF browser initialized successfully")
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
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
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
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
        // deleteOnExit() only removes EMPTY directories, so the extracted HTML/JS/CSS leaked on
        // disk across IDE restarts. Delete the whole tree when the panel's parentDisposable is
        // torn down. Registered before the browser, so LIFO disposal tears the browser down first,
        // then deletes the directory it was reading from. See BUG-06.
        Disposer.register(parentDisposable, Disposable { tempDir.deleteRecursively() })

        val classLoader = javaClass.classLoader
        
        try {
            // Priority 1: Use the plugin path from IntelliJ's PluginManager
            val plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                com.intellij.openapi.extensions.PluginId.getId("com.ghostdebugger")
            )
            
            if (plugin != null) {
                val pluginPath = plugin.pluginPath
                val webDir = pluginPath.resolve("web")
                
                // If web dir exists outside JAR (exploded)
                if (Files.isDirectory(webDir)) {
                    log.info("NeuroMapPanel: Found web resources at exploded plugin path: $webDir")
                    webDir.toFile().copyRecursively(tempDir, overwrite = true)
                } else {
                    // Look for the JAR in lib/
                    val libDir = pluginPath.resolve("lib")
                    val jarPath = if (Files.isDirectory(libDir)) {
                        Files.list(libDir).use { stream ->
                            stream.filter { it.toString().endsWith(".jar") && it.fileName.toString().contains("ghostdebugger") }
                                .findFirst().orElse(null)
                        }
                    } else null

                    if (jarPath != null) {
                        log.info("NeuroMapPanel: Extracting resources from discovered JAR: $jarPath")
                        extractFromJar(jarPath.toFile(), tempDir)
                    } else {
                        // Fallback: Use the classpath resource and try to find the JAR file manually
                        val resource = classLoader.getResource("web/index.html")
                        if (resource?.protocol == "jar") {
                            // jarFileURL is a well-formed URL; URL#toURI() -> File decodes spaces
                            // and Unicode correctly. The old code built a URI from resource.path
                            // (raw, partially-decoded) and threw URISyntaxException on spaces, and
                            // computed an unused rawJarPath. See BUG-05.
                            // `as?` (not `as`): a jar: URL always yields a JarURLConnection, but a
                            // safe cast avoids an unprovable downcast; if it ever weren't, fall
                            // through to the index.html-not-found error below.
                            val jarFileUrl = (resource.openConnection() as? java.net.JarURLConnection)?.jarFileURL
                            if (jarFileUrl != null) {
                                val jarFile = File(jarFileUrl.toURI())
                                log.info("NeuroMapPanel: Extracting resources from classpath JAR: ${jarFile.absolutePath}")
                                extractFromJar(jarFile, tempDir)
                            }
                        } else if (resource?.protocol == "file") {
                            File(resource.toURI()).parentFile.copyRecursively(tempDir, overwrite = true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            log.error("Failed to extract web resources securely", e)
            throw IllegalStateException("Fatal: Could not load NeuroMap resources. Please ensure the plugin is correctly installed.", e)
        }

        val indexFile = File(tempDir, "index.html")
        if (!indexFile.exists()) {
            throw IllegalStateException("Failed to extract web resources: index.html not found in $tempDir")
        }

        log.info("Web resources extracted successfully to: ${tempDir.absolutePath}")
        return indexFile.toURI().toString()
    }

    private fun extractFromJar(jarFile: File, targetDir: File) {
        java.util.jar.JarFile(jarFile).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.startsWith("web/") && !entry.isDirectory) {
                    val relativePath = entry.name.removePrefix("web/")
                    val targetFile = File(targetDir, relativePath)
                    targetFile.parentFile.mkdirs()
                    jar.getInputStream(entry).use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

}
