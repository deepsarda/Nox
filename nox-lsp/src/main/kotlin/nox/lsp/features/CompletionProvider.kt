package nox.lsp.features

import nox.compiler.NoxCompiler
import nox.compiler.ast.*
import nox.compiler.types.CallTarget
import nox.plugin.LibraryRegistry
import nox.lsp.protocol.*

/**
 * Context-sensitive completion:
 *  - Uses ScopeWalker to find local variables if TypedProgram is available.
 *  - Completes struct members if the dot prefix is a known struct instance.
 *  - Completes namespace functions if the dot prefix is a namespace.
 */
object CompletionProvider {
    private val KEYWORDS = 
        listOf(
            "if", "else", "while", "for", "foreach", "in", "return", "yield",
            "break", "continue", "try", "catch", "throw", "import", "true",
            "false", "null", "as", "int", "double", "boolean", "string",
            "json", "void", "type", "main"
        )

    fun complete(
        result: NoxCompiler.CompilationResult,
        source: String,
        lspLine: Int,
        lspColumn: Int,
        registry: LibraryRegistry = LibraryRegistry.createDefault(),
    ): List<CompletionItem> {
        val raw = result.program
        val namespaces = namespacesInScope(raw, registry)
        val dotCtx = dotContext(source, lspLine, lspColumn)

        val items = mutableListOf<CompletionItem>()

        if (dotCtx != null) {
            // Namespace completion
            if (dotCtx in namespaces) {
                return registry
                    .getNamespaceFunctions(dotCtx)
                    .entries
                    .map { (name, target) -> functionItem(name, target, CompletionItemKind.Function) }
            }

            // Struct member completion
            val typedProgram = result.typedProgram
            if (typedProgram != null) {
                var targetType: nox.compiler.types.TypeRef? = null
                
                // First try to find the expression immediately before the dot (outermost mode)
                val expr = ExprAtPosition.find(typedProgram, lspLine, Math.max(0, lspColumn - 1), outermost = true)
                if (expr != null) {
                    targetType = expr.type
                }
                
                // Fallback to local variables
                if (targetType == null) {
                    val locals = ScopeWalker.variablesAt(typedProgram, lspLine, lspColumn)
                    targetType = locals.find { it.name == dotCtx }?.type
                }

                if (targetType != null) {
                    // UCFS: find all global functions where the first parameter matches targetType
                    // In current file
                    raw.functionsByName.values.forEach { func ->
                        val firstParam = func.params.firstOrNull() as? RawParamImpl
                        if (firstParam != null && firstParam.type == targetType) {
                            val callTarget = CallTarget(
                                name = func.name,
                                params = func.params.filterIsInstance<RawParamImpl>().drop(1).map { nox.compiler.types.NoxParam(it.name, it.type) },
                                returnType = func.returnType,
                                astNode = func
                            )
                            items += functionItem(func.name, callTarget, CompletionItemKind.Method)
                        }
                    }
                    
                    // Built-in methods and type methods from registry
                    registry.getBuiltinMethodNames(targetType)?.forEach { methodName ->
                        val target = registry.lookupBuiltinMethod(targetType, methodName)
                        if (target != null) {
                            items += functionItem(methodName, target, CompletionItemKind.Method)
                        }
                    }
                    registry.getTypeMethodNames(targetType)?.forEach { methodName ->
                        val target = registry.lookupTypeMethod(targetType, methodName)
                        if (target != null) {
                            items += functionItem(methodName, target, CompletionItemKind.Method)
                        }
                    }

                    if (targetType.isStructType()) {
                        val structName = targetType.name
                        val structDef = raw.typesByName[structName]
                        if (structDef != null) {
                            structDef.fields.forEach { field ->
                                if (field is RawFieldDeclImpl) {
                                    items += CompletionItem(field.name, kind = CompletionItemKind.Field, detail = field.type.toString())
                                }
                            }
                        }
                    }
                    return items
                }
            }
            return emptyList()
        }

        // Default completion
        KEYWORDS.forEach { items += simpleItem(it, CompletionItemKind.Keyword) }
        raw.typesByName.keys.forEach { items += simpleItem(it, CompletionItemKind.Class) }
        raw.functionsByName.keys.forEach { items += simpleItem(it, CompletionItemKind.Function) }
        namespaces.forEach { items += simpleItem(it, CompletionItemKind.Module) }

        // Local variable completion
        val typedProgram = result.typedProgram
        if (typedProgram != null) {
            val locals = ScopeWalker.variablesAt(typedProgram, lspLine, lspColumn)
            locals.forEach { v ->
                if (items.none { it.label == v.name }) {
                    items += CompletionItem(v.name, kind = CompletionItemKind.Variable, detail = v.type.toString())
                }
            }
        }

        return items
    }

    private fun dotContext(source: String, lspLine: Int, lspColumn: Int): String? {
        val lines = source.split('\n')
        val line = lines.getOrNull(lspLine) ?: return null
        val prefix = line.substring(0, lspColumn)
        val dot = prefix.lastIndexOf('.')
        if (dot == -1) return null

        val ident = prefix.substring(0, dot).takeLastWhile { it.isLetterOrDigit() || it == '_' }
        return ident.ifEmpty { null }
    }

    private fun simpleItem(label: String, kind: Int): CompletionItem = CompletionItem(label, kind = kind)

    private fun functionItem(label: String, target: CallTarget, kind: Int): CompletionItem = 
        CompletionItem(
            label = label,
            kind = kind,
            detail = renderSignature(label, target),
            insertText = "${'$'}label(${'$'}1)",
        )

    private fun renderSignature(name: String, target: CallTarget): String {
        val params = target.params.joinToString(", ") { "${it.type} ${it.name}" }
        return "${target.returnType} $name($params)"
    }

    private fun namespacesInScope(raw: RawProgram, registry: LibraryRegistry): Set<String> {
        val out = mutableSetOf<String>()
        out.addAll(registry.builtinNamespaceNames)
        out.addAll(registry.externalPluginNamespaces)
        raw.imports.forEach { if (it is RawImportDecl) out.add(it.namespace) }
        return out
    }
}
