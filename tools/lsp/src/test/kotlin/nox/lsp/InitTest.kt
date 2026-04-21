package nox.lsp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import nox.runtime.json.NoxJsonParser
import nox.runtime.json.NoxJsonWriter


import nox.lsp.protocol.*

class InitTest :
    StringSpec({
        "initialize returns all registered capabilities" {
            val server = NoxLanguageServer()
            val params = InitializeParams(processId = 1, rootUri = "file:///tmp").toJson()
            val result = server.handleRequest("initialize", params)
            result shouldNotBe null
            val initResult = parseInitializeResult(result as JsonObject)
            val caps = initResult.capabilities
            caps.textDocumentSync shouldBe 1
            caps.hoverProvider shouldBe true
            caps.definitionProvider shouldBe true
            caps.referencesProvider shouldBe true
            caps.documentSymbolProvider shouldBe true
            caps.documentFormattingProvider shouldBe true
            caps.foldingRangeProvider shouldBe true
            caps.inlayHintProvider shouldBe true
            caps.completionProvider shouldNotBe null
            caps.signatureHelpProvider shouldNotBe null
            caps.codeActionProvider shouldBe true
            caps.renameProvider shouldBe true
            caps.callHierarchyProvider shouldBe true
            caps.semanticTokensProvider shouldNotBe null
        }

        "shutdown returns null" {
            val server = NoxLanguageServer()
            val result = server.handleRequest("shutdown", null)
            result shouldBe null
        }

        "unknown method returns null" {
            val server = NoxLanguageServer()
            val result = server.handleRequest("nonexistent/method", null)
            result shouldBe null
        }
    })
