package nox.compiler.codegen

import nox.compiler.ast.typed.*
import nox.compiler.types.*

/**
 * Performs a backward dataflow scan over the AST to compute fine-grained
 * variable liveness intervals for optimal register reuse.
 */
class LivenessAnalyzer {
    // Maps an AST node to the list of symbols whose registers can be freed AFTER executing the node.
    val freeAtNode = mutableMapOf<Any, MutableList<Symbol>>()

    // The set of symbols currently "live" (referenced moving backwards from the end of the block).
    private val live = mutableSetOf<Symbol>()

    // Whether to record nodes that can be freed. Set to false during fixed-point iterations.
    private var recordFrees = true

    /** Run the analyzer on a function definition. */
    fun analyze(func: TypedFuncDef) {
        live.clear()
        freeAtNode.clear()
        recordFrees = true
        analyzeBlock(func.body)
    }

    /** Run the analyzer on the main definition. */
    fun analyze(main: TypedMainDef) {
        live.clear()
        freeAtNode.clear()
        recordFrees = true
        analyzeBlock(main.body)
    }

    private fun analyzeBlock(block: TypedBlock) {
        for (i in block.statements.indices.reversed()) {
            analyzeStmt(block.statements[i])
        }
    }

    private fun analyzeStmt(stmt: TypedStmt) {
        when (stmt) {
            is TypedVarDeclStmt -> {
                val sym = stmt.resolvedSymbol
                if (sym != null && sym !in live) {
                    if (recordFrees) {
                        freeAtNode.getOrPut(stmt) { mutableListOf() }.add(sym)
                    }
                }

                // Initialize value
                analyzeExpr(stmt.initializer, stmt)

                // Definition: Variable is killed (born going forward) so it is no longer live going backward.
                sym?.let { live.remove(it) }
            }

            is TypedAssignStmt -> {
                analyzeExpr(stmt.target, stmt)
                analyzeExpr(stmt.value, stmt)
            }

            is TypedIncrementStmt -> {
                analyzeExpr(stmt.target, stmt)
            }

            is TypedIfStmt -> {
                val liveAfterIf = live.toSet()

                analyzeBlock(stmt.thenBlock)
                val liveInThen = live.toSet()

                var liveInElseBlock = liveAfterIf
                if (stmt.elseBlock != null) {
                    live.clear()
                    live.addAll(liveAfterIf)
                    analyzeBlock(stmt.elseBlock)
                    liveInElseBlock = live.toSet()
                }

                val allLiveInBranches = mutableSetOf<Symbol>()
                allLiveInBranches.addAll(liveInThen)
                allLiveInBranches.addAll(liveInElseBlock)

                val reversedElseIfs = stmt.elseIfs.reversed()
                for (elseIf in reversedElseIfs) {
                    live.clear()
                    live.addAll(liveAfterIf)
                    analyzeBlock(elseIf.body)
                    allLiveInBranches.addAll(live)

                    live.clear()
                    live.addAll(allLiveInBranches)
                    analyzeExpr(elseIf.condition, elseIf.condition)
                    allLiveInBranches.addAll(live)
                }

                live.clear()
                live.addAll(allLiveInBranches)
                analyzeExpr(stmt.condition, stmt.condition)
            }

            is TypedWhileStmt -> {
                val liveAfterLoop = live.toSet()
                var currentLiveIn = liveAfterLoop.toSet()
                var iteration = 0
                val wasRecording = recordFrees
                recordFrees = false
                do {
                    val previousLiveIn = currentLiveIn

                    live.clear()
                    live.addAll(liveAfterLoop)

                    // Back-edge carries liveness from loop start to loop end
                    live.addAll(previousLiveIn)

                    analyzeBlock(stmt.body)
                    analyzeExpr(stmt.condition, stmt.condition)

                    currentLiveIn = live.toSet()
                    iteration++
                } while (currentLiveIn != previousLiveIn && iteration < 10)

                recordFrees = wasRecording
                if (recordFrees) {
                    live.clear()
                    live.addAll(liveAfterLoop)
                    live.addAll(currentLiveIn)
                    analyzeBlock(stmt.body)
                    analyzeExpr(stmt.condition, stmt.condition)
                } else {
                    live.clear()
                    live.addAll(currentLiveIn)
                }
            }

            is TypedForStmt -> {
                val liveAfterLoop = live.toSet()
                var currentLiveIn = liveAfterLoop.toSet()
                var iteration = 0
                val wasRecording = recordFrees
                recordFrees = false
                do {
                    val previousLiveIn = currentLiveIn
                    live.clear()
                    live.addAll(liveAfterLoop)
                    live.addAll(previousLiveIn)

                    stmt.update?.let { analyzeStmt(it) }
                    analyzeBlock(stmt.body)
                    stmt.condition?.let { analyzeExpr(it, it) }

                    currentLiveIn = live.toSet()
                    iteration++
                } while (currentLiveIn != previousLiveIn && iteration < 10)

                recordFrees = wasRecording
                if (recordFrees) {
                    live.clear()
                    live.addAll(liveAfterLoop)
                    live.addAll(currentLiveIn)
                    stmt.update?.let { analyzeStmt(it) }
                    analyzeBlock(stmt.body)
                    stmt.condition?.let { analyzeExpr(it, it) }
                } else {
                    live.clear()
                    live.addAll(currentLiveIn)
                }

                stmt.init?.let { analyzeStmt(it) }
            }

            is TypedForEachStmt -> {
                val liveAfterLoop = live.toSet()
                var currentLiveIn = liveAfterLoop.toSet()
                var iteration = 0
                val wasRecording = recordFrees
                recordFrees = false
                do {
                    val previousLiveIn = currentLiveIn
                    live.clear()
                    live.addAll(liveAfterLoop)
                    live.addAll(previousLiveIn)

                    analyzeBlock(stmt.body)

                    currentLiveIn = live.toSet()
                    iteration++
                } while (currentLiveIn != previousLiveIn && iteration < 10)

                recordFrees = wasRecording
                if (recordFrees) {
                    live.clear()
                    live.addAll(liveAfterLoop)
                    live.addAll(currentLiveIn)
                    analyzeBlock(stmt.body)
                } else {
                    live.clear()
                    live.addAll(currentLiveIn)
                }

                analyzeExpr(stmt.iterable, stmt.iterable)
            }

            is TypedReturnStmt -> stmt.value?.let { analyzeExpr(it, stmt) }
            is TypedYieldStmt -> analyzeExpr(stmt.value, stmt)
            is TypedBreakStmt, is TypedContinueStmt -> { // Control flow skip
            }

            is TypedThrowStmt -> analyzeExpr(stmt.value, stmt)
            is TypedTryCatchStmt -> {
                val liveAfter = live.toSet()
                val allLive = mutableSetOf<Symbol>()
                allLive.addAll(liveAfter)

                for (clause in stmt.catchClauses) {
                    live.clear()
                    live.addAll(liveAfter)
                    analyzeBlock(clause.body)
                    allLive.addAll(live)
                }

                live.clear()
                live.addAll(allLive)
                analyzeBlock(stmt.tryBlock)
            }

            is TypedExprStmt -> analyzeExpr(stmt.expression, stmt)
            is TypedBlock -> analyzeBlock(stmt)
            is TypedErrorStmt -> {}
        }
    }

    private fun analyzeExpr(
        expr: TypedExpr,
        @Suppress("UNUSED_PARAMETER") parentNode: Any,
    ) {
        when (expr) {
            is TypedIdentifierExpr -> {
                val sym = expr.resolvedSymbol
                if (sym != null && (sym is VarSymbol || sym is ParamSymbol)) {
                    if (sym !in live) {
                        if (recordFrees) {
                            freeAtNode.getOrPut(expr) { mutableListOf() }.add(sym)
                        }
                        live.add(sym)
                    }
                }
            }

            is TypedBinaryExpr -> {
                // Evaluate left-to-right, so backward scan is right-to-left
                analyzeExpr(expr.right, expr)
                analyzeExpr(expr.left, expr)
            }

            is TypedUnaryExpr -> analyzeExpr(expr.operand, expr)
            is TypedPostfixExpr -> analyzeExpr(expr.operand, expr)
            is TypedCastExpr -> analyzeExpr(expr.operand, expr)
            is TypedFuncCallExpr -> {
                for (arg in expr.args.reversed()) analyzeExpr(arg, expr)
            }

            is TypedMethodCallExpr -> {
                for (arg in expr.args.reversed()) analyzeExpr(arg, expr)
                analyzeExpr(expr.target, expr)
            }

            is TypedFieldAccessExpr -> analyzeExpr(expr.target, expr)
            is TypedIndexAccessExpr -> {
                analyzeExpr(expr.index, expr)
                analyzeExpr(expr.target, expr)
            }

            is TypedArrayLiteralExpr -> {
                for (el in expr.elements.reversed()) analyzeExpr(el, expr)
            }

            is TypedStructLiteralExpr -> {
                for (field in expr.fields.reversed()) analyzeExpr(field.value, expr)
            }

            is TypedTemplateLiteralExpr -> {
                for (part in expr.parts.reversed()) {
                    if (part is TypedTemplatePart.Interpolation) {
                        analyzeExpr(part.expression, expr)
                    }
                }
            }

            is TypedIntLiteralExpr, is TypedDoubleLiteralExpr, is TypedStringLiteralExpr,
            is TypedBoolLiteralExpr, is TypedNullLiteralExpr, is TypedErrorExpr,
            -> {
                // Literals don't depend on any other variables
            }
        }
    }
}
