package nox.format

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotEndWith
import nox.format.FormatterConfig.EndOfLine
import nox.format.FormatterConfig.Indent
import nox.format.FormatterConfig.MaxBlankLines

class FormatterConfigRulesTest :
    StringSpec({
        "indent tabs renders tab characters" {
            val src = "main() {\n    int x = 1;\n}\n"
            val out = Formatter.format(src, FormatterConfig(indent = Indent.Tabs))
            out shouldContain "\tint x"
        }

        "indent 2 spaces renders two-space indent" {
            val src = "main() {\n    int x = 1;\n}\n"
            val out = Formatter.format(src, FormatterConfig(indent = Indent.Spaces(2)))
            out shouldContain "  int x"
        }

        "bracketSpacing=false collapses struct braces" {
            val src = "type P { int x; }\nmain() { P p = { x: 1 }; }\n"
            val defaulted = Formatter.format(src)
            val tight = Formatter.format(src, FormatterConfig(bracketSpacing = false))
            defaulted shouldContain "{ x: 1 }"
            tight shouldContain "{x: 1}"
        }

        "parenSpacing=true pads call parens" {
            val src = "main() { print(1); }\n"
            val padded = Formatter.format(src, FormatterConfig(parenSpacing = true))
            padded shouldContain "print( 1 )"
        }

        "endOfLine CRLF uses \\r\\n" {
            val out = Formatter.format("main() { int x = 1; }\n", FormatterConfig(endOfLine = EndOfLine.CRLF))
            out shouldContain "\r\n"
        }

        "insertFinalNewline=false drops trailing newline" {
            val out = Formatter.format("main() { int x = 1; }\n", FormatterConfig(insertFinalNewline = false))
            out shouldNotEndWith "\n"
        }

        "maxBlankLines caps top-level blanks" {
            val src = "type A { int x; }\n\n\n\n\ntype B { int y; }\nmain() { int z = 1; }\n"
            val cfg = FormatterConfig(maxBlankLines = MaxBlankLines(topLevel = 1, nested = 1))
            val out = Formatter.format(src, cfg)
            // topLevel=1 → at most one blank line between top-level decls
            val between = out.substringAfter("}").substringBefore("type B")
            between.count { it == '\n' } shouldBe 2 // terminating \n + one blank line
        }
    })
