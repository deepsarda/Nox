package nox.format

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import nox.format.FormatterConfig.EndOfLine
import nox.format.FormatterConfig.Indent
import nox.format.FormatterConfig.MaxBlankLines
import nox.format.FormatterConfig.TrailingComma
import java.nio.file.Files

class FormatterConfigLoaderTest :
    StringSpec({
        "empty json returns default" {
            FormatterConfigLoader.parse("") shouldBe FormatterConfig.DEFAULT
        }

        "blank json object inherits all defaults" {
            FormatterConfigLoader.parse("{}") shouldBe FormatterConfig.DEFAULT
        }

        "parses indent spaces" {
            val cfg = FormatterConfigLoader.parse("""{"indent": {"kind": "spaces", "width": 2}}""")
            cfg.indent shouldBe Indent.Spaces(2)
        }

        "parses indent tabs" {
            val cfg = FormatterConfigLoader.parse("""{"indent": {"kind": "tabs"}}""")
            cfg.indent shouldBe Indent.Tabs
        }

        "parses lineWidth" {
            FormatterConfigLoader.parse("""{"lineWidth": 60}""").lineWidth shouldBe 60
        }

        "parses trailingComma" {
            FormatterConfigLoader.parse("""{"trailingComma": "always"}""").trailingComma shouldBe TrailingComma.ALWAYS
        }

        "parses maxBlankLines" {
            val cfg = FormatterConfigLoader.parse("""{"maxBlankLines": {"topLevel": 2, "nested": 0}}""")
            cfg.maxBlankLines shouldBe MaxBlankLines(topLevel = 2, nested = 0)
        }

        "parses endOfLine" {
            FormatterConfigLoader.parse("""{"endOfLine": "crlf"}""").endOfLine shouldBe EndOfLine.CRLF
        }

        "parses insertFinalNewline" {
            FormatterConfigLoader.parse("""{"insertFinalNewline": false}""").insertFinalNewline shouldBe false
        }

        "parses bracketSpacing" {
            FormatterConfigLoader.parse("""{"bracketSpacing": false}""").bracketSpacing shouldBe false
        }

        "parses parenSpacing" {
            FormatterConfigLoader.parse("""{"parenSpacing": true}""").parenSpacing shouldBe true
        }

        "unknown keys are tolerated" {
            val cfg = FormatterConfigLoader.parse("""{"futureKey": 42, "lineWidth": 80}""")
            cfg.lineWidth shouldBe 80
        }

        "invalid trailingComma rejected" {
            runCatching { FormatterConfigLoader.parse("""{"trailingComma": "maybe"}""") }.isFailure shouldBe true
        }

        "invalid indent kind rejected" {
            runCatching { FormatterConfigLoader.parse("""{"indent": {"kind": "squiggles"}}""") }.isFailure shouldBe true
        }

        "invalid JSON rejected" {
            runCatching { FormatterConfigLoader.parse("not json") }.isFailure shouldBe true
        }

        "load walks up the directory tree" {
            val tmp = Files.createTempDirectory("noxfmt-test")
            try {
                Files.writeString(tmp.resolve(".noxfmt.json"), """{"lineWidth": 72}""")
                val nested = Files.createDirectories(tmp.resolve("a/b"))
                val cfg = FormatterConfigLoader.load(nested)
                cfg.lineWidth shouldBe 72
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

        "load with no config returns default" {
            val tmp = Files.createTempDirectory("noxfmt-test")
            try {
                FormatterConfigLoader.load(tmp) shouldBe FormatterConfig.DEFAULT
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }
    })
