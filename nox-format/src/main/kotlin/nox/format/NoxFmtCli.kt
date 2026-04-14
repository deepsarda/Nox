package nox.format

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

class NoxFmtCli : CliktCommand(name = "noxfmt") {
    private val check by option(
        "--check",
        help = "Exit non-zero if any file needs formatting. Do not write changes.",
    ).flag()

    private val toStdout by option(
        "--stdout",
        help = "Write formatted output to stdout instead of modifying files in place.",
    ).flag()

    private val fromStdin by option(
        "--stdin",
        help = "Read source from stdin, write formatted output to stdout.",
    ).flag()

    private val paths by argument(name = "files").path(mustExist = true, canBeDir = true).multiple()

    override fun run() {
        if (fromStdin) {
            val source = System.`in`.bufferedReader().readText()
            print(Formatter.format(source))
            return
        }

        if (paths.isEmpty()) {
            echo("noxfmt: no input files (use --stdin to read from standard input)", err = true)
            exitProcess(2)
        }

        val files = paths.flatMap { expand(it) }
        var needFormatting = 0

        for (file in files) {
            val original = file.readText()
            val formatted = Formatter.format(original)
            when {
                fromStdin -> {} // unreachable, handled above
                toStdout -> print(formatted)
                check ->
                    if (original != formatted) {
                        echo("would reformat $file", err = true)
                        needFormatting++
                    }
                original != formatted -> Files.writeString(file, formatted)
            }
        }

        if (check && needFormatting > 0) exitProcess(1)
    }

    private fun expand(path: Path): List<Path> =
        if (Files.isDirectory(path)) {
            Files.walk(path).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".nox") }.toList()
            }
        } else {
            listOf(path)
        }
}

fun main(args: Array<String>) = NoxFmtCli().main(args)
