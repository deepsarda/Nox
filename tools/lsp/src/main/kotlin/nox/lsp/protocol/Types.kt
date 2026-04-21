package nox.lsp.protocol

typealias JsonObject = Map<String, Any?>
typealias JsonArray = List<Any?>

data class RequestMessage(
    val jsonrpc: String = "2.0",
    val id: Any? = null,
    val method: String,
    val params: JsonObject? = null,
)

data class ResponseMessage(
    val jsonrpc: String = "2.0",
    val id: Any? = null,
    val result: Any? = null,
    val error: ResponseError? = null,
)

data class NotificationMessage(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonObject? = null,
)

data class ResponseError(
    val code: Int,
    val message: String,
    val data: Any? = null,
)

data class InitializeParams(
    val processId: Int? = null,
    val rootUri: String? = null,
    val capabilities: JsonObject? = null,
)

data class InitializeResult(
    val capabilities: ServerCapabilities,
)

data class ServerCapabilities(
    val textDocumentSync: Int? = null, // 1 = Full
    val completionProvider: CompletionOptions? = null,
    val signatureHelpProvider: SignatureHelpOptions? = null,
    val hoverProvider: Boolean? = null,
    val definitionProvider: Boolean? = null,
    val referencesProvider: Boolean? = null,
    val documentSymbolProvider: Boolean? = null,
    val codeActionProvider: Boolean? = null,
    val documentFormattingProvider: Boolean? = null,
    val renameProvider: Boolean? = null,
    val foldingRangeProvider: Boolean? = null,
    val inlayHintProvider: Boolean? = null,
    val semanticTokensProvider: SemanticTokensOptions? = null,
    val callHierarchyProvider: Boolean? = null,
)

data class CompletionOptions(
    val triggerCharacters: List<String>? = null,
    val resolveProvider: Boolean? = null,
)

data class SignatureHelpOptions(
    val triggerCharacters: List<String>? = null,
    val retriggerCharacters: List<String>? = null,
)

data class SemanticTokensOptions(
    val legend: SemanticTokensLegend,
    val full: Boolean,
)

data class SemanticTokensLegend(
    val tokenTypes: List<String>,
    val tokenModifiers: List<String>,
)

data class DidOpenTextDocumentParams(
    val textDocument: TextDocumentItem,
)

data class TextDocumentItem(
    val uri: String,
    val languageId: String,
    val version: Int,
    val text: String,
)

data class DidChangeTextDocumentParams(
    val textDocument: VersionedTextDocumentIdentifier,
    val contentChanges: List<TextDocumentContentChangeEvent>,
)

data class VersionedTextDocumentIdentifier(
    val uri: String,
    val version: Int? = null,
)

data class TextDocumentIdentifier(
    val uri: String,
)

data class TextDocumentContentChangeEvent(
    val text: String,
    val range: Range? = null,
)

data class DidCloseTextDocumentParams(
    val textDocument: TextDocumentIdentifier,
)

data class Position(
    val line: Int,
    val character: Int,
)

data class Range(
    val start: Position,
    val end: Position,
)

data class Location(
    val uri: String,
    val range: Range,
)

data class Diagnostic(
    val range: Range,
    val severity: Int? = null, // 1 = Error, 2 = Warning
    val source: String? = null,
    val message: String,
)

data class PublishDiagnosticsParams(
    val uri: String,
    val diagnostics: List<Diagnostic>,
)

data class DocumentSymbolParams(
    val textDocument: TextDocumentIdentifier,
)

data class DocumentSymbol(
    val name: String,
    val kind: Int,
    val range: Range,
    val selectionRange: Range,
    var children: List<DocumentSymbol>? = null,
)

data class FoldingRangeRequestParams(
    val textDocument: TextDocumentIdentifier,
)

data class FoldingRange(
    val startLine: Int,
    val endLine: Int,
    val startCharacter: Int? = null,
    val endCharacter: Int? = null,
    val kind: String? = null,
)

data class SemanticTokensParams(
    val textDocument: TextDocumentIdentifier,
)

data class SemanticTokens(
    val data: List<Int>,
)

data class InlayHintParams(
    val textDocument: TextDocumentIdentifier,
    val range: Range,
)

data class InlayHint(
    val position: Position,
    val label: String,
    val kind: Int? = null, // 1 = Type, 2 = Parameter
    val paddingLeft: Boolean? = null,
    val paddingRight: Boolean? = null,
)

data class DocumentFormattingParams(
    val textDocument: TextDocumentIdentifier,
    val options: FormattingOptions,
)

data class FormattingOptions(
    val tabSize: Int,
    val insertSpaces: Boolean,
)

data class TextEdit(
    val range: Range,
    val newText: String,
)

data class HoverParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
)

data class Hover(
    val contents: MarkupContent,
    val range: Range? = null,
)

data class MarkupContent(
    val kind: String, // "plaintext" | "markdown"
    val value: String,
)

data class DefinitionParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
)

data class ReferenceParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    val context: ReferenceContext,
)

data class ReferenceContext(
    val includeDeclaration: Boolean,
)

data class RenameParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    val newName: String,
)

data class WorkspaceEdit(
    val changes: Map<String, List<TextEdit>>? = null,
)

data class WorkspaceSymbolParams(
    val query: String,
)

data class SymbolInformation(
    val name: String,
    val kind: Int,
    val location: Location,
    val containerName: String? = null,
)

data class CompletionParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
)

data class CompletionList(
    val isIncomplete: Boolean,
    val items: List<CompletionItem>,
)

data class CompletionItem(
    val label: String,
    val kind: Int? = null,
    val detail: String? = null,
    val documentation: MarkupContent? = null,
    val textEdit: TextEdit? = null,
    val insertText: String? = null,
    val insertTextFormat: Int? = null,
)

data class SignatureHelpParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
)

data class SignatureHelp(
    val signatures: List<SignatureInformation>,
    val activeSignature: Int,
    val activeParameter: Int,
)

data class SignatureInformation(
    val label: String,
    val documentation: MarkupContent? = null,
    val parameters: List<ParameterInformation>,
)

data class ParameterInformation(
    val label: String,
    val documentation: MarkupContent? = null,
)

data class CodeActionParams(
    val textDocument: TextDocumentIdentifier,
    val range: Range,
    val context: CodeActionContext,
)

data class CodeActionContext(
    val diagnostics: List<Diagnostic>,
)

data class CodeAction(
    val title: String,
    val kind: String? = null,
    val diagnostics: List<Diagnostic>? = null,
    val isPreferred: Boolean? = null,
    val edit: WorkspaceEdit? = null,
    val command: Command? = null,
)

data class Command(
    val title: String,
    val command: String,
    val arguments: JsonArray? = null,
)

data class CallHierarchyPrepareParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
)

data class CallHierarchyItem(
    val name: String,
    val kind: Int,
    val uri: String,
    val range: Range,
    val selectionRange: Range,
    val detail: String? = null,
)

data class CallHierarchyIncomingCallsParams(
    val item: CallHierarchyItem,
)

data class CallHierarchyIncomingCall(
    val from: CallHierarchyItem,
    val fromRanges: List<Range>,
)

data class CallHierarchyOutgoingCallsParams(
    val item: CallHierarchyItem,
)

data class CallHierarchyOutgoingCall(
    val to: CallHierarchyItem,
    val fromRanges: List<Range>,
)

data class MessageParams(
    val type: Int, // 1 Error, 2 Warning, 3 Info, 4 Log
    val message: String,
)

data class LogMessageParams(
    val type: Int,
    val message: String,
)

@Suppress("ktlint:standard:property-naming")
object SymbolKind {
    const val File = 1
    const val Module = 2
    const val Namespace = 3
    const val Package = 4
    const val Class = 5
    const val Method = 6
    const val Property = 7
    const val Field = 8
    const val Constructor = 9
    const val Enum = 10
    const val Interface = 11
    const val Function = 12
    const val Variable = 13
    const val Constant = 14
    const val String = 15
    const val Number = 16
    const val Boolean = 17
    const val Array = 18
    const val Object = 19
    const val Key = 20
    const val Null = 21
    const val EnumMember = 22
    const val Struct = 23
    const val Event = 24
    const val Operator = 25
    const val TypeParameter = 26
}

@Suppress("ktlint:standard:property-naming")
object CompletionItemKind {
    const val Text = 1
    const val Method = 2
    const val Function = 3
    const val Constructor = 4
    const val Field = 5
    const val Variable = 6
    const val Class = 7
    const val Interface = 8
    const val Module = 9
    const val Property = 10
    const val Unit = 11
    const val Value = 12
    const val Enum = 13
    const val Keyword = 14
    const val Snippet = 15
    const val Color = 16
    const val File = 17
    const val Reference = 18
    const val Folder = 19
    const val EnumMember = 20
    const val Constant = 21
    const val Struct = 22
    const val Event = 23
    const val Operator = 24
    const val TypeParameter = 25
}

@Suppress("ktlint:standard:property-naming")
object DiagnosticSeverity {
    const val Error = 1
    const val Warning = 2
    const val Information = 3
    const val Hint = 4
}

@Suppress("ktlint:standard:property-naming")
object CodeActionKind {
    const val QuickFix = "quickfix"
    const val Refactor = "refactor"
    const val RefactorExtract = "refactor.extract"
    const val RefactorInline = "refactor.inline"
    const val RefactorRewrite = "refactor.rewrite"
    const val Source = "source"
    const val SourceOrganizeImports = "source.organizeImports"
    const val SourceFixAll = "source.fixAll"
}

object FoldingRangeKind {
    const val COMMENT = "comment"
    const val IMPORTS = "imports"
    const val REGION = "region"
}

object MarkupKind {
    const val PLAIN_TEXT = "plaintext"
    const val MARKDOWN = "markdown"
}

object InlayHintKind {
    const val TYPE = 1
    const val PARAMETER = 2
}

data class TypeHierarchyPrepareParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
)

data class TypeHierarchySupertypesParams(
    val item: TypeHierarchyItem,
)

data class TypeHierarchySubtypesParams(
    val item: TypeHierarchyItem,
)

data class TypeHierarchyItem(
    val name: String,
    val kind: Int,
    val tags: List<Int>? = null,
    val detail: String? = null,
    val uri: String,
    val range: Range,
    val selectionRange: Range,
    val data: Any? = null,
)

data class PrepareRenameParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
)

data class PrepareRenameResult(
    val range: Range,
    val placeholder: String,
)
