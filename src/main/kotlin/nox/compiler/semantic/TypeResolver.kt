package nox.compiler.semantic

import nox.compiler.CompilerErrors
import nox.compiler.DiagnosticHelpers
import nox.compiler.ast.*
import nox.compiler.ast.typed.*
import nox.compiler.types.*
import nox.plugin.LibraryRegistry

class TypeResolver(
    private val globalScope: SymbolTable,
    private val errors: CompilerErrors,
    private val modules: List<ResolvedModule> = emptyList(),
    private val registry: LibraryRegistry = LibraryRegistry.createDefault(),
) {
    private val exprResolver = ExpressionResolver(globalScope, errors, modules, registry)
    private val stmtResolver = StatementResolver(exprResolver, errors)
    private val resolvedModulePaths = mutableSetOf<String>()

    fun resolve(program: RawProgram): Pair<TypedProgram, List<TypedModule>> {
        val typedModules = mutableListOf<TypedModule>()
        for (module in modules) {
            if (module.sourcePath in resolvedModulePaths) continue
            resolvedModulePaths.add(module.sourcePath)

            val moduleScope = SymbolTable()
            DeclarationCollector(moduleScope, errors).collect(module.program)
            val (typedModProgram, deps) = TypeResolver(moduleScope, errors, registry = registry).resolve(module.program)
            typedModules.addAll(deps)
            typedModules.add(
                TypedModule(
                    module.namespace,
                    module.sourcePath,
                    typedModProgram,
                    module.globalBaseOffset,
                    module.globalCount,
                ),
            )
        }

        val typedDecls = mutableListOf<TypedDecl>()

        for (decl in program.declarations) {
            when (decl) {
                is RawTypeDef -> typedDecls.add(resolveStructFields(decl))
                is RawFuncDef -> typedDecls.add(resolveFunction(decl))
                is RawMainDef -> typedDecls.add(resolveMain(decl))
                is RawGlobalVarDecl -> typedDecls.add(resolveGlobalInit(decl))
                is RawImportDecl -> typedDecls.add(resolveImportDecl(decl))
                is RawErrorDecl -> typedDecls.add(TypedErrorDecl(decl.loc))
            }
        }

        val typedImports = program.imports.map {
            when (it) {
                is RawImportDecl -> resolveImportDecl(it)
                else -> TypedErrorDecl(it.loc)
            }
        }

        val typedHeaders = program.headers.mapNotNull { h ->
            if (h is RawHeaderImpl) TypedHeader(h.key, h.value, h.loc) else null
        }

        val typedProgram =
            TypedProgram(
                fileName = program.fileName,
                headers = typedHeaders,
                imports = typedImports,
                declarations = typedDecls,
            )
        typedProgram.typesByName.putAll(typedDecls.filterIsInstance<TypedTypeDef>().associateBy { it.name })
        typedProgram.functionsByName.putAll(typedDecls.filterIsInstance<TypedFuncDef>().associateBy { it.name })
        typedProgram.globals.addAll(typedDecls.filterIsInstance<TypedGlobalVarDecl>())

        return Pair(typedProgram, typedModules)
    }

    private fun resolveStructFields(typeDef: RawTypeDef): TypedTypeDef {
        val typeSym = globalScope.lookup(typeDef.name) as? TypeSymbol

        val typedFields = mutableListOf<TypedFieldDecl>()
        val seenFields = mutableSetOf<String>()

        for (field in typeDef.fields) {
            if (field is RawErrorFieldDecl) continue
            val f = field as RawFieldDeclImpl

            if (f.name in seenFields) {
                errors.report(
                    f.loc,
                    "Field '${f.name}' is declared more than once in struct '${typeDef.name}'",
                    suggestion = "Remove or rename the duplicate field",
                )
                continue
            }
            seenFields.add(f.name)

            if (!isKnownType(f.type)) {
                val candidates = globalScope.allNamesInScope { it is TypeSymbol }
                val suggestion =
                    DiagnosticHelpers.didYouMeanMsg(f.type.name, candidates)
                        ?: (
                            "Declare the type first with 'type ${f.type.name} { ... }' " +
                                "or use a built-in type (int, double, boolean, string, json)"
                        )
                errors.report(
                    f.loc,
                    "Unknown type '${f.type}' for field '${f.name}' in struct '${typeDef.name}'",
                    suggestion = suggestion,
                )
                continue
            }
            if (!f.type.isValidAsVariable()) {
                errors.report(
                    f.loc,
                    "Field '${f.name}' cannot have type '${f.type}' since 'void' is not allowed for struct fields",
                )
                continue
            }

            typeSym?.fields?.put(f.name, f.type)
            typedFields.add(TypedFieldDecl(f.type, f.name, f.loc))
        }

        return TypedTypeDef(typeDef.name, typedFields, typeDef.loc)
    }

    private fun isKnownType(type: TypeRef): Boolean {
        val baseName = type.name
        if (baseName in BUILTIN_TYPE_NAMES) return true
        return globalScope.lookup(baseName) is TypeSymbol
    }

    private fun resolveFunction(funcDef: RawFuncDef): TypedFuncDef {
        val funcScope = globalScope.child()
        val typedParams = registerParams(funcScope, funcDef.params)
        val typedBody = stmtResolver.resolveBlock(funcScope, funcDef.body, funcDef.returnType)
        return TypedFuncDef(funcDef.returnType, funcDef.name, typedParams, typedBody, funcDef.loc)
    }

    private fun resolveMain(mainDef: RawMainDef): TypedMainDef {
        val mainScope = globalScope.child()
        val typedParams = registerParams(mainScope, mainDef.params)
        stmtResolver.isMainBody = true
        val typedBody = stmtResolver.resolveBlock(mainScope, mainDef.body, TypeRef.VOID)
        stmtResolver.isMainBody = false
        return TypedMainDef(TypeRef.STRING, typedParams, typedBody, mainDef.loc)
    }

    private fun registerParams(
        scope: SymbolTable,
        params: List<RawParam>,
    ): List<TypedParam> {
        val typedParams = mutableListOf<TypedParam>()
        for (param in params) {
            if (param is RawErrorParam) continue
            val p = param as RawParamImpl

            if (!isKnownType(p.type)) {
                val candidates = globalScope.allNamesInScope { it is TypeSymbol }
                val suggestion =
                    DiagnosticHelpers.didYouMeanMsg(p.type.name, candidates)
                        ?: "Supported types: int, double, boolean, string, json, or a declared struct type"
                errors.report(
                    p.loc,
                    "Parameter '${p.name}' has unknown type '${p.type}'",
                    suggestion = suggestion,
                )
            }
            if (!p.type.isValidAsVariable()) {
                errors.report(
                    p.loc,
                    "Parameter '${p.name}' cannot have type 'void'",
                    suggestion = "Use a concrete type: int, double, boolean, string, json, or a struct type",
                )
            }

            var typedDefaultValue: TypedExpr? = null
            if (p.defaultValue != null) {
                typedDefaultValue = exprResolver.resolveExpr(scope, p.defaultValue)
                if (!p.type.isAssignableFrom(typedDefaultValue.type)) {
                    errors.report(
                        p.defaultValue.loc,
                        "Default value for '${p.name}' has type '${typedDefaultValue.type}', but the parameter expects '${p.type}'",
                        suggestion = DiagnosticHelpers.conversionHint(typedDefaultValue.type, p.type),
                    )
                }
            }

            val symbol = ParamSymbol(p.name, p.type, p.defaultValue, p.isVarargs)
            if (!scope.define(p.name, symbol)) {
                errors.report(
                    p.loc,
                    "Parameter '${p.name}' is declared more than once",
                    suggestion = "Rename one of the parameters",
                )
            }

            typedParams.add(TypedParam(p.type, p.name, typedDefaultValue, p.isVarargs, p.loc, symbol))
        }
        return typedParams
    }

    private fun resolveGlobalInit(decl: RawGlobalVarDecl): TypedGlobalVarDecl {
        if (!decl.type.isValidAsVariable()) {
            errors.report(
                decl.loc,
                "Global variable '${decl.name}' cannot have type 'void'",
                suggestion = "Use a concrete type: int, double, boolean, string, json, or a struct type",
            )
        }

        var typedInit: TypedExpr? = null
        if (decl.initializer != null) {
            typedInit = exprResolver.resolveExpr(globalScope, decl.initializer)
            if (!decl.type.isAssignableFrom(typedInit.type)) {
                errors.report(
                    decl.loc,
                    "Global '${decl.name}': initializer has type '${typedInit.type}', but variable is declared as '${decl.type}'",
                    suggestion = DiagnosticHelpers.conversionHint(typedInit.type, decl.type),
                )
            }
        }
        return TypedGlobalVarDecl(decl.type, decl.name, typedInit, decl.loc)
    }

    private fun resolveImportDecl(decl: RawImportDecl): TypedImportDecl {
        // ImportResolver resolved paths into RawImportDecl? Or the path is not available?
        // We'll just put an empty string for resolvedPath for now if it wasn't mutated,
        // or we should fetch it from modules.
        val resolvedPath = modules.find { it.program.fileName.endsWith(decl.path) }?.sourcePath ?: decl.path
        return TypedImportDecl(decl.path, decl.namespace, decl.loc, resolvedPath)
    }
}
