package nox.cli.prompt

import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.terminal.Terminal
import nox.runtime.ResourceRequest
import nox.runtime.ResourceResponse

/**
 * Interactive TUI resource-limit handler.
 */
class ResourcePrompt(
    private val terminal: Terminal,
) {
    fun prompt(request: ResourceRequest): ResourceResponse =
        when (request) {
            is ResourceRequest.InstructionQuota -> promptInstructions(request)
            is ResourceRequest.ExecutionTimeout -> promptTimeout(request)
            is ResourceRequest.CallDepth -> promptCallDepth(request)
        }

    private fun promptInstructions(request: ResourceRequest.InstructionQuota): ResourceResponse {
        terminal.println()
        terminal.println(cyan("Resource Limit"))
        terminal.println(
            "  Instruction limit reached: " +
                "${yellow("%,d".format(request.used))} / ${yellow("%,d".format(request.currentLimit))}",
        )
        terminal.println()

        val options =
            listOf(
                "Add 500,000 more" to (request.currentLimit + 500_000L),
                "Add 1,000,000 more" to (request.currentLimit + 1_000_000L),
                "Add 5,000,000 more" to (request.currentLimit + 5_000_000L),
                "Run forever (remove limit)" to Long.MAX_VALUE,
            )

        for ((i, opt) in options.withIndex()) {
            terminal.println("  ${blue("[${i + 1}]")} ${opt.first}")
        }
        terminal.println("  ${blue("[5]")} Custom amount")
        terminal.println("  ${blue("[6]")} Stop execution")
        terminal.println()

        while (true) {
            terminal.print("  Choice: ")
            val input = readlnOrNull()?.trim() ?: return ResourceResponse.Denied("EOF")
            when (input.toIntOrNull()) {
                in 1..4 -> return ResourceResponse.Granted(options[input.toInt() - 1].second)
                5 -> {
                    terminal.print("  Enter amount: ")
                    val amount = readlnOrNull()?.trim()?.toLongOrNull()
                    if (amount != null && amount > 0) {
                        return ResourceResponse.Granted(request.currentLimit + amount)
                    }
                    terminal.println(red("  Invalid amount."))
                }
                6 -> return ResourceResponse.Denied("Stopped by user")
                else -> terminal.println(red("  Invalid choice. Enter 1-6."))
            }
        }
    }

    private fun promptTimeout(request: ResourceRequest.ExecutionTimeout): ResourceResponse {
        terminal.println()
        terminal.println(cyan("Resource Limit"))
        terminal.println(
            "  Execution time limit reached: " +
                "${yellow("%.1fs".format(request.elapsedMs / 1000.0))} / " +
                "${yellow("%.1fs".format(request.currentLimitMs / 1000.0))}",
        )
        terminal.println()

        val options =
            listOf(
                "Add 5 seconds" to (request.currentLimitMs + 5_000L),
                "Add 10 seconds" to (request.currentLimitMs + 10_000L),
                "Add 30 seconds" to (request.currentLimitMs + 30_000L),
                "Add 5 minutes" to (request.currentLimitMs + 300_000L),
                "Run forever (remove limit)" to Long.MAX_VALUE,
            )

        for ((i, opt) in options.withIndex()) {
            terminal.println("  ${blue("[${i + 1}]")} ${opt.first}")
        }
        terminal.println("  ${blue("[6]")} Custom duration (seconds)")
        terminal.println("  ${blue("[7]")} Stop execution")
        terminal.println()

        while (true) {
            terminal.print("  Choice: ")
            val input = readlnOrNull()?.trim() ?: return ResourceResponse.Denied("EOF")
            when (input.toIntOrNull()) {
                in 1..5 -> return ResourceResponse.Granted(options[input.toInt() - 1].second)
                6 -> {
                    terminal.print("  Enter seconds: ")
                    val secs = readlnOrNull()?.trim()?.toLongOrNull()
                    if (secs != null && secs > 0) {
                        return ResourceResponse.Granted(request.currentLimitMs + secs * 1000)
                    }
                    terminal.println(red("  Invalid duration."))
                }
                7 -> return ResourceResponse.Denied("Stopped by user")
                else -> terminal.println(red("  Invalid choice. Enter 1-7."))
            }
        }
    }

    private fun promptCallDepth(request: ResourceRequest.CallDepth): ResourceResponse {
        terminal.println()
        terminal.println(cyan("Resource Limit"))
        terminal.println(
            "  Call stack depth limit reached: " +
                "${yellow("%,d".format(request.current))} / ${yellow("%,d".format(request.currentLimit))}",
        )
        terminal.println()

        val options =
            listOf(
                "Add 512 more" to (request.currentLimit + 512L),
                "Add 1,024 more" to (request.currentLimit + 1_024L),
                "Add 4,096 more" to (request.currentLimit + 4_096L),
                "Remove limit" to Long.MAX_VALUE,
            )

        for ((i, opt) in options.withIndex()) {
            terminal.println("  ${blue("[${i + 1}]")} ${opt.first}")
        }
        terminal.println("  ${blue("[5]")} Custom depth")
        terminal.println("  ${blue("[6]")} Stop execution")
        terminal.println()

        while (true) {
            terminal.print("  Choice: ")
            val input = readlnOrNull()?.trim() ?: return ResourceResponse.Denied("EOF")
            when (input.toIntOrNull()) {
                in 1..4 -> return ResourceResponse.Granted(options[input.toInt() - 1].second)
                5 -> {
                    terminal.print("  Enter depth: ")
                    val depth = readlnOrNull()?.trim()?.toLongOrNull()
                    if (depth != null && depth > 0) {
                        return ResourceResponse.Granted(request.currentLimit + depth)
                    }
                    terminal.println(red("  Invalid depth."))
                }
                6 -> return ResourceResponse.Denied("Stopped by user")
                else -> terminal.println(red("  Invalid choice. Enter 1-6."))
            }
        }
    }
}
