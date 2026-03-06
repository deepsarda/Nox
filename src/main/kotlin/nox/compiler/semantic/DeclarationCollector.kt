package nox.compiler.semantic

import nox.compiler.CompilerErrors
import nox.compiler.ast.*
import nox.compiler.types.*

/**
 * Pass 1: Declaration Collection.
 *
 * Scans all top-level declarations in a [Program] and registers them
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
    fun collect(program: Program) {
        for (decl in program.declarations) {
            when (decl) {
                is TypeDef -> collectType(decl)
                is FuncDef -> collectFunction(decl)
                is MainDef -> collectMain(decl, program)
                is GlobalVarDecl -> collectGlobal(decl)
                is ImportDecl -> {} // Handled by ImportResolver
                is ErrorDecl -> {} // Already reported during parsing
            }
        }
    }

    /**
     * Collects a type definition.
     * 
     * @param decl the type definition to collect
     */
    private fun collectType(decl: TypeDef) {
        if (decl.fields.isEmpty()) {
            // Grammar requires fieldDeclaration+ but ANTLR error recovery
            // can still produce a TypeDef with zero fields. Skip it.
            errors.report(decl.loc, "Empty struct '${decl.name}' is not allowed")
            return
        }

        // Register with empty fields; actual field types resolved in Pass 2
        val symbol = TypeSymbol(decl.name, linkedMapOf(), decl)
        if (!globalScope.define(decl.name, symbol)) {
            errors.report(decl.loc, "Duplicate type definition '${decl.name}'")
        }
    }

    /**
     * Collects a function definition.
     * 
     * @param decl the function definition to collect
     */
    private fun collectFunction(decl: FuncDef) {
        val params = decl.params.map { p ->
            ParamSymbol(p.name, p.type, p.defaultValue)
        }
        val symbol = FuncSymbol(decl.name, decl.returnType, params, decl)
        if (!globalScope.define(decl.name, symbol)) {
            errors.report(decl.loc, "Duplicate function definition '${decl.name}'")
        }
    }

    /**
     * Collects the main function definition.
     * 
     * @param decl the main function definition to collect
     * @param program the program to update
     */
    private fun collectMain(decl: MainDef, program: Program) {
        if (mainSeen) {
            errors.report(decl.loc, "Multiple main definitions")
            return
        }
        mainSeen = true
        program.main = decl
    }

    /**
     * Collects a global variable definition.
     * 
     * @param decl the global variable definition to collect
     */
    private fun collectGlobal(decl: GlobalVarDecl) {
        val slot = globalSlotCounter++
        val symbol = GlobalSymbol(decl.name, decl.type, slot)
        if (!globalScope.define(decl.name, symbol)) {
            errors.report(decl.loc, "Duplicate global variable '${decl.name}'")
            return
        }
        decl.globalSlot = slot
    }
}
