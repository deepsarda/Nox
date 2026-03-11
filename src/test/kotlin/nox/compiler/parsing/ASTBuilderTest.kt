package nox.compiler.parsing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import nox.compiler.ast.*
import nox.compiler.types.*
import nox.compiler.CompilerErrors

/**
 * Tests that the [ASTBuilder] correctly converts ANTLR parse trees
 * into the Nox AST for all node types.
 */
class ASTBuilderTest :
    FunSpec({

        // Helpers
        fun parse(source: String): Program = NoxParsing.parse(source, "test.nox")

        // Program structure

        test("empty program produces empty declarations list") {
            val prog = parse("")
            prog.declarations shouldHaveSize 0
            prog.headers shouldHaveSize 0
            prog.imports shouldHaveSize 0
            prog.main.shouldBeNull()
        }

        test("headers are parsed with @tool prefix stripped") {
            val prog =
                parse(
                    """
                    @tool:name "my_tool"
                    @tool:description "A test tool"
                    """.trimIndent(),
                )
            prog.headers shouldHaveSize 2
            prog.headers[0].key shouldBe "name"
            prog.headers[0].value shouldBe "my_tool"
            prog.headers[1].key shouldBe "description"
            prog.headers[1].value shouldBe "A test tool"
        }

        test("import declarations are parsed") {
            val prog =
                parse(
                    """
                    import "utils/helpers.nox" as helpers;
                    """.trimIndent(),
                )
            prog.imports shouldHaveSize 1
            prog.imports[0].path shouldBe "utils/helpers.nox"
            prog.imports[0].namespace shouldBe "helpers"
        }

        // Type definitions

        test("type definition with multiple fields") {
            val prog =
                parse(
                    """
                    type Point {
                        int x;
                        int y;
                    }
                    """.trimIndent(),
                )
            prog.typesByName.shouldNotBeNull()
            val point = prog.typesByName["Point"]
            point.shouldNotBeNull()
            point.fields shouldHaveSize 2
            point.fields[0].name shouldBe "x"
            point.fields[0].type shouldBe TypeRef.INT
            point.fields[1].name shouldBe "y"
            point.fields[1].type shouldBe TypeRef.INT
        }

        test("type definition with reference type fields") {
            val prog =
                parse(
                    """
                    type Node {
                        string value;
                        json data;
                    }
                    """.trimIndent(),
                )
            val node = prog.typesByName["Node"]!!
            node.fields[0].type shouldBe TypeRef.STRING
            node.fields[1].type shouldBe TypeRef.JSON
        }

        test("type definition with array type fields") {
            val prog =
                parse(
                    """
                    type Container {
                        int[] items;
                        string[] names;
                    }
                    """.trimIndent(),
                )
            val cont = prog.typesByName["Container"]!!
            cont.fields[0].type shouldBe TypeRef("int", 1)
            cont.fields[1].type shouldBe TypeRef("string", 1)
        }

        // Function definitions

        test("simple function definition") {
            val prog =
                parse(
                    """
                    int add(int a, int b) {
                        return a + b;
                    }
                    """.trimIndent(),
                )
            val func = prog.functionsByName["add"]
            func.shouldNotBeNull()
            func.returnType shouldBe TypeRef.INT
            func.params shouldHaveSize 2
            func.params[0].name shouldBe "a"
            func.params[0].type shouldBe TypeRef.INT
            func.params[1].name shouldBe "b"
            func.params[1].type shouldBe TypeRef.INT
        }

        test("function with default parameters") {
            val prog =
                parse(
                    """
                    void greet(string name = "World") {
                        yield name;
                    }
                    """.trimIndent(),
                )
            val func = prog.functionsByName["greet"]!!
            func.params[0].defaultValue.shouldNotBeNull()
            func.params[0].defaultValue.shouldBeInstanceOf<StringLiteralExpr>()
            (func.params[0].defaultValue as StringLiteralExpr).value shouldBe "World"
        }

        test("function with varargs parameter") {
            val prog =
                parse(
                    """
                    int sum(int ...values[]) {
                        return 0;
                    }
                    """.trimIndent(),
                )
            val func = prog.functionsByName["sum"]!!
            func.params[0].isVarargs shouldBe true
            func.params[0].type shouldBe TypeRef("int", 1)
        }

        // Main definition

        test("main definition") {
            val prog =
                parse(
                    """
                    main(string url) {
                        return url;
                    }
                    """.trimIndent(),
                )
            prog.main.shouldNotBeNull()
            prog.main!!.params shouldHaveSize 1
            prog.main!!.params[0].name shouldBe "url"
            prog.main!!.params[0].type shouldBe TypeRef.STRING
        }

        test("main with default parameters") {
            val prog =
                parse(
                    """
                    main(int count = 5) {
                        return "done";
                    }
                    """.trimIndent(),
                )
            prog.main!!
                .params[0]
                .defaultValue
                .shouldNotBeNull()
            val default = prog.main!!.params[0].defaultValue as IntLiteralExpr
            default.value shouldBe 5L
        }

        // Global variables

        test("global variable declarations") {
            val prog =
                parse(
                    """
                    int counter = 0;
                    string label = "test";
                    """.trimIndent(),
                )
            prog.globals shouldHaveSize 2
            prog.globals[0].name shouldBe "counter"
            prog.globals[0].type shouldBe TypeRef.INT
            prog.globals[1].name shouldBe "label"
            prog.globals[1].type shouldBe TypeRef.STRING
        }

        // Literals

        test("integer literal") {
            val prog = parse("main() { return 42; }")
            val ret = prog.main!!.body.statements[0] as ReturnStmt
            val lit = ret.value as IntLiteralExpr
            lit.value shouldBe 42L
        }

        test("double literal") {
            val prog = parse("main() { return 3.14; }")
            val ret = prog.main!!.body.statements[0] as ReturnStmt
            val lit = ret.value as DoubleLiteralExpr
            lit.value shouldBe 3.14
        }

        test("boolean literals") {
            val prog =
                parse(
                    """
                    main() {
                        boolean a = true;
                        boolean b = false;
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val stmts = prog.main!!.body.statements
            val a = (stmts[0] as VarDeclStmt).initializer as BoolLiteralExpr
            val b = (stmts[1] as VarDeclStmt).initializer as BoolLiteralExpr
            a.value shouldBe true
            b.value shouldBe false
        }

        test("string literal with escape sequences") {
            val prog =
                parse(
                    """
                    main() { return "hello\tworld\n"; }
                    """.trimIndent(),
                )
            val ret = prog.main!!.body.statements[0] as ReturnStmt
            val lit = ret.value as StringLiteralExpr
            lit.value shouldBe "hello\tworld\n"
        }

        test("null literal") {
            val prog =
                parse(
                    """
                    main() {
                        string s = null;
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val decl = prog.main!!.body.statements[0] as VarDeclStmt
            decl.initializer.shouldBeInstanceOf<NullLiteralExpr>()
        }

        // Array and struct literals

        test("array literal") {
            val prog =
                parse(
                    """
                    main() {
                        int[] arr = [1, 2, 3];
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val decl = prog.main!!.body.statements[0] as VarDeclStmt
            val arr = decl.initializer as ArrayLiteralExpr
            arr.elements shouldHaveSize 3
            (arr.elements[0] as IntLiteralExpr).value shouldBe 1L
        }

        test("empty array literal") {
            val prog = parse("main() { int[] arr = []; return \"ok\"; }")
            val decl = prog.main!!.body.statements[0] as VarDeclStmt
            val arr = decl.initializer as ArrayLiteralExpr
            arr.elements shouldHaveSize 0
        }

        test("struct literal") {
            val prog =
                parse(
                    """
                    type Point { int x; int y; }
                    main() {
                        Point p = { x: 10, y: 20 };
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val decl = prog.main!!.body.statements[0] as VarDeclStmt
            val struct = decl.initializer as StructLiteralExpr
            struct.fields shouldHaveSize 2
            struct.fields[0].name shouldBe "x"
            struct.fields[1].name shouldBe "y"
        }

        // Template literals

        test("template literal with interpolation") {
            val prog =
                parse(
                    """
                    main(string name) {
                        return `Hello ${'$'}{name}!`;
                    }
                    """.trimIndent(),
                )
            val ret = prog.main!!.body.statements[0] as ReturnStmt
            val tpl = ret.value as TemplateLiteralExpr
            tpl.parts shouldHaveSize 3
            (tpl.parts[0] as TemplatePart.Text).value shouldBe "Hello "
            (tpl.parts[1] as TemplatePart.Interpolation).expression.shouldBeInstanceOf<IdentifierExpr>()
            (tpl.parts[2] as TemplatePart.Text).value shouldBe "!"
        }

        // Binary expressions

        test("arithmetic binary expression") {
            val prog = parse("main() { int x = 1 + 2; return \"ok\"; }")
            val decl = prog.main!!.body.statements[0] as VarDeclStmt
            val bin = decl.initializer as BinaryExpr
            bin.op shouldBe BinaryOp.ADD
            (bin.left as IntLiteralExpr).value shouldBe 1L
            (bin.right as IntLiteralExpr).value shouldBe 2L
        }

        test("comparison expression") {
            val prog = parse("main() { boolean x = 1 < 2; return \"ok\"; }")
            val decl = prog.main!!.body.statements[0] as VarDeclStmt
            val bin = decl.initializer as BinaryExpr
            bin.op shouldBe BinaryOp.LT
        }

        test("logical expression") {
            val prog = parse("main() { boolean x = true && false; return \"ok\"; }")
            val decl = prog.main!!.body.statements[0] as VarDeclStmt
            val bin = decl.initializer as BinaryExpr
            bin.op shouldBe BinaryOp.AND
        }

        test("bitwise and shift expressions") {
            val prog =
                parse(
                    """
                    main() {
                        int a = 1 & 2;
                        int b = 1 | 2;
                        int c = 1 ^ 2;
                        int d = 1 << 2;
                        int e = 8 >> 1;
                        int f = 8 >>> 1;
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val stmts = prog.main!!.body.statements
            (stmts[0] as VarDeclStmt)
                .initializer
                .shouldBeInstanceOf<BinaryExpr>()
                .op shouldBe BinaryOp.BIT_AND
            (stmts[1] as VarDeclStmt)
                .initializer
                .shouldBeInstanceOf<BinaryExpr>()
                .op shouldBe BinaryOp.BIT_OR
            (stmts[2] as VarDeclStmt)
                .initializer
                .shouldBeInstanceOf<BinaryExpr>()
                .op shouldBe BinaryOp.BIT_XOR
            (stmts[3] as VarDeclStmt)
                .initializer
                .shouldBeInstanceOf<BinaryExpr>()
                .op shouldBe BinaryOp.SHL
            (stmts[4] as VarDeclStmt)
                .initializer
                .shouldBeInstanceOf<BinaryExpr>()
                .op shouldBe BinaryOp.SHR
            (stmts[5] as VarDeclStmt)
                .initializer
                .shouldBeInstanceOf<BinaryExpr>()
                .op shouldBe BinaryOp.USHR
        }

        // Unary and postfix expressions

        test("unary negation") {
            val prog = parse("main() { int x = -5; return \"ok\"; }")
            val decl = prog.main!!.body.statements[0] as VarDeclStmt
            val unary = decl.initializer as UnaryExpr
            unary.op shouldBe UnaryOp.NEG
        }

        test("unary not") {
            val prog = parse("main() { boolean x = !true; return \"ok\"; }")
            val decl = prog.main!!.body.statements[0] as VarDeclStmt
            val unary = decl.initializer as UnaryExpr
            unary.op shouldBe UnaryOp.NOT
        }

        test("bitwise complement") {
            val prog = parse("main() { int x = ~42; return \"ok\"; }")
            val decl = prog.main!!.body.statements[0] as VarDeclStmt
            val unary = decl.initializer as UnaryExpr
            unary.op shouldBe UnaryOp.BIT_NOT
        }

        test("postfix increment expression") {
            val prog =
                parse(
                    """
                    main() {
                        int i = 0;
                        i++;
                        i--;
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val stmtInc = prog.main!!.body.statements[1] as IncrementStmt
            stmtInc.op shouldBe PostfixOp.INCREMENT

            val stmtDec = prog.main!!.body.statements[2] as IncrementStmt
            stmtDec.op shouldBe PostfixOp.DECREMENT
        }

        test("postfix decrement as expression") {
            val prog = parse("main() { int i = 5; int j = i--; return \"ok\"; }")
            val decl = prog.main!!.body.statements[1] as VarDeclStmt
            val postfix = decl.initializer as PostfixExpr
            postfix.op shouldBe PostfixOp.DECREMENT
        }

        // Cast expression

        test("cast expression") {
            val prog =
                parse(
                    """
                    type Config { string host; }
                    main() {
                        json raw = null;
                        Config c = raw as Config;
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val decl = prog.main!!.body.statements[1] as VarDeclStmt
            val cast = decl.initializer as CastExpr
            cast.targetType shouldBe TypeRef("Config")
        }

        // Method and field access

        test("method call expression") {
            val prog =
                parse(
                    """
                    main() {
                        string s = "hello";
                        string u = s.upper();
                        return u;
                    }
                    """.trimIndent(),
                )
            val decl = prog.main!!.body.statements[1] as VarDeclStmt
            val call = decl.initializer as MethodCallExpr
            call.methodName shouldBe "upper"
            call.target.shouldBeInstanceOf<IdentifierExpr>()
        }

        test("field access expression") {
            val prog =
                parse(
                    """
                    type Point { int x; int y; }
                    main() {
                        Point p = { x: 1, y: 2 };
                        int px = p.x;
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val decl = prog.main!!.body.statements[1] as VarDeclStmt
            val acc = decl.initializer as FieldAccessExpr
            acc.fieldName shouldBe "x"
        }

        test("index access expression") {
            val prog =
                parse(
                    """
                    main() {
                        int[] arr = [10, 20];
                        int first = arr[0];
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val decl = prog.main!!.body.statements[1] as VarDeclStmt
            decl.initializer.shouldBeInstanceOf<IndexAccessExpr>()
        }

        // Control flow

        test("if/else-if/else statement") {
            val prog =
                parse(
                    """
                    main() {
                        int x = 5;
                        if (x < 0) {
                            return "neg";
                        } else if (x == 0) {
                            return "zero";
                        } else {
                            return "pos";
                        }
                    }
                    """.trimIndent(),
                )
            val ifStmt = prog.main!!.body.statements[1] as IfStmt
            ifStmt.elseIfs shouldHaveSize 1
            ifStmt.elseBlock.shouldNotBeNull()
        }

        test("if statement without else") {
            val prog = parse("main() { if (true) { yield 1; } return \"ok\"; }")
            val ifStmt = prog.main!!.body.statements[0] as IfStmt
            ifStmt.elseIfs shouldHaveSize 0
            ifStmt.elseBlock.shouldBeNull()
        }

        test("while loop") {
            val prog =
                parse(
                    """
                    main() {
                        int i = 0;
                        while (i < 10) {
                            i++;
                        }
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val whileStmt = prog.main!!.body.statements[1] as WhileStmt
            whileStmt.condition.shouldBeInstanceOf<BinaryExpr>()
        }

        test("for loop") {
            val prog =
                parse(
                    """
                    main() {
                        for (int i = 0; i < 10; i++) {
                            yield "tick";
                        }
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val forStmt = prog.main!!.body.statements[0] as ForStmt
            forStmt.init.shouldNotBeNull()
            forStmt.condition.shouldNotBeNull()
            forStmt.update.shouldNotBeNull()
        }

        test("infinite for loop (empty clauses)") {
            val prog = parse("main() { for (;;) { break; } return \"ok\"; }")
            val forStmt = prog.main!!.body.statements[0] as ForStmt
            forStmt.init.shouldBeNull()
            forStmt.condition.shouldBeNull()
            forStmt.update.shouldBeNull()
        }

        test("foreach loop") {
            val prog =
                parse(
                    """
                    main() {
                        int[] nums = [1, 2, 3];
                        foreach (int n in nums) {
                            yield "item";
                        }
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val forEach = prog.main!!.body.statements[1] as ForEachStmt
            forEach.elementType shouldBe TypeRef.INT
            forEach.elementName shouldBe "n"
        }

        // Assignment

        test("compound assignment operators") {
            val prog =
                parse(
                    """
                    main() {
                        int x = 10;
                        x += 5;
                        x -= 3;
                        x *= 2;
                        x /= 4;
                        x %= 3;
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val stmts = prog.main!!.body.statements
            (stmts[1] as AssignStmt).op shouldBe AssignOp.ADD_ASSIGN
            (stmts[2] as AssignStmt).op shouldBe AssignOp.SUB_ASSIGN
            (stmts[3] as AssignStmt).op shouldBe AssignOp.MUL_ASSIGN
            (stmts[4] as AssignStmt).op shouldBe AssignOp.DIV_ASSIGN
            (stmts[5] as AssignStmt).op shouldBe AssignOp.MOD_ASSIGN
        }

        // Exception handling

        test("try-catch with typed and catch-all clauses") {
            val prog =
                parse(
                    """
                    main() {
                        try {
                            yield "risky";
                        } catch (NetworkError e) {
                            yield e;
                        } catch (err) {
                            yield err;
                        }
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val tc = prog.main!!.body.statements[0] as TryCatchStmt
            tc.catchClauses shouldHaveSize 2
            tc.catchClauses[0].exceptionType shouldBe "NetworkError"
            tc.catchClauses[0].variableName shouldBe "e"
            tc.catchClauses[1].exceptionType.shouldBeNull()
            tc.catchClauses[1].variableName shouldBe "err"
        }

        test("throw statement") {
            val prog =
                parse(
                    """
                    main() {
                        throw "something went wrong";
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val throwStmt = prog.main!!.body.statements[0] as ThrowStmt
            (throwStmt.value as StringLiteralExpr).value shouldBe "something went wrong"
        }

        // Jump statements

        test("break and continue") {
            val prog =
                parse(
                    """
                    main() {
                        while (true) {
                            break;
                            continue;
                        }
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val whileStmt = prog.main!!.body.statements[0] as WhileStmt
            val body = whileStmt.body.statements
            body[0].shouldBeInstanceOf<BreakStmt>()
            body[1].shouldBeInstanceOf<ContinueStmt>()
        }

        test("yield statement") {
            val prog =
                parse(
                    """
                    main() {
                        yield "progress";
                        return "done";
                    }
                    """.trimIndent(),
                )
            val yieldStmt = prog.main!!.body.statements[0] as YieldStmt
            (yieldStmt.value as StringLiteralExpr).value shouldBe "progress"
        }

        test("return without value") {
            val prog =
                parse(
                    """
                    void nothing() {
                        return;
                    }
                    """.trimIndent(),
                )
            val func = prog.functionsByName["nothing"]!!
            val ret = func.body.statements[0] as ReturnStmt
            ret.value.shouldBeNull()
        }

        // Expression statement

        test("function call as statement") {
            val prog =
                parse(
                    """
                    void doSomething() { return; }
                    main() {
                        doSomething();
                        return "ok";
                    }
                    """.trimIndent(),
                )
            val exprStmt = prog.main!!.body.statements[0] as ExprStmt
            exprStmt.expression.shouldBeInstanceOf<FuncCallExpr>()
        }

        // Source locations

        test("source locations are correctly attached") {
            val prog =
                parse(
                    """
                    main() {
                        int x = 42;
                        return "ok";
                    }
                    """.trimIndent(),
                )
            prog.main!!.loc.file shouldBe "test.nox"
            prog.main!!.loc.line shouldBe 1
            val varDecl = prog.main!!.body.statements[0] as VarDeclStmt
            varDecl.loc.line shouldBe 2
        }

        // Parenthesized expression desugaring

        test("parenthesized expressions are unwrapped") {
            val prog = parse("main() { int x = (1 + 2); return \"ok\"; }")
            val decl = prog.main!!.body.statements[0] as VarDeclStmt
            // Should be a BinaryExpr, not a ParenExpr
            decl.initializer.shouldBeInstanceOf<BinaryExpr>()
        }

        // Complex program

        test("complex program with all declaration types") {
            val prog =
                parse(
                    """
                    @tool:name "processor"
                    @tool:description "Processes data"

                    import "utils.nox" as utils;

                    type Config {
                        string host;
                        int port;
                    }

                    int counter = 0;

                    int increment(int value) {
                        return value + 1;
                    }

                    main(string url = "http://localhost") {
                        Config cfg = { host: url, port: 8080 };
                        yield "starting";
                        return "done";
                    }
                    """.trimIndent(),
                )

            prog.headers shouldHaveSize 2
            prog.imports shouldHaveSize 1
            prog.typesByName.size shouldBe 1
            prog.globals shouldHaveSize 1
            prog.functionsByName.size shouldBe 1
            prog.main.shouldNotBeNull()
        }

        // Error Recovery

        test("syntax error in expression reports error and produces partial AST") {
            val errors = CompilerErrors()
            val prog = NoxParsing.parse("main() { int x = ; return \"ok\"; }", "err.nox", errors)

            errors.hasErrors() shouldBe true
            errors.all().any { it.message.contains("Syntax Error") } shouldBe true
            // The main function should still be parsed
            prog.main.shouldNotBeNull()
        }

        test("lexical error reports error via CompilerErrors") {
            val errors = CompilerErrors()
            NoxParsing.parse("main() { @invalid }", "err.nox", errors)

            errors.hasErrors() shouldBe true
        }

        test("valid declarations survive alongside broken ones") {
            val errors = CompilerErrors()
            val prog =
                NoxParsing.parse(
                    """
                    type Point { int x; int y; }
                    broken garbage here !!!
                    int add(int a, int b) { return a + b; }
                    """.trimIndent(),
                    "err.nox",
                    errors,
                )

            errors.hasErrors() shouldBe true
            // The valid type definition should still be in the AST
            prog.typesByName["Point"].shouldNotBeNull()
        }

        test("broken statement in a function body produces ErrorStmt but preserves others") {
            val errors = CompilerErrors()
            val prog =
                NoxParsing.parse(
                    """
                    main() {
                        int x = 10;
                        ??? ;
                        return "ok";
                    }
                    """.trimIndent(),
                    "err.nox",
                    errors,
                )

            errors.hasErrors() shouldBe true
            prog.main.shouldNotBeNull()
            // At least the valid variable declaration should survive
            val stmts = prog.main!!.body.statements
            stmts.any { it is VarDeclStmt } shouldBe true
        }

        test("multiple syntax errors are all reported") {
            val errors = CompilerErrors()
            NoxParsing.parse(
                """
                main() {
                    int x = ;
                    int y = ;
                    return "ok";
                }
                """.trimIndent(),
                "err.nox",
                errors,
            )

            errors.hasErrors() shouldBe true
            errors.count shouldBeGreaterThanOrEqual 2
        }

        test("empty struct produces parse error but TypeDef survives via ANTLR recovery") {
            val errors = CompilerErrors()
            val prog =
                NoxParsing.parse(
                    """
                    type Empty { }
                    main() { return "ok"; }
                    """.trimIndent(),
                    "err.nox",
                    errors,
                )

            errors.hasErrors() shouldBe true
            // ANTLR error recovery still produces a TypeDef node with empty fields.
            // DeclarationCollector rejects it with a semantic error.
            prog.typesByName["Empty"].shouldNotBeNull()
            prog.typesByName["Empty"]!!.fields shouldHaveSize 0
        }
    })
