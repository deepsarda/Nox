package nox.vm

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import nox.runtime.NoxError
import nox.runtime.NoxResult
import nox.runtime.NoxRuntime

/**
 * Tests for the [NoxVM] execution engine.
 *
 * Each test compiles a small Nox program via [NoxRuntime], executes it,
 * and asserts on the result. Observable output uses `yield` statements;
 * template literals (backtick interpolation) convert non-string values.
 */
class NoxVMTest :
    FunSpec({

        // Shorthand for Nox template interpolation inside Kotlin raw strings.
        // Usage: nox("x") produces the literal string ${x} for the Nox compiler.
        val d = '$'

        fun run(source: String): NoxResult = NoxRuntime.builder().build().execute(source.trimIndent())

        fun ok(source: String): NoxResult.Success {
            val result = run(source)
            result.shouldBeInstanceOf<NoxResult.Success>()
            return result
        }

        fun err(source: String): NoxResult.Error {
            val result = run(source)
            result.shouldBeInstanceOf<NoxResult.Error>()
            return result
        }

        fun yields(source: String): List<String> = ok(source).yields

        test("integerAddition") {
            yields(
                """
                main() {
                    int x = 1 + 2;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("3")
        }

        test("integerSubtraction") {
            yields(
                """
                main() {
                    int x = 10 - 3;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("7")
        }

        test("integerMultiplication") {
            yields(
                """
                main() {
                    int x = 6 * 7;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("42")
        }

        test("integerDivision") {
            yields(
                """
                main() {
                    int x = 20 / 4;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("5")
        }

        test("integerModulo") {
            yields(
                """
                main() {
                    int x = 17 % 5;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("2")
        }

        test("integerNegation") {
            yields(
                """
                main() {
                    int x = 5;
                    int y = -x;
                    yield `$d{y}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("-5")
        }

        test("integerDivisionByZero") {
            val result =
                err(
                    """
                main() {
                    int x = 10 / 0;
                    return "ok";
                }
            """,
                )
            result.type shouldBe NoxError.DivisionByZeroError
        }

        test("integerModuloByZero") {
            val result =
                err(
                    """
                main() {
                    int x = 10 % 0;
                    return "ok";
                }
            """,
                )
            result.type shouldBe NoxError.DivisionByZeroError
        }

        test("doubleAddition") {
            yields(
                """
                main() {
                    double x = 1.5 + 2.5;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("4")
        }

        test("doubleSubtraction") {
            yields(
                """
                main() {
                    double x = 10.5 - 3.2;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("7.3")
        }

        test("doubleMultiplication") {
            yields(
                """
                main() {
                    double x = 2.5 * 4.0;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("10")
        }

        test("doubleDivision") {
            yields(
                """
                main() {
                    double x = 7.0 / 2.0;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("3.5")
        }

        test("doubleDivisionByZero") {
            val result =
                err(
                    """
                main() {
                    double x = 10.0 / 0.0;
                    return "ok";
                }
            """,
                )
            result.type shouldBe NoxError.DivisionByZeroError
        }

        test("integerEqualTrue") {
            yields(
                """
                main() {
                    if (5 == 5) { yield "yes"; } else { yield "no"; }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("yes")
        }

        test("integerEqualFalse") {
            yields(
                """
                main() {
                    if (5 == 3) { yield "yes"; } else { yield "no"; }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("no")
        }

        test("integerNotEqual") {
            yields(
                """
                main() {
                    if (5 != 3) { yield "yes"; } else { yield "no"; }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("yes")
        }

        test("integerLessThan") {
            yields(
                """
                main() {
                    if (3 < 5) { yield "yes"; } else { yield "no"; }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("yes")
        }

        test("integerGreaterThan") {
            yields(
                """
                main() {
                    if (5 > 3) { yield "yes"; } else { yield "no"; }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("yes")
        }

        test("integerLessEqual") {
            yields(
                """
                main() {
                    if (5 <= 5) { yield "yes"; } else { yield "no"; }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("yes")
        }

        test("integerGreaterEqual") {
            yields(
                """
                main() {
                    if (5 >= 6) { yield "yes"; } else { yield "no"; }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("no")
        }

        test("stringEquality") {
            yields(
                """
                main() {
                    string a = "hello";
                    string b = "hello";
                    if (a == b) { yield "eq"; } else { yield "ne"; }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("eq")
        }

        test("stringInequality") {
            yields(
                """
                main() {
                    string a = "hello";
                    string b = "world";
                    if (a != b) { yield "ne"; } else { yield "eq"; }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("ne")
        }

        test("logicalAnd") {
            yields(
                """
                main() {
                    if (true && true) { yield "yes"; } else { yield "no"; }
                    if (true && false) { yield "yes"; } else { yield "no"; }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("yes", "no")
        }

        test("logicalOr") {
            yields(
                """
                main() {
                    if (false || true) { yield "yes"; } else { yield "no"; }
                    if (false || false) { yield "yes"; } else { yield "no"; }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("yes", "no")
        }

        test("logicalNot") {
            yields(
                """
                main() {
                    if (!false) { yield "yes"; } else { yield "no"; }
                    if (!true) { yield "yes"; } else { yield "no"; }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("yes", "no")
        }

        test("ifElseTrue") {
            yields(
                """
                main() {
                    int x = 10;
                    if (x > 5) { yield "big"; } else { yield "small"; }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("big")
        }

        test("ifElseFalse") {
            yields(
                """
                main() {
                    int x = 2;
                    if (x > 5) { yield "big"; } else { yield "small"; }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("small")
        }

        test("whileLoop") {
            yields(
                """
                main() {
                    int i = 0;
                    while (i < 3) {
                        yield `$d{i}`;
                        i++;
                    }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("0", "1", "2")
        }

        test("forLoop") {
            yields(
                """
                main() {
                    for (int i = 1; i <= 3; i++) {
                        yield `$d{i}`;
                    }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("1", "2", "3")
        }

        test("breakInLoop") {
            yields(
                """
                main() {
                    for (int i = 0; i < 10; i++) {
                        if (i == 3) { break; }
                        yield `$d{i}`;
                    }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("0", "1", "2")
        }

        test("continueInLoop") {
            yields(
                """
                main() {
                    for (int i = 0; i < 5; i++) {
                        if (i % 2 == 0) { continue; }
                        yield `$d{i}`;
                    }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("1", "3")
        }

        test("simpleFunctionCall") {
            yields(
                """
                int add(int a, int b) { return a + b; }
                main() {
                    int x = add(3, 4);
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("7")
        }

        test("recursiveFunctionCall") {
            yields(
                """
                int fib(int n) {
                    if (n <= 1) { return n; }
                    return fib(n - 1) + fib(n - 2);
                }
                main() {
                    int x = fib(10);
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("55")
        }

        test("multiArgFunction") {
            yields(
                """
                int sum3(int a, int b, int c) { return a + b + c; }
                main() {
                    int r = sum3(10, 20, 30);
                    yield `$d{r}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("60")
        }

        test("voidFunction") {
            yields(
                """
                void greet() { yield "hello"; }
                main() {
                    greet();
                    yield "done";
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("hello", "done")
        }

        test("stringReturnFunction") {
            yields(
                """
                string greet(string name) { return "hi " + name; }
                main() {
                    yield greet("world");
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("hi world")
        }

        test("mathSqrt") {
            yields(
                """
                main() {
                    double x = Math.sqrt(16.0);
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("4")
        }

        test("mathAbs") {
            yields(
                """
                main() {
                    double x = Math.abs(-42.0);
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("42")
        }

        test("mathFloor") {
            yields(
                """
                main() {
                    int x = Math.floor(3.7);
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("3")
        }

        test("mathCeil") {
            yields(
                """
                main() {
                    int x = Math.ceil(3.2);
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("4")
        }

        test("mathMinMax") {
            yields(
                """
                main() {
                    double lo = Math.min(3.0, 7.0);
                    double hi = Math.max(3.0, 7.0);
                    yield `$d{lo}`;
                    yield `$d{hi}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("3", "7")
        }

        test("mathPow") {
            yields(
                """
                main() {
                    double x = Math.pow(2.0, 10.0);
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("1024")
        }

        test("stringConcatenation") {
            yields(
                """
                main() {
                    string a = "hello";
                    string b = " world";
                    yield a + b;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("hello world")
        }

        test("templateLiteral") {
            yields(
                """
                main() {
                    int x = 42;
                    string name = "Nox";
                    yield `$d{name} is $d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("Nox is 42")
        }

        test("stringMethodUpper") {
            yields(
                """
                main() {
                    string s = "hello";
                    yield s.upper();
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("HELLO")
        }

        test("stringMethodLength") {
            yields(
                """
                main() {
                    string s = "hello";
                    int len = s.length();
                    yield `$d{len}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("5")
        }

        test("intToStringViaTemplate") {
            yields(
                """
                main() {
                    int x = 123;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("123")
        }

        test("doubleToStringViaTemplate") {
            yields(
                """
                main() {
                    double d = 3.14;
                    yield `$d{d}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("3.14")
        }

        test("boolToStringViaTemplate") {
            yields(
                """
                main() {
                    boolean b = true;
                    yield `$d{b}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("true")
        }

        test("tryCatchBasic") {
            yields(
                """
                main() {
                    try {
                        throw "boom";
                    } catch (err) {
                        yield err;
                    }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("boom")
        }

        test("tryCatchDoesNotCatchWhenNoError") {
            yields(
                """
                main() {
                    try {
                        yield "try";
                    } catch (err) {
                        yield "catch";
                    }
                    yield "after";
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("try", "after")
        }

        test("uncaughtExceptionReturnsError") {
            val result =
                err(
                    """
                main() {
                    throw "fatal";
                    return "ok";
                }
            """,
                )
            result.type shouldBe NoxError.Error
            result.message shouldBe "fatal"
        }

        test("exceptionUnwindsCallStack") {
            yields(
                """
                void inner() { throw "from inner"; }
                void middle() { inner(); }
                main() {
                    try {
                        middle();
                    } catch (err) {
                        yield err;
                    }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("from inner")
        }

        test("divisionByZeroCaughtInTryCatch") {
            yields(
                """
                main() {
                    try {
                        int x = 10 / 0;
                    } catch (err) {
                        yield "caught";
                    }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("caught")
        }

        test("globalVariableInit") {
            yields(
                """
                int counter = 42;
                main() {
                    yield `$d{counter}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("42")
        }

        test("globalVariableAssignment") {
            yields(
                """
                int counter = 0;
                main() {
                    counter = 10;
                    yield `$d{counter}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("10")
        }

        test("globalVariableArithmetic") {
            yields(
                """
                int counter = 5;
                main() {
                    counter = counter + 10;
                    yield `$d{counter}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("15")
        }

        test("globalVariableAcrossFunctions") {
            yields(
                """
                int counter = 0;
                void inc() { counter = counter + 1; }
                main() {
                    inc();
                    inc();
                    inc();
                    yield `$d{counter}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("3")
        }

        test("globalStringVariable") {
            yields(
                """
                string greeting = "hello";
                main() {
                    yield greeting;
                    greeting = "world";
                    yield greeting;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("hello", "world")
        }

        test("integerIncrement") {
            yields(
                """
                main() {
                    int x = 5;
                    x++;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("6")
        }

        test("integerDecrement") {
            yields(
                """
                main() {
                    int x = 5;
                    x--;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("4")
        }

        test("bitwiseAnd") {
            yields(
                """
                main() {
                    int x = 255 & 15;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("15")
        }

        test("bitwiseOr") {
            yields(
                """
                main() {
                    int x = 240 | 15;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("255")
        }

        test("leftShift") {
            yields(
                """
                main() {
                    int x = 1 << 8;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("256")
        }

        test("rightShift") {
            yields(
                """
                main() {
                    int x = 256 >> 4;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("16")
        }

        test("multipleYields") {
            yields(
                """
                main() {
                    yield "one";
                    yield "two";
                    yield "three";
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("one", "two", "three")
        }

        test("yieldInLoop") {
            yields(
                """
                main() {
                    for (int i = 0; i < 3; i++) {
                        yield `item $d{i}`;
                    }
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("item 0", "item 1", "item 2")
        }

        test("yieldsCollectedBeforeError") {
            val result =
                err(
                    """
                main() {
                    yield "before";
                    throw "boom";
                    return "ok";
                }
            """,
                )
            result.yields shouldContainExactly listOf("before")
            result.type shouldBe NoxError.Error
        }

        test("factorial") {
            yields(
                """
                int factorial(int n) {
                    if (n <= 1) { return 1; }
                    return n * factorial(n - 1);
                }
                main() {
                    int r = factorial(10);
                    yield `$d{r}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("3628800")
        }

        test("nestedFunctionCalls") {
            yields(
                """
                int twice(int x) { return x * 2; }
                int thrice(int x) { return x * 3; }
                main() {
                    int x = twice(thrice(5));
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("30")
        }

        test("compoundArithmetic") {
            yields(
                """
                main() {
                    int x = (3 + 4) * 2 - 1;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("13")
        }

        test("largeIntConstant") {
            yields(
                """
                main() {
                    int x = 100000;
                    yield `$d{x}`;
                    return "ok";
                }
            """,
            ) shouldContainExactly listOf("100000")
        }

        test("emptyMainReturnsSuccess") {
            val result =
                ok(
                    """
                main() { return "ok"; }
            """,
                )
            result.shouldBeInstanceOf<NoxResult.Success>()
        }
    })
