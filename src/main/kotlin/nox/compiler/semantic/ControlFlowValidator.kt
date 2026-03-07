package nox.compiler.semantic

import nox.compiler.CompilerErrors
import nox.compiler.CompilerWarnings
import nox.compiler.ast.*
import nox.compiler.types.TypeRef

/**
 * Pass 3: Control Flow Validation.
 *
 * Validates execution flow properties of the annotated AST:
 * - **Return path analysis:** every non-void function must return on all code paths.
 * - **Loop context tracking:** `break` and `continue` are only valid inside loops.
 * - **Dead code detection:** warns on unreachable statements after `return`, `throw`,
 *   `break`, or `continue`.
 * 
 * See docs/compiler/semantic-analysis.md.
 *
 * @property errors   shared error collector
 * @property warnings shared warning collector
 */
class ControlFlowValidator(
    private val errors: CompilerErrors,
    private val warnings: CompilerWarnings,
) {
    /** Current loop nesting depth. `break`/`continue` are only valid when > 0. */
    private var loopDepth = 0

    /**
     * Validate the entire [program].
     *
     * Checks return paths for every non-void function, then walks every
     * function body (including `main`) for loop-context and dead-code issues.
     */
    fun validate(program: Program) {
        // Return path analysis for non-void functions
        for (decl in program.declarations) {
            if (decl is FuncDef && decl.returnType != TypeRef.VOID) {
                if (!allPathsReturn(decl.body)) {
                    errors.report(
                        decl.loc,
                        "Function '${decl.name}' must return a value of type '${decl.returnType}' on all code paths",
                    )
                }
            }
        }

        // Walk every function body for loop-context and dead-code checks
        for (decl in program.declarations) {
            when (decl) {
                is FuncDef -> validateBlock(decl.body)
                else -> {} // TypeDef, GlobalVarDecl, ImportDecl, ErrorDecl, etc don't have bodies.
            }
        }

        // Walk main body
        program.main?.let { validateBlock(it.body) }
    }

    /**
     * Returns `true` if every execution path through [block] ends with
     * a `return` or `throw` statement.
     */
    private fun allPathsReturn(block: Block): Boolean {
        for (stmt in block.statements) {
            if (definitelyTerminates(stmt)) return true
        }
        return false
    }

    /**
     * Returns `true` if [stmt] guarantees that control flow will not
     * reach the statement that follows it (i.e. it always returns or throws).
     *
     * Note: `break`/`continue` are intentionally excluded since they terminate
     * the current loop iteration, not the enclosing function.
     */
    private fun definitelyTerminates(stmt: Stmt): Boolean =
        when (stmt) {
            is ReturnStmt -> true
            is ThrowStmt -> true

            is IfStmt -> {
                // Only terminates if all branches exist and all terminate.
                if (stmt.elseBlock == null) {
                    false // No else, there's a path that falls through
                } else {
                    val thenTerminates = allPathsReturn(stmt.thenBlock)
                    val elseIfsTerminate = stmt.elseIfs.all { allPathsReturn(it.body) }
                    val elseTerminates = allPathsReturn(stmt.elseBlock!!)
                    thenTerminates && elseIfsTerminate && elseTerminates
                }
            }

            is TryCatchStmt -> {
                allPathsReturn(stmt.tryBlock) &&
                    stmt.catchClauses.all { allPathsReturn(it.body) }
            }

            is Block -> allPathsReturn(stmt)

            // Loops, variable declarations, expressions, etc. don't guarantee termination
            else -> false
        }

    /**
     * Recursively validate all statements in [block] for:
     * - `break`/`continue` outside a loop
     * - dead code after terminating statements
     */
    private fun validateBlock(block: Block) {
        var terminated = false

        for (stmt in block.statements) {
            // Dead code detection: any statement after a terminator is unreachable
            if (terminated) {
                warnings.report(stmt.loc, "Unreachable code")
                break // Only report once per block
            }

            // Check this statement
            validateStmt(stmt)

            // Mark as terminated if this statement always exits the block
            if (stmt is ReturnStmt || stmt is ThrowStmt ||
                stmt is BreakStmt || stmt is ContinueStmt
            ) {
                terminated = true
            }
        }
    }

    /**
     * Validate a single statement for loop context and recurse into
     * child blocks.
     */
    private fun validateStmt(stmt: Stmt) {
        when (stmt) {
            // Loop context checks
            is BreakStmt -> {
                if (loopDepth == 0) {
                    errors.report(stmt.loc, "'break' can only appear inside a loop")
                }
            }
            is ContinueStmt -> {
                if (loopDepth == 0) {
                    errors.report(stmt.loc, "'continue' can only appear inside a loop")
                }
            }

            // Loops: increment depth, validate body, decrement
            is WhileStmt -> {
                loopDepth++
                validateBlock(stmt.body)
                loopDepth--
            }
            is ForStmt -> {
                loopDepth++
                validateBlock(stmt.body)
                loopDepth--
            }
            is ForEachStmt -> {
                loopDepth++
                validateBlock(stmt.body)
                loopDepth--
            }

            // Recurse into child blocks
            is IfStmt -> {
                validateBlock(stmt.thenBlock)
                for (elseIf in stmt.elseIfs) {
                    validateBlock(elseIf.body)
                }
                stmt.elseBlock?.let { validateBlock(it) }
            }
            is TryCatchStmt -> {
                validateBlock(stmt.tryBlock)
                for (cc in stmt.catchClauses) {
                    validateBlock(cc.body)
                }
            }
            is Block -> validateBlock(stmt)

            // Leaf statements nothing to recurse into
            is VarDeclStmt, is AssignStmt, is IncrementStmt,
            is ReturnStmt, is YieldStmt, is ThrowStmt,
            is ExprStmt, is ErrorStmt,
            -> {}
        }
    }
}
