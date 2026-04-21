package nox.lsp.protocol

fun RequestMessage.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["jsonrpc"] = jsonrpc
    if (id != null) map["id"] = id
    map["method"] = method
    if (params != null) map["params"] = params
    return map
}

fun ResponseMessage.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["jsonrpc"] = jsonrpc
    if (id != null) map["id"] = id
    if (result != null) map["result"] = result
    if (error != null) map["error"] = error.toJson()
    return map
}

fun NotificationMessage.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["jsonrpc"] = jsonrpc
    map["method"] = method
    if (params != null) map["params"] = params
    return map
}

fun ResponseError.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["code"] = code
    map["message"] = message
    if (data != null) map["data"] = data
    return map
}

fun InitializeParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    if (processId != null) map["processId"] = processId
    if (rootUri != null) map["rootUri"] = rootUri
    if (capabilities != null) map["capabilities"] = capabilities
    return map
}

fun InitializeResult.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["capabilities"] = capabilities.toJson()
    return map
}

fun ServerCapabilities.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    if (textDocumentSync != null) map["textDocumentSync"] = textDocumentSync
    if (completionProvider != null) map["completionProvider"] = completionProvider.toJson()
    if (signatureHelpProvider != null) map["signatureHelpProvider"] = signatureHelpProvider.toJson()
    if (hoverProvider != null) map["hoverProvider"] = hoverProvider
    if (definitionProvider != null) map["definitionProvider"] = definitionProvider
    if (referencesProvider != null) map["referencesProvider"] = referencesProvider
    if (documentSymbolProvider != null) map["documentSymbolProvider"] = documentSymbolProvider
    if (codeActionProvider != null) map["codeActionProvider"] = codeActionProvider
    if (documentFormattingProvider != null) map["documentFormattingProvider"] = documentFormattingProvider
    if (renameProvider != null) map["renameProvider"] = renameProvider
    if (foldingRangeProvider != null) map["foldingRangeProvider"] = foldingRangeProvider
    if (inlayHintProvider != null) map["inlayHintProvider"] = inlayHintProvider
    if (semanticTokensProvider != null) map["semanticTokensProvider"] = semanticTokensProvider.toJson()
    if (callHierarchyProvider != null) map["callHierarchyProvider"] = callHierarchyProvider
    return map
}

fun CompletionOptions.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    if (triggerCharacters != null) map["triggerCharacters"] = triggerCharacters
    if (resolveProvider != null) map["resolveProvider"] = resolveProvider
    return map
}

fun SignatureHelpOptions.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    if (triggerCharacters != null) map["triggerCharacters"] = triggerCharacters
    if (retriggerCharacters != null) map["retriggerCharacters"] = retriggerCharacters
    return map
}

fun SemanticTokensOptions.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["legend"] = legend.toJson()
    map["full"] = full
    return map
}

fun SemanticTokensLegend.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["tokenTypes"] = tokenTypes
    map["tokenModifiers"] = tokenModifiers
    return map
}

fun DidOpenTextDocumentParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    return map
}

fun TextDocumentItem.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["uri"] = uri
    map["languageId"] = languageId
    map["version"] = version
    map["text"] = text
    return map
}

fun DidChangeTextDocumentParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    map["contentChanges"] = contentChanges.map { it.toJson() }
    return map
}

fun VersionedTextDocumentIdentifier.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["uri"] = uri
    if (version != null) map["version"] = version
    return map
}

fun TextDocumentIdentifier.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["uri"] = uri
    return map
}

fun TextDocumentContentChangeEvent.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["text"] = text
    if (range != null) map["range"] = range.toJson()
    return map
}

fun DidCloseTextDocumentParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    return map
}

fun Position.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["line"] = line
    map["character"] = character
    return map
}

fun Range.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["start"] = start.toJson()
    map["end"] = end.toJson()
    return map
}

fun Location.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["uri"] = uri
    map["range"] = range.toJson()
    return map
}

fun Diagnostic.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["range"] = range.toJson()
    if (severity != null) map["severity"] = severity
    if (source != null) map["source"] = source
    map["message"] = message
    return map
}

fun PublishDiagnosticsParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["uri"] = uri
    map["diagnostics"] = diagnostics.map { it.toJson() }
    return map
}

fun DocumentSymbolParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    return map
}

fun DocumentSymbol.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["name"] = name
    map["kind"] = kind
    map["range"] = range.toJson()
    map["selectionRange"] = selectionRange.toJson()
    return map
}

fun FoldingRangeRequestParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    return map
}

fun FoldingRange.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["startLine"] = startLine
    map["endLine"] = endLine
    if (startCharacter != null) map["startCharacter"] = startCharacter
    if (endCharacter != null) map["endCharacter"] = endCharacter
    if (kind != null) map["kind"] = kind
    return map
}

fun SemanticTokensParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    return map
}

fun SemanticTokens.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["data"] = data
    return map
}

fun InlayHintParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    map["range"] = range.toJson()
    return map
}

fun InlayHint.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["position"] = position.toJson()
    map["label"] = label
    if (kind != null) map["kind"] = kind
    if (paddingLeft != null) map["paddingLeft"] = paddingLeft
    if (paddingRight != null) map["paddingRight"] = paddingRight
    return map
}

fun DocumentFormattingParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    map["options"] = options.toJson()
    return map
}

fun FormattingOptions.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["tabSize"] = tabSize
    map["insertSpaces"] = insertSpaces
    return map
}

fun TextEdit.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["range"] = range.toJson()
    map["newText"] = newText
    return map
}

fun HoverParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    map["position"] = position.toJson()
    return map
}

fun Hover.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["contents"] = contents.toJson()
    if (range != null) map["range"] = range.toJson()
    return map
}

fun MarkupContent.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["kind"] = kind
    map["value"] = value
    return map
}

fun DefinitionParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    map["position"] = position.toJson()
    return map
}

fun ReferenceParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    map["position"] = position.toJson()
    map["context"] = context.toJson()
    return map
}

fun ReferenceContext.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["includeDeclaration"] = includeDeclaration
    return map
}

fun RenameParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    map["position"] = position.toJson()
    map["newName"] = newName
    return map
}

fun WorkspaceEdit.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    // TODO Map Map<String
    return map
}

fun WorkspaceSymbolParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["query"] = query
    return map
}

fun SymbolInformation.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["name"] = name
    map["kind"] = kind
    map["location"] = location.toJson()
    if (containerName != null) map["containerName"] = containerName
    return map
}

fun CompletionParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    map["position"] = position.toJson()
    return map
}

fun CompletionList.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["isIncomplete"] = isIncomplete
    map["items"] = items.map { it.toJson() }
    return map
}

fun CompletionItem.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["label"] = label
    if (kind != null) map["kind"] = kind
    if (detail != null) map["detail"] = detail
    if (documentation != null) map["documentation"] = documentation.toJson()
    if (textEdit != null) map["textEdit"] = textEdit.toJson()
    if (insertText != null) map["insertText"] = insertText
    if (insertTextFormat != null) map["insertTextFormat"] = insertTextFormat
    return map
}

fun SignatureHelpParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    map["position"] = position.toJson()
    return map
}

fun SignatureHelp.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["signatures"] = signatures.map { it.toJson() }
    map["activeSignature"] = activeSignature
    map["activeParameter"] = activeParameter
    return map
}

fun SignatureInformation.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["label"] = label
    if (documentation != null) map["documentation"] = documentation.toJson()
    map["parameters"] = parameters.map { it.toJson() }
    return map
}

fun ParameterInformation.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["label"] = label
    if (documentation != null) map["documentation"] = documentation.toJson()
    return map
}

fun CodeActionParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    map["range"] = range.toJson()
    map["context"] = context.toJson()
    return map
}

fun CodeActionContext.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["diagnostics"] = diagnostics.map { it.toJson() }
    return map
}

fun CodeAction.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["title"] = title
    if (kind != null) map["kind"] = kind
    if (diagnostics != null) map["diagnostics"] = diagnostics.map { it.toJson() }
    if (isPreferred != null) map["isPreferred"] = isPreferred
    if (edit != null) map["edit"] = edit.toJson()
    if (command != null) map["command"] = command.toJson()
    return map
}

fun Command.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["title"] = title
    map["command"] = command
    if (arguments != null) map["arguments"] = arguments
    return map
}

fun CallHierarchyPrepareParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    map["position"] = position.toJson()
    return map
}

fun CallHierarchyItem.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["name"] = name
    map["kind"] = kind
    map["uri"] = uri
    map["range"] = range.toJson()
    map["selectionRange"] = selectionRange.toJson()
    if (detail != null) map["detail"] = detail
    return map
}

fun CallHierarchyIncomingCallsParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["item"] = item.toJson()
    return map
}

fun CallHierarchyIncomingCall.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["from"] = from.toJson()
    map["fromRanges"] = fromRanges.map { it.toJson() }
    return map
}

fun CallHierarchyOutgoingCallsParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["item"] = item.toJson()
    return map
}

fun CallHierarchyOutgoingCall.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["to"] = to.toJson()
    map["fromRanges"] = fromRanges.map { it.toJson() }
    return map
}

fun MessageParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["type"] = type
    map["message"] = message
    return map
}

fun LogMessageParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["type"] = type
    map["message"] = message
    return map
}

fun TypeHierarchyPrepareParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    map["position"] = position.toJson()
    return map
}

fun TypeHierarchySupertypesParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["item"] = item.toJson()
    return map
}

fun TypeHierarchySubtypesParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["item"] = item.toJson()
    return map
}

fun TypeHierarchyItem.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["name"] = name
    map["kind"] = kind
    if (tags != null) map["tags"] = tags
    if (detail != null) map["detail"] = detail
    map["uri"] = uri
    map["range"] = range.toJson()
    map["selectionRange"] = selectionRange.toJson()
    if (data != null) map["data"] = data
    return map
}

fun PrepareRenameParams.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["textDocument"] = textDocument.toJson()
    map["position"] = position.toJson()
    return map
}

fun PrepareRenameResult.toJson(): JsonObject {
    val map = mutableMapOf<String, Any?>()
    map["range"] = range.toJson()
    map["placeholder"] = placeholder
    return map
}

@Suppress("UNCHECKED_CAST")
fun parseRequestMessage(json: JsonObject): RequestMessage =
    RequestMessage(
        jsonrpc = json["jsonrpc"] as? String ?: error("Missing required field jsonrpc"),
        id = json["id"],
        method = json["method"] as? String ?: error("Missing required field method"),
        params = json["params"] as? JsonObject,
    )

@Suppress("UNCHECKED_CAST")
fun parseResponseMessage(json: JsonObject): ResponseMessage =
    ResponseMessage(
        jsonrpc = json["jsonrpc"] as? String ?: error("Missing required field jsonrpc"),
        id = json["id"],
        result = json["result"],
        error = (json["error"] as? JsonObject)?.let { parseResponseError(it) },
    )

@Suppress("UNCHECKED_CAST")
fun parseNotificationMessage(json: JsonObject): NotificationMessage =
    NotificationMessage(
        jsonrpc = json["jsonrpc"] as? String ?: error("Missing required field jsonrpc"),
        method = json["method"] as? String ?: error("Missing required field method"),
        params = json["params"] as? JsonObject,
    )

@Suppress("UNCHECKED_CAST")
fun parseResponseError(json: JsonObject): ResponseError =
    ResponseError(
        code = (json["code"] as? Number)?.toInt() ?: error("Missing required field code"),
        message = json["message"] as? String ?: error("Missing required field message"),
        data = json["data"],
    )

@Suppress("UNCHECKED_CAST")
fun parseInitializeParams(json: JsonObject): InitializeParams =
    InitializeParams(
        processId = (json["processId"] as? Number)?.toInt(),
        rootUri = json["rootUri"] as? String,
        capabilities = json["capabilities"] as? JsonObject,
    )

@Suppress("UNCHECKED_CAST")
fun parseInitializeResult(json: JsonObject): InitializeResult =
    InitializeResult(
        capabilities = parseServerCapabilities(json["capabilities"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseServerCapabilities(json: JsonObject): ServerCapabilities =
    ServerCapabilities(
        textDocumentSync = (json["textDocumentSync"] as? Number)?.toInt(),
        completionProvider = (json["completionProvider"] as? JsonObject)?.let { parseCompletionOptions(it) },
        signatureHelpProvider = (json["signatureHelpProvider"] as? JsonObject)?.let { parseSignatureHelpOptions(it) },
        hoverProvider = json["hoverProvider"] as? Boolean,
        definitionProvider = json["definitionProvider"] as? Boolean,
        referencesProvider = json["referencesProvider"] as? Boolean,
        documentSymbolProvider = json["documentSymbolProvider"] as? Boolean,
        codeActionProvider = json["codeActionProvider"] as? Boolean,
        documentFormattingProvider = json["documentFormattingProvider"] as? Boolean,
        renameProvider = json["renameProvider"] as? Boolean,
        foldingRangeProvider = json["foldingRangeProvider"] as? Boolean,
        inlayHintProvider = json["inlayHintProvider"] as? Boolean,
        semanticTokensProvider =
            (json["semanticTokensProvider"] as? JsonObject)?.let {
                parseSemanticTokensOptions(
                    it,
                )
            },
        callHierarchyProvider = json["callHierarchyProvider"] as? Boolean,
    )

@Suppress("UNCHECKED_CAST")
fun parseCompletionOptions(json: JsonObject): CompletionOptions =
    CompletionOptions(
        triggerCharacters = json["triggerCharacters"] as? List<String>,
        resolveProvider = json["resolveProvider"] as? Boolean,
    )

@Suppress("UNCHECKED_CAST")
fun parseSignatureHelpOptions(json: JsonObject): SignatureHelpOptions =
    SignatureHelpOptions(
        triggerCharacters = json["triggerCharacters"] as? List<String>,
        retriggerCharacters = json["retriggerCharacters"] as? List<String>,
    )

@Suppress("UNCHECKED_CAST")
fun parseSemanticTokensOptions(json: JsonObject): SemanticTokensOptions =
    SemanticTokensOptions(
        legend = parseSemanticTokensLegend(json["legend"] as JsonObject),
        full = json["full"] as? Boolean ?: error("Missing required field full"),
    )

@Suppress("UNCHECKED_CAST")
fun parseSemanticTokensLegend(json: JsonObject): SemanticTokensLegend =
    SemanticTokensLegend(
        tokenTypes = (json["tokenTypes"] as? List<String>) ?: emptyList(),
        tokenModifiers = (json["tokenModifiers"] as? List<String>) ?: emptyList(),
    )

@Suppress("UNCHECKED_CAST")
fun parseDidOpenTextDocumentParams(json: JsonObject): DidOpenTextDocumentParams =
    DidOpenTextDocumentParams(
        textDocument = parseTextDocumentItem(json["textDocument"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseTextDocumentItem(json: JsonObject): TextDocumentItem =
    TextDocumentItem(
        uri = json["uri"] as? String ?: error("Missing required field uri"),
        languageId = json["languageId"] as? String ?: error("Missing required field languageId"),
        version = (json["version"] as? Number)?.toInt() ?: error("Missing required field version"),
        text = json["text"] as? String ?: error("Missing required field text"),
    )

@Suppress("UNCHECKED_CAST")
fun parseDidChangeTextDocumentParams(json: JsonObject): DidChangeTextDocumentParams =
    DidChangeTextDocumentParams(
        textDocument = parseVersionedTextDocumentIdentifier(json["textDocument"] as JsonObject),
        contentChanges =
            ((json["contentChanges"] as? List<JsonObject>) ?: emptyList()).map {
                parseTextDocumentContentChangeEvent(it)
            },
    )

@Suppress("UNCHECKED_CAST")
fun parseVersionedTextDocumentIdentifier(json: JsonObject): VersionedTextDocumentIdentifier =
    VersionedTextDocumentIdentifier(
        uri = json["uri"] as? String ?: error("Missing required field uri"),
        version = (json["version"] as? Number)?.toInt(),
    )

@Suppress("UNCHECKED_CAST")
fun parseTextDocumentIdentifier(json: JsonObject): TextDocumentIdentifier =
    TextDocumentIdentifier(
        uri = json["uri"] as? String ?: error("Missing required field uri"),
    )

@Suppress("UNCHECKED_CAST")
fun parseTextDocumentContentChangeEvent(json: JsonObject): TextDocumentContentChangeEvent =
    TextDocumentContentChangeEvent(
        text = json["text"] as? String ?: error("Missing required field text"),
        range = (json["range"] as? JsonObject)?.let { parseRange(it) },
    )

@Suppress("UNCHECKED_CAST")
fun parseDidCloseTextDocumentParams(json: JsonObject): DidCloseTextDocumentParams =
    DidCloseTextDocumentParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parsePosition(json: JsonObject): Position =
    Position(
        line = (json["line"] as? Number)?.toInt() ?: error("Missing required field line"),
        character = (json["character"] as? Number)?.toInt() ?: error("Missing required field character"),
    )

@Suppress("UNCHECKED_CAST")
fun parseRange(json: JsonObject): Range =
    Range(
        start = parsePosition(json["start"] as JsonObject),
        end = parsePosition(json["end"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseLocation(json: JsonObject): Location =
    Location(
        uri = json["uri"] as? String ?: error("Missing required field uri"),
        range = parseRange(json["range"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseDiagnostic(json: JsonObject): Diagnostic =
    Diagnostic(
        range = parseRange(json["range"] as JsonObject),
        severity = (json["severity"] as? Number)?.toInt(),
        source = json["source"] as? String,
        message = json["message"] as? String ?: error("Missing required field message"),
    )

@Suppress("UNCHECKED_CAST")
fun parsePublishDiagnosticsParams(json: JsonObject): PublishDiagnosticsParams =
    PublishDiagnosticsParams(
        uri = json["uri"] as? String ?: error("Missing required field uri"),
        diagnostics = ((json["diagnostics"] as? List<JsonObject>) ?: emptyList()).map { parseDiagnostic(it) },
    )

@Suppress("UNCHECKED_CAST")
fun parseDocumentSymbolParams(json: JsonObject): DocumentSymbolParams =
    DocumentSymbolParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseDocumentSymbol(json: JsonObject): DocumentSymbol =
    DocumentSymbol(
        name = json["name"] as? String ?: error("Missing required field name"),
        kind = (json["kind"] as? Number)?.toInt() ?: error("Missing required field kind"),
        range = parseRange(json["range"] as JsonObject),
        selectionRange = parseRange(json["selectionRange"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseFoldingRangeRequestParams(json: JsonObject): FoldingRangeRequestParams =
    FoldingRangeRequestParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseFoldingRange(json: JsonObject): FoldingRange =
    FoldingRange(
        startLine = (json["startLine"] as? Number)?.toInt() ?: error("Missing required field startLine"),
        endLine = (json["endLine"] as? Number)?.toInt() ?: error("Missing required field endLine"),
        startCharacter = (json["startCharacter"] as? Number)?.toInt(),
        endCharacter = (json["endCharacter"] as? Number)?.toInt(),
        kind = json["kind"] as? String,
    )

@Suppress("UNCHECKED_CAST")
fun parseSemanticTokensParams(json: JsonObject): SemanticTokensParams =
    SemanticTokensParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseSemanticTokens(json: JsonObject): SemanticTokens =
    SemanticTokens(
        data = (json["data"] as? List<Int>) ?: emptyList(),
    )

@Suppress("UNCHECKED_CAST")
fun parseInlayHintParams(json: JsonObject): InlayHintParams =
    InlayHintParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
        range = parseRange(json["range"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseInlayHint(json: JsonObject): InlayHint =
    InlayHint(
        position = parsePosition(json["position"] as JsonObject),
        label = json["label"] as? String ?: error("Missing required field label"),
        kind = (json["kind"] as? Number)?.toInt(),
        paddingLeft = json["paddingLeft"] as? Boolean,
        paddingRight = json["paddingRight"] as? Boolean,
    )

@Suppress("UNCHECKED_CAST")
fun parseDocumentFormattingParams(json: JsonObject): DocumentFormattingParams =
    DocumentFormattingParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
        options = parseFormattingOptions(json["options"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseFormattingOptions(json: JsonObject): FormattingOptions =
    FormattingOptions(
        tabSize = (json["tabSize"] as? Number)?.toInt() ?: error("Missing required field tabSize"),
        insertSpaces = json["insertSpaces"] as? Boolean ?: error("Missing required field insertSpaces"),
    )

@Suppress("UNCHECKED_CAST")
fun parseTextEdit(json: JsonObject): TextEdit =
    TextEdit(
        range = parseRange(json["range"] as JsonObject),
        newText = json["newText"] as? String ?: error("Missing required field newText"),
    )

@Suppress("UNCHECKED_CAST")
fun parseHoverParams(json: JsonObject): HoverParams =
    HoverParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
        position = parsePosition(json["position"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseHover(json: JsonObject): Hover =
    Hover(
        contents = parseMarkupContent(json["contents"] as JsonObject),
        range = (json["range"] as? JsonObject)?.let { parseRange(it) },
    )

@Suppress("UNCHECKED_CAST")
fun parseMarkupContent(json: JsonObject): MarkupContent =
    MarkupContent(
        kind = json["kind"] as? String ?: error("Missing required field kind"),
        value = json["value"] as? String ?: error("Missing required field value"),
    )

@Suppress("UNCHECKED_CAST")
fun parseDefinitionParams(json: JsonObject): DefinitionParams =
    DefinitionParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
        position = parsePosition(json["position"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseReferenceParams(json: JsonObject): ReferenceParams =
    ReferenceParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
        position = parsePosition(json["position"] as JsonObject),
        context = parseReferenceContext(json["context"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseReferenceContext(json: JsonObject): ReferenceContext =
    ReferenceContext(
        includeDeclaration =
            json["includeDeclaration"] as? Boolean ?: error("Missing required field includeDeclaration"),
    )

@Suppress("UNCHECKED_CAST")
fun parseRenameParams(json: JsonObject): RenameParams =
    RenameParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
        position = parsePosition(json["position"] as JsonObject),
        newName = json["newName"] as? String ?: error("Missing required field newName"),
    )

@Suppress("UNCHECKED_CAST")
fun parseWorkspaceEdit(json: JsonObject): WorkspaceEdit =
    WorkspaceEdit(
        // TODO MAP Map<String
    )

@Suppress("UNCHECKED_CAST")
fun parseWorkspaceSymbolParams(json: JsonObject): WorkspaceSymbolParams =
    WorkspaceSymbolParams(
        query = json["query"] as? String ?: error("Missing required field query"),
    )

@Suppress("UNCHECKED_CAST")
fun parseSymbolInformation(json: JsonObject): SymbolInformation =
    SymbolInformation(
        name = json["name"] as? String ?: error("Missing required field name"),
        kind = (json["kind"] as? Number)?.toInt() ?: error("Missing required field kind"),
        location = parseLocation(json["location"] as JsonObject),
        containerName = json["containerName"] as? String,
    )

@Suppress("UNCHECKED_CAST")
fun parseCompletionParams(json: JsonObject): CompletionParams =
    CompletionParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
        position = parsePosition(json["position"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseCompletionList(json: JsonObject): CompletionList =
    CompletionList(
        isIncomplete = json["isIncomplete"] as? Boolean ?: error("Missing required field isIncomplete"),
        items = ((json["items"] as? List<JsonObject>) ?: emptyList()).map { parseCompletionItem(it) },
    )

@Suppress("UNCHECKED_CAST")
fun parseCompletionItem(json: JsonObject): CompletionItem =
    CompletionItem(
        label = json["label"] as? String ?: error("Missing required field label"),
        kind = (json["kind"] as? Number)?.toInt(),
        detail = json["detail"] as? String,
        documentation = (json["documentation"] as? JsonObject)?.let { parseMarkupContent(it) },
        textEdit = (json["textEdit"] as? JsonObject)?.let { parseTextEdit(it) },
        insertText = json["insertText"] as? String,
        insertTextFormat = (json["insertTextFormat"] as? Number)?.toInt(),
    )

@Suppress("UNCHECKED_CAST")
fun parseSignatureHelpParams(json: JsonObject): SignatureHelpParams =
    SignatureHelpParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
        position = parsePosition(json["position"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseSignatureHelp(json: JsonObject): SignatureHelp =
    SignatureHelp(
        signatures = ((json["signatures"] as? List<JsonObject>) ?: emptyList()).map { parseSignatureInformation(it) },
        activeSignature =
            (json["activeSignature"] as? Number)?.toInt() ?: error("Missing required field activeSignature"),
        activeParameter =
            (json["activeParameter"] as? Number)?.toInt() ?: error("Missing required field activeParameter"),
    )

@Suppress("UNCHECKED_CAST")
fun parseSignatureInformation(json: JsonObject): SignatureInformation =
    SignatureInformation(
        label = json["label"] as? String ?: error("Missing required field label"),
        documentation = (json["documentation"] as? JsonObject)?.let { parseMarkupContent(it) },
        parameters = ((json["parameters"] as? List<JsonObject>) ?: emptyList()).map { parseParameterInformation(it) },
    )

@Suppress("UNCHECKED_CAST")
fun parseParameterInformation(json: JsonObject): ParameterInformation =
    ParameterInformation(
        label = json["label"] as? String ?: error("Missing required field label"),
        documentation = (json["documentation"] as? JsonObject)?.let { parseMarkupContent(it) },
    )

@Suppress("UNCHECKED_CAST")
fun parseCodeActionParams(json: JsonObject): CodeActionParams =
    CodeActionParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
        range = parseRange(json["range"] as JsonObject),
        context = parseCodeActionContext(json["context"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseCodeActionContext(json: JsonObject): CodeActionContext =
    CodeActionContext(
        diagnostics = ((json["diagnostics"] as? List<JsonObject>) ?: emptyList()).map { parseDiagnostic(it) },
    )

@Suppress("UNCHECKED_CAST")
fun parseCodeAction(json: JsonObject): CodeAction =
    CodeAction(
        title = json["title"] as? String ?: error("Missing required field title"),
        kind = json["kind"] as? String,
        diagnostics = (json["diagnostics"] as? List<JsonObject>)?.map { parseDiagnostic(it) },
        isPreferred = json["isPreferred"] as? Boolean,
        edit = (json["edit"] as? JsonObject)?.let { parseWorkspaceEdit(it) },
        command = (json["command"] as? JsonObject)?.let { parseCommand(it) },
    )

@Suppress("UNCHECKED_CAST")
fun parseCommand(json: JsonObject): Command =
    Command(
        title = json["title"] as? String ?: error("Missing required field title"),
        command = json["command"] as? String ?: error("Missing required field command"),
        arguments = json["arguments"] as? JsonArray,
    )

@Suppress("UNCHECKED_CAST")
fun parseCallHierarchyPrepareParams(json: JsonObject): CallHierarchyPrepareParams =
    CallHierarchyPrepareParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
        position = parsePosition(json["position"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseCallHierarchyItem(json: JsonObject): CallHierarchyItem =
    CallHierarchyItem(
        name = json["name"] as? String ?: error("Missing required field name"),
        kind = (json["kind"] as? Number)?.toInt() ?: error("Missing required field kind"),
        uri = json["uri"] as? String ?: error("Missing required field uri"),
        range = parseRange(json["range"] as JsonObject),
        selectionRange = parseRange(json["selectionRange"] as JsonObject),
        detail = json["detail"] as? String,
    )

@Suppress("UNCHECKED_CAST")
fun parseCallHierarchyIncomingCallsParams(json: JsonObject): CallHierarchyIncomingCallsParams =
    CallHierarchyIncomingCallsParams(
        item = parseCallHierarchyItem(json["item"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseCallHierarchyIncomingCall(json: JsonObject): CallHierarchyIncomingCall =
    CallHierarchyIncomingCall(
        from = parseCallHierarchyItem(json["from"] as JsonObject),
        fromRanges = ((json["fromRanges"] as? List<JsonObject>) ?: emptyList()).map { parseRange(it) },
    )

@Suppress("UNCHECKED_CAST")
fun parseCallHierarchyOutgoingCallsParams(json: JsonObject): CallHierarchyOutgoingCallsParams =
    CallHierarchyOutgoingCallsParams(
        item = parseCallHierarchyItem(json["item"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseCallHierarchyOutgoingCall(json: JsonObject): CallHierarchyOutgoingCall =
    CallHierarchyOutgoingCall(
        to = parseCallHierarchyItem(json["to"] as JsonObject),
        fromRanges = ((json["fromRanges"] as? List<JsonObject>) ?: emptyList()).map { parseRange(it) },
    )

@Suppress("UNCHECKED_CAST")
fun parseMessageParams(json: JsonObject): MessageParams =
    MessageParams(
        type = (json["type"] as? Number)?.toInt() ?: error("Missing required field type"),
        message = json["message"] as? String ?: error("Missing required field message"),
    )

@Suppress("UNCHECKED_CAST")
fun parseLogMessageParams(json: JsonObject): LogMessageParams =
    LogMessageParams(
        type = (json["type"] as? Number)?.toInt() ?: error("Missing required field type"),
        message = json["message"] as? String ?: error("Missing required field message"),
    )

@Suppress("UNCHECKED_CAST")
fun parseTypeHierarchyPrepareParams(json: JsonObject): TypeHierarchyPrepareParams =
    TypeHierarchyPrepareParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
        position = parsePosition(json["position"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseTypeHierarchySupertypesParams(json: JsonObject): TypeHierarchySupertypesParams =
    TypeHierarchySupertypesParams(
        item = parseTypeHierarchyItem(json["item"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseTypeHierarchySubtypesParams(json: JsonObject): TypeHierarchySubtypesParams =
    TypeHierarchySubtypesParams(
        item = parseTypeHierarchyItem(json["item"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parseTypeHierarchyItem(json: JsonObject): TypeHierarchyItem =
    TypeHierarchyItem(
        name = json["name"] as? String ?: error("Missing required field name"),
        kind = (json["kind"] as? Number)?.toInt() ?: error("Missing required field kind"),
        tags = json["tags"] as? List<Int>,
        detail = json["detail"] as? String,
        uri = json["uri"] as? String ?: error("Missing required field uri"),
        range = parseRange(json["range"] as JsonObject),
        selectionRange = parseRange(json["selectionRange"] as JsonObject),
        data = json["data"],
    )

@Suppress("UNCHECKED_CAST")
fun parsePrepareRenameParams(json: JsonObject): PrepareRenameParams =
    PrepareRenameParams(
        textDocument = parseTextDocumentIdentifier(json["textDocument"] as JsonObject),
        position = parsePosition(json["position"] as JsonObject),
    )

@Suppress("UNCHECKED_CAST")
fun parsePrepareRenameResult(json: JsonObject): PrepareRenameResult =
    PrepareRenameResult(
        range = parseRange(json["range"] as JsonObject),
        placeholder = json["placeholder"] as? String ?: error("Missing required field placeholder"),
    )

fun Iterable<*>.toJson(): JsonArray =
    this.map {
        when (it) {
            is Location -> it.toJson()
            is DocumentSymbol -> it.toJson()
            is FoldingRange -> it.toJson()
            is InlayHint -> it.toJson()
            is TextEdit -> it.toJson()
            is CodeAction -> it.toJson()
            is TypeHierarchyItem -> it.toJson()
            is CallHierarchyItem -> it.toJson()
            is CallHierarchyIncomingCall -> it.toJson()
            is CallHierarchyOutgoingCall -> it.toJson()
            else -> it
        }
    }
