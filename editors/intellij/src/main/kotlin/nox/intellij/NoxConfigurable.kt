package nox.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

class NoxConfigurable : BoundConfigurable("Nox") {
    override fun createPanel(): DialogPanel {
        val settings = NoxSettings.instance()
        return panel {
            row("nox-lsp binary path:") {
                cell(
                    TextFieldWithBrowseButton().apply {
                        addBrowseFolderListener(
                            "Select nox-lsp Binary",
                            "Path to the nox-lsp executable. Leave empty to use the NOX_LSP env var or PATH.",
                            null,
                            FileChooserDescriptorFactory.createSingleFileDescriptor(),
                        )
                    },
                ).bindText(settings::lspPath)
                    .resizableColumn()
                    .comment(
                        "Overrides the <code>NOX_LSP</code> environment variable and " +
                            "<code>PATH</code> lookup. Restart the LSP after changing.",
                    )
            }

            row {
                button("Download/Update LSP") {
                    ProgressManager.getInstance().run(
                        object : Task.Backgroundable(null, "Downloading Nox LSP", true) {
                            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                                try {
                                    val path =
                                        NoxLspBinary.downloadAndInstallLatest { status ->
                                            indicator.text = status
                                        }
                                    ApplicationManager.getApplication().invokeLater {
                                        if (path != null) {
                                            Messages.showInfoMessage(
                                                "Successfully downloaded and installed Nox LSP to:\n$path\n\nLeave the path field empty to use this version. Please restart the LSP/IDE to apply.",
                                                "Nox LSP",
                                            )
                                        } else {
                                            Messages.showErrorDialog(
                                                "Failed to verify download of Nox LSP binary.",
                                                "Nox LSP Error",
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    ApplicationManager.getApplication().invokeLater {
                                        Messages.showErrorDialog(
                                            "Failed to download Nox LSP: ${e.message}",
                                            "Nox LSP Error",
                                        )
                                    }
                                }
                            }
                        },
                    )
                }.comment(
                    "Downloads and installs the latest Nox toolchain binaries " +
                        "(nox, noxc, nox-lsp, noxfmt) to <code>~/.nox/bin/</code>.",
                )
            }

            row("LSP backend:") {
                comboBox(NoxSettings.LspBackend.entries.toList())
                    .bindItem(
                        getter = { settings.lspBackend },
                        setter = { settings.lspBackend = it ?: NoxSettings.LspBackend.AUTO },
                    ).comment(
                        "Which LSP integration to drive <code>nox-lsp</code>.<br/>" +
                            "<b>Auto</b>: prefer the IDE's built-in LSP module (Ultimate-tier IDEs); " +
                            "fall back to LSP4IJ when it isn't bundled.<br/>" +
                            "<b>Jetbrains</b>: only the built-in LSP module (Ultimate-only).<br/>" +
                            "<b>Lsp4ij</b>: only the LSP4IJ plugin. Requires LSP4IJ to be installed.<br/>" +
                            "Restart the LSP after changing.",
                    )
            }
        }
    }
}
