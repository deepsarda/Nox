import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2024.2.4")
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(libs.kotest.assertions)
}

intellijPlatform {
    pluginConfiguration {
        id = "nox-lang"
        name = "Nox Language"
        version = project.findProperty("pluginVersion")?.toString() ?: "0.1.0"

        description = """
            <h3>Nox language support for IntelliJ IDEA.</h3>
            <p>Powered by <code>nox-lsp</code>. Provides diagnostics, hover, completion,
            go-to-definition, find usages, rename, semantic highlighting, inlay hints,
            quick-fixes, and code formatting for <code>.nox</code> files.</p>
            <p>Requires the <code>nox-lsp</code> binary on PATH or configured in settings.</p>
        """.trimIndent()

        changeNotes = "<p>Initial release.</p>"

        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }

        vendor {
            name = "Nox Language"
            url = "https://github.com/DeepSarda/nox"
        }
    }

    signing {
        certificateChainFile = project.layout.projectDirectory.file("secrets/chain.crt").asFile
            .takeIf { it.exists() }?.let { project.layout.projectDirectory.file("secrets/chain.crt") }
        privateKeyFile = project.layout.projectDirectory.file("secrets/private.pem").asFile
            .takeIf { it.exists() }?.let { project.layout.projectDirectory.file("secrets/private.pem") }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
        channels = listOf(
            project.findProperty("pluginChannel")?.toString() ?: "default",
        )
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
