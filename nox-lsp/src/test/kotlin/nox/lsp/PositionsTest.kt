package nox.lsp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import nox.compiler.types.SourceLocation
import nox.lsp.conversions.Positions

class PositionsTest :
    StringSpec({
        "compiler line is 1-based, LSP line is 0-based" {
            val loc = SourceLocation("f.nox", line = 5, column = 2)
            val pos = Positions.toLspPosition(loc)
            pos.line shouldBe 4
            pos.character shouldBe 2
        }

        "range widens to requested length on same line" {
            val loc = SourceLocation("f.nox", line = 3, column = 7)
            val range = Positions.toLspRange(loc, length = 5)
            range.start.line shouldBe 2
            range.start.character shouldBe 7
            range.end.line shouldBe 2
            range.end.character shouldBe 12
        }

        "line 1 becomes line 0 (no underflow)" {
            val loc = SourceLocation("f.nox", line = 1, column = 0)
            Positions.toLspPosition(loc).line shouldBe 0
        }

        "contains checks both line and column span" {
            val loc = SourceLocation("f.nox", line = 2, column = 4)
            Positions.contains(loc, lspLine = 1, lspColumn = 4, length = 3) shouldBe true
            Positions.contains(loc, lspLine = 1, lspColumn = 6, length = 3) shouldBe true
            Positions.contains(loc, lspLine = 1, lspColumn = 7, length = 3) shouldBe false
            Positions.contains(loc, lspLine = 0, lspColumn = 4, length = 3) shouldBe false
        }
    })
