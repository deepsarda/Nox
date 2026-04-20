package nox.format

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import nox.BuildInfo
import nox.format.FormatterConfig.EndOfLine
import nox.format.FormatterConfig.Indent
import nox.format.FormatterConfig.TrailingComma
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

class NoxFmtCli : CliktCommand(name = "noxfmt") {
    init {
        versionOption(BuildInfo.VERSION, names = setOf("--version"), message = { "noxfmt $it" })
    }

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

    private val configPath by option(
        "--config",
        help = "Path to a .noxfmt.json file. Overrides discovery.",
    ).path(mustExist = true, canBeDir = false)

    private val indentSpaces by option("--indent-spaces", help = "Indent with N spaces.").int()
    private val indentTabs by option("--indent-tabs", help = "Indent with tabs.").flag()
    private val lineWidth by option("--line-width", help = "Max line width before wrapping.").int()
    private val trailingCommaFlag by option("--trailing-comma", help = "never|always").enum<TrailingComma>()
    private val eolFlag by option("--eol", help = "lf|crlf|native").enum<EndOfLine>()
    private val finalNewline: Boolean? by option(help = "Ensure file ends with newline.")
        .switch("--final-newline" to true, "--no-final-newline" to false)
    private val bracketSpacing: Boolean? by option(help = "Space inside struct literal braces.")
        .switch("--bracket-spacing" to true, "--no-bracket-spacing" to false)
    private val parenSpacing: Boolean? by option(help = "Space inside call parens/brackets.")
        .switch("--paren-spacing" to true, "--no-paren-spacing" to false)

    private val paths by argument(name = "files").path(mustExist = true, canBeDir = true).multiple()

    override fun run() {
        if (fromStdin) {
            val source = System.`in`.bufferedReader().readText()
            val config = overrideConfig(loadConfig(null))
            print(Formatter.format(source, config))
            return
        }

        if (paths.isEmpty()) {
            echo("noxfmt: no input files (use --stdin to read from standard input)", err = true)
            exitProcess(2)
        }

        val files = paths.flatMap { expand(it) }
        var needFormatting = 0

        for (file in files) {
            val config = overrideConfig(loadConfig(file))
            val original = file.readText()
            val formatted = Formatter.format(original, config)
            when {
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

    private fun loadConfig(file: Path?): FormatterConfig {
        try {
            configPath?.let { return FormatterConfigLoader.parse(Files.readString(it)) }
            if (file != null) return FormatterConfigLoader.load(file.parent ?: file)
            return FormatterConfig.DEFAULT
        } catch (e: IllegalArgumentException) {
            echo("noxfmt: ${e.message}", err = true)
            exitProcess(2)
        }
    }

    private fun overrideConfig(base: FormatterConfig): FormatterConfig {
        val indent =
            when {
                indentTabs -> Indent.Tabs
                indentSpaces != null -> Indent.Spaces(indentSpaces!!)
                else -> base.indent
            }
        return base.copy(
            indent = indent,
            lineWidth = lineWidth ?: base.lineWidth,
            trailingComma = trailingCommaFlag ?: base.trailingComma,
            endOfLine = eolFlag ?: base.endOfLine,
            insertFinalNewline = finalNewline ?: base.insertFinalNewline,
            bracketSpacing = bracketSpacing ?: base.bracketSpacing,
            parenSpacing = parenSpacing ?: base.parenSpacing,
        )
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
