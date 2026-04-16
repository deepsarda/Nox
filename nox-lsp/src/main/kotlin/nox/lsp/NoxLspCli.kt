package nox.lsp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import nox.lsp.protocol.*
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket

/**
 * `nox-lsp` CLI. Speaks LSP over stdio by default; `--socket <port>` is available for
 * editors that prefer a socket connection (IntelliJ tends to). Printing on stdout would
 * corrupt the JSON-RPC stream, so every log goes to stderr.
 */
class NoxLspCli : CliktCommand(name = "nox-lsp") {
    private val socket by option("--socket", help = "Listen for LSP on this TCP port instead of stdio").int()
    private val version by option("--version", help = "Print version and exit").flag()
    private val stdio by option("--stdio", help = "Use stdio for LSP (default)").flag()

    override fun run() {
        if (version) {
            println("nox-lsp ${BuildInfo.VERSION}")
            return
        }
        if (socket != null) {
            runSocket(socket!!)
        } else {
            runStdio(System.`in`, System.out)
        }
    }

    private fun runStdio(
        input: InputStream,
        output: OutputStream,
    ) {
        val server = NoxLanguageServer()
        val rpc = JsonRpcServer(input, output, server)
        server.client = rpc
        rpc.listen()
    }

    private fun runSocket(port: Int) {
        ServerSocket(port).use { server ->
            System.err.println("nox-lsp listening on port $port")
            val socket = server.accept()
            runStdio(socket.getInputStream(), socket.getOutputStream())
        }
    }
}

fun main(args: Array<String>) = NoxLspCli().main(args)
