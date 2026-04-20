package nox.lsp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import nox.lsp.features.SemanticTokensProvider
import nox.lsp.protocol.*

/**
 * Nox language server. Advertises every capability we implement so clients (VSCode,
 * IntelliJ's LSP bridge, Neovim, Zed) enable the matching UI automatically.
 */
class NoxLanguageServer {
    val docs = DocumentManager()
    val cache = AnalysisCache()
    val textService = NoxTextDocumentService(docs, cache)

    var client: JsonRpcServer? = null
        set(value) {
            field = value
            textService.notifyClient = { method: String, params: JsonElement -> value?.notify(method, params) }
        }

    fun handleRequest(
        method: String,
        params: JsonElement?,
    ): JsonElement? {
        // Here we do synchronous dispatch. TODO: might use CompletableFutures.
        // For simplicity, we just block and wait for the async results.
        return when (method) {
            "initialize" -> {
                val p = json.decodeFromJsonElement<InitializeParams>(params!!)
                json.encodeToJsonElement(initialize(p))
            }
            "shutdown" -> {
                shutdown()
                null
            }

            // Text Document Sync
            "textDocument/hover" ->
                json.encodeToJsonElement(
                    textService.hover(json.decodeFromJsonElement<HoverParams>(params!!)),
                )
            "textDocument/definition" ->
                json.encodeToJsonElement(
                    textService.definition(json.decodeFromJsonElement<DefinitionParams>(params!!)),
                )
            "textDocument/references" ->
                json.encodeToJsonElement(
                    textService.references(json.decodeFromJsonElement<ReferenceParams>(params!!)),
                )
            "textDocument/rename" ->
                json.encodeToJsonElement(
                    textService.rename(json.decodeFromJsonElement<RenameParams>(params!!)),
                )
            "textDocument/prepareRename" ->
                json.encodeToJsonElement(
                    textService.prepareRename(json.decodeFromJsonElement<PrepareRenameParams>(params!!)),
                )
            "textDocument/documentSymbol" ->
                json.encodeToJsonElement(
                    textService.documentSymbol(json.decodeFromJsonElement<DocumentSymbolParams>(params!!)),
                )
            "textDocument/foldingRange" ->
                json.encodeToJsonElement(
                    textService.foldingRange(json.decodeFromJsonElement<FoldingRangeRequestParams>(params!!)),
                )
            "textDocument/semanticTokens/full" ->
                json.encodeToJsonElement(
                    textService.semanticTokensFull(json.decodeFromJsonElement<SemanticTokensParams>(params!!)),
                )
            "textDocument/inlayHint" ->
                json.encodeToJsonElement(
                    textService.inlayHint(json.decodeFromJsonElement<InlayHintParams>(params!!)),
                )
            "textDocument/formatting" ->
                json.encodeToJsonElement(
                    textService.formatting(json.decodeFromJsonElement<DocumentFormattingParams>(params!!)),
                )
            "textDocument/completion" ->
                json.encodeToJsonElement(
                    textService.completion(json.decodeFromJsonElement<CompletionParams>(params!!)),
                )
            "textDocument/signatureHelp" ->
                json.encodeToJsonElement(
                    textService.signatureHelp(json.decodeFromJsonElement<SignatureHelpParams>(params!!)),
                )
            "textDocument/codeAction" ->
                json.encodeToJsonElement(
                    textService.codeAction(json.decodeFromJsonElement<CodeActionParams>(params!!)),
                )

            else -> null
        }
    }

    fun handleNotification(
        method: String,
        params: JsonElement?,
    ) {
        when (method) {
            "initialized" -> {}
            "exit" -> exit()

            "textDocument/didOpen" ->
                textService.didOpen(
                    json.decodeFromJsonElement<DidOpenTextDocumentParams>(params!!),
                )
            "textDocument/didChange" ->
                textService.didChange(
                    json.decodeFromJsonElement<DidChangeTextDocumentParams>(params!!),
                )
            "textDocument/didClose" ->
                textService.didClose(
                    json.decodeFromJsonElement<DidCloseTextDocumentParams>(params!!),
                )

            "$/cancelRequest" -> {} // Ignore for now
            "$/setTrace" -> {} // Ignore
        }
    }

    private fun initialize(params: InitializeParams): InitializeResult {
        val caps =
            ServerCapabilities(
                textDocumentSync = 1, // Full
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
                semanticTokensProvider =
                    SemanticTokensOptions(
                        legend =
                            SemanticTokensLegend(
                                tokenTypes = SemanticTokensProvider.LEGEND.tokenTypes,
                                tokenModifiers = SemanticTokensProvider.LEGEND.tokenModifiers,
                            ),
                        full = true,
                    ),
            )
        return InitializeResult(caps)
    }

    private fun shutdown(): Any? = null

    private fun exit() {
        System.exit(0)
    }

    companion object {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
