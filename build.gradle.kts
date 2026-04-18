import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.ghostdebugger"
version = "1.1.1"

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
        testFramework(TestFrameworkType.Platform)
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.ghostdebugger"
        name = "Aegis Debug"
        version = project.version.toString()
        description = """
            <h2>Aegis Debug — privacy-first debugging for IntelliJ</h2>

            <p><strong>Static-first analysis. Deterministic fixes. Optional local or cloud AI.</strong></p>

            <p>
                Aegis Debug finds real bugs in your TypeScript and JavaScript code without sending
                anything to the cloud by default. Every finding is labeled with its
                source — engine-verified, local AI, or cloud AI — so you always know
                what you are trusting.
            </p>

            <h3>What's inside</h3>
            <ul>
                <li><strong>Language Support</strong> — Full static analysis and fixers for <strong>TypeScript & JavaScript</strong>; project graph and cycle detection for <strong>Kotlin & Java</strong>.</li>
                <li><strong>Seven deterministic analyzers</strong> —
                    syntax, compilation, null safety, state-before-init, async flow, circular dependencies, complexity.</li>
                <li><strong>Three deterministic fixers</strong> with diff preview and native undo.</li>
                <li><strong>NeuroMap</strong> — visual project graph with per-file issue overlay.</li>
                <li><strong>Engine status pill</strong> — know at a glance whether you're on static, local AI, or cloud AI.</li>
                <li><strong>Ollama (local)</strong> or <strong>OpenAI (cloud)</strong> — both optional, both off by default.</li>
                <li><strong>Secure key storage</strong> via IntelliJ PasswordSafe.</li>
            </ul>

            <h3>Privacy by default</h3>
            <ul>
                <li>No telemetry.</li>
                <li>No cloud uploads unless you configure them explicitly.</li>
                <li>Local mode works offline.</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "232.0"
            untilBuild = "261.*"
        }

        vendor {
            name = "Aegis Debug"
            email = "team@aegisdebug.dev"
            url = "https://aegisdebug.dev"
        }
    }

    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaUltimate, "2023.2.6")
            ide(IntelliJPlatformType.IntellijIdeaUltimate, "2024.1.6")
            ide(IntelliJPlatformType.IntellijIdeaUltimate, "2024.3.2.2")
            ide(IntelliJPlatformType.IntellijIdeaUltimate, "2025.1")
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
