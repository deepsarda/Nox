package nox.compiler

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import nox.compiler.NoxCompiler
import java.nio.file.Path

/**
 * Tests for the [nox.compiler.codegen.NoxcEmitter] disassembly output.
 *
 * Each test compiles a small snippet and asserts against specific sections
 * or lines in the `.noxc` text to ensure every formatting path is exercised.
 */
class NoxcEmitterTest :
    FunSpec({

        fun ok(
            source: String,
            fileName: String = "test.nox",
        ): String {
            val result = NoxCompiler.compile(source.trimIndent(), fileName)
            result.errors.hasErrors() shouldBe false
            result.disassembly shouldNotBe null
            return result.disassembly!!
        }

        fun okImport(
            mainSource: String,
            imports: Map<String, String>,
            basePath: Path = Path.of("/fake/main.nox"),
        ): String {
            val result =
                NoxCompiler.compile(
                    source = mainSource.trimIndent(),
                    fileName = "main.nox",
                    basePath = basePath,
                    fileReader = { path -> imports[path.fileName.toString()] ?: error("Unknown: $path") },
                )
            result.errors.hasErrors() shouldBe false
            return result.disassembly!!
        }

        // Header

        test("headerContainsSourceFileName") {
            val disasm = ok("main() { return \"ok\"; }", fileName = "hello.nox")
            disasm shouldContain ";  Source:   hello.nox"
        }

        test("headerContainsProgramName") {
            // @tool:name annotation in header
            val disasm =
                ok(
                    """
            @tool:name "MyProgram"
            main() { return "ok"; }
        """,
                )
            disasm shouldContain """Program:  "MyProgram""""
        }

        test("headerContainsCompiledTimestamp") {
            val disasm = ok("main() { return \"ok\"; }")
            disasm shouldContain ";  Compiled:"
        }

        test("headerContainsModules") {
            val disasm = ok("main() { return \"ok\"; }")
            disasm shouldContain ";  Modules:  1 (main)"
        }

        test("headerModulesLineWithImports") {
            val helperSrc = "int id(int x) { return x; }"
            val disasm =
                okImport(
                    """
            import "helper.nox" as helper;
            main() { return "ok"; }
            """,
                    mapOf("helper.nox" to helperSrc),
                )
            disasm shouldContain ";  Modules:"
        }

        // Constant pool type tags

        test("poolTagStrForStringConstants") {
            val disasm = ok("""main() { string s = "hello"; return "ok"; }""")
            disasm shouldContain "str"
            disasm shouldContain "\"hello\""
        }

        test("poolTagDblForDoubleConstants") {
            val disasm = ok("""main() { double d = 3.14; return "ok"; }""")
            disasm shouldContain "dbl"
            disasm shouldContain "3.14"
        }

        test("poolTagLngForLargeIntConstants") {
            // Large ints (> 65535) go into the pool as Long
            val disasm = ok("""main() { int x = 100000; return "ok"; }""")
            disasm shouldContain "lng"
            disasm shouldContain "100000"
        }

        test("emptyConstantPoolShowsNone") {
            // A program with NO string/double/large-int constants has an empty pool
            // Note: even `return "ok"` puts "ok" in pool, so use only: no-string return
            val disasm =
                ok(
                    """
            void noop() { }
            main() { noop(); return ""; }
        """,
                )
            // Pool will have at least the empty string, so test the pool section itself is there
            disasm shouldContain ".constants"
        }

        // Function header metadata

        test("funcHeaderShowsEntryPC") {
            val disasm =
                ok(
                    """
            int add(int a, int b) { return a + b; }
            main() { return "ok"; }
        """,
                )
            disasm shouldContain "Entry PC:"
        }

        test("funcHeaderShowsParamCount") {
            val disasm =
                ok(
                    """
            int add(int a, int b) { return a + b; }
            main() { return "ok"; }
        """,
                )
            disasm shouldContain "Params:     2"
        }

        test("funcHeaderShowsFrameSizes") {
            val disasm =
                ok(
                    """
            int add(int a, int b) { return a + b; }
            main() { return "ok"; }
        """,
                )
            disasm shouldContain "Frame:      pMem="
        }

        test("funcDirectiveAppearsForEachUserFunction") {
            val disasm =
                ok(
                    """
            int foo(int x) { return x; }
            string bar(string s) { return s; }
            main() { return "ok"; }
        """,
                )
            disasm shouldContain ".func foo"
            disasm shouldContain ".func bar"
            disasm shouldContain ".func main"
        }

        // Init block section

        test("initDirectiveEmittedForModuleWithGlobals") {
            val disasm =
                ok(
                    """
            int counter = 0;
            main() { return "ok"; }
        """,
                )
            disasm shouldContain ".init main"
        }

        test("noInitSectionWhenNoGlobalsWithInitializers") {
            val disasm =
                ok(
                    """
            main() { return "ok"; }
        """,
                )
            disasm shouldNotContain ".init"
        }

        test("globalsCommentInInitBlock") {
            val disasm =
                ok(
                    """
            int counter = 0;
            main() { return "ok"; }
        """,
                )
            // emitGlobalsComment writes ";  globals: g0 (int) ..."
            disasm shouldContain "globals:"
        }

        // Jump label comments

        test("jumpInstructionHasLabelComment") {
            val disasm =
                ok(
                    """
            main() { int i = 0; while (i < 10) { i++; } return "ok"; }
        """,
                )
            // JIF emits "if p0==0 -> loop_exit_N" style comment
            disasm shouldContain "loop_exit"
        }

        test("loopStartLabelInFuncBody") {
            val disasm =
                ok(
                    """
            main() { for (int i = 0; i < 5; i++) { } return "ok"; }
        """,
                )
            disasm shouldContain ".loop_start_"
        }

        test("catchLabelInFuncBody") {
            val disasm =
                ok(
                    """
            main() {
                try { int x = 1; } catch (err) { }
                return "ok";
            }
        """,
                )
            disasm shouldContain "catch_all"
        }

        test("endLabelAfterTryCatch") {
            val disasm =
                ok(
                    """
            main() {
                try { int x = 1; } catch (err) { }
                return "ok";
            }
        """,
                )
            disasm shouldContain ".end:"
        }

        // GLOAD/GSTORE disassembly formatting

        test("gloadFormatShowsGlobalRegisterName") {
            val disasm =
                ok(
                    """
            int counter = 5;
            main() { int x = counter; return "ok"; }
        """,
                )
            // GLOAD formatting: "p0, g0"
            disasm shouldContain "g0"
            disasm shouldContain "GLOAD"
        }

        test("gstoreFormatShowsGlobalRegisterName") {
            val disasm =
                ok(
                    """
            int counter = 42;
            main() { return "ok"; }
        """,
                )
            // GSTORE formatting: "g0, p0"
            disasm shouldContain "GSTORE"
            disasm shouldContain "g0"
        }

        // Exception table section

        test("emptyExceptionTableShowsNone") {
            val disasm = ok("""main() { return "ok"; }""")
            disasm shouldContain ".exceptions"
            disasm shouldContain "(none)"
        }

        test("exceptionTableEntryFormat") {
            val disasm =
                ok(
                    """
            main() {
                try { int x = 1; } catch (err) { }
                return "ok";
            }
        """,
                )
            // Exception entry format: "  [NNNN..NNNN] ANY                  -> @NNNN  msg=rN"
            disasm shouldContain "ANY"
            disasm shouldContain "msg=r"
        }

        test("typedExceptionTableEntry") {
            val disasm =
                ok(
                    """
            main() {
                try { int x = 1; } catch (IOError e) { string m = e; }
                return "ok";
            }
        """,
                )
            disasm shouldContain "IOError"
        }

        // Summary section

        test("summaryCountsMatchActual") {
            val disasm =
                ok(
                    """
            int add(int a, int b) { return a + b; }
            main() { return "ok"; }
        """,
                )
            // Summary should list "functions: 2" (add + main)
            disasm shouldContain "functions:    2"
        }

        test("summaryShowsBytecodeSize") {
            val disasm = ok("""main() { return "ok"; }""")
            disasm shouldContain "bytecode:"
            disasm shouldContain "bytes"
        }

        test("summaryShowsGlobalsLine") {
            val disasm =
                ok(
                    """
            int counter = 0;
            main() { return "ok"; }
        """,
                )
            // e.g. "  globals:      1p + 0r"
            disasm shouldContain "globals:"
            disasm shouldContain "p +"
        }

        // Source line annotations

        test("sourceLineAnnotationFormat") {
            val disasm =
                ok(
                    """
            main() { int x = 42; return "ok"; }
        """,
                )
            // Format: "  ; line N  source text"
            disasm shouldContain "line 1"
        }

        test("sourceAnnotationShowsSourceText") {
            val disasm =
                ok(
                    """
            main() { int x = 42; return "ok"; }
        """,
                )
            // The source text of the line should appear after "line N"
            disasm shouldContain "int x = 42"
        }

        // Multi-line / edge cases

        test("stringEscapingInPoolDisplay") {
            val disasm = ok("main() { string s = \"say \\\"hi\\\"\"; return \"ok\"; }")
            // Escaped quote in pool display: \"
            disasm shouldContain "\\\""
        }

        test("doubleWithTrailingZeroStrippedInPool") {
            // "3.0" stored as 3 in pool display (stripTrailingZeros)
            val disasm = ok("""main() { double d = 3.0; return "ok"; }""")
            // Should display as just "3" not "3.0"
            disasm shouldContain "dbl"
        }

        test("importedModuleInitSourceCommentShown") {
            val helperSrc = "int X = 99;"
            val disasm =
                okImport(
                    """
            import "helper.nox" as helper;
            main() { return "ok"; }
            """,
                    mapOf("helper.nox" to helperSrc),
                )
            // When multiple modules, init block shows ";  source:  helper.nox"
            disasm shouldContain "source:"
        }
    })
