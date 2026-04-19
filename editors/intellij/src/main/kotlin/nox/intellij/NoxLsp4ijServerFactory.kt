package nox.intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

/**
 * LSP4IJ-side adapter for `nox-lsp`. Loaded only when the user has the LSP4IJ
 * plugin installed (gated by the optional dependency in `lsp-lsp4ij.xml`).
 */
class NoxLsp4ijServerFactory : LanguageServerFactory {
    override fun createConnectionProvider(project: Project): StreamConnectionProvider {
        if (!NoxSettings.instance().shouldActivate(NoxSettings.LspBackend.LSP4IJ)) {
            error(
                "LSP4IJ Nox server suppressed: another LSP backend is preferred. " +
                    "Change Settings | Tools | Nox | LSP backend to 'LSP4IJ' to use it here.",
            )
        }
        return NoxLsp4ijConnectionProvider()
    }
}

private class NoxLsp4ijConnectionProvider : OSProcessStreamConnectionProvider() {
    init {
        val binary = NoxLspBinary.resolve() ?: error(NoxLspBinary.NOT_FOUND_MESSAGE)
        commandLine = GeneralCommandLine(binary).withCharset(Charsets.UTF_8)
    }
}
