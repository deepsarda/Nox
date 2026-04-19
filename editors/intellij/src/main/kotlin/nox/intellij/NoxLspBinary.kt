package nox.intellij

import java.io.File

/**
 * Resolves the `nox-lsp` binary for both the JetBrains LSP and LSP4IJ adapters.
 * Search order: First we check the settings panel, `nox.lsp.path` system property then `NOX_LSP` env var then `PATH`.
 */
object NoxLspBinary {
    fun resolve(): String? {
        runCatching { NoxSettings.instance().lspPath }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        System.getProperty("nox.lsp.path")?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv("NOX_LSP")?.takeIf { it.isNotBlank() }?.let { return it }
        val exe =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "nox-lsp.exe"
            } else {
                "nox-lsp"
            }
        System
            .getenv("PATH")
            ?.split(File.pathSeparatorChar)
            ?.map { File(it, exe) }
            ?.firstOrNull { it.canExecute() }
            ?.let { return it.absolutePath }
        return null
    }

    const val NOT_FOUND_MESSAGE: String =
        "nox-lsp binary not found. Set `nox.lsp.path` in settings, the `NOX_LSP` env var, " +
            "or put nox-lsp on your PATH."
}
