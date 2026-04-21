package nox.lsp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nox.lsp.conversions.Diagnostics
import nox.lsp.features.*
import nox.lsp.protocol.*
import java.util.concurrent.ConcurrentHashMap

class NoxTextDocumentService(
    private val docs: DocumentManager,
    private val cache: AnalysisCache,
) {
    @Volatile
    var notifyClient: ((String, JsonObject?) -> Unit)? = null

    private val debounceJobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(Dispatchers.Default)

    fun didOpen(params: DidOpenTextDocumentParams) {
        val td = params.textDocument
        docs.open(td.uri, td.version, td.text)
        publishDiagnostics(td.uri)
    }

    fun didChange(params: DidChangeTextDocumentParams) {
        val doc = docs.get(params.textDocument.uri) ?: return
        val newText = params.contentChanges.firstOrNull()?.text ?: doc.text
        docs.replace(doc.uri, params.textDocument.version ?: 0, newText)

        debounceJobs[doc.uri]?.cancel()
        debounceJobs[doc.uri] =
            scope.launch {
                delay(250)
                publishDiagnostics(doc.uri)
            }
    }

    fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        docs.close(uri)
        cache.invalidate(uri)
        debounceJobs.remove(uri)?.cancel()
        sendDiagnostics(uri, emptyList())
    }

    fun didSave(params: Any) = Unit

    fun hover(params: HoverParams): Hover? {
        val typed = typed(params.textDocument.uri) ?: return null
        return HoverProvider.hover(typed, params.position.line, params.position.character)
    }

    fun definition(params: DefinitionParams): List<Location> {
        val doc = docs.get(params.textDocument.uri) ?: return emptyList()
        val result = cache.analyze(docs, doc)
        val typed = result.typedProgram ?: return emptyList()
        return DefinitionProvider.definition(
            typed,
            result.program,
            doc.uri,
            params.position.line,
            params.position.character,
        )
    }

    fun references(params: ReferenceParams): List<Location> {
        val doc = docs.get(params.textDocument.uri) ?: return emptyList()
        val result = cache.analyze(docs, doc)
        val typed = result.typedProgram ?: return emptyList()
        return ReferencesProvider.references(
            typed = typed,
            raw = result.program,
            uri = doc.uri,
            lspLine = params.position.line,
            lspColumn = params.position.character,
            includeDeclaration = params.context.includeDeclaration,
        )
    }

    fun rename(params: RenameParams): WorkspaceEdit? {
        val doc = docs.get(params.textDocument.uri) ?: return null
        val result = cache.analyze(docs, doc)
        val typed = result.typedProgram ?: return null
        return RenameProvider.rename(
            typed,
            result.program,
            doc.uri,
            params.position.line,
            params.position.character,
            params.newName,
        )
    }

    fun prepareRename(params: PrepareRenameParams): PrepareRenameResult? {
        val doc = docs.get(params.textDocument.uri) ?: return null
        val result = cache.analyze(docs, doc)
        val typed = result.typedProgram ?: return null
        val expr =
            nox.lsp.features.ExprAtPosition
                .find(typed, params.position.line, params.position.character) ?: return null
        val name =
            when (expr) {
                is nox.compiler.ast.typed.TypedIdentifierExpr -> expr.name
                is nox.compiler.ast.typed.TypedFuncCallExpr -> expr.name
                is nox.compiler.ast.typed.TypedFieldAccessExpr -> expr.fieldName
                else -> return null
            }
        return PrepareRenameResult(
            nox.lsp.conversions.Positions
                .toLspRange(expr.loc, length = name.length),
            name,
        )
    }

    fun documentSymbol(params: DocumentSymbolParams): List<DocumentSymbol> {
        val doc = docs.get(params.textDocument.uri) ?: return emptyList()
        val result = cache.analyze(docs, doc)
        return DocumentSymbols.collect(result.program)
    }

    fun foldingRange(params: FoldingRangeRequestParams): List<FoldingRange> {
        val doc = docs.get(params.textDocument.uri) ?: return emptyList()
        val result = cache.analyze(docs, doc)
        return FoldingRanges.collect(result.program, doc.text)
    }

    fun semanticTokensFull(params: SemanticTokensParams): SemanticTokens {
        val doc = docs.get(params.textDocument.uri) ?: return SemanticTokens(emptyList())
        val result = cache.analyze(docs, doc)
        val raw = SemanticTokensProvider.fullFile(result.typedProgram ?: return SemanticTokens(emptyList()))
        return SemanticTokens(raw.data)
    }

    fun inlayHint(params: InlayHintParams): List<InlayHint> {
        val doc = docs.get(params.textDocument.uri) ?: return emptyList()
        val result = cache.analyze(docs, doc)
        val typed = result.typedProgram ?: return emptyList()
        return InlayHintsProvider.hintsFor(typed)
    }

    fun formatting(params: DocumentFormattingParams): List<TextEdit> {
        val doc = docs.get(params.textDocument.uri) ?: return emptyList()
        return FormattingProvider.format(doc.text, doc.uri)
    }

    fun completion(params: CompletionParams): CompletionList {
        val doc = docs.get(params.textDocument.uri) ?: return CompletionList(false, emptyList())
        val result = cache.analyze(docs, doc)
        val items =
            CompletionProvider.complete(
                result,
                doc.text,
                params.position.line,
                params.position.character,
                uri = doc.uri,
            )
        return CompletionList(isIncomplete = false, items = items)
    }

    fun signatureHelp(params: SignatureHelpParams): SignatureHelp? {
        val doc = docs.get(params.textDocument.uri) ?: return null
        val result = cache.analyze(docs, doc)
        return SignatureHelpProvider.help(result.program, doc.text, params.position.line, params.position.character)
    }

    fun codeAction(params: CodeActionParams): List<CodeAction> {
        val doc = docs.get(params.textDocument.uri) ?: return emptyList()
        val result = cache.analyze(docs, doc)
        return CodeActionProvider.actions(result, doc.uri, params.range)
    }

    fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams): List<TypeHierarchyItem> {
        val doc = docs.get(params.textDocument.uri) ?: return emptyList()
        val result = cache.analyze(docs, doc)
        return TypeHierarchyProvider.prepare(result.program, doc.uri, params.position)
    }

    fun typeHierarchySupertypes(params: TypeHierarchySupertypesParams): List<TypeHierarchyItem> =
        TypeHierarchyProvider.supertypes(params.item)

    fun typeHierarchySubtypes(params: TypeHierarchySubtypesParams): List<TypeHierarchyItem> =
        TypeHierarchyProvider.subtypes(params.item)

    fun callHierarchyPrepare(params: CallHierarchyPrepareParams): List<CallHierarchyItem> {
        val doc = docs.get(params.textDocument.uri) ?: return emptyList()
        val result = cache.analyze(docs, doc)
        return CallHierarchyProvider.prepare(result.program, doc.uri, params.position)
    }

    fun callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): List<CallHierarchyIncomingCall> {
        val doc = docs.get(params.item.uri) ?: return emptyList()
        val result = cache.analyze(docs, doc)
        return CallHierarchyProvider.incomingCalls(params.item, result.typedProgram)
    }

    fun callHierarchyOutgoingCalls(params: CallHierarchyOutgoingCallsParams): List<CallHierarchyOutgoingCall> {
        val doc = docs.get(params.item.uri) ?: return emptyList()
        val result = cache.analyze(docs, doc)
        return CallHierarchyProvider.outgoingCalls(params.item, result.typedProgram)
    }

    private fun typed(uri: String): nox.compiler.ast.typed.TypedProgram? {
        val doc = docs.get(uri) ?: return null
        return cache.analyze(docs, doc).typedProgram
    }

    private fun publishDiagnostics(uri: String) {
        val doc = docs.get(uri) ?: return
        val result = cache.analyze(docs, doc)
        val diags = Diagnostics.fromCompilation(result)
        sendDiagnostics(uri, diags)
    }

    private fun sendDiagnostics(
        uri: String,
        diagnostics: List<Diagnostic>,
    ) {
        notifyClient?.invoke(
            "textDocument/publishDiagnostics",
            PublishDiagnosticsParams(uri, diagnostics).toJson(),
        )
    }
}
