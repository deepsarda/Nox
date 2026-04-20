package nox.lsp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import nox.compiler.NoxCompiler
import nox.lsp.features.ScopeWalker

private fun compile(src: String) = NoxCompiler.analyze(src, "test.nox")

class ScopeWalkerTest :
    StringSpec({

        "finds params and locals in scope" {
            val src =
                """
                int add(int a, int b) {
                    int sum = a + b;
                    sum;
                }
                """.trimIndent()
            val typed = compile(src).typedProgram!!
            val vars = ScopeWalker.variablesAt(typed, lspLine = 2, lspColumn = 4)
            vars.map { it.name }.toSet() shouldContainAll setOf("a", "b", "sum")
        }

        "globals are visible everywhere" {
            val src =
                """
                int counter = 0;
                main() {
                    counter;
                }
                """.trimIndent()
            val typed = compile(src).typedProgram!!
            val vars = ScopeWalker.variablesAt(typed, lspLine = 2, lspColumn = 4)
            vars.any { it.name == "counter" } shouldBe true
        }

        "variable declared after cursor is not in scope" {
            val src =
                """
                main() {
                    int x = 1;
                    int y = 2;
                    int z = 3;
                }
                """.trimIndent()
            val typed = compile(src).typedProgram!!
            val vars = ScopeWalker.variablesAt(typed, lspLine = 1, lspColumn = 4)
            vars.any { it.name == "x" } shouldBe true
            vars.any { it.name == "y" } shouldBe false
        }

        "foreach loop variable is in scope inside body" {
            val src =
                """
                main() {
                    int[] items = [1, 2, 3];
                    foreach (int item in items) {
                        item;
                    }
                }
                """.trimIndent()
            val typed = compile(src).typedProgram!!
            val vars = ScopeWalker.variablesAt(typed, lspLine = 3, lspColumn = 8)
            vars.any { it.name == "item" } shouldBe true
        }
    })
