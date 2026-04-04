package nox.compiler.semantic

import nox.compiler.CompilerErrors
import nox.compiler.ast.*
import nox.compiler.types.*

/**
 * Pass 1: Declaration Collection.
 *
 * Scans all top-level declarations in a [RawProgram] and registers them
 * in the global [SymbolTable] scope. After this pass, every type name,
 * function name, and global variable is known, enabling forward
 * references in subsequent passes.
 *
 * Type fields and function bodies are NOT resolved here; only names
 * and signatures are registered. Field type resolution happens in Pass 2.
 *
 * See docs/compiler/semantic-analysis.md.
 *
 * @property globalScope the root symbol table to populate
 * @property errors      shared error collector
 */
class DeclarationCollector(
    private val globalScope: SymbolTable,
    private val errors: CompilerErrors,
) {
    /** Counter for assigning global memory slots. */
    private var globalSlotCounter = 0

    /** Whether we have already seen a `main` definition. */
    private var mainSeen = false

    /**
     * Collect all top-level declarations from [program].
     *
     * After this method returns, [globalScope] contains a [TypeSymbol],
     * [FuncSymbol], or [GlobalSymbol] for every valid declaration.
     */
    fun collect(program: RawProgram) {
        for (decl in program.declarations) {
            when (decl) {
                is RawTypeDef -> collectType(decl)
                is RawFuncDef -> collectFunction(decl)
                is RawMainDef -> collectMain(decl, program)
                is RawGlobalVarDecl -> collectGlobal(decl)
                is RawImportDecl -> {} // Handled by ImportResolver
                is RawErrorDecl -> {} // Already reported during parsing
            }
        }
    }

    /**
     * Collects a type definition.
     *
     * @param decl the type definition to collect
     */
    private fun collectType(decl: RawTypeDef) {
        if (decl.fields.isEmpty()) {
            // Grammar requires fieldDeclaration+ but ANTLR error recovery
            // can still produce a TypeDef with zero fields. Skip it.
            errors.report(
                decl.loc,
                "Struct '${decl.name}' has no fields! Structs must have at least one field declaration",
                suggestion = "Add a field: 'type ${decl.name} { string name; }'",
            )
            return
        }

        // Register with empty fields; actual field types resolved in Pass 2
        val symbol = TypeSymbol(decl.name, linkedMapOf(), decl)
        if (!globalScope.define(decl.name, symbol)) {
            errors.report(
                decl.loc,
                "Type '${decl.name}' is already defined",
                suggestion = "Rename one of the type definitions or remove the duplicate",
            )
        }
    }

    /**
     * Collects a function definition.
     *
     * @param decl the function definition to collect
     */
    private fun collectFunction(decl: RawFuncDef) {
        var optionalSeen = false
        var varargsSeen = false

        val params =
            decl.params.mapIndexed { index, p ->
                // Validate Optional Param Ordering
                if (p.defaultValue != null) {
                    optionalSeen = true
                } else if (!p.isVarargs && optionalSeen) {
                    errors.report(
                        p.loc,
                        "Required parameter '${p.name}' must come before optional parameters",
                        suggestion = "Reorder parameters: put all required params before any with default values",
                    )
                }

                // Validate Varargs Constraints
                if (p.isVarargs) {
                    if (varargsSeen) {
                        errors.report(
                            p.loc,
                            "A function can only have one varargs parameter ('...')",
                            suggestion = "Remove extra '...' markers",
                        )
                    }
                    if (index != decl.params.size - 1) {
                        errors.report(
                            p.loc,
                            "Varargs parameter '${p.name}' must be the last parameter in the function signature",
                            suggestion = "Move '${p.name}' to the end of the parameter list",
                        )
                    }
                    if (p.defaultValue != null) {
                        errors.report(
                            p.loc,
                            "Varargs parameter '${p.name}' cannot have a default value",
                            suggestion = "Remove the '= ...' default from '${p.name}'",
                        )
                    }
                    varargsSeen = true
                }

                ParamSymbol(p.name, p.type, p.defaultValue, p.isVarargs)
            }

        val symbol = FuncSymbol(decl.name, decl.returnType, params, decl)
        if (!globalScope.define(decl.name, symbol)) {
            errors.report(
                decl.loc,
                "Function '${decl.name}' is already defined",
                suggestion = "Rename one of the functions or remove the duplicate definition",
            )
        }
    }

    /**
     * Collects the main function definition.
     *
     * @param decl the main function definition to collect
     * @param program the program to update
     */
    private fun collectMain(
        decl: RawMainDef,
        program: RawProgram,
    ) {
        if (mainSeen) {
            errors.report(
                decl.loc,
                "Only one 'main()' block is allowed per file",
                suggestion = "Remove the extra 'main()' definition",
            )
            return
        }
        mainSeen = true
    }

    /**
     * Collects a global variable definition.
     *
     * @param decl the global variable definition to collect
     */
    private fun collectGlobal(decl: RawGlobalVarDecl) {
        val symbol = GlobalSymbol(decl.name, decl.type, globalSlotCounter)
        if (!globalScope.define(decl.name, symbol)) {
            errors.report(
                decl.loc,
                "Global variable '${decl.name}' is already declared",
                suggestion = "Rename the variable or remove the duplicate declaration",
            )
            return
        }
        globalSlotCounter++
    }
}