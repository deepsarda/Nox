package nox.lsp

import kotlinx.serialization.encodeToString
import nox.lsp.protocol.*
import nox.lsp.features.SemanticTokensProvider

fun main() {
    val caps = ServerCapabilities(
        textDocumentSync = 1,
        hoverProvider = true,
        definitionProvider = true,
        referencesProvider = true,
        documentSymbolProvider = true,
        documentFormattingProvider = true,
        foldingRangeProvider = true,
        inlayHintProvider = true,
        completionProvider = CompletionOptions(triggerCharacters = listOf(".")),
        signatureHelpProvider = SignatureHelpOptions(triggerCharacters = listOf("(", ",")),
        codeActionProvider = true,
        renameProvider = true,
        callHierarchyProvider = true,
        semanticTokensProvider = SemanticTokensOptions(
            legend = SemanticTokensLegend(
                tokenTypes = SemanticTokensProvider.LEGEND.tokenTypes,
                tokenModifiers = SemanticTokensProvider.LEGEND.tokenModifiers
            ),
            full = true
        )
    )
    val res = InitializeResult(caps)
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    println(json.encodeToString(res))
}
