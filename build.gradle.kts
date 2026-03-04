plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    antlr
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
val antlrOutputDir =
    layout.buildDirectory
        .dir("generated-src/antlr/main")
        .get()
        .asFile

tasks.generateGrammarSource {
    outputDirectory = antlrOutputDir
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
            srcDir(antlrOutputDir)
        }
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
    version.set("1.5.0")
    filter {
        exclude { entry -> entry.file.path.contains("generated-src") }
        exclude { entry -> entry.file.path.contains("antlr4") }
    }
}
