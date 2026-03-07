package nox.compiler.semantic

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import nox.compiler.CompilerErrors
import nox.compiler.CompilerWarnings
import nox.compiler.ast.*
import nox.compiler.parsing.NoxParsing
import nox.compiler.types.SymbolTable

/**
 * Tests for Pass 3: Control Flow Validation.
 */
class ControlFlowValidatorTest :
    FunSpec({

        data class ValidationResult(
            val errors: CompilerErrors,
            val warnings: CompilerWarnings,
            val program: Program,
        )

        /** Helper: parse to Pass 1 to Pass 2 to Pass 3. */
        fun validate(source: String): ValidationResult {
            val errors = CompilerErrors()
            val warnings = CompilerWarnings()
            val program = NoxParsing.parse(source, "test.nox", errors)
            val globalScope = SymbolTable()
            DeclarationCollector(globalScope, errors).collect(program)
            TypeResolver(globalScope, errors).resolve(program)
            ControlFlowValidator(errors, warnings).validate(program)
            return ValidationResult(errors, warnings, program)
        }

        /** Shorthand: validate and expect no errors and no warnings. */
        fun validateOk(source: String): ValidationResult {
            val result = validate(source)
            if (result.errors.hasErrors()) {
                throw AssertionError("Expected no errors, but got:\n${result.errors.formatAll()}")
            }
            result.warnings.hasWarnings() shouldBe false
            return result
        }

        /** Shorthand: validate and expect at least one error containing [msg]. */
        fun validateError(source: String, msg: String) {
            val result = validate(source)
            result.errors.hasErrors() shouldBe true
            result.errors.all().any { it.message.contains(msg) } shouldBe true
        }

        /** Shorthand: validate and expect at least one warning containing [msg]. */
        fun validateWarning(source: String, msg: String) {
            val result = validate(source)
            result.errors.hasErrors() shouldBe false
            result.warnings.hasWarnings() shouldBe true
            result.warnings.all().any { it.message.contains(msg) } shouldBe true
        }

        test("nonVoidFunctionWithNoReturn") {
            validateError(
                """
                int getValue() {
                    int x = 42;
                }
                main() { return "ok"; }
                """.trimIndent(),
                "must return a value",
            )
        }

        test("nonVoidFunctionWithReturnOk") {
            validateOk(
                """
                int getValue() {
                    return 42;
                }
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        test("nonVoidFunctionReturnOnlyInIfNoElse") {
            validateError(
                """
                int getValue(boolean cond) {
                    if (cond) {
                        return 42;
                    }
                }
                main() { return "ok"; }
                """.trimIndent(),
                "must return a value",
            )
        }

        test("nonVoidFunctionReturnInAllBranchesOfIfElse") {
            validateOk(
                """
                int getValue(boolean cond) {
                    if (cond) {
                        return 42;
                    } else {
                        return 0;
                    }
                }
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        test("nonVoidFunctionReturnInIfElseIfElse") {
            validateOk(
                """
                int getValue(int x) {
                    if (x > 0) {
                        return 1;
                    } else if (x < 0) {
                        return -1;
                    } else {
                        return 0;
                    }
                }
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        test("nonVoidFunctionMissingReturnInElseIf") {
            validateError(
                """
                int getValue(int x) {
                    if (x > 0) {
                        return 1;
                    } else if (x < 0) {
                        int y = -1;
                    } else {
                        return 0;
                    }
                }
                main() { return "ok"; }
                """.trimIndent(),
                "must return a value",
            )
        }

        test("nonVoidFunctionReturnInTryCatchAll") {
            validateOk(
                """
                int getValue() {
                    try {
                        return 42;
                    } catch (err) {
                        return 0;
                    }
                }
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        test("nonVoidFunctionReturnMissingInCatch") {
            validateError(
                """
                int getValue() {
                    try {
                        return 42;
                    } catch (err) {
                        int x = 0;
                    }
                }
                main() { return "ok"; }
                """.trimIndent(),
                "must return a value",
            )
        }

        test("voidFunctionNoReturnOk") {
            validateOk(
                """
                void doWork() {
                    int x = 1;
                }
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        test("mainNoReturnOk") {
            validateOk(
                """
                main() {
                    int x = 1;
                }
                """.trimIndent(),
            )
        }

        test("functionAlwaysThrowsOk") {
            validateOk(
                """
                int fail() {
                    throw "fatal error";
                }
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        test("nonVoidFunctionThrowInIfReturnInElse") {
            validateOk(
                """
                int getValue(boolean cond) {
                    if (cond) {
                        throw "error";
                    } else {
                        return 42;
                    }
                }
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        // Loop Context Tracking

        test("breakOutsideLoopFails") {
            validateError(
                """
                main() {
                    break;
                    return "ok";
                }
                """.trimIndent(),
                "'break' can only appear inside a loop",
            )
        }

        test("continueOutsideLoopFails") {
            validateError(
                """
                main() {
                    continue;
                    return "ok";
                }
                """.trimIndent(),
                "'continue' can only appear inside a loop",
            )
        }

        test("breakInsideWhileOk") {
            validateOk(
                """
                main() {
                    while (true) {
                        break;
                    }
                    return "ok";
                }
                """.trimIndent(),
            )
        }

        test("breakInsideForOk") {
            validateOk(
                """
                main() {
                    for (int i = 0; i < 10; i++) {
                        break;
                    }
                    return "ok";
                }
                """.trimIndent(),
            )
        }

        test("breakInsideForEachOk") {
            validateOk(
                """
                main() {
                    int[] items = [1, 2, 3];
                    foreach (int item in items) {
                        break;
                    }
                    return "ok";
                }
                """.trimIndent(),
            )
        }

        test("breakInsideNestedIfInsideLoopOk") {
            validateOk(
                """
                main() {
                    while (true) {
                        if (true) {
                            break;
                        }
                    }
                    return "ok";
                }
                """.trimIndent(),
            )
        }

        test("continueInsideNestedIfInsideLoopOk") {
            validateOk(
                """
                main() {
                    for (int i = 0; i < 10; i++) {
                        if (i > 5) {
                            continue;
                        }
                    }
                    return "ok";
                }
                """.trimIndent(),
            )
        }

        test("breakInFunctionOutsideLoopFails") {
            validateError(
                """
                void doWork() {
                    break;
                }
                main() { return "ok"; }
                """.trimIndent(),
                "'break' can only appear inside a loop",
            )
        }

        // Yield

        test("yieldInMainOk") {
            validateOk(
                """
                main() {
                    yield "progress";
                    return "done";
                }
                """.trimIndent(),
            )
        }

        test("yieldInHelperFunctionOk") {
            validateOk(
                """
                void report(string msg) {
                    yield msg;
                }
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        // Dead Code Detection

        test("deadCodeAfterReturn") {
            validateWarning(
                """
                int getValue() {
                    return 42;
                    int x = 0;
                }
                main() { return "ok"; }
                """.trimIndent(),
                "Unreachable code",
            )
        }

        test("deadCodeAfterThrow") {
            validateWarning(
                """
                int fail() {
                    throw "error";
                    return 0;
                }
                main() { return "ok"; }
                """.trimIndent(),
                "Unreachable code",
            )
        }

        test("deadCodeAfterBreak") {
            validateWarning(
                """
                main() {
                    while (true) {
                        break;
                        int x = 1;
                    }
                    return "ok";
                }
                """.trimIndent(),
                "Unreachable code",
            )
        }

        test("deadCodeAfterContinue") {
            validateWarning(
                """
                main() {
                    for (int i = 0; i < 10; i++) {
                        continue;
                        int x = 1;
                    }
                    return "ok";
                }
                """.trimIndent(),
                "Unreachable code",
            )
        }

        test("noDeadCodeNoWarnings") {
            validateOk(
                """
                int getValue(boolean cond) {
                    if (cond) {
                        return 1;
                    }
                    return 0;
                }
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        test("noDeadCodeInSequentialStatements") {
            validateOk(
                """
                main() {
                    int a = 1;
                    int b = 2;
                    int c = a + b;
                    return "ok";
                }
                """.trimIndent(),
            )
        }

        test("deadCodeAfterIfElseBothReturn") {
            validateWarning(
                """
                int getValue(boolean cond) {
                    if (cond) {
                        return 1;
                    } else {
                        return 0;
                    }
                    int x = 999;
                }
                main() { return "ok"; }
                """.trimIndent(),
                "Unreachable code",
            )
        }

        test("deadCodeAfterIfElseIfElseAllReturn") {
            validateWarning(
                """
                int getValue(int x) {
                    if (x > 0) {
                        return 1;
                    } else if (x < 0) {
                        return -1;
                    } else {
                        return 0;
                    }
                    int dead = 42;
                }
                main() { return "ok"; }
                """.trimIndent(),
                "Unreachable code",
            )
        }

        test("deadCodeAfterTryCatchBothReturn") {
            validateWarning(
                """
                int getValue() {
                    try {
                        return 42;
                    } catch (err) {
                        return 0;
                    }
                    int dead = 99;
                }
                main() { return "ok"; }
                """.trimIndent(),
                "Unreachable code",
            )
        }

        test("noDeadCodeAfterIfWithoutElse") {
            validateOk(
                """
                int getValue(boolean cond) {
                    if (cond) {
                        return 1;
                    }
                    return 0;
                }
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        test("noDeadCodeAfterIfElsePartialReturn") {
            validateOk(
                """
                int getValue(boolean cond) {
                    if (cond) {
                        return 1;
                    } else {
                        int x = 0;
                    }
                    return 0;
                }
                main() { return "ok"; }
                """.trimIndent(),
            )
        }

        test("deadCodeAfterIfElseBothThrow") {
            validateWarning(
                """
                int getValue(boolean cond) {
                    if (cond) {
                        throw "error A";
                    } else {
                        throw "error B";
                    }
                    return 0;
                }
                main() { return "ok"; }
                """.trimIndent(),
                "Unreachable code",
            )
        }
    })
