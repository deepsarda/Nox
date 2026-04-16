package nox.compiler.semantic

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import nox.compiler.CompilerErrors
import nox.compiler.ast.*
import nox.compiler.ast.typed.*
import nox.compiler.types.*

class TreeValidatorTest :
    FunSpec({
        val loc1 = SourceLocation("test.nox", 1, 1)
        val loc2 = SourceLocation("test.nox", 2, 2)

        test("validates identical programs") {
            val errors = CompilerErrors()
            val validator = TreeValidator(errors)

            val raw = RawProgram("test.nox", emptyList(), emptyList(), emptyList())
            val typed = TypedProgram("test.nox", emptyList(), emptyList(), emptyList())

            validator.validate(raw, typed)
            errors.hasErrors() shouldBe false
        }

        test("reports mismatch on declaration count") {
            val errors = CompilerErrors()
            val validator = TreeValidator(errors)

            val raw = RawProgram("test.nox", emptyList(), emptyList(), listOf(RawErrorDecl(loc1)))
            val typed = TypedProgram("test.nox", emptyList(), emptyList(), emptyList())

            validator.validate(raw, typed)
            errors.hasErrors() shouldBe true
            errors.all().any { it.message.contains("RawProgram has 1 declarations, TypedProgram has 0") } shouldBe true
        }

        test("reports mismatch on declaration source location") {
            val errors = CompilerErrors()
            val validator = TreeValidator(errors)

            val raw = RawProgram("test.nox", emptyList(), emptyList(), listOf(RawErrorDecl(loc1)))
            val typed = TypedProgram("test.nox", emptyList(), emptyList(), listOf(TypedErrorDecl(loc2)))

            validator.validate(raw, typed)
            errors.hasErrors() shouldBe true
            errors.all().any { it.message.contains("SourceLocation mismatch for declaration") } shouldBe true
        }

        test("reports mismatch on declaration type") {
            val errors = CompilerErrors()
            val validator = TreeValidator(errors)

            val raw = RawProgram("test.nox", emptyList(), emptyList(), listOf(RawErrorDecl(loc1)))
            val typed =
                TypedProgram(
                    "test.nox",
                    emptyList(),
                    emptyList(),
                    listOf(TypedImportDecl("test", "test", loc1, "test")),
                )

            validator.validate(raw, typed)
            errors.hasErrors() shouldBe true
            errors.all().any {
                it.message.contains("AST mismatch: expected mapped node for RawErrorDecl, but found TypedImportDecl")
            } shouldBe
                true
        }

        test("validates RawFuncDef matches TypedFuncDef") {
            val errors = CompilerErrors()
            val validator = TreeValidator(errors)

            val rawBlock = RawBlock(emptyList(), loc1)
            val typedBlock = TypedBlock(emptyList(), loc1, 0)

            val rawParam = RawParamImpl(TypeRef.INT, "x", null, false, loc1)
            val typedParam = TypedParam(TypeRef.INT, "x", null, false, loc1, VarSymbol("x", TypeRef.INT, 0))

            val raw = RawFuncDef(TypeRef.VOID, "f", listOf(rawParam), rawBlock, loc1)
            val typed = TypedFuncDef(TypeRef.VOID, "f", listOf(typedParam), typedBlock, loc1, 0, 0)

            validator.validate(
                RawProgram("", emptyList(), emptyList(), listOf(raw)),
                TypedProgram("", emptyList(), emptyList(), listOf(typed)),
            )
            if (errors.hasErrors()) println("ERRORS: " + errors.all().joinToString { it.message })
            errors.hasErrors() shouldBe false
        }

        test("validates RawMainDef matches TypedMainDef") {
            val errors = CompilerErrors()
            val validator = TreeValidator(errors)

            val rawBlock = RawBlock(emptyList(), loc1)
            val typedBlock = TypedBlock(emptyList(), loc1, 0)

            val raw = RawMainDef(emptyList(), rawBlock, loc1)
            val typed = TypedMainDef(TypeRef.STRING, emptyList(), typedBlock, loc1, 0, 0)

            validator.validate(
                RawProgram("", emptyList(), emptyList(), listOf(raw)),
                TypedProgram("", emptyList(), emptyList(), listOf(typed)),
            )
            if (errors.hasErrors()) println("ERRORS: " + errors.all().joinToString { it.message })
            errors.hasErrors() shouldBe false
        }

        test("validates RawGlobalVarDecl matches TypedGlobalVarDecl") {
            val errors = CompilerErrors()
            val validator = TreeValidator(errors)

            val raw = RawGlobalVarDecl(TypeRef.INT, "g", RawIntLiteralExpr(1, loc1), loc1)
            val typed = TypedGlobalVarDecl(TypeRef.INT, "g", TypedIntLiteralExpr(1, loc1, TypeRef.INT), loc1, 0)

            validator.validate(
                RawProgram("", emptyList(), emptyList(), listOf(raw)),
                TypedProgram("", emptyList(), emptyList(), listOf(typed)),
            )
            if (errors.hasErrors()) println("ERRORS: " + errors.all().joinToString { it.message })
            errors.hasErrors() shouldBe false
        }

        test("validates RawTypeDef matches TypedTypeDef") {
            val errors = CompilerErrors()
            val validator = TreeValidator(errors)

            val raw = RawTypeDef("T", listOf(RawFieldDeclImpl(TypeRef.INT, "f", loc1)), loc1)
            val typed = TypedTypeDef("T", listOf(TypedFieldDecl(TypeRef.INT, "f", loc1)), loc1)

            validator.validate(
                RawProgram("", emptyList(), emptyList(), listOf(raw)),
                TypedProgram("", emptyList(), emptyList(), listOf(typed)),
            )
            if (errors.hasErrors()) println("ERRORS: " + errors.all().joinToString { it.message })
            errors.hasErrors() shouldBe false
        }

        test("reports mismatch on statement source location") {
            val errors = CompilerErrors()
            val validator = TreeValidator(errors)

            val rawBlock = RawBlock(listOf(RawExprStmt(RawIntLiteralExpr(1, loc1), loc1)), loc1)
            val typedBlock = TypedBlock(listOf(TypedExprStmt(TypedIntLiteralExpr(1, loc2, TypeRef.INT), loc2)), loc1, 0)

            val raw = RawMainDef(emptyList(), rawBlock, loc1)
            val typed = TypedMainDef(TypeRef.STRING, emptyList(), typedBlock, loc1, 0, 0)

            validator.validate(
                RawProgram("", emptyList(), emptyList(), listOf(raw)),
                TypedProgram("", emptyList(), emptyList(), listOf(typed)),
            )
            errors.hasErrors() shouldBe true
            errors.all().any { it.message.contains("SourceLocation mismatch for statement") } shouldBe true
        }

        test("validates various statements") {
            val errors = CompilerErrors()
            val validator = TreeValidator(errors)

            val rawStmts =
                listOf(
                    RawVarDeclStmt(TypeRef.INT, "v", RawIntLiteralExpr(1, loc1), loc1),
                    RawAssignStmt(RawIdentifierExpr("v", loc1), AssignOp.ASSIGN, RawIntLiteralExpr(2, loc1), loc1),
                    RawIncrementStmt(RawIdentifierExpr("v", loc1), PostfixOp.INCREMENT, loc1),
                    RawReturnStmt(RawIntLiteralExpr(3, loc1), loc1),
                    RawYieldStmt(RawIntLiteralExpr(4, loc1), loc1),
                    RawThrowStmt(RawStringLiteralExpr("e", loc1), loc1),
                    RawWhileStmt(RawBoolLiteralExpr(true, loc1), RawBlock(emptyList(), loc1), loc1),
                    RawForStmt(null, null, null, RawBlock(emptyList(), loc1), loc1),
                    RawForEachStmt(
                        TypeRef.INT,
                        "e",
                        RawArrayLiteralExpr(emptyList(), loc1),
                        RawBlock(emptyList(), loc1),
                        loc1,
                    ),
                    RawBreakStmt(loc1),
                    RawContinueStmt(loc1),
                    RawTryCatchStmt(RawBlock(emptyList(), loc1), emptyList(), loc1),
                    RawErrorStmt(loc1),
                )

            val typedStmts =
                listOf(
                    TypedVarDeclStmt(
                        TypeRef.INT,
                        "v",
                        TypedIntLiteralExpr(1, loc1, TypeRef.INT),
                        loc1,
                        VarSymbol("v", TypeRef.INT, 0),
                        0,
                    ),
                    TypedAssignStmt(
                        TypedIdentifierExpr("v", loc1, TypeRef.INT, VarSymbol("v", TypeRef.INT, 0)),
                        AssignOp.ASSIGN,
                        TypedIntLiteralExpr(2, loc1, TypeRef.INT),
                        loc1,
                    ),
                    TypedIncrementStmt(
                        TypedIdentifierExpr("v", loc1, TypeRef.INT, VarSymbol("v", TypeRef.INT, 0)),
                        PostfixOp.INCREMENT,
                        loc1,
                    ),
                    TypedReturnStmt(TypedIntLiteralExpr(3, loc1, TypeRef.INT), loc1),
                    TypedYieldStmt(TypedIntLiteralExpr(4, loc1, TypeRef.INT), loc1),
                    TypedThrowStmt(TypedStringLiteralExpr("e", loc1, TypeRef.STRING), loc1),
                    TypedWhileStmt(
                        TypedBoolLiteralExpr(true, loc1, TypeRef.BOOLEAN),
                        TypedBlock(emptyList(), loc1, 0),
                        loc1,
                    ),
                    TypedForStmt(null, null, null, TypedBlock(emptyList(), loc1, 0), loc1),
                    TypedForEachStmt(
                        TypeRef.INT,
                        "e",
                        TypedArrayLiteralExpr(emptyList(), loc1, TypeRef.INT.arrayOf(), TypeRef.INT),
                        TypedBlock(emptyList(), loc1, 0),
                        loc1,
                        VarSymbol("e", TypeRef.INT, 0),
                        0,
                    ),
                    TypedBreakStmt(loc1),
                    TypedContinueStmt(loc1),
                    TypedTryCatchStmt(TypedBlock(emptyList(), loc1, 0), emptyList(), loc1),
                    TypedErrorStmt(loc1),
                )

            val rawBlock = RawBlock(rawStmts, loc1)
            val typedBlock = TypedBlock(typedStmts, loc1, 0)

            val raw = RawMainDef(emptyList(), rawBlock, loc1)
            val typed = TypedMainDef(TypeRef.STRING, emptyList(), typedBlock, loc1, 0, 0)

            validator.validate(
                RawProgram("", emptyList(), emptyList(), listOf(raw)),
                TypedProgram("", emptyList(), emptyList(), listOf(typed)),
            )
            if (errors.hasErrors()) println("ERRORS: " + errors.all().joinToString { it.message })
            errors.hasErrors() shouldBe false
        }

        test("reports mismatch on expression source location") {
            val errors = CompilerErrors()
            val validator = TreeValidator(errors)

            val rawBlock = RawBlock(listOf(RawExprStmt(RawIntLiteralExpr(1, loc1), loc1)), loc1)
            val typedBlock = TypedBlock(listOf(TypedExprStmt(TypedIntLiteralExpr(1, loc2, TypeRef.INT), loc1)), loc1, 0)

            val raw = RawMainDef(emptyList(), rawBlock, loc1)
            val typed = TypedMainDef(TypeRef.STRING, emptyList(), typedBlock, loc1, 0, 0)

            validator.validate(
                RawProgram("", emptyList(), emptyList(), listOf(raw)),
                TypedProgram("", emptyList(), emptyList(), listOf(typed)),
            )
            errors.hasErrors() shouldBe true
            errors.all().any { it.message.contains("SourceLocation mismatch for expression") } shouldBe true
        }

        test("validates various expressions") {
            val errors = CompilerErrors()
            val validator = TreeValidator(errors)

            val rawExprs =
                listOf(
                    RawIntLiteralExpr(1, loc1),
                    RawDoubleLiteralExpr(1.0, loc1),
                    RawBoolLiteralExpr(true, loc1),
                    RawStringLiteralExpr("s", loc1),
                    RawNullLiteralExpr(loc1),
                    RawIdentifierExpr("id", loc1),
                    RawTemplateLiteralExpr(emptyList(), loc1),
                    RawArrayLiteralExpr(emptyList(), loc1),
                    RawStructLiteralExpr(emptyList(), loc1),
                    RawFieldAccessExpr(RawIdentifierExpr("t", loc1), "f", loc1),
                    RawIndexAccessExpr(RawIdentifierExpr("t", loc1), RawIntLiteralExpr(0, loc1), loc1),
                    RawFuncCallExpr("f", emptyList(), loc1),
                    RawMethodCallExpr(RawIdentifierExpr("t", loc1), "m", emptyList(), loc1),
                    RawBinaryExpr(RawIntLiteralExpr(1, loc1), BinaryOp.ADD, RawIntLiteralExpr(2, loc1), loc1),
                    RawUnaryExpr(UnaryOp.NEG, RawIntLiteralExpr(1, loc1), loc1),
                    RawPostfixExpr(RawIdentifierExpr("i", loc1), PostfixOp.INCREMENT, loc1),
                    RawCastExpr(RawIdentifierExpr("obj", loc1), TypeRef("S"), loc1),
                    RawErrorExpr(loc1),
                )

            val typedExprs =
                listOf(
                    TypedIntLiteralExpr(1, loc1, TypeRef.INT),
                    TypedDoubleLiteralExpr(1.0, loc1, TypeRef.DOUBLE),
                    TypedBoolLiteralExpr(true, loc1, TypeRef.BOOLEAN),
                    TypedStringLiteralExpr("s", loc1, TypeRef.STRING),
                    TypedNullLiteralExpr(loc1, TypeRef.JSON),
                    TypedIdentifierExpr("id", loc1, TypeRef.INT, VarSymbol("id", TypeRef.INT, 0)),
                    TypedTemplateLiteralExpr(emptyList(), loc1, TypeRef.STRING),
                    TypedArrayLiteralExpr(emptyList(), loc1, TypeRef.INT.arrayOf(), TypeRef.INT),
                    TypedStructLiteralExpr(emptyList(), loc1, TypeRef("S")),
                    TypedFieldAccessExpr(
                        TypedIdentifierExpr("t", loc1, TypeRef("S"), VarSymbol("t", TypeRef("S"), 0)),
                        "f",
                        loc1,
                        TypeRef.INT,
                    ),
                    TypedIndexAccessExpr(
                        TypedIdentifierExpr("t", loc1, TypeRef.INT.arrayOf(), VarSymbol("t", TypeRef.INT.arrayOf(), 0)),
                        TypedIntLiteralExpr(0, loc1, TypeRef.INT),
                        loc1,
                        TypeRef.INT,
                    ),
                    TypedFuncCallExpr(
                        "f",
                        emptyList(),
                        loc1,
                        TypeRef.VOID,
                        FuncSymbol(
                            "f",
                            TypeRef.VOID,
                            emptyList(),
                            RawFuncDef(TypeRef.VOID, "f", emptyList(), RawBlock(emptyList(), loc1), loc1),
                        ),
                    ),
                    TypedMethodCallExpr(
                        TypedIdentifierExpr("t", loc1, TypeRef("S"), VarSymbol("t", TypeRef("S"), 0)),
                        "m",
                        emptyList(),
                        loc1,
                        TypeRef.VOID,
                        TypedMethodCallExpr.Resolution.TYPE_BOUND,
                        CallTarget(
                            "m",
                            emptyList(),
                            TypeRef.VOID,
                            RawFuncDef(TypeRef.VOID, "f", emptyList(), RawBlock(emptyList(), loc1), loc1),
                        ),
                    ),
                    TypedBinaryExpr(
                        TypedIntLiteralExpr(1, loc1, TypeRef.INT),
                        BinaryOp.ADD,
                        TypedIntLiteralExpr(2, loc1, TypeRef.INT),
                        loc1,
                        TypeRef.INT,
                    ),
                    TypedUnaryExpr(UnaryOp.NEG, TypedIntLiteralExpr(1, loc1, TypeRef.INT), loc1, TypeRef.INT),
                    TypedPostfixExpr(
                        TypedIdentifierExpr("i", loc1, TypeRef.INT, VarSymbol("i", TypeRef.INT, 0)),
                        PostfixOp.INCREMENT,
                        loc1,
                        TypeRef.INT,
                    ),
                    TypedCastExpr(
                        TypedIdentifierExpr("obj", loc1, TypeRef.JSON, VarSymbol("obj", TypeRef.JSON, 0)),
                        TypeRef("S"),
                        loc1,
                        TypeRef("S"),
                    ),
                    TypedErrorExpr(loc1, TypeRef.JSON),
                )

            val rawStmts = rawExprs.map { RawExprStmt(it, loc1) }
            val typedStmts = typedExprs.map { TypedExprStmt(it, loc1) }

            val rawBlock = RawBlock(rawStmts, loc1)
            val typedBlock = TypedBlock(typedStmts, loc1, 0)

            val raw = RawMainDef(emptyList(), rawBlock, loc1)
            val typed = TypedMainDef(TypeRef.STRING, emptyList(), typedBlock, loc1, 0, 0)

            validator.validate(
                RawProgram("", emptyList(), emptyList(), listOf(raw)),
                TypedProgram("", emptyList(), emptyList(), listOf(typed)),
            )
            if (errors.hasErrors()) println("ERRORS: " + errors.all().joinToString { it.message })
            errors.hasErrors() shouldBe false
        }
    })
