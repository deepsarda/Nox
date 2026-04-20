plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover) apply false
    id("org.graalvm.buildtools.native") version "0.11.1" apply false
}

allprojects {
    version = rootProject.version
    group = rootProject.group
    repositories {
        mavenCentral()
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
                        Regex("\"version\"\\s*:\\s*\"[^\"]*\"", RegexOption.DOT_MATCHES_ALL),
                        "\"version\": \"${rootVersion}\"",
                    )
                if (updated != text) f.writeText(updated)
            }
    }
}
