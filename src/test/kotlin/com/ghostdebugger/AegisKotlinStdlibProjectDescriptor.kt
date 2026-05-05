package com.ghostdebugger

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import java.io.File

/**
 * Light-fixture project descriptor that registers Kotlin stdlib on the test module
 * so the Analysis API can resolve types instead of returning `KaErrorType`.
 *
 * In IntelliJ Platform Gradle Plugin 2.14.0, `TestFrameworkType.Plugin.Kotlin` is not
 * exposed and the production Kotlin plugin distribution doesn't ship its test fixtures
 * (`KotlinLightProjectDescriptor` etc.). This descriptor stands in for those fixtures by
 * locating `kotlin-stdlib.jar` inside the downloaded IDE distribution at test runtime via
 * [PathManager.getHomePath].
 *
 * Why this matters: without stdlib on the module classpath, every `expressionType()` call
 * inside `analyze { }` returns `KaErrorType`. Tests built on the conservative-miss bias
 * would silently produce zero findings on every input — the R3 risk in the V1.3 design spec.
 */
class AegisKotlinStdlibProjectDescriptor : DefaultLightProjectDescriptor() {

    private val log = logger<AegisKotlinStdlibProjectDescriptor>()

    override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
        super.configureModule(module, model, contentEntry)
        addKotlinStdlib(model)
    }

    private fun addKotlinStdlib(model: ModifiableRootModel) {
        val ideHome = PathManager.getHomePath()
        val stdlibPath = File(ideHome, "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
        if (!stdlibPath.exists()) {
            log.warn(
                "kotlin-stdlib.jar not found at ${stdlibPath.absolutePath}. " +
                "Analysis API tests will see KaErrorType for stdlib references — the R3 failure mode " +
                "in the V1.3 design spec. If the IDE layout changed, update this descriptor."
            )
            return
        }

        val localVf = LocalFileSystem.getInstance().findFileByIoFile(stdlibPath)
            ?: run {
                log.warn("Cannot resolve VirtualFile for ${stdlibPath.absolutePath}")
                return
            }
        val jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(localVf)
            ?: run {
                log.warn("Cannot resolve JAR root for ${stdlibPath.absolutePath}")
                return
            }

        val library = model.moduleLibraryTable.createLibrary("kotlin-stdlib")
        val libModel = library.modifiableModel
        try {
            libModel.addRoot(jarRoot, OrderRootType.CLASSES)
        } finally {
            libModel.commit()
        }
    }

    companion object {
        val INSTANCE: LightProjectDescriptor = AegisKotlinStdlibProjectDescriptor()
    }
}
