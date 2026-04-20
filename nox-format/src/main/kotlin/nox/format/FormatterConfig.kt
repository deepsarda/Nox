package nox.format

data class FormatterConfig(
    val indent: Indent = Indent.Spaces(4),
    val lineWidth: Int = 100,
    val trailingComma: TrailingComma = TrailingComma.NEVER,
    val maxBlankLines: MaxBlankLines = MaxBlankLines(),
    val endOfLine: EndOfLine = EndOfLine.LF,
    val insertFinalNewline: Boolean = true,
    val bracketSpacing: Boolean = true,
    val parenSpacing: Boolean = false,
) {
    sealed interface Indent {
        val unit: String

        data object Tabs : Indent {
            override val unit: String = "\t"
        }

        data class Spaces(
            val width: Int,
        ) : Indent {
            override val unit: String = " ".repeat(width)
        }
    }

    enum class TrailingComma { NEVER, ALWAYS }

    enum class EndOfLine {
        LF,
        CRLF,
        NATIVE,
        ;

        fun sequence(): String =
            when (this) {
                LF -> "\n"
                CRLF -> "\r\n"
                NATIVE -> System.lineSeparator()
            }
    }

    data class MaxBlankLines(
        val topLevel: Int = 3,
        val nested: Int = 1,
    ) {
        fun forDepth(isTopLevel: Boolean): Int = if (isTopLevel) topLevel else nested
    }

    companion object {
        val DEFAULT = FormatterConfig()
    }
}
