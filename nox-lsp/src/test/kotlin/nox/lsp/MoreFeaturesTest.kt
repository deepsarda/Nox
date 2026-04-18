package nox.lsp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import nox.compiler.NoxCompiler
import nox.lsp.features.*
import nox.lsp.protocol.*

private fun compile(src: String): NoxCompiler.CompilationResult = NoxCompiler.analyze(src, "test.nox")

class MoreFeaturesTest :
    StringSpec({

        "completion on empty struct fields returns nothing" {
            val src =
                """
                type Empty {}
                main() {
                    Empty e = {};
                    e.
                }
                """.trimIndent()
            val result = compile(src)
            val items = CompletionProvider.complete(result, src, 3, 6)
            items.filter { it.kind == CompletionItemKind.Field } shouldHaveSize 0
        }

        "go-to-definition does not crash on field access" {
            val src =
                """
                type Point { int x; }
                main() {
                    Point p = { x: 10 };
                    p.x;
                }
                """.trimIndent()
            val result = compile(src)
            DefinitionProvider.definition(result.typedProgram!!, result.program, "file:///test.nox", 3, 6)
        }

        "references of a function" {
            val src =
                """
                int add(int a, int b) { return a + b; }
                main() {
                    add(1, 2);
                    add(3, 4);
                }
                """.trimIndent()
            val result = compile(src)
            // Position at line 2, col 4 is 'add' in 'add(1, 2)'
            val refs = ReferencesProvider.references(result.typedProgram!!, result.program, "file:///x.nox", 2, 4, true)
            refs shouldHaveSize 3 // declaration + 2 calls
        }

        "rename a function across calls" {
            val src =
                """
                int calc() { return 0; }
                main() {
                    calc();
                    calc();
                }
                """.trimIndent()
            val result = compile(src)
            val we = RenameProvider.rename(result.typedProgram!!, result.program, "file:///x.nox", 2, 4, "compute")
            we shouldNotBe null
            we!!.changes!!["file:///x.nox"]!!.size shouldBe 3
        }

        "hover on function does not crash" {
            val src =
                """
                int calc(int a) { return a; }
                main() {
                    calc(1);
                }
                """.trimIndent()
            val result = compile(src)
            val hover = HoverProvider.hover(result.typedProgram!!, 2, 4)
            hover shouldNotBe null
        }

        "signature help without parameters does not crash" {
            val src =
                """
                int calc() { return 0; }
                main() { calc(
                """.trimIndent()
            val result = compile(src)
            val help = SignatureHelpProvider.help(result.program, src, 1, 14)
            help shouldNotBe null
            help!!.signatures[0].parameters shouldHaveSize 0
        }
    })
