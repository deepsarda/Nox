plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    id("org.graalvm.buildtools.native") version "0.11.1"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

graalvmNative {
    toolchainDetection.set(true)
    binaries {
        named("main") {
            imageName.set("nox-lsp")
            mainClass.set("nox.lsp.NoxLspCliKt")
            buildArgs.add("-O3")
            buildArgs.add("--report-unsupported-elements-at-runtime")
            buildArgs.add("--initialize-at-build-time=kotlin")
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

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    implementation(project(":core"))
    implementation(project(":tools:format"))
    implementation(libs.clikt)
    implementation(libs.coroutines.core)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
