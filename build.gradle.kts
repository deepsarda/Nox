plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    antlr
    idea
    id("org.graalvm.buildtools.native") version "0.11.1"
}

allprojects {
    version = rootProject.version
    group = rootProject.group
}

graalvmNative {
    toolchainDetection.set(true)
    binaries {
        all {
            verbose.set(true)
        }

        named("main") {
            imageName.set("nox")
            mainClass.set("nox.cli.NoxCliKt")
            buildArgs.add("-O3")
            buildArgs.add("--report-unsupported-elements-at-runtime")
            sharedLibrary.set(false)
            javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(21))
                    vendor.set(JvmVendorSpec.matching("GraalVM"))
                },
            )
        }

        create("noxc") {
            imageName.set("noxc")
            mainClass.set("nox.cli.NoxcCliKt")
            buildArgs.add("-O3")
            buildArgs.add("--report-unsupported-elements-at-runtime")
            sharedLibrary.set(false)
            javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(21))
                    vendor.set(JvmVendorSpec.matching("GraalVM"))
                },
            )
        }
    }
}

// JVM target
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

repositories {
    mavenCentral()
}

// JVM compatibility for Java sources (ANTLR-generated)
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    // ANTLR4: code-generation tool (used only during grammar processing)
    antlr(libs.antlr4.tool)

    // ANTLR4: runtime (shipped in the final JAR, needed by generated lexer/parser)
    implementation(libs.antlr4.runtime)

    implementation("net.java.dev.jna:jna:5.14.0")

    // Kotlin reflection: used by plugin system for annotation scanning and MethodHandle linking
    implementation(libs.kotlin.reflect)

    ksp(project(":nox-ksp"))

    // Kotlin coroutines: used for lightweight Sandbox execution (each Sandbox is a coroutine)
    implementation(libs.coroutines.core)

    // CLI framework (Clikt) and terminal UI (Mordant)
    implementation(libs.clikt)
    implementation(libs.mordant)

    // kotest-runner
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.coroutines.test)
    // Gradle 9 needs the launcher on the test runtime classpath to find engines
    testRuntimeOnly(libs.junit.launcher)
}

/*
 * ANTLR4 grammar generation
 *
 *  Grammar files live in:   src/main/antlr4/
 *  Generated Java sources:  build/generated-src/antlr/main/
 *
 *  We pass:
 *    -visitor      →  generate NoxParserVisitor + NoxParserBaseVisitor
 *    -no-listener  →  skip listener boilerplate (we use visitors in the ASTBuilder)
 *    -package      →  put generated classes in the nox.parser package
 */
val antlrSourceRoot =
    layout.buildDirectory
        .dir("generated-src/antlr/main")
        .get()
        .asFile

val antlrPackageDir = File(antlrSourceRoot, "nox/parser")

tasks.generateGrammarSource {
    outputDirectory = antlrPackageDir
    arguments = arguments +
        listOf(
            "-visitor",
            "-no-listener",
            "-package",
            "nox.parser",
            "-Werror", // Grammar warnings are errors
            "-Xlog", // Verbose grammar diagnostics
        )
}

// Make sure ANTLR runs before Kotlin compiles
tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource)
}

// ANTLR generates Java. The Java compiler also needs it.
tasks.compileJava {
    dependsOn(tasks.generateGrammarSource)
}

// Generate `nox.BuildInfo.VERSION` from rootProject.version so the version
// reported by `nox`, `noxc`, `nox-lsp`, and `noxfmt` --version flags is always
// the one Gradle is building
val buildInfoSourceRoot =
    layout.buildDirectory
        .dir("generated/source/buildInfo/main/kotlin")
        .get()
        .asFile

val generateBuildInfo by tasks.registering {
    val versionString = project.version.toString()
    inputs.property("version", versionString)
    outputs.dir(buildInfoSourceRoot)
    doLast {
        val file = File(buildInfoSourceRoot, "nox/BuildInfo.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package nox

            object BuildInfo {
                const val VERSION: String = "$versionString"
            }
            """.trimIndent() + "\n",
        )
    }
}

tasks.compileKotlin {
    dependsOn(generateBuildInfo)
}

// KSP reads the same source roots as compileKotlin, so it also needs the
// generated BuildInfo present before it runs.
tasks.matching { it.name == "kspKotlin" }.configureEach {
    dependsOn(generateBuildInfo)
}

sourceSets {
    main {
        antlr {
            // Tell the ANTLR plugin where our grammar files actually live
            setSrcDirs(listOf("src/main/antlr4"))
        }
        java {
            // Include the ANTLR-generated Java alongside regular Java sources
            srcDir(antlrSourceRoot)
        }
        kotlin {
            srcDir(antlrSourceRoot)
            srcDir(buildInfoSourceRoot)
        }
    }
}

// Tell IntelliJ to treat the ANTLR output as generated sources
idea {
    module {
        generatedSourceDirs.add(antlrSourceRoot)
    }
}

// Test runner
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // Enable virtual threads for coroutine tests (JVM 21+)
    jvmArgs("-XX:+EnableDynamicAgentLoading", "--enable-preview")
}

// ktlint, exclude ANTLR-generated Java (not our code)
ktlint {

    filter {
        exclude { entry -> entry.file.path.contains("generated-src") }
        exclude { entry -> entry.file.path.contains("antlr4") }
        exclude { entry -> entry.file.path.contains("generated/source/buildInfo") }
    }
}

// ktlint coverage configuration
kover {
    reports {
        filters {
            excludes {
                classes("nox.parser.*")
            }
        }
    }
}

tasks.withType<Test> {
    jvmArgs(
        "-agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image/nox-agent",
        "--enable-native-access=ALL-UNNAMED",
    )
    this.testLogging {
        this.showStandardStreams = true
    }
}

/**
 * Copy the canonical `llms.txt` (LLM-optimized Nox reference) to
 * `ai-cli/shared/NOX_LANGUAGE_REFERENCE.md` so every AI CLI integration
 * ships the same source-of-truth. CI's `check-ai-reference` job fails
 * if the committed copy drifts from `llms.txt`.
 */
tasks.register<Copy>("generateAiReference") {
    group = "nox"
    description = "Sync ai-cli/shared/NOX_LANGUAGE_REFERENCE.md from llms.txt"
    from(file("llms.txt"))
    into(file("ai-cli/shared"))
    rename { "NOX_LANGUAGE_REFERENCE.md" }
}

/**
 * Sync the `version` field of every `ai-cli/<integration>-extension.json`
 * manifest to `rootProject.version`. AI CLI integrations are bundled with the
 * language release, so they always carry the same version as the language
 * reference they ship.
 */
tasks.register("syncAiCliVersions") {
    group = "nox"
    description = "Sync version field in every ai-cli/*-extension.json to rootProject.version."
    val rootVersion = project.version.toString()
    val aiCliDir = file("ai-cli")
    inputs.property("version", rootVersion)
    doLast {
        aiCliDir
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith("-extension.json") }
            .forEach { f ->
                val text = f.readText()
                val updated =
                    text.replace(
                        Regex("\"version\"\\s*:\\s*\"[^\"]*\""),
                        "\"version\": \"$rootVersion\"",
                    )
                if (updated != text) f.writeText(updated)
            }
    }
}

/**
 * Run a Nox program.
 * Usage: ./gradlew nox --args="<file.nox> [options]"
 */
tasks.register<JavaExec>("nox") {
    group = "nox"
    description = "Runs a Nox program with interactive permission/resource prompts."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("nox.cli.NoxCliKt")
    jvmArgs("--enable-preview")
    standardInput = System.`in`
    dependsOn(tasks.compileKotlin)
}

/**
 * Compile a Nox source file and generate its .noxc disassembly.
 * Usage: ./gradlew noxc --args="<file.nox> [options]"
 */
tasks.register<JavaExec>("noxc") {
    group = "nox"
    description = "Compiles a Nox source file and generates its .noxc disassembly."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("nox.cli.NoxcCliKt")
    jvmArgs("--enable-preview")
    dependsOn(tasks.compileKotlin)
}
