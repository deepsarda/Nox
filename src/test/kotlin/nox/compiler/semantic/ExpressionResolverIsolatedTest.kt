package nox.compiler.semantic

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import nox.compiler.CompilerErrors
import nox.compiler.ast.*
import nox.compiler.ast.typed.*
import nox.compiler.types.*
import nox.plugin.LibraryRegistry

class ExpressionResolverIsolatedTest :
    FunSpec({
        val loc = SourceLocation("test.nox", 1, 1)

        fun createResolver(
            scope: SymbolTable,
            errors: CompilerErrors,
        ): ExpressionResolver = ExpressionResolver(scope, errors, emptyList(), LibraryRegistry.createDefault())

        context("Literals") {
            test("resolves all primitive literals") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                resolver.resolveExpr(scope, RawIntLiteralExpr(42, loc)).type shouldBe TypeRef.INT
                resolver.resolveExpr(scope, RawDoubleLiteralExpr(3.14, loc)).type shouldBe TypeRef.DOUBLE
                resolver.resolveExpr(scope, RawBoolLiteralExpr(true, loc)).type shouldBe TypeRef.BOOLEAN
                resolver.resolveExpr(scope, RawStringLiteralExpr("hello", loc)).type shouldBe TypeRef.STRING
                errors.hasErrors() shouldBe false
            }

            test("resolves null literal with context") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                resolver.resolveExpr(scope, RawNullLiteralExpr(loc), TypeRef.STRING).type shouldBe TypeRef.STRING
                resolver.resolveExpr(scope, RawNullLiteralExpr(loc), TypeRef.JSON).type shouldBe TypeRef.JSON
                resolver.resolveExpr(scope, RawNullLiteralExpr(loc), TypeRef.INT).type shouldBe TypeRef.NULL // Non-nullable fallback
            }

            test("resolves template literals") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                val parts =
                    listOf(
                        RawTemplatePart.Text("val: "),
                        RawTemplatePart.Interpolation(RawIntLiteralExpr(42, loc)),
                    )
                val typed = resolver.resolveExpr(scope, RawTemplateLiteralExpr(parts, loc))

                typed.shouldBeInstanceOf<TypedTemplateLiteralExpr>()
                typed.type shouldBe TypeRef.STRING
                typed.parts.size shouldBe 2
            }
        }

        context("Arrays") {
            test("resolves empty array with and without context") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                val noCtx = resolver.resolveExpr(scope, RawArrayLiteralExpr(emptyList(), loc))
                noCtx.type shouldBe TypeRef.JSON.arrayOf()

                val withCtx = resolver.resolveExpr(scope, RawArrayLiteralExpr(emptyList(), loc), TypeRef.INT.arrayOf())
                withCtx.type shouldBe TypeRef.INT.arrayOf()
            }

            test("resolves homogeneous and widened arrays") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                val arr1 = RawArrayLiteralExpr(listOf(RawIntLiteralExpr(1, loc), RawIntLiteralExpr(2, loc)), loc)
                resolver.resolveExpr(scope, arr1).type shouldBe TypeRef.INT.arrayOf()

                val arr2 = RawArrayLiteralExpr(listOf(RawIntLiteralExpr(1, loc), RawDoubleLiteralExpr(2.0, loc)), loc)
                resolver.resolveExpr(scope, arr2).type shouldBe TypeRef.DOUBLE.arrayOf()
            }

            test("reports error on mismatched array types") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                val arr = RawArrayLiteralExpr(listOf(RawIntLiteralExpr(1, loc), RawStringLiteralExpr("a", loc)), loc)
                resolver.resolveExpr(scope, arr)
                errors.hasErrors() shouldBe true
            }
        }

        context("Structs") {
            test("resolves valid struct literal") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                val sType = TypeRef("S")
                val sDef = RawTypeDef("S", listOf(RawFieldDecl(TypeRef.INT, "a", loc)), loc)
                scope.define("S", TypeSymbol("S", linkedMapOf("a" to TypeRef.INT), sDef))

                val expr = RawStructLiteralExpr(listOf(RawFieldInit("a", RawIntLiteralExpr(1, loc), loc)), loc)
                val typed = resolver.resolveExpr(scope, expr, sType)
                typed.type shouldBe sType
                errors.hasErrors() shouldBe false
            }

            test("reports missing fields") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                val sType = TypeRef("S")
                val sDef = RawTypeDef("S", listOf(RawFieldDecl(TypeRef.INT, "a", loc)), loc)
                scope.define("S", TypeSymbol("S", linkedMapOf("a" to TypeRef.INT), sDef))

                val expr = RawStructLiteralExpr(emptyList(), loc)
                resolver.resolveExpr(scope, expr, sType)
                errors.hasErrors() shouldBe true
            }

            test("resolves field access") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                val sType = TypeRef("S")
                val sDef = RawTypeDef("S", listOf(RawFieldDecl(TypeRef.INT, "a", loc)), loc)
                scope.define("S", TypeSymbol("S", linkedMapOf("a" to TypeRef.INT), sDef))
                scope.define("obj", VarSymbol("obj", sType, 0))

                val expr = RawFieldAccessExpr(RawIdentifierExpr("obj", loc), "a", loc)
                resolver.resolveExpr(scope, expr).type shouldBe TypeRef.INT
            }
        }

        context("Variables and Parameters") {
            test("resolves existing identifier") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                val sym = VarSymbol("myVar", TypeRef.STRING, 0)
                scope.define("myVar", sym)

                val typed = resolver.resolveExpr(scope, RawIdentifierExpr("myVar", loc))
                typed.shouldBeInstanceOf<TypedIdentifierExpr>()
                typed.resolvedSymbol shouldBe sym
            }

            test("reports missing identifier") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                resolver.resolveExpr(scope, RawIdentifierExpr("myVar", loc))
                errors.hasErrors() shouldBe true
            }
        }

        context("Binary Operations") {
            test("resolves arithmetic") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                val expr = RawBinaryExpr(RawIntLiteralExpr(1, loc), BinaryOp.MUL, RawDoubleLiteralExpr(2.0, loc), loc)
                resolver.resolveExpr(scope, expr).type shouldBe TypeRef.DOUBLE
            }

            test("resolves logical and equality") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                val andExpr =
                    RawBinaryExpr(RawBoolLiteralExpr(true, loc), BinaryOp.AND, RawBoolLiteralExpr(false, loc), loc)
                resolver.resolveExpr(scope, andExpr).type shouldBe TypeRef.BOOLEAN

                val eqExpr = RawBinaryExpr(RawIntLiteralExpr(1, loc), BinaryOp.EQ, RawIntLiteralExpr(1, loc), loc)
                resolver.resolveExpr(scope, eqExpr).type shouldBe TypeRef.BOOLEAN
            }
        }

        context("Unary, Postfix, and Casts") {
            test("resolves unary negate and not") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                resolver.resolveExpr(scope, RawUnaryExpr(UnaryOp.NEG, RawIntLiteralExpr(1, loc), loc)).type shouldBe
                    TypeRef.INT
                resolver.resolveExpr(scope, RawUnaryExpr(UnaryOp.NOT, RawBoolLiteralExpr(true, loc), loc)).type shouldBe
                    TypeRef.BOOLEAN
            }

            test("resolves postfix increment") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                scope.define("i", VarSymbol("i", TypeRef.INT, 0))
                val expr = RawPostfixExpr(RawIdentifierExpr("i", loc), PostfixOp.INCREMENT, loc)
                resolver.resolveExpr(scope, expr).type shouldBe TypeRef.INT
            }

            test("resolves json to struct cast") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                val sType = TypeRef("S")
                val sDef = RawTypeDef("S", listOf(RawFieldDecl(TypeRef.INT, "a", loc)), loc)
                scope.define("S", TypeSymbol("S", linkedMapOf("a" to TypeRef.INT), sDef))
                scope.define("obj", VarSymbol("obj", TypeRef.JSON, 0))

                val castExpr = RawCastExpr(RawIdentifierExpr("obj", loc), sType, loc)
                resolver.resolveExpr(scope, castExpr).type shouldBe sType
                errors.hasErrors() shouldBe false
            }
        }

        context("Function and Method Calls") {
            test("resolves standard function call") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                val funcDef = RawFuncDef(TypeRef.INT, "myFunc", emptyList(), RawBlock(emptyList(), loc), loc)
                scope.define("myFunc", FuncSymbol("myFunc", TypeRef.INT, emptyList(), funcDef))

                val call = RawFuncCallExpr("myFunc", emptyList(), loc)
                resolver.resolveExpr(scope, call).type shouldBe TypeRef.INT
            }

            test("resolves builtin namespace method call") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                // Math.abs(-1.0)
                val call =
                    RawMethodCallExpr(
                        RawIdentifierExpr("Math", loc),
                        "abs",
                        listOf(RawDoubleLiteralExpr(-1.0, loc)),
                        loc,
                    )
                val typed = resolver.resolveExpr(scope, call)
                typed.type shouldBe TypeRef.DOUBLE
                errors.hasErrors() shouldBe false
            }

            test("resolves string length UFCS method") {
                val errors = CompilerErrors()
                val scope = SymbolTable()
                val resolver = createResolver(scope, errors)

                // "hello".length()
                val call = RawMethodCallExpr(RawStringLiteralExpr("hello", loc), "length", emptyList(), loc)
                val typed = resolver.resolveExpr(scope, call)
                typed.type shouldBe TypeRef.INT
                typed.shouldBeInstanceOf<TypedMethodCallExpr>()
                typed.resolution shouldBe TypedMethodCallExpr.Resolution.TYPE_BOUND
            }
        }
    })
