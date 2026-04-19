package nox.intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor

class NoxLspServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "Nox") {
    override fun isSupportedFile(file: VirtualFile): Boolean = file.extension == "nox"

    override fun createCommandLine(): GeneralCommandLine {
        val binary = resolveLspBinary()
            ?: error(
                "nox-lsp binary not found. Set `nox.lsp.path` in settings, the `NOX_LSP` env var, " +
                    "or put nox-lsp on your PATH.",
            )
        return GeneralCommandLine(binary).withCharset(Charsets.UTF_8)
    }

    private fun resolveLspBinary(): String? {
        System.getProperty("nox.lsp.path")?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv("NOX_LSP")?.takeIf { it.isNotBlank() }?.let { return it }
        val exe = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "nox-lsp.exe" else "nox-lsp"
        System.getenv("PATH")?.split(java.io.File.pathSeparatorChar)
            ?.map { java.io.File(it, exe) }
            ?.firstOrNull { it.canExecute() }
            ?.let { return it.absolutePath }
        return null
    }
}
