import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.14.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

group = "com.ghostdebugger"
version = "3.0.0"

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
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
        // TestFrameworkType.Plugin.Kotlin does not exist in intellij-platform-gradle-plugin 2.14.0.
        // Kotlin test fixtures are provided by the bundled "org.jetbrains.kotlin" plugin (declared
        // above via bundledPlugin) plus the kotlin-test / kotlin-test-junit5 testImplementation
        // entries already in this file. No separate testFramework line needed; revisit if/when
        // the Gradle plugin exposes TestFrameworkType.Plugin.Kotlin in a future release.
    }

    // Kotlin Coroutines — coroutines-core is compileOnly because the platform ships a
    // forked version with extra methods; bundling it clobbers the fork at runtime.
    // -swing and -jdk8 are thin wrappers over core and must be on the runtime classpath.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Kotlin Serialization (JSON) + YAML front-end for custom rules
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.charleskorn.kaml:kaml:0.61.0")

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

// Belt-and-suspenders for the fork collision explained above: transitive coroutines-core
// pulled in by anything else (e.g. test libs) still breaks platform internals like
// UnindexedFilesScanner with NoSuchMethodError, so strip it from runtime classpaths entirely.
configurations.matching { it.name in listOf("runtimeClasspath", "testRuntimeClasspath") }
    .configureEach {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
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

            <p><strong>Static-first analysis, unconditional. Fix engine and AI augmentation: implemented, gated in 3.0.0.</strong></p>

            <p>
                Aegis Debug finds real bugs in your TypeScript, JavaScript, Kotlin, and Java code
                without sending anything to the cloud — no AI provider is reachable in this
                release, so there is nothing to configure that results in a network request.
                3.0.0 ships as a read-only analysis and visualization tool: several implemented,
                tested capabilities are gated off rather than shipped half-finished. See
                <code>docs/IMPLEMENTATION_STATUS.md</code> in the repository for the full
                breakdown of what's live versus gated, and why.
            </p>

            <h3>What's inside</h3>
            <ul>
                <li><strong>Language Support</strong> — Full line-oriented lexical analysis (regex-based, string/comment-masked) for <strong>TypeScript & JavaScript</strong>; type-aware Analysis API analyzers for <strong>Kotlin</strong>; PSI symbol extraction for <strong>Java</strong>. Dependency-graph edges resolve for relative imports only, so cycle detection and impact analysis reach TS/JS today.</li>
                <li><strong>12 analyzers (11 built-in rules + a custom-rule engine)</strong> —
                    syntax, compilation, null safety (TS/JS/Kotlin), state-before-init, async flow, circular dependencies, complexity, unsafe-cast (Kotlin), type-mismatch (Kotlin), redundant-let (Kotlin), plus a dispatcher for user-authored YAML rules. Runs unconditionally.</li>
                <li><strong>8 deterministic fixers</strong>, PSI-validity-gated — implemented and tested, but fix <em>application</em> is gated in 3.0.0.</li>
                <li><strong>NeuroMap</strong> — visual project graph with per-file issue overlay.</li>
                <li><strong>Engine status pill</strong> — know at a glance whether you're on static, local AI, or cloud AI.</li>
                <li><strong>Ollama (local)</strong> and <strong>OpenAI (cloud)</strong> backends exist and are tested in isolation; the AI analysis pass and AI explanations are both gated off in 3.0.0.</li>
                <li><strong>Secure key storage</strong> via IntelliJ PasswordSafe.</li>
            </ul>

            <h3>Privacy by default</h3>
            <ul>
                <li>No telemetry.</li>
                <li>No AI provider reachable in this release — the cloud-upload consent check is implemented and enforced for when AI is re-enabled.</li>
                <li>Fully local, read-only in 3.0.0.</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "243.0"
            untilBuild = "262.*"
        }

        vendor {
            name = "Aegis Debug"
            email = "team@aegisdebug.dev"
            url = "https://aegisdebug.dev"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2024.3.2.2")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.1")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2")
        }
    }
}

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val npmCmd = if (isWindows) listOf("cmd", "/c", "npm") else listOf("npm")

tasks {
    test {
        useJUnitPlatform()

        // DocumentationCountsTest, NoDeadSettingsTest, ReportGeneratorXssTest, and
        // DemoSampleFindingsTest all read these paths at runtime via java.io.File(...).readText()
        // rather than through the compiled classpath, so Gradle's normal task-input tracking
        // (source files -> compileKotlin -> classes -> test classpath) never sees them. Without
        // declaring them explicitly, the build cache can restore a cached PASS for `:test` after
        // one of these files changes underneath it - including a plain `./gradlew test`, not just
        // `cleanTest` or an incremental run - which is the exact defect this task exists to
        // eliminate, reproduced inside its own guard. Discovered during Task 11's fix rounds; see
        // docs/IMPLEMENTATION_STATUS.md. Keep this list in sync with grep -rn 'File("' src/test.
        inputs.files(
            "README.md",
            "docs/IMPLEMENTATION_STATUS.md",
            "site/index.html",
            "src/main/resources/META-INF/plugin.xml",
            "src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt",
            "src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerConfigurable.kt",
            "src/main/kotlin/com/ghostdebugger/ReportGenerator.kt",
        )
            .withPropertyName("runtimeFileReadDocsAndSourceInputs")
            .withPathSensitivity(PathSensitivity.RELATIVE)

        // DemoSampleFindingsTest reads every file under here by name (File(sampleSrcDir, name)),
        // not a fixed list this task could enumerate, so it's declared as a directory input.
        inputs.dir("samples/aegis-demo/src")
            .withPropertyName("demoSampleSourceInputs")
            .withPathSensitivity(PathSensitivity.RELATIVE)
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

// ── Detekt static-analysis quality gate ───────────────────────────────────────
// Run via `./gradlew detekt` — fails on findings outside the baseline. Implemented as a
// JavaExec over detekt-cli (not the Detekt Gradle plugin) to stay decoupled from that
// plugin's Gradle-version compatibility matrix (this build is on Gradle 9). After
// intentionally accepting new findings, regenerate the baseline with `./gradlew detektBaseline`.
// Rules disabled because they conflict with project conventions (e.g. the PCE-rethrow idiom)
// or are pure style noise are documented in config/detekt/detekt.yml.
val detektCli: Configuration by configurations.creating
dependencies {
    detektCli("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
}
fun detektArgs(extra: List<String>): List<String> = listOf(
    "--input", "src/main/kotlin",
    "--config", "config/detekt/detekt.yml",
    "--build-upon-default-config",
    "--baseline", "config/detekt/baseline.xml",
) + extra
tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Detekt static analysis on main sources; fails on new findings outside the baseline."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    args = detektArgs(emptyList())
}
tasks.register<JavaExec>("detektBaseline") {
    group = "verification"
    description = "Regenerates config/detekt/baseline.xml (run after intentionally accepting findings)."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    args = detektArgs(listOf("--create-baseline"))
}
