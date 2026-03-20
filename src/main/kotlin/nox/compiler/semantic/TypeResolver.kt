package nox.compiler.semantic

import nox.compiler.CompilerErrors
import nox.compiler.DiagnosticHelpers
import nox.compiler.ast.*
import nox.compiler.types.*
import nox.plugin.LibraryRegistry

/**
 * Pass 2: Type Resolution
 *
 * Coordinates struct field resolution, function body type-checking,
 * and global variable initialization validation. After this pass,
 * every `Expr` node has its `.resolvedType` set, every `IdentifierExpr`
 * has its `.resolvedSymbol` set, and every `MethodCallExpr` knows
 * exactly what it calls.
 *
 * See docs/compiler/semantic-analysis.md.
 *
 * @property globalScope the root symbol table (populated by Pass 1)
 * @property errors      shared error collector
 * @property modules     resolved import modules (from Phase 0)
 */
class TypeResolver(
    private val globalScope: SymbolTable,
    private val errors: CompilerErrors,
    private val modules: List<ResolvedModule> = emptyList(),
    private val registry: LibraryRegistry = LibraryRegistry.createDefault(),
) {
    private val exprResolver = ExpressionResolver(globalScope, errors, modules, registry)
    private val stmtResolver = StatementResolver(exprResolver, errors)
    private val resolvedModulePaths = mutableSetOf<String>()

    /**
     * Run Pass 2 on the given [program].
     *
     * 0. Analyze imported modules (Pass 1 + Pass 2, depth-first order).
     * 1. Resolve struct field types (all type names are now known from Pass 1).
     * 2. Resolve each function body.
     * 3. Resolve the `main` body (implicitly returns `string`).
     * 4. Resolve global variable initializers.
     */
    fun resolve(program: Program) {
        // Step 0: Analyze imported modules (docs/compiler/semantic-analysis.md line 71-73)
        // ImportResolver resolves in depth-first order, so we iterate in that order.
        for (module in modules) {
            if (module.sourcePath in resolvedModulePaths) continue
            resolvedModulePaths.add(module.sourcePath)

            val moduleScope = SymbolTable()
            DeclarationCollector(moduleScope, errors).collect(module.program)
            TypeResolver(moduleScope, errors, registry = registry).resolve(module.program)
        }

        // Step 1: Resolve struct field types
        for (decl in program.declarations) {
            if (decl is TypeDef) resolveStructFields(decl)
        }

        // Step 2: Resolve each function body
        for (decl in program.declarations) {
            if (decl is FuncDef) resolveFunction(decl)
        }

        // Step 3: Resolve main body
        program.main?.let { resolveMain(it) }

        // Step 4: Resolve global variable initializers
        for (decl in program.declarations) {
            if (decl is GlobalVarDecl) resolveGlobalInit(decl)
        }
    }

    /**
     * Populate the [TypeSymbol.fields] map for a struct definition.
     *
     * Each field's type is validated: primitives, arrays, and other
     * struct types (forward references work because Pass 1 registered
     * all type names).
     */
    private fun resolveStructFields(typeDef: TypeDef) {
        val typeSym = globalScope.lookup(typeDef.name) as? TypeSymbol ?: return

        val seenFields = mutableSetOf<String>()
        for (field in typeDef.fields) {
            // Check for duplicate field names
            if (field.name in seenFields) {
                errors.report(
                    field.loc,
                    "Field '${field.name}' is declared more than once in struct '${typeDef.name}'",
                    suggestion = "Remove or rename the duplicate field",
                )
                continue
            }
            seenFields.add(field.name)

            // Validate the field type exists and is a valid variable type (not void)
            if (!isKnownType(field.type)) {
                val candidates = globalScope.allNamesInScope { it is TypeSymbol }
                val suggestion =
                    DiagnosticHelpers.didYouMeanMsg(field.type.name, candidates)
                        ?: "Declare the type first with 'type ${field.type.name} { ... }' " +
                        "or use a built-in type (int, double, boolean, string, json)"
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

            typeSym.fields[field.name] = field.type
        }
    }

    /**
     * Check whether a [TypeRef] refers to a known type.
     *
     * Known types include primitives, built-in types (`string`, `json`, `void`),
     * and user-defined struct types registered in the global scope.
     */
    private fun isKnownType(type: TypeRef): Boolean {
        val baseName = type.name
        // Built-in type names are always valid
        if (baseName in BUILTIN_TYPE_NAMES) return true
        // User-defined struct types must be in the global scope
        return globalScope.lookup(baseName) is TypeSymbol
    }

    /**
     * Resolve a user-defined function: create a function scope,
     * register parameters, and resolve the body.
     */
    private fun resolveFunction(funcDef: FuncDef) {
        val funcScope = globalScope.child()
        registerParams(funcScope, funcDef.params)
        stmtResolver.resolveBlock(funcScope, funcDef.body, funcDef.returnType)
    }

    /**
     * Resolve the `main` entry point.
     * `main` can return any type and the runtime auto-converts to string.
     * We pass VOID as expectedReturn so resolveReturn accepts anything.
     */
    private fun resolveMain(mainDef: MainDef) {
        val mainScope = globalScope.child()
        registerParams(mainScope, mainDef.params)
        stmtResolver.isMainBody = true
        stmtResolver.resolveBlock(mainScope, mainDef.body, TypeRef.VOID)
        stmtResolver.isMainBody = false
    }

    /**
     * Register function parameters as [ParamSymbol] entries in the given scope.
     *
     * Validates that no two parameters share the same name, and that
     * each parameter's type is known.
     */
    private fun registerParams(
        scope: SymbolTable,
        params: List<Param>,
    ) {
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

            // Validate default value type (P2)
            if (param.defaultValue != null) {
                // Set struct type for struct literal default values
                if (param.defaultValue is StructLiteralExpr && param.type.isStructType()) {
                    param.defaultValue.structType = param.type
                }
                val defaultValueType = exprResolver.resolveExpr(scope, param.defaultValue)
                if (!param.type.isAssignableFrom(defaultValueType)) {
                    errors.report(
                        param.defaultValue.loc,
                        "Default value for '${param.name}' has type '${defaultValueType ?: "null"}', but the parameter expects '${param.type}'",
                        suggestion = DiagnosticHelpers.conversionHint(defaultValueType, param.type),
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
            } else {
                param.resolvedSymbol = symbol // back-link so codegen can write sym.register
            }
        }
    }

    /**
     * Resolve and type-check a global variable's initializer expression.
     */
    private fun resolveGlobalInit(decl: GlobalVarDecl) {
        if (!decl.type.isValidAsVariable()) {
            errors.report(
                decl.loc,
                "Global variable '${decl.name}' cannot have type 'void'",
                suggestion = "Use a concrete type: int, double, boolean, string, json, or a struct type",
            )
        }
        if (decl.initializer == null) return

        // Set struct type for struct literal initializers
        val init = decl.initializer
        if (init is StructLiteralExpr && decl.type.isStructType()) {
            init.structType = decl.type
        }

        val initType = exprResolver.resolveExpr(globalScope, init)
        if (!decl.type.isAssignableFrom(initType)) {
            errors.report(
                decl.loc,
                "Global '${decl.name}': initializer has type '${initType ?: "null"}', but variable is declared as '${decl.type}'",
                suggestion = DiagnosticHelpers.conversionHint(initType, decl.type),
            )
        }
    }
}
