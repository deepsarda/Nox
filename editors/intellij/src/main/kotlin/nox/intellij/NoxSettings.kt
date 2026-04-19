package nox.intellij

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level settings for the Nox plugin. Currently exposes a single
 * configurable: an explicit override path for the `nox-lsp` binary, which
 * takes precedence over the `NOX_LSP` env var and `PATH` lookup.
 */
@State(name = "NoxSettings", storages = [Storage("nox.xml")])
@Service(Service.Level.APP)
class NoxSettings : PersistentStateComponent<NoxSettings.State> {
    enum class LspBackend { AUTO, JETBRAINS, LSP4IJ }

    data class State(
        var lspPath: String = "",
        var lspBackend: LspBackend = LspBackend.AUTO,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var lspPath: String
        get() = state.lspPath
        set(value) {
            state.lspPath = value
        }

    var lspBackend: LspBackend
        get() = state.lspBackend
        set(value) {
            state.lspBackend = value
        }

    /**
     * Whether a given LSP backend should activate. Only one backend runs at a
     * time so a user with both the JetBrains LSP module (Ultimate) and LSP4IJ
     * installed does not get two language servers spawning for the same file.
     *
     * - AUTO: JetBrains wins when its module is present, else LSP4IJ.
     * - JETBRAINS / LSP4IJ: explicit pin.
     */
    fun shouldActivate(backend: LspBackend): Boolean {
        val pref = state.lspBackend
        if (pref == backend) return true
        if (pref != LspBackend.AUTO) return false
        return when (backend) {
            LspBackend.JETBRAINS -> true
            LspBackend.LSP4IJ -> !isJetBrainsLspModuleLoaded()
            LspBackend.AUTO -> false
        }
    }

    private fun isJetBrainsLspModuleLoaded(): Boolean =
        runCatching {
            Class.forName(
                "com.intellij.platform.lsp.api.LspServerSupportProvider",
                false,
                NoxSettings::class.java.classLoader,
            )
        }.isSuccess

    companion object {
        fun instance(): NoxSettings = service()
    }
}
