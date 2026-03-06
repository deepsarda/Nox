package nox.compiler

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import nox.compiler.ast.VarDeclStmt
import nox.compiler.ast.TypeRef

/**
 * Tests for the [NoxCompiler] facade.
 *
 * Verifies that the facade correctly orchestrates all compilation phases
 * and returns a coherent [NoxCompiler.CompilationResult].
 */
class NoxCompilerTest :
    FunSpec({

        test("compileValidProgram") {
            val result = NoxCompiler.compile(
                source = """
                    int add(int a, int b) { return a + b; }
                    main() { int x = add(1, 2); return "ok"; }
                """.trimIndent(),
                fileName = "test.nox",
            )
            result.errors.hasErrors() shouldBe false
            result.warnings.hasWarnings() shouldBe false
            result.program.main shouldNotBe null
            result.program.functionsByName.containsKey("add") shouldBe true
        }

        test("compileWithTypeErrors") {
            val result = NoxCompiler.compile(
                source = """
                    main() { int x = "hello"; return "ok"; }
                """.trimIndent(),
                fileName = "test.nox",
            )
            result.errors.hasErrors() shouldBe true
            result.errors.all().any { it.message.contains("Type mismatch") } shouldBe true
        }

        test("compileWithSyntaxErrors") {
            val result = NoxCompiler.compile(
                source = """
                    main() { int x = ; return "ok"; }
                """.trimIndent(),
                fileName = "test.nox",
            )
            result.errors.hasErrors() shouldBe true
        }

        test("compileSetsResolvedTypes") {
            val result = NoxCompiler.compile(
                source = """
                    main() { int x = 42; return "ok"; }
                """.trimIndent(),
                fileName = "test.nox",
            )
            result.errors.hasErrors() shouldBe false
            val varDecl = result.program.main!!.body.statements[0] as VarDeclStmt
            varDecl.initializer.resolvedType shouldBe TypeRef.INT
        }
    })

private infix fun Any?.shouldNotBe(other: Any?) {
    (this != other) shouldBe true
}
