package nox.lsp

import nox.lsp.features.SemanticTokensProvider
import nox.lsp.protocol.*
import nox.lsp.protocol.JsonObject

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
            textService.notifyClient = { method: String, params: JsonObject? -> value?.notify(method, params) }
        }

    fun handleRequest(
        method: String,
        params: Any?,
    ): Any? {
        // Here we do synchronous dispatch. TODO: might use CompletableFutures.
        // For simplicity, we just block and wait for the async results.
        return when (method) {
            "initialize" -> {
                val p = parseInitializeParams(params as JsonObject)
                initialize(p).toJson()
            }
            "shutdown" -> {
                shutdown()
                null
            }

            // Text Document Sync
            "textDocument/hover" ->
                textService.hover(parseHoverParams(params as JsonObject))?.toJson()
            "textDocument/definition" ->
                textService.definition(parseDefinitionParams(params as JsonObject))?.toJson()
            "textDocument/references" ->
                textService.references(parseReferenceParams(params as JsonObject))?.toJson()
            "textDocument/rename" ->
                textService.rename(parseRenameParams(params as JsonObject))?.toJson()
            "textDocument/prepareRename" ->
                textService.prepareRename(parsePrepareRenameParams(params as JsonObject))?.toJson()
            "textDocument/documentSymbol" ->
                textService.documentSymbol(parseDocumentSymbolParams(params as JsonObject))?.toJson()
            "textDocument/foldingRange" ->
                textService.foldingRange(parseFoldingRangeRequestParams(params as JsonObject))?.toJson()
            "textDocument/semanticTokens/full" ->
                textService.semanticTokensFull(parseSemanticTokensParams(params as JsonObject))?.toJson()
            "textDocument/inlayHint" ->
                textService.inlayHint(parseInlayHintParams(params as JsonObject))?.toJson()
            "textDocument/formatting" ->
                textService.formatting(parseDocumentFormattingParams(params as JsonObject))?.toJson()
            "textDocument/completion" ->
                textService.completion(parseCompletionParams(params as JsonObject))?.toJson()
            "textDocument/signatureHelp" ->
                textService.signatureHelp(parseSignatureHelpParams(params as JsonObject))?.toJson()
            "textDocument/codeAction" ->
                textService.codeAction(parseCodeActionParams(params as JsonObject))?.toJson()

            else -> null
        }
    }

    fun handleNotification(
        method: String,
        params: Any?,
    ) {
        when (method) {
            "initialized" -> {}
            "exit" -> exit()

            "textDocument/didOpen" ->
                textService.didOpen(
                    parseDidOpenTextDocumentParams(params as JsonObject),
                )
            "textDocument/didChange" ->
                textService.didChange(
                    parseDidChangeTextDocumentParams(params as JsonObject),
                )
            "textDocument/didClose" ->
                textService.didClose(
                    parseDidCloseTextDocumentParams(params as JsonObject),
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
}
