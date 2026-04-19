import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

// IntelliJ plugin releases on its own cadence
version = project.findProperty("pluginVersion")?.toString() ?: version

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
        bundledPlugin("org.jetbrains.plugins.textmate")
        plugin("com.redhat.devtools.lsp4ij", "0.19.3")
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(libs.kotest.assertions)
}

val vscodeSyntaxes = rootProject.layout.projectDirectory.dir("editors/vscode/syntaxes")
val textmateBundleDir = layout.buildDirectory.dir("generated/textmate/Nox.tmbundle")

val copyTextMateGrammars =
    tasks.register<Copy>("copyTextMateGrammars") {
        from(vscodeSyntaxes) {
            include("*.tmLanguage.json")
        }
        into(textmateBundleDir.map { it.dir("syntaxes") })
    }

val generateTextMateManifest =
    tasks.register("generateTextMateManifest") {
        val outFile = textmateBundleDir.map { it.file("package.json") }
        outputs.file(outFile)
        doLast {
            outFile.get().asFile.writeText(
                """
                {
                  "name": "nox",
                  "displayName": "Nox Language",
                  "version": "${project.version}",
                  "publisher": "nox-lang",
                  "engines": { "vscode": "^1.82.0" },
                  "contributes": {
                    "languages": [
                      { "id": "nox",  "extensions": [".nox"],  "aliases": ["Nox"] },
                      { "id": "noxc", "extensions": [".noxc"], "aliases": ["Nox Disassembly"] }
                    ],
                    "grammars": [
                      { "language": "nox",  "scopeName": "source.nox",  "path": "./syntaxes/nox.tmLanguage.json" },
                      { "language": "noxc", "scopeName": "source.noxc", "path": "./syntaxes/noxc.tmLanguage.json" }
                    ]
                  }
                }
                """.trimIndent(),
            )
        }
    }

sourceSets["main"].resources.srcDir(
    layout.buildDirectory.dir("generated/textmate").map { it.asFile },
)

tasks.named("processResources") {
    dependsOn(copyTextMateGrammars, generateTextMateManifest)
}

intellijPlatform {
    pluginConfiguration {
        id = "nox-lang"
        name = "Nox Language"
        version = project.findProperty("pluginVersion")?.toString() ?: "0.1.0"

        description =
            """
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
        certificateChainFile =
            project.layout.projectDirectory
                .file("secrets/chain.crt")
                .asFile
                .takeIf { it.exists() }
                ?.let { project.layout.projectDirectory.file("secrets/chain.crt") }
        privateKeyFile =
            project.layout.projectDirectory
                .file("secrets/private.pem")
                .asFile
                .takeIf { it.exists() }
                ?.let { project.layout.projectDirectory.file("secrets/private.pem") }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
        channels =
            listOf(
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
