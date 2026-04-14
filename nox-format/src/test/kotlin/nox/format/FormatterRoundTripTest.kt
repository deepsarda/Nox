package nox.format

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import nox.parser.NoxLexer
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText

class FormatterRoundTripTest :
    FunSpec({
        val programsDir = Paths.get("..", "src", "test", "resources", "nox", "programs").toAbsolutePath().normalize()
        val programs =
            if (Files.isDirectory(programsDir)) {
                Files.walk(programsDir).use { s ->
                    s.filter { Files.isRegularFile(it) && it.toString().endsWith(".nox") }.sorted().toList()
                }
            } else {
                emptyList()
            }

        programs.forEach { path ->
            test("idempotent: ${programsDir.relativize(path)}") {
                val source = path.readText()
                val once = Formatter.format(source)
                val twice = Formatter.format(once)
                twice shouldBe once
            }

            test("non-destructive: ${programsDir.relativize(path)}") {
                val source = path.readText()
                val formatted = Formatter.format(source)
                tokenSignature(formatted) shouldBe tokenSignature(source)
            }
        }
    })

/**
 * Returns the sequence of (tokenType, text) pairs for non-hidden tokens.
 * Two sources with the same signature parse to the same token stream (modulo trivia),
 * which is the strongest structural-equivalence check short of comparing ASTs.
 */
private fun tokenSignature(source: String): List<Pair<Int, String>> {
    if (source.isBlank()) return emptyList()
    val lexer = NoxLexer(CharStreams.fromString(source))
    val stream = CommonTokenStream(lexer)
    stream.fill()
    return stream.tokens
        .filter { it.channel == Token.DEFAULT_CHANNEL && it.type != Token.EOF }
        .map { it.type to it.text }
}

@Suppress("unused")
private fun Path.exists() = Files.exists(this)
