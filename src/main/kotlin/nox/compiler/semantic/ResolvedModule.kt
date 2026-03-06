package nox.compiler.semantic

import nox.compiler.ast.Program

/**
 * A fully resolved import module, ready for codegen.
 *
 * Created by [ImportResolver] after an `import "path.nox" as namespace;`
 * declaration is successfully resolved, parsed, and semantically analyzed.
 *
 * @property namespace        the user-chosen namespace alias (e.g. `"helpers"`)
 * @property sourcePath       the absolute, normalized file path of the imported file
 * @property program          the parsed and analyzed AST of the imported file
 * @property globalBaseOffset the starting index for this module's globals in global memory
 * @property globalCount      the number of global variables declared by this module
 */
data class ResolvedModule(
    val namespace: String,
    val sourcePath: String,
    val program: Program,
    val globalBaseOffset: Int,
    val globalCount: Int,
)

/**
 * Cache entry for an already-resolved file.
 *
 * When the same `.nox` file is imported from multiple locations, we reuse
 * the parsed [Program] and its global memory allocation rather than
 * re-parsing and re-resolving the file.
 */
data class ResolvedFile(
    val program: Program,
    val globalBaseOffset: Int,
    val globalCount: Int,
)
