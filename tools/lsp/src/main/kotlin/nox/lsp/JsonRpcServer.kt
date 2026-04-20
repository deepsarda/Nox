package nox.lsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import nox.lsp.protocol.NotificationMessage
import nox.lsp.protocol.RequestMessage
import nox.lsp.protocol.ResponseMessage
import java.io.InputStream
import java.io.OutputStream

class JsonRpcServer(
    private val input: InputStream,
    private val output: OutputStream,
    private val languageServer: NoxLanguageServer,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    init {
        System.err.println("Starting Nox LSP Server")
    }

    private fun log(
        dir: String,
        msg: String,
    ) {
        System.err.println("[$dir] $msg")
    }

    fun listen() {
        try {
            while (true) {
                val contentLength = readHeader() ?: break
                val bodyBytes = ByteArray(contentLength)
                var bytesRead = 0
                while (bytesRead < contentLength) {
                    val read = input.read(bodyBytes, bytesRead, contentLength - bytesRead)
                    if (read == -1) return
                    bytesRead += read
                }

                val bodyString = String(bodyBytes, Charsets.UTF_8)
                log("RECV", bodyString)

                try {
                    val element = json.parseToJsonElement(bodyString)
                    if (element is JsonObject) {
                        if (element.containsKey("id")) {
                            handleRequest(element)
                        } else {
                            handleNotification(element)
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("Error processing message: $bodyString")
                    e.printStackTrace(System.err)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace(System.err)
        }
    }

    private fun readHeader(): Int? {
        var contentLength = -1
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) {
                if (contentLength == -1) return null
                return contentLength
            }
            if (line.startsWith("Content-Length: ")) {
                contentLength = line.substringAfter("Content-Length: ").trim().toInt()
            }
        }
    }

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) {
                if (sb.isEmpty()) return null
                return sb.toString()
            }
            if (c == '\r'.code) {
                val next = input.read()
                if (next == '\n'.code) {
                    return sb.toString()
                }
                sb.append('\r')
                if (next != -1) sb.append(next.toChar())
            } else if (c == '\n'.code) {
                return sb.toString()
            } else {
                sb.append(c.toChar())
            }
        }
    }

    private fun handleRequest(element: JsonObject) {
        try {
            val req = json.decodeFromJsonElement<RequestMessage>(element)
            val result = languageServer.handleRequest(req.method, req.params)
            val res = ResponseMessage(id = req.id, result = result)
            send(json.encodeToString(res))
        } catch (e: Exception) {
            e.printStackTrace(System.err)
            // Could send error response here if id is known
        }
    }

    private fun handleNotification(element: JsonObject) {
        try {
            val notif = json.decodeFromJsonElement<NotificationMessage>(element)
            languageServer.handleNotification(notif.method, notif.params)
        } catch (e: Exception) {
            e.printStackTrace(System.err)
        }
    }

    fun notify(
        method: String,
        params: JsonElement,
    ) {
        val notif = NotificationMessage(method = method, params = params)
        send(json.encodeToString(notif))
    }

    @Synchronized
    private fun send(msg: String) {
        log("SEND", msg)
        val bytes = msg.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }
}
