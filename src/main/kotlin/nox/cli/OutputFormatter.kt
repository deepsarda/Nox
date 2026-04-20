package nox.cli

import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.terminal.Terminal
import nox.runtime.NoxResult

/**
 * Formats program output for the CLI.
 */
object OutputFormatter {
    fun printYield(
        terminal: Terminal,
        data: String,
    ) {
        // TEST
        terminal.println(data)
    }

    fun printResult(
        terminal: Terminal,
        result: NoxResult,
    ) {
        when (result) {
            is NoxResult.Success -> {
                if (result.returnValue != null) {
                    terminal.println(result.returnValue)
                }
            }
            is NoxResult.Error -> {
                terminal.println(red("Error [${result.type}]: ${result.message}"))
            }
        }
    }

    fun printDisassembly(
        terminal: Terminal,
        disassembly: String,
    ) {
        terminal.println(disassembly)
    }
}
