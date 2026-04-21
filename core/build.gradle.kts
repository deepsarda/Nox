plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    antlr
    idea
}

// JVM target
kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

repositories {
    mavenCentral()
}

// JVM compatibility for Java sources (ANTLR-generated)
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    // ANTLR4: code-generation tool (used only during grammar processing)
    antlr(libs.antlr4.tool)

    // ANTLR4: runtime (shipped in the final JAR, needed by generated lexer/parser)
    implementation(libs.antlr4.runtime)

    // JNA: used by ExternalPluginBridge for Tier 1 C plugins
    implementation("net.java.dev.jna:jna:5.14.0")

    ksp(project(":tools:ksp"))

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
    val versionString = rootProject.version.toString()
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
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

// ktlint, exclude ANTLR-generated Java (not our code)
ktlint {
    filter {
        exclude { entry -> entry.file.path.contains("generated-src") }
        exclude { entry -> entry.file.path.contains("antlr4") }
        exclude { entry -> entry.file.path.contains("generated/source/buildInfo") }
        exclude { entry -> entry.file.path.contains("build") }
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
        "--enable-native-access=ALL-UNNAMED",
    )
    this.testLogging {
        this.showStandardStreams = true
    }
}
