package nox.format

/**
 * A Wadler-style document model for the layout engine.
 * Allows structural formatting that can break lines based on width constraints.
 */
sealed interface Doc {
    data class Text(
        val content: String,
    ) : Doc

    data object Line : Doc // Hard line break

    data class SoftLine(
        val fallback: String = " ",
    ) : Doc // Space if flat, newline if broken

    data class Indent(
        val doc: Doc,
    ) : Doc

    data class Group(
        val doc: Doc,
    ) : Doc // Tries to format on one line, breaks all internal SoftLines if it doesn't fit

    data class Concat(
        val parts: List<Doc>,
    ) : Doc

    companion object {
        val empty = Text("")
        val space = Text(" ")

        fun text(s: String) = Text(s)

        fun group(vararg docs: Doc) = Group(Concat(docs.toList()))

        fun concat(vararg docs: Doc) = Concat(docs.toList())

        fun indent(vararg docs: Doc) = Indent(Concat(docs.toList()))
    }
}
