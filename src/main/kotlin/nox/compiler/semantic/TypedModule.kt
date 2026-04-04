package nox.compiler.semantic

import nox.compiler.ast.typed.TypedProgram

/**
 * A fully resolved and type-checked import module, ready for codegen.
 */
data class TypedModule(
    val namespace: String,
    val sourcePath: String,
    val program: TypedProgram,
    val globalBaseOffset: Int,
    val globalCount: Int,
)
