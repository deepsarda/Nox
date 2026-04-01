package nox.compiler.semantic

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import nox.compiler.CompilerErrors
import nox.compiler.parsing.NoxParsing
import nox.compiler.types.*

/**
 * Tests for [DeclarationCollector] (Pass 1).
 *
 * Each test parses a Nox source snippet, runs declaration collection,
 * and verifies the resulting [SymbolTable] contents or error messages.
 */
class DeclarationCollectorTest :
    FunSpec({

        // Helper: parse source and run declaration collection
        fun collect(source: String): Triple<SymbolTable, CompilerErrors, nox.compiler.ast.Program> {
            val errors = CompilerErrors()
            val program = NoxParsing.parse(source, "test.nox", errors)
            val globalScope = SymbolTable()
            DeclarationCollector(globalScope, errors).collect(program)
            return Triple(globalScope, errors, program)
        }

        fun collectError(
            source: String,
            msg: String,
        ) {
            val (_, errors) = collect(source)
            errors.hasErrors() shouldBe true
            errors.all().any { it.message.contains(msg) } shouldBe true
        }

        test("registers type definitions as TypeSymbol with empty fields") {
            val (scope, errors) =
                collect(
                    """
                    type Point { int x; int y; }
                    main() { return "ok"; }
                    """.trimIndent(),
                )

            errors.hasErrors() shouldBe false
            val sym = scope.lookup("Point")
            sym.shouldNotBeNull()
            sym.shouldBeInstanceOf<TypeSymbol>()
            sym.name shouldBe "Point"
            // Fields are empty in Pass 1; resolved in Pass 2
            sym.fields.size shouldBe 0
        }

        test("registers function definitions as FuncSymbol") {
            val (scope, errors) =
                collect(
                    """
                    int add(int a, int b) { return a + b; }
                    main() { return "ok"; }
                    """.trimIndent(),
                )

            errors.hasErrors() shouldBe false
            val sym = scope.lookup("add")
            sym.shouldNotBeNull()
            sym.shouldBeInstanceOf<FuncSymbol>()
            sym.name shouldBe "add"
            sym.returnType shouldBe TypeRef.INT
            sym.params.size shouldBe 2
            sym.params[0].name shouldBe "a"
            sym.params[0].type shouldBe TypeRef.INT
            sym.params[1].name shouldBe "b"
            sym.params[1].type shouldBe TypeRef.INT
        }

        test("registers function with default parameters") {
            val (scope, errors) =
                collect(
                    """
                    int greet(string name = "world") { return 0; }
                    main() { return "ok"; }
                    """.trimIndent(),
                )

            errors.hasErrors() shouldBe false
            val sym = scope.lookup("greet") as FuncSymbol
            sym.params[0].defaultValue.shouldNotBeNull()
        }

        test("registers global variables as GlobalSymbol with sequential slots") {
            val (scope, errors) =
                collect(
                    """
                    int counter = 0;
                    string label = "test";
                    main() { return "ok"; }
                    """.trimIndent(),
                )

            errors.hasErrors() shouldBe false

            val counter = scope.lookup("counter")
            counter.shouldBeInstanceOf<GlobalSymbol>()
            counter.globalSlot shouldBe 0
            counter.type shouldBe TypeRef.INT

            val label = scope.lookup("label")
            label.shouldBeInstanceOf<GlobalSymbol>()
            label.globalSlot shouldBe 1
            label.type shouldBe TypeRef.STRING
        }

        test("sets program.main for MainDef") {
            val (_, errors, program) =
                collect(
                    """
                    main(string url) { return url; }
                    """.trimIndent(),
                )

            errors.hasErrors() shouldBe false
            program.main.shouldNotBeNull()
            program.main!!.params.size shouldBe 1
        }

        test("reports error on duplicate type name") {
            val (_, errors) =
                collect(
                    """
                    type Point { int x; }
                    type Point { int y; }
                    main() { return "ok"; }
                    """.trimIndent(),
                )

            errors.hasErrors() shouldBe true
            errors.all().any { it.message.contains("Duplicate") && it.message.contains("Point") } shouldBe true
        }

        test("reports error on duplicate function name") {
            val (_, errors) =
                collect(
                    """
                    int foo() { return 1; }
                    int foo() { return 2; }
                    main() { return "ok"; }
                    """.trimIndent(),
                )

            errors.hasErrors() shouldBe true
            errors.all().any { it.message.contains("Duplicate") && it.message.contains("foo") } shouldBe true
        }

        test("reports error on duplicate global variable name") {
            val (_, errors) =
                collect(
                    """
                    int x = 1;
                    int x = 2;
                    main() { return "ok"; }
                    """.trimIndent(),
                )

            errors.hasErrors() shouldBe true
            errors.all()[0].message shouldContain "already declared"
        }

        test("reports error on multiple main definitions") {
            val (_, errors) =
                collect(
                    """
                    main() { return "first"; }
                    main() { return "second"; }
                    """.trimIndent(),
                )

            errors.hasErrors() shouldBe true
            errors.all()[0].message shouldContain "Only one 'main()' block is allowed"
        }

        test("rejects empty struct from ANTLR recovery") {
            // ANTLR recovery produces a TypeDef with zero fields on `type Empty { }`.
            // DeclarationCollector must reject it.
            val (scope, errors) =
                collect(
                    """
                    type Empty { }
                    main() { return "ok"; }
                    """.trimIndent(),
                )

            errors.hasErrors() shouldBe true
            errors.all().any { it.message.contains("has no fields") } shouldBe true
            scope.lookup("Empty").shouldBeNull()
        }

        test("skips ErrorDecl without crashing") {
            // Force a parse error that produces an ErrorDecl
            val errors = CompilerErrors()
            val program =
                NoxParsing.parse(
                    "int ??? = bad;\nmain() { return \"ok\"; }",
                    "test.nox",
                    errors,
                )

            // Parse errors exist, but collection should not throw
            val globalScope = SymbolTable()
            DeclarationCollector(globalScope, errors).collect(program)

            // main should still be collected despite ErrorDecl
            program.main.shouldNotBeNull()
        }

        test("assigns globalSlot on the GlobalVarDecl AST node") {
            val (_, errors, program) =
                collect(
                    """
                    int a = 1;
                    int b = 2;
                    main() { return "ok"; }
                    """.trimIndent(),
                )

            errors.hasErrors() shouldBe false
            program.globals[0].globalSlot shouldBe 0
            program.globals[1].globalSlot shouldBe 1
        }

        test("handles program with no declarations") {
            val errors = CompilerErrors()
            val program = NoxParsing.parse("", "empty.nox", errors)
            val globalScope = SymbolTable()
            DeclarationCollector(globalScope, errors).collect(program)

            globalScope.allSymbols().size shouldBe 0
            program.main.shouldBeNull()
        }

        // Varargs & Parameter Ordering
        test("rejects multiple varargs parameters") {
            collectError(
                """
                void foo(int ...a[], string ...b[]) { }
                main() { return "ok"; }
                """.trimIndent(),
                "only have one varargs parameter",
            )
        }

        test("rejects varargs not as last parameter") {
            collectError(
                """
                void foo(int ...a[], int b) { }
                main() { return "ok"; }
                """.trimIndent(),
                "Varargs parameter 'a' must be the last parameter",
            )
        }

        test("rejects varargs with default value") {
            collectError(
                """
                void foo(int ...a[] = [1]) { }
                main() { return "ok"; }
                """.trimIndent(),
                "Varargs parameter 'a' cannot have a default value",
            )
        }

        test("rejects required parameter after optional") {
            collectError(
                """
                void foo(int a = 1, int b) { }
                main() { return "ok"; }
                """.trimIndent(),
                "Required parameter 'b' must come before optional parameters",
            )
        }
    })
