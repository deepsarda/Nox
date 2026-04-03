plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    antlr
    idea
}

// JVM target
kotlin {
    jvmToolchain(25)
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

    // Kotlin reflection: used by plugin system for annotation scanning and MethodHandle linking
    implementation(libs.kotlin.reflect)

    // ClassGraph: fast classpath scanner for auto-discovering @NoxModule plugins
    implementation(libs.classgraph)

    // Kotlin coroutines: used for lightweight Sandbox execution (each Sandbox is a coroutine)
    implementation(libs.coroutines.core)

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
    this.testLogging {
        this.showStandardStreams = true
    }
}

/**
 * Compile a Nox source file and generate its .noxc disassembly.
 * Usage: ./gradlew noxc -Pfile=path/to/file.nox
 */
tasks.register<JavaExec>("noxc") {
    group = "nox"
    description = "Compiles a Nox source file and generates its .noxc disassembly."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("nox.compiler.NoxcApp")

    // Pass the file argument from the command line property 'file'
    if (project.hasProperty("file")) {
        args(project.property("file") as String)
    }

    // Ensure compilation happens before execution
    dependsOn(tasks.compileKotlin)
}
