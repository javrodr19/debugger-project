plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.ghostdebugger"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // IntelliJ Platform
    intellijPlatform {
        intellijIdeaCommunity("2024.3.2")
        pluginVerifier()
        zipSigner()
    }

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Kotlin Serialization (JSON)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // HTTP Client for OpenAI API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.mockk:mockk:1.13.15")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.ghostdebugger"
        name = "GhostDebugger"
        version = project.version.toString()
        description = """
            Intelligent debugging system that understands, predicts,
            and fixes code like a senior developer.

            Features:
            - Global project analysis with NeuroMap visualization
            - AI-powered error explanations (OpenAI GPT-4o)
            - Contextual automatic fixes
            - Impact analysis
            - Execution flow simulation
        """.trimIndent()

        ideaVersion {
            sinceBuild = "243"
            untilBuild = "251.*"
        }

        vendor {
            name = "GhostDebugger Team"
        }
    }
}

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val npmCmd = if (isWindows) listOf("cmd", "/c", "npm") else listOf("npm")

tasks {
    test {
        useJUnitPlatform()
    }

    register<Exec>("npmInstallWebview") {
        workingDir = file("webview")
        commandLine(npmCmd + listOf("install"))
        inputs.file("webview/package.json")
        inputs.file("webview/package-lock.json")
        outputs.dir("webview/node_modules")
    }

    register<Exec>("buildWebview") {
        dependsOn("npmInstallWebview")
        workingDir = file("webview")
        commandLine(npmCmd + listOf("run", "build"))
        inputs.dir("webview/src")
        inputs.file("webview/package.json")
        inputs.file("webview/vite.config.ts")
        outputs.dir("src/main/resources/web")
    }

    processResources {
        dependsOn("buildWebview")
    }

    instrumentCode {
        enabled = false
    }
}
