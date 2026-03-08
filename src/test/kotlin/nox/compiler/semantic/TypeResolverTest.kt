package nox.compiler.semantic

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import nox.compiler.CompilerErrors
import nox.compiler.parsing.NoxParsing
import nox.compiler.ast.*
import nox.compiler.types.*

/**
 * Tests for Pass 2: Type Resolution.
 * 
 * TODO: There is a desperate need to refactor the tests to be more organized and split across more files. In fact for the whole test suite this probably should be done.
 *
 * Each test parses a Nox source snippet, runs Pass 1 (declaration collection)
 * and Pass 2 (type resolution), then verifies resolved types and/or error messages.
 */
class TypeResolverTest :
    FunSpec({

        /**
         * Helper: parse source then Pass 1 then Pass 2, collect results.
         */
        fun resolve(source: String): Triple<SymbolTable, CompilerErrors, Program> {
            val errors = CompilerErrors()
            val program = NoxParsing.parse(source, "test.nox", errors)
            val globalScope = SymbolTable()
            DeclarationCollector(globalScope, errors).collect(program)
            TypeResolver(globalScope, errors).resolve(program)
            return Triple(globalScope, errors, program)
        }

        /** Shorthand: resolve and expect no errors. */
        fun resolveOk(source: String): Triple<SymbolTable, CompilerErrors, Program> {

            val result = resolve(source)
            result.second.hasErrors() shouldBe false
            return result
        }

        /** Shorthand: resolve and expect at least one error containing [msg]. */
        fun resolveError(source: String, msg: String) {
            val (_, errors) = resolve(source)
            errors.hasErrors() shouldBe true
            errors.all().any { it.message.contains(msg) } shouldBe true
        }

        // Literal types

        test("intLiteralResolvesToInt") {
            val (_, _, program) = resolveOk(
                """
                main() { int x = 42; return "ok"; }
                """.trimIndent(),
            )
            val mainBody = program.main!!.body
            val varDecl = mainBody.statements[0] as VarDeclStmt
            val init = varDecl.initializer
            init.resolvedType shouldBe TypeRef.INT
        }

        test("doubleLiteralResolvesToDouble") {
            val (_, _, program) = resolveOk(
                """
                main() { double x = 3.14; return "ok"; }
                """.trimIndent(),
            )
            val mainBody = program.main!!.body
            val varDecl = mainBody.statements[0] as VarDeclStmt
            varDecl.initializer.resolvedType shouldBe TypeRef.DOUBLE
        }


        test("boolLiteralResolvesToBoolean") {
            val (_, _, program) = resolveOk(
                """
                main() { boolean x = true; return "ok"; }
                """.trimIndent(),
            )
            val varDecl = program.main!!.body.statements[0] as VarDeclStmt
            varDecl.initializer.resolvedType shouldBe TypeRef.BOOLEAN
        }

        test("stringLiteralResolvesToString") {
            val (_, _, program) = resolveOk(
                """
                main() { string x = "hello"; return "ok"; }
                """.trimIndent(),
            )
            val varDecl = program.main!!.body.statements[0] as VarDeclStmt
            varDecl.initializer.resolvedType shouldBe TypeRef.STRING
        }

        test("nullLiteralInferredFromContext") {
            val (_, _, program) = resolveOk(
                """
                main() { string x = null; return "ok"; }
                """.trimIndent(),
            )
            val varDecl = program.main!!.body.statements[0] as VarDeclStmt
            varDecl.initializer.resolvedType.shouldBeNull()
        }

        test("templateResolvesToString") {
            val dollar = '$'
            val (_, _, program) = resolveOk(
                """
                main() { int x = 42; string s = `value is ${dollar}{x}`; return "ok"; }
                """.trimIndent(),
            )
            val varDecl = program.main!!.body.statements[1] as VarDeclStmt
            varDecl.initializer.resolvedType shouldBe TypeRef.STRING
        }

        // Binary operator types

        test("intPlusInt") {
            val (_, _, program) = resolveOk(
                """
                int add(int a, int b) { return a + b; }
                main() { return "ok"; }
                """.trimIndent(),
            )
            val func = program.functionsByName["add"]!!
            val retStmt = func.body.statements[0] as ReturnStmt
            retStmt.value!!.resolvedType shouldBe TypeRef.INT
        }

        test("intPlusDouble") {
            val (_, _, program) = resolveOk(
                """
                double widen(int a, double b) { return a + b; }
                main() { return "ok"; }
                """.trimIndent(),
            )
            val func = program.functionsByName["widen"]!!
            val retStmt = func.body.statements[0] as ReturnStmt
            retStmt.value!!.resolvedType shouldBe TypeRef.DOUBLE
        }

        test("doublePlusDouble") {
            val (_, _, program) = resolveOk(
                """
                double add(double a, double b) { return a + b; }
                main() { return "ok"; }
                """.trimIndent(),
            )
            val func = program.functionsByName["add"]!!
            val retStmt = func.body.statements[0] as ReturnStmt
            retStmt.value!!.resolvedType shouldBe TypeRef.DOUBLE
        }

        test("stringPlusStringOk") {
            resolveOk(
                """
                main() { string s = "a" + "b"; return s; }
                """.trimIndent(),
            )
        }

        test("intCompareInt") {
            val (_, _, program) = resolveOk(
                """
                boolean cmp(int a, int b) { return a < b; }
                main() { return "ok"; }
                """.trimIndent(),
            )
            val func = program.functionsByName["cmp"]!!
            val retStmt = func.body.statements[0] as ReturnStmt
            retStmt.value!!.resolvedType shouldBe TypeRef.BOOLEAN
        }

        test("intCompareDouble") {
            resolveOk(
                """
                boolean cmp(int a, double b) { return a < b; }
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        test("boolAndBool") {
            val (_, _, program) = resolveOk(
                """
                boolean both(boolean a, boolean b) { return a && b; }
                main() { return "ok"; }
                """.trimIndent(),
            )
            val func = program.functionsByName["both"]!!
            val retStmt = func.body.statements[0] as ReturnStmt
            retStmt.value!!.resolvedType shouldBe TypeRef.BOOLEAN
        }

        test("intAndIntFails") {
            resolveError(
                """
                main() { boolean b = 1 && 2; return "ok"; }
                """.trimIndent(),
                "requires 'boolean'",
            )
        }

        test("bitwiseIntInt") {
            resolveOk(
                """
                int mask(int a, int b) { return a & b; }
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        test("bitwiseDoubleFails") {
            resolveError(
                """
                main() { double d = 1.0 & 2.0; return "ok"; }
                """.trimIndent(),
                "Bitwise operator '&' requires 'int'",
            )
        }

        test("equalitySameType") {
            resolveOk(
                """
                main() { boolean b = 1 == 1; return "ok"; }
                """.trimIndent(),
            )
        }

        test("equalityIntStringFails") {
            resolveError(
                """
                main() { boolean b = 1 == "hello"; return "ok"; }
                """.trimIndent(),
                "cannot compare",
            )
        }

        // Unary operators

        test("negateInt") {
            resolveOk(
                """
                main() { int x = -42; return "ok"; }
                """.trimIndent(),
            )
        }

        test("negateDouble") {
            resolveOk(
                """
                main() { double x = -3.14; return "ok"; }
                """.trimIndent(),
            )
        }

        test("negateBoolFails") {
            resolveError(
                """
                main() { int x = -true; return "ok"; }
                """.trimIndent(),
                "Cannot negate non-numeric",
            )
        }

        test("notBool") {
            resolveOk(
                """
                main() { boolean x = !true; return "ok"; }
                """.trimIndent(),
            )
        }

        test("notIntFails") {
            resolveError(
                """
                main() { boolean x = !0; return "ok"; }
                """.trimIndent(),
                "Logical NOT requires 'boolean'",
            )
        }

        test("bitwiseNotInt") {
            resolveOk(
                """
                main() { int x = ~0; return "ok"; }
                """.trimIndent(),
            )
        }

        // Null safety
        test("nullToPrimitiveFails") {
            resolveError(
                """
                main() { int x = null; return "ok"; }
                """.trimIndent(),
                "Cannot assign null to non-nullable",
            )
        }

        test("nullToStringOk") {
            resolveOk(
                """
                main() { string x = null; return "ok"; }
                """.trimIndent(),
            )
        }

        test("nullToJsonOk") {
            resolveOk(
                """
                main() { json x = null; return "ok"; }
                """.trimIndent(),
            )
        }

        test("nullToStructOk") {
            resolveOk(
                """
                type Config { string name; }
                main() { Config c = null; return "ok"; }
                """.trimIndent(),
            )
        }

        test("nullToArrayOk") {
            resolveOk(
                """
                main() { int[] x = null; return "ok"; }
                """.trimIndent(),
            )
        }

        // Struct validation

        test("structComplete") {
            resolveOk(
                """
                type Point { int x; int y; }
                main() { Point p = { x: 1, y: 2 }; return "ok"; }
                """.trimIndent(),
            )
        }

        test("structMissingFieldFails") {
            resolveError(
                """
                type Point { int x; int y; }
                main() { Point p = { x: 1 }; return "ok"; }
                """.trimIndent(),
                "Missing field 'y'",
            )
        }

        test("structExtraFieldFails") {
            resolveError(
                """
                type Point { int x; int y; }
                main() { Point p = { x: 1, y: 2, z: 3 }; return "ok"; }
                """.trimIndent(),
                "Unknown field 'z'",
            )
        }

        test("structFieldTypeMismatch") {
            resolveError(
                """
                type Point { int x; int y; }
                main() { Point p = { x: 1, y: "hello" }; return "ok"; }
                """.trimIndent(),
                "Type mismatch for field 'y'",
            )
        }

        test("structForwardReference") {
            resolveOk(
                """
                type Node { string val; Node next; }
                main() { Node n = { val: "a", next: null }; return "ok"; }
                """.trimIndent(),
            )
        }

        // Cast validation
        test("castJsonToStructOk") {
            resolveOk(
                """
                type Config { string name; }
                main() { json j = null; Config c = j as Config; return "ok"; }
                """.trimIndent(),
            )
        }

        test("castIntToStructFails") {
            resolveError(
                """
                type Config { string name; }
                main() { int x = 1; Config c = x as Config; return "ok"; }
                """.trimIndent(),
                "Cannot cast from 'int'",
            )
        }

        test("castToUnknownTypeFails") {
            resolveError(
                """
                main() { json j = null; j as Unknown; return "ok"; }
                """.trimIndent(),
                "Unknown cast target type",
            )
        }

        // Method/call resolution

        test("namespaceCallResolved") {
            val (_, _, program) = resolveOk(
                """
                main() { double x = Math.sqrt(4.0); return "ok"; }
                """.trimIndent(),
            )
            val varDecl = program.main!!.body.statements[0] as VarDeclStmt
            val call = varDecl.initializer as MethodCallExpr
            call.resolution shouldBe MethodCallExpr.Resolution.NAMESPACE
        }

        test("builtinMethodResolved") {
            val (_, _, program) = resolveOk(
                """
                main() { string s = "hello"; string u = s.upper(); return "ok"; }
                """.trimIndent(),
            )
            val varDecl = program.main!!.body.statements[1] as VarDeclStmt
            val call = varDecl.initializer as MethodCallExpr
            call.resolution shouldBe MethodCallExpr.Resolution.TYPE_BOUND
        }

        test("pluginTypeMethodResolved") {
            val (_, _, program) = resolveOk(
                """
                main() { int x = 42; double d = x.toDouble(); return "ok"; }
                """.trimIndent(),
            )
            val varDecl = program.main!!.body.statements[1] as VarDeclStmt
            val call = varDecl.initializer as MethodCallExpr
            call.resolution shouldBe MethodCallExpr.Resolution.TYPE_BOUND
        }

        test("ufcsGlobalFuncResolved") {
            val (_, _, program) = resolveOk(
                """
                type Point { int x; int y; }
                int getX(Point p) { return p.x; }
                main() { Point p = { x: 1, y: 2 }; int x = p.getX(); return "ok"; }
                """.trimIndent(),
            )
            val varDecl = program.main!!.body.statements[1] as VarDeclStmt
            val call = varDecl.initializer as MethodCallExpr
            call.resolution shouldBe MethodCallExpr.Resolution.UFCS
        }

        test("unknownMethodFails") {
            resolveError(
                """
                main() { int x = 42; x.nonexistent(); return "ok"; }
                """.trimIndent(),
                "No method 'nonexistent' found on type 'int'",
            )
        }

        // Variable scoping
        test("undeclaredVariableFails") {
            resolveError(
                """
                main() { int x = y; return "ok"; }
                """.trimIndent(),
                "Undefined variable 'y'",
            )
        }

        test("variableShadowingAllowed") {
            resolveOk(
                """
                main() {
                    int x = 1;
                    if (true) {
                        int x = 2;
                    }
                    return "ok";
                }
                """.trimIndent(),
            )
        }

        test("outOfScopeAccess") {
            resolveError(
                """
                main() {
                    if (true) {
                        int x = 1;
                    }
                    int y = x;
                    return "ok";
                }
                """.trimIndent(),
                "Undefined variable 'x'",
            )
        }

        // Statement validation
        test("varDeclTypeMismatchFails") {
            resolveError(
                """
                main() { int x = "hello"; return "ok"; }
                """.trimIndent(),
                "Type mismatch",
            )
        }

        test("compoundAssignmentOk") {
            resolveOk(
                """
                main() { int x = 0; x += 5; return "ok"; }
                """.trimIndent(),
            )
        }

        test("forEachRequiresArray") {
            resolveError(
                """
                main() {
                    int x = 5;
                    foreach (int i in x) { }
                    return "ok";
                }
                """.trimIndent(),
                "foreach requires an array",
            )
        }

        test("throwRequiresString") {
            resolveError(
                """
                main() { throw 42; return "ok"; }
                """.trimIndent(),
                "throw requires a string",
            )
        }

        test("assignToLiteralFails") {
            resolveError(
                """
                main() { 42 = 10; return "ok"; }
                """.trimIndent(),
                "Invalid assignment target",
            )
        }

        // Assignability
        test("intToDoubleOk") {
            resolveOk(
                """
                main() { double d = 42; return "ok"; }
                """.trimIndent(),
            )
        }

        test("doubleToIntFails") {
            resolveError(
                """
                main() { int x = 3.14; return "ok"; }
                """.trimIndent(),
                "Type mismatch",
            )
        }

        test("structToJsonOk") {
            resolveOk(
                """
                type Config { string name; }
                main() { Config c = { name: "test" }; json j = c; return "ok"; }
                """.trimIndent(),
            )
        }

        test("arrayElementTypeMustMatch") {
            resolveError(
                """
                main() { int[] a = [1, 2, 3]; string[] b = a; return "ok"; }
                """.trimIndent(),
                "Type mismatch",
            )
        }

        // Struct field resolution
        test("structFieldsResolved") {
            val (scope) = resolveOk(
                """
                type Point { int x; int y; }
                main() { return "ok"; }
                """.trimIndent(),
            )
            val typeSym = scope.lookup("Point") as TypeSymbol
            typeSym.fields.size shouldBe 2
            typeSym.fields["x"] shouldBe TypeRef.INT
            typeSym.fields["y"] shouldBe TypeRef.INT
        }

        test("structFieldUnknownTypeFails") {
            resolveError(
                """
                type Bad { Unknown field; }
                main() { return "ok"; }
                """.trimIndent(),
                "Unknown type",
            )
        }

        // Field and index access
        test("structFieldAccess") {
            resolveOk(
                """
                type Point { int x; int y; }
                main() { Point p = { x: 1, y: 2 }; int x = p.x; return "ok"; }
                """.trimIndent(),
            )
        }

        test("arrayIndexAccess") {
            resolveOk(
                """
                main() { int[] a = [1, 2, 3]; int x = a[0]; return "ok"; }
                """.trimIndent(),
            )
        }

        test("stringLengthProperty") {
            resolveOk(
                """
                main() { string s = "hello"; int l = s.length; return "ok"; }
                """.trimIndent(),
            )
        }

        test("arrayLengthProperty") {
            resolveOk(
                """
                main() { int[] a = [1, 2]; int l = a.length; return "ok"; }
                """.trimIndent(),
            )
        }

        // Function calls
        test("funcCallOk") {
            resolveOk(
                """
                int add(int a, int b) { return a + b; }
                main() { int x = add(1, 2); return "ok"; }
                """.trimIndent(),
            )
        }

        test("funcCallTooFewArgsFails") {
            resolveError(
                """
                int add(int a, int b) { return a + b; }
                main() { int x = add(1); return "ok"; }
                """.trimIndent(),
                "expects at least 2 arguments",
            )
        }

        test("funcCallWithDefaultArgs") {
            resolveOk(
                """
                int inc(int a, int b = 1) { return a + b; }
                main() { int x = inc(5); return "ok"; }
                """.trimIndent(),
            )
        }

        // Conditions

        test("nonBoolConditionFails") {
            resolveError(
                """
                main() { if (0) { } return "ok"; }
                """.trimIndent(),
                "requires 'boolean'",
            )
        }

        test("stringConditionFails") {
            resolveError(
                """
                main() { if ("") { } return "ok"; }
                """.trimIndent(),
                "requires 'boolean'",
            )
        }

        // Global variable initializers

        test("globalInitTypeMismatchFails") {
            resolveError(
                """
                int counter = "hello";
                main() { return "ok"; }
                """.trimIndent(),
                "Type mismatch for global 'counter'",
            )
        }

        test("globalInitOk") {
            resolveOk(
                """
                int counter = 0;
                string label = "test";
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        // Try-catch

        test("tryCatchVariableIsString") {
            resolveOk(
                """
                main() {
                    try {
                        int x = 1;
                    } catch (err) {
                        string msg = err;
                    }
                    return "ok";
                }
                """.trimIndent(),
            )
        }

        // Return type checking
        test("returnTypeMismatchFails") {
            resolveError(
                """
                int getNumber() { return "hello"; }
                main() { return "ok"; }
                """.trimIndent(),
                "Return type mismatch",
            )
        }

        test("voidReturnWithoutValueOK") {
            resolveOk(
                """
                void doWork() { return; }
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        test("voidReturnWithValueFails") {
            resolveError(
                """
                void doWork() { return "hello"; }
                main() { return "ok"; }
                """.trimIndent(),
                "Return type mismatch",
            )
        }

        test("voidReturnWithIntFails") {
            resolveError(
                """
                void doWork() { return 42; }
                main() { return "ok"; }
                """.trimIndent(),
                "Return type mismatch",
            )
        }

        test("voidReturnWithNullFails") {
            resolveError(
                """
                void doWork() { return null; }
                main() { return "ok"; }
                """.trimIndent(),
                "Return type mismatch",
            )
        }

        test("voidReturnInNestedIfFails") {
            resolveError(
                """
                void doWork(boolean cond) {
                    if (cond) { return 1; }
                }
                main() { return "ok"; }
                """.trimIndent(),
                "Return type mismatch",
            )
        }

        test("mainCanReturnInt") {
            resolveOk(
                """
                main() { return 42; }
                """.trimIndent(),
            )
        }

        test("mainCanReturnBool") {
            resolveOk(
                """
                main() { return true; }
                """.trimIndent(),
            )
        }

        test("mainBareReturnOk") {
            resolveOk(
                """
                main() { return; }
                """.trimIndent(),
            )
        }

        test("voidIsNotNullable") {
            TypeRef.VOID.isNullable() shouldBe false
        }

        // UFCS (Unified Function Call Syntax)

        test("ufcsWithExtraArgs") {
            val (_, _, program) = resolveOk(
                """
                type Point { int x; int y; }
                int distance(Point p, int dx, int dy) { return dx + dy; }
                main() { Point p = { x: 1, y: 2 }; int d = p.distance(3, 4); return "ok"; }
                """.trimIndent(),
            )
            val varDecl = program.main!!.body.statements[1] as VarDeclStmt
            val call = varDecl.initializer as MethodCallExpr
            call.resolution shouldBe MethodCallExpr.Resolution.UFCS
        }

        test("ufcsTypeMismatchFails") {
            resolveError(
                """
                int process(string s) { return s.length; }
                main() { int x = 42; x.process(); return "ok"; }
                """.trimIndent(),
                "No method 'process' found on type 'int'",
            )
        }

        test("ufcsPriorityTypeBoundBeatsGlobal") {
            val (_, _, program) = resolveOk(
                """
                string upper(string s) { return s; }
                main() { string s = "hello"; string u = s.upper(); return "ok"; }
                """.trimIndent(),
            )
            val varDecl = program.main!!.body.statements[1] as VarDeclStmt
            val call = varDecl.initializer as MethodCallExpr
            // Built-in type method (TempRegistry) should win over UFCS
            call.resolution shouldBe MethodCallExpr.Resolution.TYPE_BOUND
        }

        test("ufcsNoParamsFails") {
            // A global function with zero params cannot be called via UFCS
            resolveError(
                """
                int getValue() { return 42; }
                main() { int x = 1; x.getValue(); return "ok"; }
                """.trimIndent(),
                "No method 'getValue' found on type 'int'",
            )
        }

        // Import namespace resolution

        test("importNamespaceCallResolved") {
            // Construct a fake imported module with a function
            val helperSource = """
                int twice(int x) { return x + x; }
            """.trimIndent()
            val helperErrors = CompilerErrors()
            val helperProgram = NoxParsing.parse(helperSource, "helpers.nox", helperErrors)

            val module = ResolvedModule(
                namespace = "helpers",
                sourcePath = "/fake/helpers.nox",
                program = helperProgram,
                globalBaseOffset = 0,
                globalCount = 0,
            )

            val mainSource = """
                main() { int x = helpers.twice(5); return "ok"; }
            """.trimIndent()
            val errors = CompilerErrors()
            val program = NoxParsing.parse(mainSource, "test.nox", errors)
            val globalScope = SymbolTable()
            DeclarationCollector(globalScope, errors).collect(program)
            TypeResolver(globalScope, errors, listOf(module)).resolve(program)

            errors.hasErrors() shouldBe false
            val varDecl = program.main!!.body.statements[0] as VarDeclStmt
            val call = varDecl.initializer as MethodCallExpr
            call.resolution shouldBe MethodCallExpr.Resolution.NAMESPACE
        }

        test("importNamespaceUnknownFuncFails") {
            val helperSource = """
                int twice(int x) { return x + x; }
            """.trimIndent()
            val helperErrors = CompilerErrors()
            val helperProgram = NoxParsing.parse(helperSource, "helpers.nox", helperErrors)

            val module = ResolvedModule(
                namespace = "helpers",
                sourcePath = "/fake/helpers.nox",
                program = helperProgram,
                globalBaseOffset = 0,
                globalCount = 0,
            )

            val mainSource = """
                main() { helpers.nonexistent(); return "ok"; }
            """.trimIndent()
            val errors = CompilerErrors()
            val program = NoxParsing.parse(mainSource, "test.nox", errors)
            val globalScope = SymbolTable()
            DeclarationCollector(globalScope, errors).collect(program)
            TypeResolver(globalScope, errors, listOf(module)).resolve(program)

            errors.hasErrors() shouldBe true
            errors.all().any { it.message.contains("has no function 'nonexistent'") } shouldBe true
        }

        // Namespace arg validation

        test("namespaceArgCountMismatch") {
            resolveError(
                """
                main() { Math.sqrt(1.0, 2.0); return "ok"; }
                """.trimIndent(),
                "expects at most 1 arguments",
            )
        }

        test("namespaceArgTypeMismatch") {
            resolveError(
                """
                main() { Math.sqrt("hello"); return "ok"; }
                """.trimIndent(),
                "expected 'double'",
            )
        }

        // Built-in methods on types

        test("stringSplitReturnsArray") {
            val (_, _, program) = resolveOk(
                """
                main() { string s = "a,b,c"; string[] parts = s.split(","); return "ok"; }
                """.trimIndent(),
            )
            val varDecl = program.main!!.body.statements[1] as VarDeclStmt
            val call = varDecl.initializer as MethodCallExpr
            call.resolution shouldBe MethodCallExpr.Resolution.TYPE_BOUND
            call.resolvedType shouldBe TypeRef("string", isArray = true)
        }

        test("stringContainsReturnsBool") {
            resolveOk(
                """
                main() { string s = "hello"; boolean b = s.contains("ell"); return "ok"; }
                """.trimIndent(),
            )
        }

        test("mathAbsReturnsDouble") {
            resolveOk(
                """
                main() { double x = Math.abs(-3.14); return "ok"; }
                """.trimIndent(),
            )
        }

        test("mathMaxReturnsDouble") {
            resolveOk(
                """
                main() { double x = Math.max(1.0, 2.0); return "ok"; }
                """.trimIndent(),
            )
        }

        // JSON literal support

        test("jsonLiteralOk") {
            val (_, _, program) = resolveOk(
                """
                main() { json a = {ok: true, msg: "hello"}; return "ok"; }
                """.trimIndent(),
            )
            val varDecl = program.main!!.body.statements[0] as VarDeclStmt
            varDecl.initializer.resolvedType shouldBe TypeRef.JSON
        }

        test("jsonLiteralWithExpressions") {
            resolveOk(
                """
                main() { int x = 10; json a = {value: x + 20 * 2, name: "test"}; return "ok"; }
                """.trimIndent(),
            )
        }

        // Struct field expression resolution

        test("structFieldExpressionResolution") {
            resolveOk(
                """
                type Point { int x; int y; }
                main() {
                    int dist = 5;
                    Point p = { x: 10 + 20 * 2, y: dist + 10 / 2 };
                    return "ok";
                }
                """.trimIndent(),
            )
        }

        // Struct to json array assignability

        test("structArrayToJsonArrayOk") {
            resolveOk(
                """
                type Config { string name; }
                main() {
                    Config[] configs = [{ name: "a" }, { name: "b" }];
                    json[] data = configs;
                    return "ok";
                }
                """.trimIndent(),
            )
        }

        // JSON array with struct literals and mixed elements

        test("jsonArrayWithStructLiteralsOk") {
            resolveOk(
                """
                main() {
                    json[] arr = [{ok: true}, {msg: "hello"}];
                    return "ok";
                }
                """.trimIndent(),
            )
        }

        test("jsonArrayMixedStructAndVariable") {
            resolveOk(
                """
                type Point { int x; int y; }
                main() {
                    Point p = { x: 1, y: 2 };
                    json[] response = [{ok: true}, p];
                    return "ok";
                }
                """.trimIndent(),
            )
        }

        test("jsonArrayWithIncompatibleTypeFails") {
            resolveError(
                """
                main() {
                    json[] arr = [1, 2, 3];
                    return "ok";
                }
                """.trimIndent(),
                "Type mismatch",
            )
        }

        // Struct to json in function args and UFCS

        test("funcArgStructToJsonOk") {
            resolveOk(
                """
                type Point { int x; int y; }
                void process(json data) { return; }
                main() { Point p = { x: 1, y: 2 }; process(p); return "ok"; }
                """.trimIndent(),
            )
        }

        test("ufcsStructToJsonFirstParamOk") {
            val (_, _, program) = resolveOk(
                """
                type Point { int x; int y; }
                string serialize(json data) { return "{}"; }
                main() { Point p = { x: 1, y: 2 }; string s = p.serialize(); return "ok"; }
                """.trimIndent(),
            )
            val varDecl = program.main!!.body.statements[1] as VarDeclStmt
            val call = varDecl.initializer as MethodCallExpr
            call.resolution shouldBe MethodCallExpr.Resolution.UFCS
        }

        test("builtinMethodArgStructToJsonOk") {
            // json.getString takes json + string params; struct arg to json param should work
            resolveOk(
                """
                type Config { string name; }
                void send(json data, string endpoint) { return; }
                main() { Config c = { name: "test" }; send(c, "/api"); return "ok"; }
                """.trimIndent(),
            )
        }

        test("validates varargs calls") {
            val source = """
                int sum(int ...vals[]) { return 0; }
                main() {
                    int s1 = sum();
                    int s2 = sum(1);
                    int s3 = sum(1, 2, 3);
                    return "ok";
                }
            """.trimIndent()
            val (_, errors) = resolve(source)
            errors.hasErrors() shouldBe false
        }

        test("rejects varargs calls with wrong element type") {
            resolveError(
                """
                void foo(int ...vals[]) { }
                main() { foo(1, "bad"); return "ok"; }
                """.trimIndent(),
                "Argument 2 of 'foo': expected 'int', got 'string'"
            )
        }

        test("rejects void in struct fields") {
            resolveError(
                """
                type Bad { void x; }
                main() { return "ok"; }
                """.trimIndent(),
                "Invalid type 'void' for field 'x' in struct 'Bad'"
            )
        }

        test("rejects void in function parameters") {
            resolveError(
                """
                void foo(void x) { }
                main() { return "ok"; }
                """.trimIndent(),
                "Invalid parameter type 'void'"
            )
        }

        test("rejects void in global variables") {
            resolveError(
                """
                void g = null;
                main() { return "ok"; }
                """.trimIndent(),
                "Invalid type 'void' for global variable 'g'"
            )
        }

        test("rejects void in local variables") {
            resolveError(
                """
                main() { void x = null; return "ok"; }
                """.trimIndent(),
                "Invalid type 'void' for variable 'x'"
            )
        }

        test("rejects void in foreach element") {
            resolveError(
                """
                main() {
                    int[] arr = [1];
                    foreach (void v in arr) { }
                    return "ok";
                }
                """.trimIndent(),
                "Invalid type 'void' for foreach element 'v'"
            )
        }

        test("validates default value types") {
            resolveError(
                """
                void foo(int x = "bad") { }
                main() { return "ok"; }
                """.trimIndent(),
                "Default value for parameter 'x' does not match parameter type 'int': got 'string'"
            )
        }

        test("allows compatible default value types (int to double)") {
            val source = """
                void foo(double x = 42) { }
                main() { return "ok"; }
            """.trimIndent()
            val (_, errors) = resolve(source)
            errors.hasErrors() shouldBe false
        }

        // Compound Assignment

        test("compound assign int += int ok") {
            resolveOk(
                """
                main() { int x = 1; x += 2; return "ok"; }
            """.trimIndent()
            )
        }

        test("compound assign double += int ok") {
            resolveOk(
                """
                main() { double d = 1.0; d += 2; return "ok"; }
            """.trimIndent()
            )
        }

        test("compound assign double += double ok") {
            resolveOk(
                """
                main() { double d = 1.0; d += 2.5; return "ok"; }
            """.trimIndent()
            )
        }

        test("compound assign int += double fails") {
            resolveError(
                """
                main() { int x = 1; x += 2.5; return "ok"; }
            """.trimIndent(), "narrow"
            )
        }

        test("compound assign int -= double fails") {
            resolveError(
                """
                main() { int x = 10; x -= 1.5; return "ok"; }
            """.trimIndent(), "narrow"
            )
        }

        test("compound assign int *= double fails") {
            resolveError(
                """
                main() { int x = 3; x *= 2.0; return "ok"; }
            """.trimIndent(), "narrow"
            )
        }

        test("compound assign string += string ok") {
            resolveOk(
                """
                main() { string s = "a"; s += "b"; return "ok"; }
            """.trimIndent()
            )
        }

        test("compound assign string -= string fails") {
            resolveError(
                """
                main() { string s = "a"; s -= "b"; return "ok"; }
            """.trimIndent(), "requires numeric"
            )
        }

        test("compound assign int += string fails") {
            resolveError(
                """
                main() { int x = 1; x += "a"; return "ok"; }
            """.trimIndent(), "requires numeric or string"
            )
        }

        // Struct-to-JSON Autoboxing

        test("struct assigned to json ok") {
            resolveOk(
                """
                type Pt { int x; int y; }
                main() {
                    Pt p = { x: 1, y: 2 };
                    json j = p;
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("struct returned as json ok") {
            resolveOk(
                """
                type Pt { int x; int y; }
                json getJ() {
                    Pt p = { x: 1, y: 2 };
                    return p;
                }
                main() { return "ok"; }
            """.trimIndent()
            )
        }

        test("struct compared to json with == ok") {
            resolveOk(
                """
                type Pt { int x; int y; }
                main() {
                    Pt p = { x: 1, y: 2 };
                    json j = p;
                    boolean eq = p == j;
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("struct compared to json with != ok") {
            resolveOk(
                """
                type Pt { int x; int y; }
                main() {
                    Pt p = { x: 1, y: 2 };
                    json j = p;
                    boolean neq = j != p;
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("struct calls json size method") {
            resolveOk(
                """
                type Pt { int x; int y; }
                main() {
                    Pt p = { x: 1, y: 2 };
                    int sz = p.size();
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("struct calls json getString method") {
            resolveOk(
                """
                type Pt { int x; int y; }
                main() {
                    Pt p = { x: 1, y: 2 };
                    string s = p.getString("x", "def");
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("struct calls json has method") {
            resolveOk(
                """
                type Pt { int x; int y; }
                main() {
                    Pt p = { x: 1, y: 2 };
                    boolean h = p.has("x");
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("struct calls json keys method") {
            resolveOk(
                """
                type Pt { int x; int y; }
                main() {
                    Pt p = { x: 1, y: 2 };
                    string[] k = p.keys();
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("struct in json varargs ok") {
            resolveOk(
                """
                type Pt { int x; int y; }
                void process(json ...items[]) { }
                main() {
                    Pt p = { x: 1, y: 2 };
                    process(p);
                    return "ok";
                }
            """.trimIndent()
            )
        }

        // Varargs

        test("varargs with required prefix ok") {
            resolveOk(
                """
                void greet(string prefix, string ...names[]) { }
                main() { greet("Hi", "Alice", "Bob"); return "ok"; }
            """.trimIndent()
            )
        }

        test("varargs direct array pass ok") {
            resolveOk(
                """
                void process(int ...vals[]) { }
                main() {
                    int[] arr = [1, 2, 3];
                    process(arr);
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("varargs direct array pass with prefix args ok") {
            resolveOk(
                """
                void greet(string prefix, string ...names[]) { }
                main() {
                    string[] n = ["Alice", "Bob"];
                    greet("Hi", n);
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("varargs direct inline array pass ok") {
            resolveOk(
                """
                int sum(int ...vals[]) { return 0; }
                main() {
                    int x = sum([1, 0, 10]);
                    return "ok";
                }
            """.trimIndent()
            )
        }

        // Main Return Type

        test("main returns string ok") {
            resolveOk(
                """
                main() { return "ok"; }
            """.trimIndent()
            )
        }

        test("main returns int ok") {
            resolveOk(
                """
                main() { return 42; }
            """.trimIndent()
            )
        }

        test("main returns double ok") {
            resolveOk(
                """
                main() { return 3.14; }
            """.trimIndent()
            )
        }

        test("main returns boolean ok") {
            resolveOk(
                """
                main() { return true; }
            """.trimIndent()
            )
        }

        test("main returns null ok") {
            resolveOk(
                """
                main() { return null; }
            """.trimIndent()
            )
        }

        test("main returns json ok") {
            resolveOk(
                """
                main() {
                    json j = { k: "v" };
                    return j;
                }
            """.trimIndent()
            )
        }

        test("main returns struct ok") {
            resolveOk(
                """
                type Pt { int x; int y; }
                main() {
                    Pt p = { x: 1, y: 2 };
                    return p;
                }
            """.trimIndent()
            )
        }

        // Int-to-Double Widening

        test("int assigned to double ok") {
            resolveOk(
                """
                main() { double d = 42; return "ok"; }
            """.trimIndent()
            )
        }

        test("int returned as double ok") {
            resolveOk(
                """
                double foo() { return 1; }
                main() { return "ok"; }
            """.trimIndent()
            )
        }

        test("int passed to double param ok") {
            resolveOk(
                """
                void foo(double d) { }
                main() { foo(1); return "ok"; }
            """.trimIndent()
            )
        }

        test("double assigned to int fails") {
            resolveError(
                """
                main() { int x = 3.14; return "ok"; }
            """.trimIndent(), "Type mismatch"
            )
        }

        test("double returned as int fails") {
            resolveError(
                """
                int foo() { return 3.14; }
                main() { return "ok"; }
            """.trimIndent(), "Return type mismatch"
            )
        }

        // Duplicate JSON Keys

        test("json literal with unique keys ok") {
            resolveOk(
                """
                main() {
                    json j = { a: 1, b: 2 };
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("json literal with duplicate keys fails") {
            resolveError(
                """
                main() {
                    json j = { a: 1, a: 2 };
                    return "ok";
                }
            """.trimIndent(), "Duplicate key 'a'"
            )
        }

        // Miscellaneous Edge Cases

        test("null to non-nullable int fails") {
            resolveError(
                """
                main() { int x = null; return "ok"; }
            """.trimIndent(), "Cannot assign null to non-nullable"
            )
        }

        test("null to nullable string ok") {
            resolveOk(
                """
                main() { string s = null; return "ok"; }
            """.trimIndent()
            )
        }

        test("empty array with type context ok") {
            resolveOk(
                """
                main() { int[] a = []; return "ok"; }
            """.trimIndent()
            )
        }

        test("function call with too many args fails") {
            resolveError(
                """
                void foo(int a) { }
                main() { foo(1, 2, 3); return "ok"; }
            """.trimIndent(), "expects at most"
            )
        }

        test("index access with non-int fails") {
            resolveError(
                """
                main() {
                    int[] arr = [1, 2];
                    int x = arr[1.5];
                    return "ok";
                }
            """.trimIndent(), "Array index must be 'int'"
            )
        }

        test("string plus int fails") {
            resolveError(
                """
                main() {
                    string s = "hello" + 1;
                    return "ok";
                }
            """.trimIndent(), "requires numeric operands"
            )
        }

        test("boolean in arithmetic fails") {
            resolveError(
                """
                main() {
                    int x = true + 1;
                    return "ok";
                }
            """.trimIndent(), "requires numeric operands"
            )
        }

        test("non-boolean in if condition fails") {
            resolveError(
                """
                main() { if (42) { } return "ok"; }
            """.trimIndent(), "requires 'boolean'"
            )
        }

        test("foreach on non-array fails") {
            resolveError(
                """
                main() { foreach (int x in 42) { } return "ok"; }
            """.trimIndent(), "foreach requires an array"
            )
        }

        test("throw non-string fails") {
            resolveError(
                """
                main() { throw 42; return "ok"; }
            """.trimIndent(), "throw requires a string"
            )
        }

        test("cast non-json fails") {
            resolveError(
                """
                type Pt { int x; int y; }
                main() {
                    string s = "hi";
                    Pt p = s as Pt;
                    return "ok";
                }
            """.trimIndent(), "Cannot cast from 'string'"
            )
        }

        test("variable shadows outer scope ok") {
            resolveOk(
                """
                main() {
                    int x = 1;
                    if (true) {
                        int x = 2;
                    }
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("nested struct literal ok") {
            resolveOk(
                """
                type Inner { int x; }
                type Outer { Inner i; }
                main() {
                    Outer o = { i: { x: 42 } };
                    return "ok";
                }
            """.trimIndent()
            )
        }

        // Multi-dimensional Arrays

        test("2d array declaration ok") {
            resolveOk(
                """
                main() {
                    int[][] matrix = [[1, 2], [3, 4]];
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("2d array index gives 1d array") {
            resolveOk(
                """
                main() {
                    int[][] matrix = [[1, 2], [3, 4]];
                    int[] row = matrix[0];
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("2d array double index gives scalar") {
            resolveOk(
                """
                main() {
                    int[][] matrix = [[1, 2], [3, 4]];
                    int cell = matrix[0][1];
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("2d array assigned to 1d fails") {
            resolveError(
                """
                main() {
                    int[][] matrix = [[1, 2]];
                    int[] row = matrix;
                    return "ok";
                }
            """.trimIndent(), "Type mismatch"
            )
        }

        test("foreach over 2d array gives 1d element") {
            resolveOk(
                """
                main() {
                    int[][] matrix = [[1, 2], [3, 4]];
                    foreach (int[] row in matrix) { }
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("foreach over 2d with wrong element type fails") {
            resolveError(
                """
                main() {
                    int[][] matrix = [[1, 2]];
                    foreach (int x in matrix) { }
                    return "ok";
                }
            """.trimIndent(), "foreach element type mismatch"
            )
        }

        test("3d array declaration ok") {
            resolveOk(
                """
                main() {
                    int[][][] cube = [[[1]]];
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("2d array as function param ok") {
            resolveOk(
                """
                void process(int[][] matrix) { }
                main() {
                    int[][] m = [[1, 2], [3, 4]];
                    process(m);
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("2d array as function return ok") {
            resolveOk(
                """
                int[][] identity() {
                    int[][] m = [[1, 0], [0, 1]];
                    return m;
                }
                main() { return "ok"; }
            """.trimIndent()
            )
        }

        test("2d array return type mismatch fails") {
            resolveError(
                """
                int[] flat() {
                    int[][] m = [[1, 2]];
                    return m;
                }
                main() { return "ok"; }
            """.trimIndent(), "Return type mismatch"
            )
        }

        test("varargs with 1d elements builds 1d array ok") {
            resolveOk(
                """
                void sum(int ...vals[]) { }
                main() { sum(1, 2, 3); return "ok"; }
            """.trimIndent()
            )
        }

        test("varargs with 1d array elements builds 2d ok") {
            resolveOk(
                """
                void processRows(int[] ...rows[]) { }
                main() {
                    int[] r1 = [1, 2];
                    int[] r2 = [3, 4];
                    processRows(r1, r2);
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("varargs 2d direct array pass ok") {
            resolveOk(
                """
                void processRows(int[] ...rows[]) { }
                main() {
                    int[][] matrix = [[1, 2], [3, 4]];
                    processRows(matrix);
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("varargs 2d wrong element type fails") {
            resolveError(
                """
                void processRows(int[] ...rows[]) { }
                main() {
                    processRows("bad");
                    return "ok";
                }
            """.trimIndent(), "expected 'int[]'"
            )
        }

        test("2d array push 1d element ok") {
            resolveOk(
                """
                main() {
                    int[][] matrix = [[1, 2]];
                    int[] newRow = [3, 4];
                    matrix.push(newRow);
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("2d array push scalar fails") {
            resolveError(
                """
                main() {
                    int[][] matrix = [[1, 2]];
                    matrix.push(5);
                    return "ok";
                }
            """.trimIndent(), "expected 'int[]'"
            )
        }

        test("2d array in struct field ok") {
            resolveOk(
                """
                type Grid { int[][] cells; }
                main() {
                    Grid g = { cells: [[1, 2], [3, 4]] };
                    int[] row = g.cells[0];
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("2d array global variable ok") {
            resolveOk(
                """
                int[][] MATRIX = [[1, 0], [0, 1]];
                main() { return "ok"; }
            """.trimIndent()
            )
        }

        test("2d array default param ok") {
            resolveOk(
                """
                void foo(int[][] m = [[1]]) { }
                main() { return "ok"; }
            """.trimIndent()
            )
        }

        test("2d array length property ok") {
            resolveOk(
                """
                main() {
                    int[][] matrix = [[1, 2], [3, 4]];
                    int rows = matrix.length;
                    int cols = matrix[0].length;
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("3d array index chain ok") {
            resolveOk(
                """
                main() {
                    int[][][] cube = [[[1, 2], [3, 4]]];
                    int[][] slice = cube[0];
                    int[] row = cube[0][0];
                    int cell = cube[0][0][1];
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("3d array foreach peels one layer") {
            resolveOk(
                """
                main() {
                    int[][][] cube = [[[1]]];
                    foreach (int[][] slice in cube) { }
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("3d array push 2d ok") {
            resolveOk(
                """
                main() {
                    int[][][] cube = [[[1, 2]]];
                    int[][] newSlice = [[3, 4]];
                    cube.push(newSlice);
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("3d array length property ok") {
            resolveOk(
                """
                main() {
                    int[][][] cube = [[[1, 2], [3, 4]]];
                    int slices = cube.length;
                    int rows = cube[0].length;
                    int cols = cube[0][0].length;
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("4d array declaration and access ok") {
            resolveOk(
                """
                main() {
                    int[][][][] hyper = [[[[1]]]];
                    int[][][] s = hyper[0];
                    int[][] m = hyper[0][0];
                    int[] r = hyper[0][0][0];
                    int v = hyper[0][0][0][0];
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("varargs of 2d array elements ok") {
            resolveOk(
                """
                void process(int[][] ...cubeSlices[]) { }
                main() {
                    int[][] a = [[1, 2]];
                    int[][] b = [[3, 4]];
                    process(a, b);
                    return "ok";
                }
            """.trimIndent()
            )
        }

        test("string array length ok") {
            resolveOk(
                """
                main() {
                    string[] names = ["a", "b", "c"];
                    int n = names.length;
                    return "ok";
                }
            """.trimIndent()
            )
        }
    })
