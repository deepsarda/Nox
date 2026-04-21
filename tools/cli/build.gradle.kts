plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    id("org.graalvm.buildtools.native") version "0.11.1"
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.core)
    implementation(libs.clikt)
    implementation(libs.mordant)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.launcher)
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
                    languageVersion.set(JavaLanguageVersion.of(25))
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
                    languageVersion.set(JavaLanguageVersion.of(25))
                    vendor.set(JvmVendorSpec.matching("GraalVM"))
                },
            )
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

/**
 * Run a Nox program via the CLI module.
 * Usage: ./gradlew :tools:cli:nox --args="<file.nox> [options]"
 */
tasks.register<JavaExec>("nox") {
    group = "nox"
    description = "Runs a Nox program"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("nox.cli.NoxCliKt")
    standardInput = System.`in`
    dependsOn(tasks.named("classes"))
}

/**
 * Compile a Nox source file and generate its .noxc disassembly via the CLI module.
 * Usage: ./gradlew :tools:cli:noxc --args="<file.nox> [options]"
 */
tasks.register<JavaExec>("noxc") {
    group = "nox"
    description = "Compiles a Nox source file"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("nox.cli.NoxcCliKt")
    dependsOn(tasks.named("classes"))
}
