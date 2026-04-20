package nox.compiler

import java.io.File
import kotlin.system.exitProcess

/**
 * CLI tool to compile a .nox file and output its .noxc disassembly.
 *
 * Usage:
 *   java -cp ... nox.compiler.NoxcApp <input.nox>
 */
object NoxcApp {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: noxc <input.nox>")
            exitProcess(1)
        }

        val inputFile = File(args[0])
        if (!inputFile.exists()) {
            println("Error: File not found: ${inputFile.absolutePath}")
            exitProcess(1)
        }

        val source = inputFile.readText()
        val result = NoxCompiler.compile(source, inputFile.name, inputFile.toPath())

        if (result.errors.hasErrors()) {
            println("Compilation Failed")
            println(result.errors.formatAll())
            exitProcess(1)
        }

        val disassembly = result.disassembly ?: "Error: No disassembly generated."
        val outputFile = File(inputFile.parentFile, inputFile.nameWithoutExtension + ".noxc")

        outputFile.writeText(disassembly)
        println("Generated: ${outputFile.absolutePath}")
    }
}
