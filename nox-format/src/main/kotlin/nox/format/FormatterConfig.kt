package nox.format

data class FormatterConfig(
    val indent: Indent = Indent.Spaces(4),
    val lineWidth: Int = 100,
    val trailingComma: TrailingComma = TrailingComma.NEVER,
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

    companion object {
        val DEFAULT = FormatterConfig()
    }
}
