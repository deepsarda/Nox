package nox.compiler.semantic

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import nox.compiler.CompilerErrors
import nox.compiler.ast.*
import nox.compiler.ast.typed.*
import nox.compiler.types.*
import nox.plugin.LibraryRegistry

class StatementResolverIsolatedTest :
    FunSpec({
        val loc = SourceLocation("test.nox", 1, 1)

        fun createResolvers(
            scope: SymbolTable,
            errors: CompilerErrors,
        ): Pair<ExpressionResolver, StatementResolver> {
            val exprResolver = ExpressionResolver(scope, errors, emptyList(), LibraryRegistry.createDefault())
            val stmtResolver = StatementResolver(exprResolver, errors)
            return Pair(exprResolver, stmtResolver)
        }

        context("Variable Declarations") {
            test("resolves variable declaration correctly") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val initExpr = RawIntLiteralExpr(42, loc)
                val rawDecl = RawVarDeclStmt(TypeRef.INT, "x", initExpr, loc)

                val typed = resolver.resolveStmt(scope, rawDecl, TypeRef.VOID)

                typed.shouldNotBeNull()
                typed.shouldBeInstanceOf<TypedVarDeclStmt>()
                typed.name shouldBe "x"
                typed.type shouldBe TypeRef.INT
                typed.initializer.shouldBeInstanceOf<TypedIntLiteralExpr>()

                val symbol = scope.lookupLocal("x")
                symbol.shouldNotBeNull()
                typed.resolvedSymbol shouldBe symbol

                errors.hasErrors() shouldBe false
            }

            test("reports type mismatch on invalid variable declaration") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val initExpr = RawStringLiteralExpr("hello", loc)
                val rawDecl = RawVarDeclStmt(TypeRef.INT, "x", initExpr, loc)

                resolver.resolveStmt(scope, rawDecl, TypeRef.VOID)

                errors.hasErrors() shouldBe true
                errors.all().any {
                    it.message.contains(
                        "Variable type mismatch: variable 'x' is declared as 'int' but initializer has type 'string'",
                    )
                } shouldBe
                    true
            }

            test("reports error on void variable declaration") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val rawDecl = RawVarDeclStmt(TypeRef.VOID, "x", RawNullLiteralExpr(loc), loc)
                resolver.resolveStmt(scope, rawDecl, TypeRef.VOID)
                errors.hasErrors() shouldBe true
                errors.all().any { it.message.contains("Cannot declare variable 'x' with type 'void'") } shouldBe true
            }
        }

        context("Assignments") {
            test("resolves simple assignment") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                scope.define("x", VarSymbol("x", TypeRef.INT, 0))

                val rawAssign =
                    RawAssignStmt(RawIdentifierExpr("x", loc), AssignOp.ASSIGN, RawIntLiteralExpr(10, loc), loc)
                val typed = resolver.resolveStmt(scope, rawAssign, TypeRef.VOID)

                typed.shouldNotBeNull()
                typed.shouldBeInstanceOf<TypedAssignStmt>()
                typed.op shouldBe AssignOp.ASSIGN
                typed.target.type shouldBe TypeRef.INT
                typed.value.type shouldBe TypeRef.INT
                errors.hasErrors() shouldBe false
            }

            test("reports error on narrowing compound assignment") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                scope.define("x", VarSymbol("x", TypeRef.INT, 0))

                val rawAssign =
                    RawAssignStmt(RawIdentifierExpr("x", loc), AssignOp.ADD_ASSIGN, RawDoubleLiteralExpr(1.5, loc), loc)
                resolver.resolveStmt(scope, rawAssign, TypeRef.VOID)

                errors.hasErrors() shouldBe true
                errors.all().any {
                    it.message.contains(
                        "Compound assignment type mismatch: cannot narrow 'double' to 'int'",
                    )
                } shouldBe
                    true
            }

            test("resolves increment and decrement") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                scope.define("i", VarSymbol("i", TypeRef.INT, 0))

                val rawInc = RawIncrementStmt(RawIdentifierExpr("i", loc), PostfixOp.INCREMENT, loc)
                resolver.resolveStmt(scope, rawInc, TypeRef.VOID).shouldBeInstanceOf<TypedIncrementStmt>()
            }
        }

        context("Control Flow") {
            test("resolves if statement with boolean condition") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val rawIf =
                    RawIfStmt(
                        condition = RawBoolLiteralExpr(true, loc),
                        thenBlock = RawBlock(listOf(RawExprStmt(RawIntLiteralExpr(1, loc), loc)), loc),
                        elseIfs = emptyList(),
                        elseBlock = null,
                        loc = loc,
                    )
                val typed = resolver.resolveStmt(scope, rawIf, TypeRef.VOID)

                typed.shouldNotBeNull()
                typed.shouldBeInstanceOf<TypedIfStmt>()
                errors.hasErrors() shouldBe false
            }

            test("reports error on non-boolean if condition") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val rawIf =
                    RawIfStmt(
                        condition = RawIntLiteralExpr(1, loc),
                        thenBlock = RawBlock(emptyList(), loc),
                        elseIfs = emptyList(),
                        elseBlock = null,
                        loc = loc,
                    )
                resolver.resolveStmt(scope, rawIf, TypeRef.VOID)

                errors.hasErrors() shouldBe true
                errors.all().any {
                    it.message.contains(
                        "Condition type mismatch: if condition must be 'boolean'",
                    )
                } shouldBe
                    true
            }
        }

        context("Loops") {
            test("resolves while loop correctly") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val rawWhile = RawWhileStmt(RawBoolLiteralExpr(true, loc), RawBlock(emptyList(), loc), loc)
                val typed = resolver.resolveStmt(scope, rawWhile, TypeRef.VOID)

                typed.shouldBeInstanceOf<TypedWhileStmt>()
                errors.hasErrors() shouldBe false
            }

            test("resolves for loop correctly") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val rawFor =
                    RawForStmt(
                        init = RawVarDeclStmt(TypeRef.INT, "i", RawIntLiteralExpr(0, loc), loc),
                        condition = RawBoolLiteralExpr(true, loc),
                        update = RawIncrementStmt(RawIdentifierExpr("i", loc), PostfixOp.INCREMENT, loc),
                        body = RawBlock(emptyList(), loc),
                        loc = loc,
                    )
                val typed = resolver.resolveStmt(scope, rawFor, TypeRef.VOID)

                typed.shouldBeInstanceOf<TypedForStmt>()
                errors.hasErrors() shouldBe false
            }

            test("resolves foreach over array") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val rawIterable = RawArrayLiteralExpr(listOf(RawIntLiteralExpr(1, loc)), loc)
                val rawForEach = RawForEachStmt(TypeRef.INT, "x", rawIterable, RawBlock(emptyList(), loc), loc)

                val typed = resolver.resolveStmt(scope, rawForEach, TypeRef.VOID)

                typed.shouldBeInstanceOf<TypedForEachStmt>()
                typed.resolvedSymbol.type shouldBe TypeRef.INT
                errors.hasErrors() shouldBe false
            }

            test("reports error on invalid foreach element type") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val rawIterable = RawArrayLiteralExpr(listOf(RawIntLiteralExpr(1, loc)), loc)
                // expecting array of strings, but it's an array of ints
                val rawForEach = RawForEachStmt(TypeRef.STRING, "x", rawIterable, RawBlock(emptyList(), loc), loc)

                resolver.resolveStmt(scope, rawForEach, TypeRef.VOID)

                errors.hasErrors() shouldBe true
                errors.all().any { it.message.contains("Foreach type mismatch") } shouldBe true
            }

            test("resolves break and continue") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                resolver.resolveStmt(scope, RawBreakStmt(loc), TypeRef.VOID).shouldBeInstanceOf<TypedBreakStmt>()
                resolver.resolveStmt(scope, RawContinueStmt(loc), TypeRef.VOID).shouldBeInstanceOf<TypedContinueStmt>()
            }
        }

        context("Returns, Yields, and Throws") {
            test("resolves return statement correctly") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val rawReturn = RawReturnStmt(RawIntLiteralExpr(42, loc), loc)
                val typed = resolver.resolveStmt(scope, rawReturn, TypeRef.INT)

                typed.shouldNotBeNull()
                typed.shouldBeInstanceOf<TypedReturnStmt>()
                errors.hasErrors() shouldBe false
            }

            test("reports return type mismatch") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val rawReturn = RawReturnStmt(RawIntLiteralExpr(42, loc), loc)
                resolver.resolveStmt(scope, rawReturn, TypeRef.STRING) // Expect STRING but return INT

                errors.hasErrors() shouldBe true
                errors.all().any { it.message.contains("Return type mismatch: expected string, got int") } shouldBe true
            }

            test("resolves yield") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val rawYield = RawYieldStmt(RawIntLiteralExpr(1, loc), loc)
                resolver.resolveStmt(scope, rawYield, TypeRef.VOID).shouldBeInstanceOf<TypedYieldStmt>()
            }

            test("resolves throw with string") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val rawThrow = RawThrowStmt(RawStringLiteralExpr("err", loc), loc)
                resolver.resolveStmt(scope, rawThrow, TypeRef.VOID).shouldBeInstanceOf<TypedThrowStmt>()
                errors.hasErrors() shouldBe false
            }

            test("reports error when throw is not string") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val rawThrow = RawThrowStmt(RawIntLiteralExpr(1, loc), loc)
                resolver.resolveStmt(scope, rawThrow, TypeRef.VOID)
                errors.hasErrors() shouldBe true
            }
        }

        context("Try Catch") {
            test("resolves try catch block") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val tryBlock = RawBlock(listOf(RawThrowStmt(RawStringLiteralExpr("err", loc), loc)), loc)
                val catchClauses = listOf(RawCatchClause("string", "e", RawBlock(emptyList(), loc), loc))
                val rawTryCatch = RawTryCatchStmt(tryBlock, catchClauses, loc)

                val typed = resolver.resolveStmt(scope, rawTryCatch, TypeRef.VOID)

                typed.shouldBeInstanceOf<TypedTryCatchStmt>()
                typed.catchClauses.size shouldBe 1
                errors.hasErrors() shouldBe false
            }

            test("resolves try catch with null/any type") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val tryBlock = RawBlock(emptyList(), loc)
                val catchClauses = listOf(RawCatchClause(null, "e", RawBlock(emptyList(), loc), loc))
                val rawTryCatch = RawTryCatchStmt(tryBlock, catchClauses, loc)

                val typed = resolver.resolveStmt(scope, rawTryCatch, TypeRef.VOID)

                typed.shouldBeInstanceOf<TypedTryCatchStmt>()
                typed.catchClauses[0].exceptionType shouldBe null
                errors.hasErrors() shouldBe false
            }
        }

        context("Blocks and Expressions") {
            test("resolves block and manages child scope") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val rawBlock =
                    RawBlock(
                        listOf(
                            RawVarDeclStmt(TypeRef.INT, "x", RawIntLiteralExpr(1, loc), loc),
                        ),
                        loc,
                    )

                val typed = resolver.resolveBlock(scope, rawBlock, TypeRef.VOID)
                typed.statements.size shouldBe 1
                typed.statements[0].shouldBeInstanceOf<TypedVarDeclStmt>()

                // x should not leak into parent scope
                scope.lookupLocal("x") shouldBe null
            }

            test("resolves standalone expression statement") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val (_, resolver) = createResolvers(scope, errors)

                val rawExprStmt = RawExprStmt(RawIntLiteralExpr(1, loc), loc)
                val typed = resolver.resolveStmt(scope, rawExprStmt, TypeRef.VOID)

                typed.shouldBeInstanceOf<TypedExprStmt>()
                typed.expression.type shouldBe TypeRef.INT
            }
        }
    })
