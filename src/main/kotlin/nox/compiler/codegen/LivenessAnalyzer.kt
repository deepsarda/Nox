package nox.compiler.codegen

import nox.compiler.ast.*
import nox.compiler.types.ParamSymbol
import nox.compiler.types.Symbol
import nox.compiler.types.VarSymbol

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
    fun analyze(func: FuncDef) {
        live.clear()
        freeAtNode.clear()
        recordFrees = true
        analyzeBlock(func.body)
    }

    /** Run the analyzer on the main definition. */
    fun analyze(main: MainDef) {
        live.clear()
        freeAtNode.clear()
        recordFrees = true
        analyzeBlock(main.body)
    }

    private fun analyzeBlock(block: Block) {
        for (i in block.statements.indices.reversed()) {
            analyzeStmt(block.statements[i])
        }
    }

    private fun analyzeStmt(stmt: Stmt) {
        when (stmt) {
            is VarDeclStmt -> {
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

            is AssignStmt -> {
                analyzeExpr(stmt.target, stmt)
                analyzeExpr(stmt.value, stmt)
            }

            is IncrementStmt -> {
                analyzeExpr(stmt.target, stmt)
            }

            is IfStmt -> {
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

            is WhileStmt -> {
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

            is ForStmt -> {
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

            is ForEachStmt -> {
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

            is ReturnStmt -> stmt.value?.let { analyzeExpr(it, stmt) }
            is YieldStmt -> analyzeExpr(stmt.value, stmt)
            is BreakStmt, is ContinueStmt -> { // Control flow skip
            }

            is ThrowStmt -> analyzeExpr(stmt.value, stmt)
            is TryCatchStmt -> {
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

            is ExprStmt -> analyzeExpr(stmt.expression, stmt)
            is Block -> analyzeBlock(stmt)
            is ErrorStmt -> {}
        }
    }

    private fun analyzeExpr(
        expr: Expr,
        @Suppress("UNUSED_PARAMETER") parentNode: Any,
    ) {
        when (expr) {
            is IdentifierExpr -> {
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

            is BinaryExpr -> {
                // Evaluate left-to-right, so backward scan is right-to-left
                analyzeExpr(expr.right, expr)
                analyzeExpr(expr.left, expr)
            }

            is UnaryExpr -> analyzeExpr(expr.operand, expr)
            is PostfixExpr -> analyzeExpr(expr.operand, expr)
            is CastExpr -> analyzeExpr(expr.operand, expr)
            is FuncCallExpr -> {
                for (arg in expr.args.reversed()) analyzeExpr(arg, expr)
            }

            is MethodCallExpr -> {
                for (arg in expr.args.reversed()) analyzeExpr(arg, expr)
                analyzeExpr(expr.target, expr)
            }

            is FieldAccessExpr -> analyzeExpr(expr.target, expr)
            is IndexAccessExpr -> {
                analyzeExpr(expr.index, expr)
                analyzeExpr(expr.target, expr)
            }

            is ArrayLiteralExpr -> {
                for (el in expr.elements.reversed()) analyzeExpr(el, expr)
            }

            is StructLiteralExpr -> {
                for (field in expr.fields.reversed()) analyzeExpr(field.value, expr)
            }

            is TemplateLiteralExpr -> {
                for (part in expr.parts.reversed()) {
                    if (part is TemplatePart.Interpolation) {
                        analyzeExpr(part.expression, expr)
                    }
                }
            }

            is IntLiteralExpr, is DoubleLiteralExpr, is StringLiteralExpr,
            is BoolLiteralExpr, is NullLiteralExpr, is ErrorExpr,
            -> {
                // Literals don't depend on any other variables
            }
        }
    }
}
