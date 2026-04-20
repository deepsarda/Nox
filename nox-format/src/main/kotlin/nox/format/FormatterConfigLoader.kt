package nox.format

import nox.format.FormatterConfig.EndOfLine
import nox.format.FormatterConfig.Indent
import nox.format.FormatterConfig.MaxBlankLines
import nox.format.FormatterConfig.TrailingComma
import nox.runtime.json.NoxJsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads [FormatterConfig] from a `.noxfmt.json` file.
 *
 * Discovery walks upward from the starting directory; the nearest file wins.
 * Missing files produce [FormatterConfig.DEFAULT]. Missing keys inherit defaults.
 * Unknown keys are ignored with a stderr warning so old binaries keep working
 * against newer config files.
 */
object FormatterConfigLoader {
    const val FILE_NAME = ".noxfmt.json"

    private val KNOWN_KEYS =
        setOf(
            "indent",
            "lineWidth",
            "trailingComma",
            "maxBlankLines",
            "endOfLine",
            "insertFinalNewline",
            "bracketSpacing",
            "parenSpacing",
        )

    fun load(start: Path): FormatterConfig {
        val file = discover(start) ?: return FormatterConfig.DEFAULT
        return try {
            parse(Files.readString(file))
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to load $file: ${e.message}", e)
        }
    }

    fun discover(start: Path): Path? {
        var dir: Path? = if (Files.isDirectory(start)) start else start.parent
        while (dir != null) {
            val candidate = dir.resolve(FILE_NAME)
            if (Files.isRegularFile(candidate)) return candidate
            dir = dir.parent
        }
        return null
    }

    fun parse(json: String): FormatterConfig {
        if (json.isBlank()) return FormatterConfig.DEFAULT
        val root =
            NoxJsonParser(json).parse() as? Map<*, *>
                ?: throw IllegalArgumentException("Expected top-level JSON object")

        for (key in root.keys) {
            if (key !is String || key !in KNOWN_KEYS) {
                System.err.println("noxfmt: ignoring unknown config key '$key'")
            }
        }

        return FormatterConfig(
            indent = root["indent"]?.let(::parseIndent) ?: FormatterConfig.DEFAULT.indent,
            lineWidth = (root["lineWidth"] as? Number)?.toInt() ?: FormatterConfig.DEFAULT.lineWidth,
            trailingComma =
                (root["trailingComma"] as? String)?.let(::parseTrailingComma)
                    ?: FormatterConfig.DEFAULT.trailingComma,
            maxBlankLines = root["maxBlankLines"]?.let(::parseMaxBlankLines) ?: FormatterConfig.DEFAULT.maxBlankLines,
            endOfLine = (root["endOfLine"] as? String)?.let(::parseEndOfLine) ?: FormatterConfig.DEFAULT.endOfLine,
            insertFinalNewline = (root["insertFinalNewline"] as? Boolean) ?: FormatterConfig.DEFAULT.insertFinalNewline,
            bracketSpacing = (root["bracketSpacing"] as? Boolean) ?: FormatterConfig.DEFAULT.bracketSpacing,
            parenSpacing = (root["parenSpacing"] as? Boolean) ?: FormatterConfig.DEFAULT.parenSpacing,
        )
    }

    private fun parseIndent(raw: Any?): Indent {
        val obj = raw as? Map<*, *> ?: throw IllegalArgumentException("indent must be an object")
        return when (val kind = obj["kind"] as? String) {
            "tabs" -> Indent.Tabs
            "spaces" -> {
                val width = (obj["width"] as? Number)?.toInt() ?: 4
                require(width in 1..16) { "indent.width must be between 1 and 16, got $width" }
                Indent.Spaces(width)
            }
            null -> throw IllegalArgumentException("indent.kind is required")
            else -> throw IllegalArgumentException("indent.kind must be 'spaces' or 'tabs', got '$kind'")
        }
    }

    private fun parseTrailingComma(value: String): TrailingComma =
        when (value.lowercase()) {
            "never" -> TrailingComma.NEVER
            "always" -> TrailingComma.ALWAYS
            else -> throw IllegalArgumentException("trailingComma must be 'never' or 'always', got '$value'")
        }

    private fun parseEndOfLine(value: String): EndOfLine =
        when (value.lowercase()) {
            "lf" -> EndOfLine.LF
            "crlf" -> EndOfLine.CRLF
            "native" -> EndOfLine.NATIVE
            else -> throw IllegalArgumentException("endOfLine must be 'lf', 'crlf', or 'native', got '$value'")
        }

    private fun parseMaxBlankLines(raw: Any?): MaxBlankLines {
        val obj = raw as? Map<*, *> ?: throw IllegalArgumentException("maxBlankLines must be an object")
        val top = (obj["topLevel"] as? Number)?.toInt() ?: MaxBlankLines().topLevel
        val nested = (obj["nested"] as? Number)?.toInt() ?: MaxBlankLines().nested
        require(top >= 0 && nested >= 0) { "maxBlankLines values must be non-negative" }
        return MaxBlankLines(topLevel = top, nested = nested)
    }
}
