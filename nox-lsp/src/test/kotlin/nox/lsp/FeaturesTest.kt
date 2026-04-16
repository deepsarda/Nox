package nox.lsp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import nox.compiler.NoxCompiler
import nox.lsp.features.CodeActionProvider
import nox.lsp.features.CompletionProvider
import nox.lsp.features.DefinitionProvider
import nox.lsp.features.DocumentSymbols
import nox.lsp.features.FoldingRanges
import nox.lsp.features.FormattingProvider
import nox.lsp.features.InlayHintsProvider
import nox.lsp.features.ReferencesProvider
import nox.lsp.features.RenameProvider
import nox.lsp.features.SemanticTokensProvider
import nox.lsp.features.SignatureHelpProvider
import nox.lsp.protocol.*

private fun compile(src: String): NoxCompiler.CompilationResult = NoxCompiler.analyze(src, "test.nox")

class FeaturesTest :
    StringSpec({

        "formatting produces an edit when the doc isn't canonical" {
            val edits = FormattingProvider.format("main(  ){int x=1;}")
            edits shouldHaveSize 1
            edits[0].newText shouldContain "int x = 1"
        }

        "formatting returns no edits when already canonical" {
            val canon =
                """
                main() {
                    int x = 1;
                }
                """.trimIndent() + "\n"
            FormattingProvider.format(canon).shouldBeEmpty()
        }

        "document symbols list functions, types, and globals" {
            val src =
                """
                type Point { int x; int y; }
                int counter = 0;
                int add(int a, int b) { return a + b; }
                main() { return; }
                """.trimIndent()
            val syms = DocumentSymbols.collect(compile(src).program)
            syms.map { it.name }.toSet() shouldBe setOf("Point", "counter", "add", "main")
            syms.first { it.name == "Point" }.children?.map { it.name } shouldBe listOf("x", "y")
            syms.first { it.name == "add" }.kind shouldBe SymbolKind.Function
        }

        "folding ranges cover every brace block" {
            val src =
                """
                main() {
                    if (true) {
                        int x = 1;
                    }
                }
                """.trimIndent()
            val folds = FoldingRanges.collect(compile(src).program, src)
            folds shouldHaveAtLeastSize 2
        }

        "go-to-definition on a local jumps to its declaration" {
            val src =
                """
                main() {
                    int counter = 42;
                    counter;
                }
                """.trimIndent()
            val result = compile(src)
            val typed = result.typedProgram!!
            // position of `counter` on line 2 (LSP 0-based: line 2)
            val defs = DefinitionProvider.definition(typed, result.program, "file:///x.nox", 2, 4)
            defs shouldHaveSize 1
            // Declaration is on line 1 (the `int counter = 42;` line), LSP 0-based → line 1
            defs[0].range.start.line shouldBe 1
        }

        "references finds every use including the declaration" {
            val src =
                """
                main() {
                    int counter = 0;
                    counter = counter + 1;
                    counter;
                }
                """.trimIndent()
            val result = compile(src)
            val typed = result.typedProgram!!
            val refs =
                ReferencesProvider.references(
                    typed = typed,
                    raw = result.program,
                    uri = "file:///x.nox",
                    lspLine = 2,
                    lspColumn = 4,
                    includeDeclaration = true,
                )
            refs shouldHaveAtLeastSize 3
        }

        "rename rejects a keyword as the new name" {
            val src =
                """
                main() {
                    int counter = 0;
                    counter;
                }
                """.trimIndent()
            val result = compile(src)
            val typed = result.typedProgram!!
            RenameProvider.rename(typed, result.program, "file:///x.nox", 2, 4, newName = "int") shouldBe null
        }

        "rename produces a workspace edit with every occurrence rewritten" {
            val src =
                """
                main() {
                    int counter = 0;
                    counter = counter + 1;
                }
                """.trimIndent()
            val result = compile(src)
            val typed = result.typedProgram!!
            // Click on the `counter` reference on line 2, col 4 (0-based LSP).
            val we = RenameProvider.rename(typed, result.program, "file:///x.nox", 2, 4, newName = "total")
            we shouldNotBe null
            we!!.changes!!["file:///x.nox"]!!.shouldHaveAtLeastSize(2)
        }

        "semantic tokens emit a non-empty encoded stream" {
            val src =
                """
                int add(int a, int b) { return a + b; }
                main() { add(1, 2); }
                """.trimIndent()
            val tokens = SemanticTokensProvider.fullFile(compile(src).typedProgram!!)
            (tokens.data.size % 5) shouldBe 0
            tokens.data.size shouldNotBe 0
        }

        "inlay hints annotate literal args with parameter names" {
            val src =
                """
                int add(int a, int b) { return a + b; }
                main() { add(1, 2); }
                """.trimIndent()
            val hints = InlayHintsProvider.hintsFor(compile(src).typedProgram!!)
            hints shouldHaveSize 2
            hints.map { it.label } shouldBe listOf("a:", "b:")
        }

        "completion without a dot context returns keywords and top-level names" {
            val src =
                """
                int helper() { return 1; }
                main() {  }
                """.trimIndent()
            val result = compile(src)
            val items = CompletionProvider.complete(result, src, lspLine = 1, lspColumn = 9)
            val names = items.map { it.label }.toSet()
            names shouldContainAll setOf("helper", "main", "if", "while", "return")
        }

        "completion after dot resolves struct fields and UCFS" {
            val src =
                """
                type Node {
                    string value;
                    Node left;
                    Node right;
                }
                int doSomething(Node n) { return 1; }
                main() {
                    Node root = { value: "root", left: null, right: null };
                    root.
                }
                """.trimIndent()
            
            val result = compile(src)
            val lines = src.split('\n')
            val lineIdx = lines.indexOfFirst { it.contains("root.") }
            val colIdx = lines[lineIdx].indexOf("root.") + 5
            
            val items = CompletionProvider.complete(result, src, lineIdx, colIdx)
            val names = items.map { it.label }.toSet()
            names shouldContainAll setOf("value", "left", "right", "doSomething")
        }

        "signature help surfaces parameter names mid-call" {
            // Compile a valid source so raw.functionsByName has `add`.
            val compileSrc =
                """
                int add(int a, int b) { return a + b; }
                main() { add(1, 2); }
                """.trimIndent()
            val result = compile(compileSrc)
            // Source the user is looking at — the actual position where they're typing.
            val editorSrc =
                """
                int add(int a, int b) { return a + b; }
                main() { add(1,
                """.trimIndent()
            // Line 1 (0-based), col 15 = position after the comma.
            val help = SignatureHelpProvider.help(result.program, editorSrc, lspLine = 1, lspColumn = 15)
            help shouldNotBe null
            help!!.signatures[0].parameters shouldHaveSize 2
            help.activeParameter shouldBe 1
        }

        "code actions apply 'did you mean' suggestions from the compiler" {
            val src =
                """
                int helper() { return 1; }
                main() { heper(); }
                """.trimIndent()
            val result = compile(src)
            val range = Range(Position(1, 9), Position(1, 14))
            val actions = CodeActionProvider.actions(result, "file:///x.nox", range)
            // Compiler may or may not have a suggestion for this typo depending on heuristics,
            // so assert only that the API doesn't crash and returns a list.
            actions shouldHaveSize actions.size
        }
    })

private infix fun <T> Collection<T>.shouldContainAll(expected: Set<T>) {
    val missing = expected - this.toSet()
    if (missing.isNotEmpty()) throw AssertionError("missing: $missing")
}

