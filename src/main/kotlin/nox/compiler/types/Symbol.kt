package nox.compiler.types

import nox.compiler.ast.*

/**
 * A named entity registered in a [SymbolTable] scope.
 *
 * Symbols are created during declaration collection (Pass 1) and
 * referenced during type resolution (Pass 2).
 *
 * See docs/compiler/semantic-analysis.md.
 */
sealed interface Symbol {
    val name: String
    val type: TypeRef
}

/**
 * A local variable declared inside a function body.
 *
 * @property scopeDepth nesting depth of the scope this variable lives in
 * @property register   pMem or rMem register index assigned by the register allocator (-1 = unassigned)
 */
data class VarSymbol(
    override val name: String,
    override val type: TypeRef,
    val scopeDepth: Int,
) : Symbol {
    /** Register assigned by the register allocator. Set during codegen. */
    var register: Int = -1
}

/**
 * A function parameter.
 *
 * @property defaultValue the default value expression, or `null` if the parameter is required
 * @property register     pMem or rMem register index assigned by the register allocator (-1 = unassigned)
 */
data class ParamSymbol(
    override val name: String,
    override val type: TypeRef,
    val defaultValue: RawExpr?,
    val isVarargs: Boolean = false,
) : Symbol {
    /** Register assigned by the register allocator. Set during codegen. */
    var register: Int = -1
}

/**
 * A global variable declared at module level.
 *
 * @property globalSlot index into global memory, offset by the module's `globalBaseOffset`
 */
data class GlobalSymbol(
    override val name: String,
    override val type: TypeRef,
    val globalSlot: Int,
) : Symbol

/**
 * A user-defined function (or `main`).
 *
 * @property returnType the declared return type
 * @property params     parameter symbols in declaration order
 * @property astNode    back-reference to the AST node for codegen
 */
data class FuncSymbol(
    override val name: String,
    val returnType: TypeRef,
    val params: List<ParamSymbol>,
    val astNode: RawFuncDef,
) : Symbol {
    override val type: TypeRef get() = returnType
}

/**
 * A struct type definition.
 *
 * @property fields ordered map of field names to their types
 * @property astNode back-reference to the AST node
 */
data class TypeSymbol(
    override val name: String,
    val fields: LinkedHashMap<String, TypeRef>,
    val astNode: RawTypeDef,
) : Symbol {
    override val type: TypeRef get() = TypeRef(name)
}

/**
 * A namespace reference (imported module or built-in).
 */
data class NamespaceSymbol(
    override val name: String,
) : Symbol {
    override val type: TypeRef get() = TypeRef.JSON
}
