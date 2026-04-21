package nox.lsp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import nox.lsp.protocol.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class DiagnosticsFlowTest :
    StringSpec({
        "publishes diagnostics on didOpen when source has a type error" {
            val (server, client) = bootServer()
            val uri = "file:///tmp/bad.nox"
            val src =
                """
                main() {
                    int x = "hello";
                }
                """.trimIndent()
            server.textService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "nox", 1, src)),
            )
            val diags = client.diagnostics.poll(5, TimeUnit.SECONDS)
            diags.shouldNotBeNull()
            diags!!.diagnostics.shouldNotBeEmpty()
            diags.uri shouldBe uri
        }

        "publishes empty diagnostics on didOpen when source is valid" {
            val (server, client) = bootServer()
            val uri = "file:///tmp/ok.nox"
            val src =
                """
                main() {
                    int x = 1;
                }
                """.trimIndent()
            server.textService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "nox", 1, src)),
            )
            val diags = client.diagnostics.poll(5, TimeUnit.SECONDS)
            diags.shouldNotBeNull()
            diags!!.diagnostics shouldBe emptyList()
        }

        "hover on a typed identifier returns its type" {
            val (server, _) = bootServer()
            val uri = "file:///tmp/hover.nox"
            val src =
                """
                main() {
                    int counter = 42;
                    counter;
                }
                """.trimIndent()
            server.textService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "nox", 1, src)),
            )

            // Line indices are 0-based in LSP: line 2 is "    counter;", column 4 lands on 'c'.
            val hover =
                server.textService
                    .hover(HoverParams(TextDocumentIdentifier(uri), Position(2, 4)))
            hover.shouldNotBeNull()
            val markup = hover!!.contents.value
            (markup.contains("int")) shouldBe true
        }

        "didChange re-publishes diagnostics for the new version" {
            val (server, client) = bootServer()
            val uri = "file:///tmp/change.nox"
            server.textService.didOpen(
                DidOpenTextDocumentParams(
                    TextDocumentItem(
                        uri,
                        "nox",
                        1,
                        """
                        main() {
                            int x = 1;
                        }
                        """.trimIndent(),
                    ),
                ),
            )
            client.diagnostics.poll(2, TimeUnit.SECONDS)

            val bad =
                """
                main() {
                    int x = "bad";
                }
                """.trimIndent()
            server.textService.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, 2),
                    listOf(TextDocumentContentChangeEvent(bad)),
                ),
            )
            val diags = client.diagnostics.poll(5, TimeUnit.SECONDS)
            diags.shouldNotBeNull()
            diags!!.diagnostics.shouldNotBeEmpty()
        }

        "didClose clears diagnostics" {
            val (server, client) = bootServer()
            val uri = "file:///tmp/close.nox"
            server.textService.didOpen(
                DidOpenTextDocumentParams(
                    TextDocumentItem(uri, "nox", 1, "main() { int x = \"bad\"; }"),
                ),
            )
            client.diagnostics.poll(2, TimeUnit.SECONDS) // wait for open diagnostics

            server.textService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
            val diags = client.diagnostics.poll(2, TimeUnit.SECONDS)
            diags.shouldNotBeNull()
            diags!!.diagnostics shouldBe emptyList()
        }

        "server responds to basic feature requests" {
            val (server, _) = bootServer()
            val uri = "file:///tmp/features.nox"
            val src = "main() { int x = 1; x; }"
            server.textService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "nox", 1, src)))

            // Test definition
            val defs = server.textService.definition(DefinitionParams(TextDocumentIdentifier(uri), Position(0, 20)))
            defs.shouldNotBeNull()

            // Test references
            val refs =
                server.textService.references(
                    ReferenceParams(TextDocumentIdentifier(uri), Position(0, 20), ReferenceContext(true)),
                )
            refs.shouldNotBeNull()

            // Test rename
            val rename = server.textService.rename(RenameParams(TextDocumentIdentifier(uri), Position(0, 20), "y"))
            rename.shouldNotBeNull()
        }
    })

private fun bootServer(): Pair<NoxLanguageServer, FakeClient> {
    val server = NoxLanguageServer()
    val client = FakeClient()
    server.textService.notifyClient = { method, params ->
        if (method == "textDocument/publishDiagnostics") {
            client.diagnostics.put(
                parsePublishDiagnosticsParams(params as JsonObject),
            )
        }
    }
    return server to client
}

private fun <T> T?.shouldNotBeNull() {
    if (this == null) throw AssertionError("expected non-null")
}

private class FakeClient {
    val diagnostics = LinkedBlockingQueue<PublishDiagnosticsParams>()
}
