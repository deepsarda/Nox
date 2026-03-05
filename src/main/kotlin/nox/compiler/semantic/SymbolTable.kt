package nox.compiler.semantic

/**
 * A scope in the lexical scope chain.
 *
 * Each `{ ... }` block creates a new child scope. Variables declared
 * in inner scopes shadow outer ones. The [lookup] method walks
 * the chain from innermost to outermost.
 *
 * ```
 * Global Scope (depth 0)
 * ├── TypeSymbol("Point")
 * ├── FuncSymbol("process")
 * │
 * └── Function Scope (depth 1)
 *     ├── ParamSymbol("url")
 *     │
 *     └── Block Scope (depth 2)
 *         └── VarSymbol("data")
 * ```
 *
 * See docs/compiler/semantic-analysis.md.
 *
 * @property parent the enclosing scope, or `null` for the global scope
 * @property depth  nesting depth (0 = global)
 */
class SymbolTable(
    private val parent: SymbolTable? = null,
    val depth: Int = 0,
) {
    private val symbols = mutableMapOf<String, Symbol>()

    /**
     * Look up a name, walking up the scope chain.
     *
     * @return the first matching [Symbol], or `null` if unresolved
     */
    fun lookup(name: String): Symbol? =
        symbols[name] ?: parent?.lookup(name)

    /**
     * Look up a name in the current scope only (aka no chain walk).
     *
     * Useful for detecting duplicate definitions within the same scope.
     */
    fun lookupLocal(name: String): Symbol? = symbols[name]

    /**
     * Define a symbol in the current scope.
     *
     * @return `true` if the symbol was successfully added,
     *         `false` if a symbol with the same name already exists in this scope
     */
    fun define(name: String, symbol: Symbol): Boolean {
        if (name in symbols) return false
        symbols[name] = symbol
        return true
    }

    /**
     * Create a child scope (entering a `{ ... }` block).
     */
    fun child(): SymbolTable = SymbolTable(parent = this, depth = depth + 1)

    /**
     * Return an unmodifiable view of all symbols defined in this scope.
     *
     * Does **not** include parent symbols. Intended for testing and debugging.
     */
    fun allSymbols(): Map<String, Symbol> = symbols.toMap()
}
