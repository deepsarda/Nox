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

        val typedImports = program.imports.map { resolveImportDecl(it) }

        val typedProgram =
            TypedProgram(
                fileName = program.fileName,
                headers = program.headers.map { TypedHeader(it.key, it.value, it.loc) },
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
            if (field.name in seenFields) {
                errors.report(
                    field.loc,
                    "Field '${field.name}' is declared more than once in struct '${typeDef.name}'",
                    suggestion = "Remove or rename the duplicate field",
                )
                continue
            }
            seenFields.add(field.name)

            if (!isKnownType(field.type)) {
                val candidates = globalScope.allNamesInScope { it is TypeSymbol }
                val suggestion =
                    DiagnosticHelpers.didYouMeanMsg(field.type.name, candidates)
                        ?: (
                            "Declare the type first with 'type ${field.type.name} { ... }' " +
                                "or use a built-in type (int, double, boolean, string, json)"
                        )
                errors.report(
                    field.loc,
                    "Unknown type '${field.type}' for field '${field.name}' in struct '${typeDef.name}'",
                    suggestion = suggestion,
                )
                continue
            }
            if (!field.type.isValidAsVariable()) {
                errors.report(
                    field.loc,
                    "Field '${field.name}' cannot have type '${field.type}' since 'void' is not allowed for struct fields",
                )
                continue
            }

            typeSym?.fields?.put(field.name, field.type)
            typedFields.add(TypedFieldDecl(field.type, field.name, field.loc))
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
        return TypedFuncDef(funcDef.returnType, funcDef.name, typedParams, typedBody as TypedBlock, funcDef.loc)
    }

    private fun resolveMain(mainDef: RawMainDef): TypedMainDef {
        val mainScope = globalScope.child()
        val typedParams = registerParams(mainScope, mainDef.params)
        stmtResolver.isMainBody = true
        val typedBody = stmtResolver.resolveBlock(mainScope, mainDef.body, TypeRef.VOID)
        stmtResolver.isMainBody = false
        return TypedMainDef(TypeRef.STRING, typedParams, typedBody as TypedBlock, mainDef.loc)
    }

    private fun registerParams(
        scope: SymbolTable,
        params: List<RawParam>,
    ): List<TypedParam> {
        val typedParams = mutableListOf<TypedParam>()
        for (param in params) {
            if (!isKnownType(param.type)) {
                val candidates = globalScope.allNamesInScope { it is TypeSymbol }
                val suggestion =
                    DiagnosticHelpers.didYouMeanMsg(param.type.name, candidates)
                        ?: "Supported types: int, double, boolean, string, json, or a declared struct type"
                errors.report(
                    param.loc,
                    "Parameter '${param.name}' has unknown type '${param.type}'",
                    suggestion = suggestion,
                )
            }
            if (!param.type.isValidAsVariable()) {
                errors.report(
                    param.loc,
                    "Parameter '${param.name}' cannot have type 'void'",
                    suggestion = "Use a concrete type: int, double, boolean, string, json, or a struct type",
                )
            }

            var typedDefaultValue: TypedExpr? = null
            if (param.defaultValue != null) {
                // If it's a struct literal, ExpressionResolver needs to know the expected type.
                // We'll pass it down via context or handle it.
                // For now we assume exprResolver.resolveExpr can take expectedType?
                // Wait, ExpressionResolver.resolveExpr doesn't take expectedType.
                // It just returns TypedExpr.
                typedDefaultValue = exprResolver.resolveExpr(scope, param.defaultValue)
                if (!param.type.isAssignableFrom(typedDefaultValue.type)) {
                    errors.report(
                        param.defaultValue.loc,
                        "Default value for '${param.name}' has type '${typedDefaultValue.type}', but the parameter expects '${param.type}'",
                        suggestion = DiagnosticHelpers.conversionHint(typedDefaultValue.type, param.type),
                    )
                }
            }

            val symbol = ParamSymbol(param.name, param.type, param.defaultValue, param.isVarargs)
            if (!scope.define(param.name, symbol)) {
                errors.report(
                    param.loc,
                    "Parameter '${param.name}' is declared more than once",
                    suggestion = "Rename one of the parameters",
                )
            }

            typedParams.add(TypedParam(param.type, param.name, typedDefaultValue, param.isVarargs, param.loc, symbol))
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
