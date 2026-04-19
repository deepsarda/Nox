package nox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.terminal.Terminal
import nox.BuildInfo
import nox.compiler.NoxCompiler
import nox.plugin.LibraryRegistry
import java.io.File
import kotlin.system.exitProcess

/**
 * `noxc` CLI compiler: compiles a Nox source file and outputs its `.noxc` disassembly.
 *
 * Replaces the old `NoxcApp`.
 */
class NoxcCli : CliktCommand(name = "noxc") {
    init {
        versionOption(BuildInfo.VERSION, names = setOf("--version"), message = { "noxc $it" })
    }

    private val file by argument(help = "Nox source file to compile").path(mustExist = true, canBeDir = false)
    private val output by option("-o", "--output", help = "Output path for .noxc file").path()
    private val plugins by option("--plugin", help = "Load a C plugin for type resolution").multiple()
    private val stdout by option("--stdout", help = "Print disassembly to stdout instead of file").flag()

    override fun run() {
        val terminal = Terminal()
        val registry = LibraryRegistry.createDefault()

        if (plugins.isNotEmpty()) {
            PluginLoader.loadAll(plugins, registry, terminal)
        }

        val source = file.toFile().readText()
        val result = NoxCompiler.compile(source, file.fileName.toString(), file.toAbsolutePath(), registry)

        if (result.errors.hasErrors()) {
            terminal.println("Compilation Failed")
            terminal.println(result.errors.formatAll())
            exitProcess(1)
        }

        val disassembly = result.disassembly ?: "Error: No disassembly generated."

        if (stdout) {
            terminal.println(disassembly)
        } else {
            val outPath =
                output?.toFile()
                    ?: File(file.toFile().parentFile, file.toFile().nameWithoutExtension + ".noxc")
            outPath.writeText(disassembly)
            terminal.println("Generated: ${outPath.absolutePath}")
        }
    }
}

fun main(args: Array<String>) = NoxcCli().main(args)
