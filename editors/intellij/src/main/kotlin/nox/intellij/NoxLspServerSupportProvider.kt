package nox.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider

/**
 * Registers `nox-lsp` for `.nox` files. Binary discovery order:
 *   1. `nox.lsp.path` project / application property (set in settings).
 *   2. `NOX_LSP` environment variable.
 *   3. `nox-lsp` on `PATH`.
 *
 * When none resolve, the platform surfaces "server not started".
 */
class NoxLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (file.extension != "nox") return
        if (!NoxSettings.instance().shouldActivate(NoxSettings.LspBackend.JETBRAINS)) return
        serverStarter.ensureServerStarted(NoxLspServerDescriptor(project))
    }
}
