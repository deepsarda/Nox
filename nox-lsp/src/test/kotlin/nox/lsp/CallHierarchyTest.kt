package nox.lsp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import nox.compiler.NoxCompiler
import nox.lsp.features.CallHierarchyProvider
import nox.lsp.protocol.*

private fun compile(src: String) = NoxCompiler.analyze(src, "test.nox")

class CallHierarchyTest :
    StringSpec({

        "prepare finds function at cursor position" {
            val src =
                """
                int add(int a, int b) { return a + b; }
                main() { add(1, 2); }
                """.trimIndent()
            val result = compile(src)
            val items = CallHierarchyProvider.prepare(result.program, "file:///x.nox", Position(0, 4))
            items shouldHaveSize 1
            items[0].name shouldBe "add"
        }

        "prepare returns empty when cursor is not on a function name" {
            val src =
                """
                main() { int x = 1; }
                """.trimIndent()
            val result = compile(src)
            val items = CallHierarchyProvider.prepare(result.program, "file:///x.nox", Position(0, 12))
            items shouldHaveSize 0
        }

        "outgoing calls lists callees from a function" {
            val src =
                """
                int helper() { return 1; }
                int compute() { return helper(); }
                main() { compute(); }
                """.trimIndent()
            val result = compile(src)
            val item =
                CallHierarchyItem(
                    name = "compute",
                    kind = SymbolKind.Function,
                    uri = "file:///x.nox",
                    range = Range(Position(1, 0), Position(1, 7)),
                    selectionRange = Range(Position(1, 0), Position(1, 7)),
                )
            val calls = CallHierarchyProvider.outgoingCalls(item, result.typedProgram)
            calls shouldHaveSize 1
            calls[0].to.name shouldBe "helper"
        }

        "incoming calls finds all callers of a function" {
            val src =
                """
                int helper() { return 1; }
                int foo() { return helper(); }
                int bar() { return helper(); }
                main() { foo(); bar(); }
                """.trimIndent()
            val result = compile(src)
            val item =
                CallHierarchyItem(
                    name = "helper",
                    kind = SymbolKind.Function,
                    uri = "file:///x.nox",
                    range = Range(Position(0, 0), Position(0, 6)),
                    selectionRange = Range(Position(0, 0), Position(0, 6)),
                )
            val calls = CallHierarchyProvider.incomingCalls(item, result.typedProgram)
            calls shouldHaveSize 2
            calls.map { it.from.name }.toSet() shouldBe setOf("foo", "bar")
        }
    })
