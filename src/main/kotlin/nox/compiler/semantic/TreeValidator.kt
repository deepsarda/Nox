package nox.compiler.semantic

import nox.compiler.CompilerErrors
import nox.compiler.ast.*
import nox.compiler.ast.typed.*
import nox.compiler.types.SourceLocation

/**
 * Validates that a [TypedProgram] exactly matches the structure and source
 * locations of its originating [RawProgram].
 *
 * This ensures that the Semantic Analysis (Lowering) phase does not accidentally
 * drop nodes, duplicate nodes, or invent new structural nodes without retaining
 * correct source mappings.
 */
class TreeValidator(
    private val errors: CompilerErrors,
) {
    fun validate(
        raw: RawProgram,
        typed: TypedProgram,
    ) {
        if (raw.declarations.size != typed.declarations.size) {
            errors.report(
                SourceLocation("", 0, 0),
                "AST mismatch: RawProgram has ${raw.declarations.size} declarations, TypedProgram has ${typed.declarations.size}",
            )
            return
        }

        for (i in raw.declarations.indices) {
            validateDecl(raw.declarations[i], typed.declarations[i])
        }
    }

    private fun validateDecl(
        raw: RawDecl,
        typed: TypedDecl,
    ) {
        if (raw.loc != typed.loc) {
            errors.report(
                raw.loc,
                "AST SourceLocation mismatch for declaration: Raw is at ${raw.loc}, Typed is at ${typed.loc}",
            )
        }

        when (raw) {
            is RawFuncDef -> {
                if (typed !is TypedFuncDef) return reportMismatch(raw, typed)
                if (raw.name != typed.name) return reportMismatch(raw, typed)
                if (raw.params.size != typed.params.size) return reportMismatch(raw, typed)
                for (i in raw.params.indices) {
                    val rParam = raw.params[i]
                    val tParam = typed.params[i]
                    if (rParam.loc != tParam.loc || rParam.name != tParam.name) return reportMismatch(raw, typed)
                    if (rParam.defaultValue != null && tParam.defaultValue != null) {
                        validateExpr(rParam.defaultValue, tParam.defaultValue)
                    } else if (rParam.defaultValue != null || tParam.defaultValue != null) {
                        return reportMismatch(raw, typed)
                    }
                }
                validateStmt(raw.body, typed.body)
            }
            is RawMainDef -> {
                if (typed !is TypedMainDef) return reportMismatch(raw, typed)
                if (raw.params.size != typed.params.size) return reportMismatch(raw, typed)
                validateStmt(raw.body, typed.body)
            }
            is RawGlobalVarDecl -> {
                if (typed !is TypedGlobalVarDecl) return reportMismatch(raw, typed)
                if (raw.name != typed.name) return reportMismatch(raw, typed)
                if (raw.initializer != null && typed.initializer != null) {
                    validateExpr(raw.initializer, typed.initializer)
                } else if (raw.initializer != null || typed.initializer != null) {
                    return reportMismatch(raw, typed)
                }
            }
            is RawTypeDef -> {
                if (typed !is TypedTypeDef) return reportMismatch(raw, typed)
                if (raw.name != typed.name) return reportMismatch(raw, typed)
                if (raw.fields.size != typed.fields.size) return reportMismatch(raw, typed)
                for (i in raw.fields.indices) {
                    if (raw.fields[i].loc != typed.fields[i].loc || raw.fields[i].name != typed.fields[i].name) {
                        return reportMismatch(raw, typed)
                    }
                }
            }
            is RawImportDecl -> {
                if (typed !is TypedImportDecl) return reportMismatch(raw, typed)
            }
            is RawErrorDecl -> {
                if (typed !is TypedErrorDecl) return reportMismatch(raw, typed)
            }
        }
    }

    private fun validateStmt(
        raw: RawStmt,
        typed: TypedStmt,
    ) {
        if (raw.loc != typed.loc) {
            errors.report(
                raw.loc,
                "AST SourceLocation mismatch for statement: Raw is at ${raw.loc}, Typed is at ${typed.loc}",
            )
        }

        when (raw) {
            is RawBlock -> {
                if (typed !is TypedBlock) return reportMismatch(raw, typed)
                if (raw.statements.size != typed.statements.size) return reportMismatch(raw, typed)
                for (i in raw.statements.indices) {
                    validateStmt(raw.statements[i], typed.statements[i])
                }
            }
            is RawVarDeclStmt -> {
                if (typed !is TypedVarDeclStmt) return reportMismatch(raw, typed)
                if (raw.name != typed.name) return reportMismatch(raw, typed)
                if (raw.initializer != null) validateExpr(raw.initializer, typed.initializer)
            }
            is RawAssignStmt -> {
                if (typed !is TypedAssignStmt) return reportMismatch(raw, typed)
                validateExpr(raw.target, typed.target)
                validateExpr(raw.value, typed.value)
            }
            is RawIncrementStmt -> {
                if (typed !is TypedIncrementStmt) return reportMismatch(raw, typed)
                validateExpr(raw.target, typed.target)
            }
            is RawExprStmt -> {
                if (typed !is TypedExprStmt) return reportMismatch(raw, typed)
                validateExpr(raw.expression, typed.expression)
            }
            is RawReturnStmt -> {
                if (typed !is TypedReturnStmt) return reportMismatch(raw, typed)
                if (raw.value != null && typed.value != null) {
                    validateExpr(raw.value, typed.value)
                } else if (raw.value != null || typed.value != null) {
                    return reportMismatch(raw, typed)
                }
            }
            is RawYieldStmt -> {
                if (typed !is TypedYieldStmt) return reportMismatch(raw, typed)
                if (raw.value != null && typed.value != null) {
                    validateExpr(raw.value, typed.value)
                } else if (raw.value != null || typed.value != null) {
                    return reportMismatch(raw, typed)
                }
            }
            is RawThrowStmt -> {
                if (typed !is TypedThrowStmt) return reportMismatch(raw, typed)
                validateExpr(raw.value, typed.value)
            }
            is RawIfStmt -> {
                if (typed !is TypedIfStmt) return reportMismatch(raw, typed)
                validateExpr(raw.condition, typed.condition)
                validateStmt(raw.thenBlock, typed.thenBlock)
                if (raw.elseIfs.size != typed.elseIfs.size) return reportMismatch(raw, typed)
                for (i in raw.elseIfs.indices) {
                    validateExpr(raw.elseIfs[i].condition, typed.elseIfs[i].condition)
                    validateStmt(raw.elseIfs[i].body, typed.elseIfs[i].body)
                }
                if (raw.elseBlock != null && typed.elseBlock != null) {
                    validateStmt(raw.elseBlock, typed.elseBlock)
                } else if (raw.elseBlock != null || typed.elseBlock != null) {
                    return reportMismatch(raw, typed)
                }
            }
            is RawWhileStmt -> {
                if (typed !is TypedWhileStmt) return reportMismatch(raw, typed)
                validateExpr(raw.condition, typed.condition)
                validateStmt(raw.body, typed.body)
            }
            is RawForStmt -> {
                if (typed !is TypedForStmt) return reportMismatch(raw, typed)
                if (raw.init != null && typed.init != null) validateStmt(raw.init, typed.init)
                if (raw.condition != null && typed.condition != null) validateExpr(raw.condition, typed.condition)
                if (raw.update != null && typed.update != null) validateStmt(raw.update, typed.update)
                validateStmt(raw.body, typed.body)
            }
            is RawForEachStmt -> {
                if (typed !is TypedForEachStmt) return reportMismatch(raw, typed)
                validateExpr(raw.iterable, typed.iterable)
                validateStmt(raw.body, typed.body)
            }
            is RawBreakStmt -> {
                if (typed !is TypedBreakStmt) return reportMismatch(raw, typed)
            }
            is RawContinueStmt -> {
                if (typed !is TypedContinueStmt) return reportMismatch(raw, typed)
            }
            is RawTryCatchStmt -> {
                if (typed !is TypedTryCatchStmt) return reportMismatch(raw, typed)
                validateStmt(raw.tryBlock, typed.tryBlock)
                if (raw.catchClauses.size != typed.catchClauses.size) return reportMismatch(raw, typed)
                for (i in raw.catchClauses.indices) {
                    if (raw.catchClauses[i].variableName !=
                        typed.catchClauses[i].variableName
                    ) {
                        return reportMismatch(raw, typed)
                    }
                    validateStmt(raw.catchClauses[i].body, typed.catchClauses[i].body)
                }
            }
            is RawErrorStmt -> {
                if (typed !is TypedErrorStmt) return reportMismatch(raw, typed)
            }
        }
    }

    private fun validateExpr(
        raw: RawExpr,
        typed: TypedExpr,
    ) {
        if (raw.loc != typed.loc) {
            errors.report(
                raw.loc,
                "AST SourceLocation mismatch for expression: Raw is at ${raw.loc}, Typed is at ${typed.loc}",
            )
        }

        when (raw) {
            is RawIntLiteralExpr -> if (typed !is TypedIntLiteralExpr) return reportMismatch(raw, typed)
            is RawDoubleLiteralExpr -> if (typed !is TypedDoubleLiteralExpr) return reportMismatch(raw, typed)
            is RawBoolLiteralExpr -> if (typed !is TypedBoolLiteralExpr) return reportMismatch(raw, typed)
            is RawStringLiteralExpr -> if (typed !is TypedStringLiteralExpr) return reportMismatch(raw, typed)
            is RawNullLiteralExpr -> if (typed !is TypedNullLiteralExpr) return reportMismatch(raw, typed)
            is RawIdentifierExpr ->
                if (typed !is TypedIdentifierExpr ||
                    raw.name != typed.name
                ) {
                    return reportMismatch(raw, typed)
                }
            is RawTemplateLiteralExpr -> {
                if (typed !is TypedTemplateLiteralExpr) return reportMismatch(raw, typed)
                if (raw.parts.size != typed.parts.size) return reportMismatch(raw, typed)
                for (i in raw.parts.indices) {
                    val rPart = raw.parts[i]
                    val tPart = typed.parts[i]
                    if (rPart is RawTemplatePart.Interpolation && tPart is TypedTemplatePart.Interpolation) {
                        validateExpr(rPart.expression, tPart.expression)
                    } else if (rPart is RawTemplatePart.Text && tPart is TypedTemplatePart.Text) {
                        if (rPart.value != tPart.value) return reportMismatch(raw, typed)
                    } else if (rPart is RawTemplatePart.ErrorPart && tPart is TypedTemplatePart.ErrorPart) {
                        // Match
                    } else {
                        return reportMismatch(raw, typed)
                    }
                }
            }
            is RawArrayLiteralExpr -> {
                if (typed !is TypedArrayLiteralExpr) return reportMismatch(raw, typed)
                if (raw.elements.size != typed.elements.size) return reportMismatch(raw, typed)
                for (i in raw.elements.indices) {
                    validateExpr(raw.elements[i], typed.elements[i])
                }
            }
            is RawStructLiteralExpr -> {
                if (typed !is TypedStructLiteralExpr) return reportMismatch(raw, typed)
                if (raw.fields.size != typed.fields.size) return reportMismatch(raw, typed)
                for (i in raw.fields.indices) {
                    if (raw.fields[i].name != typed.fields[i].name) return reportMismatch(raw, typed)
                    validateExpr(raw.fields[i].value, typed.fields[i].value)
                }
            }
            is RawFieldAccessExpr -> {
                if (typed !is TypedFieldAccessExpr) return reportMismatch(raw, typed)
                if (raw.fieldName != typed.fieldName) return reportMismatch(raw, typed)
                validateExpr(raw.target, typed.target)
            }
            is RawIndexAccessExpr -> {
                if (typed !is TypedIndexAccessExpr) return reportMismatch(raw, typed)
                validateExpr(raw.target, typed.target)
                validateExpr(raw.index, typed.index)
            }
            is RawFuncCallExpr -> {
                if (typed !is TypedFuncCallExpr) return reportMismatch(raw, typed)
                if (raw.name != typed.name) return reportMismatch(raw, typed)
                if (raw.args.size != typed.args.size) return reportMismatch(raw, typed)
                for (i in raw.args.indices) {
                    validateExpr(raw.args[i], typed.args[i])
                }
            }
            is RawMethodCallExpr -> {
                if (typed !is TypedMethodCallExpr) return reportMismatch(raw, typed)
                if (raw.methodName != typed.methodName) return reportMismatch(raw, typed)
                if (raw.args.size != typed.args.size) return reportMismatch(raw, typed)
                validateExpr(raw.target, typed.target)
                for (i in raw.args.indices) {
                    validateExpr(raw.args[i], typed.args[i])
                }
            }
            is RawBinaryExpr -> {
                if (typed !is TypedBinaryExpr) return reportMismatch(raw, typed)
                if (raw.op != typed.op) return reportMismatch(raw, typed)
                validateExpr(raw.left, typed.left)
                validateExpr(raw.right, typed.right)
            }
            is RawUnaryExpr -> {
                if (typed !is TypedUnaryExpr) return reportMismatch(raw, typed)
                if (raw.op != typed.op) return reportMismatch(raw, typed)
                validateExpr(raw.operand, typed.operand)
            }
            is RawPostfixExpr -> {
                if (typed !is TypedPostfixExpr) return reportMismatch(raw, typed)
                if (raw.op != typed.op) return reportMismatch(raw, typed)
                validateExpr(raw.operand, typed.operand)
            }
            is RawCastExpr -> {
                if (typed !is TypedCastExpr) return reportMismatch(raw, typed)
                if (raw.targetType != typed.targetType) return reportMismatch(raw, typed)
                validateExpr(raw.operand, typed.operand)
            }
            is RawErrorExpr -> {
                if (typed !is TypedErrorExpr) return reportMismatch(raw, typed)
            }
        }
    }

    private fun reportMismatch(
        raw: Any,
        typed: Any,
    ) {
        val loc =
            when (raw) {
                is RawDecl -> raw.loc
                is RawStmt -> raw.loc
                is RawExpr -> raw.loc
                else -> SourceLocation("", 0, 0)
            }
        errors.report(
            loc,
            "AST mismatch: expected mapped node for ${raw::class.simpleName}, but found ${typed::class.simpleName}",
        )
    }
}
