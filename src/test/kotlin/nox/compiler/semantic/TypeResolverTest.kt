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

        test("voidReturnWithValueFails") {
            resolveOk(
                """
                void doWork() { return; }
                main() { return "ok"; }
                """.trimIndent(),
            )
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
    })
