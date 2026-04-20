package nox.cli.prompt

import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.terminal.Terminal
import nox.cli.policy.PermissionPolicy
import nox.cli.policy.SessionPolicyCache
import nox.runtime.PermissionRequest
import nox.runtime.PermissionResponse
import nox.runtime.PluginValue
import java.nio.file.Path

/**
 * Interactive TUI permission handler.
 *
 * Only called when [PermissionPolicy] and [SessionPolicyCache] have no match.
 * Records decisions in the cache for the remainder of execution.
 */
class PermissionPrompt(
    private val terminal: Terminal,
    private val cache: SessionPolicyCache,
) {
    fun prompt(request: PermissionRequest): PermissionResponse =
        when (request) {
            is PermissionRequest.File -> promptFile(request)
            is PermissionRequest.Http -> promptHttp(request)
            is PermissionRequest.Env -> promptEnv(request)
            is PermissionRequest.Plugin -> promptPlugin(request)
        }

    private fun promptFile(request: PermissionRequest.File): PermissionResponse {
        val path =
            when (request) {
                is PermissionRequest.File.Read -> request.path
                is PermissionRequest.File.Write -> request.path
                is PermissionRequest.File.Append -> request.path
                is PermissionRequest.File.Delete -> request.path
                is PermissionRequest.File.List -> request.directory
                is PermissionRequest.File.Metadata -> request.path
                is PermissionRequest.File.CreateDirectory -> request.path
            }
        val opName =
            when (request) {
                is PermissionRequest.File.Read -> "Read"
                is PermissionRequest.File.Write -> "Write"
                is PermissionRequest.File.Append -> "Append"
                is PermissionRequest.File.Delete -> "Delete"
                is PermissionRequest.File.List -> "List"
                is PermissionRequest.File.Metadata -> "Metadata"
                is PermissionRequest.File.CreateDirectory -> "Create Directory"
            }
        val isRead =
            request is PermissionRequest.File.Read ||
                request is PermissionRequest.File.List ||
                request is PermissionRequest.File.Metadata
        val isDelete = request is PermissionRequest.File.Delete
        val parentDir = Path.of(path).parent?.toString() ?: "/"
        val ext =
            Path
                .of(path)
                .fileName
                ?.toString()
                ?.substringAfterLast('.', "")

        terminal.println()
        terminal.println(cyan("File $opName Permission"))
        terminal.println("  Path: ${yellow(path)}")
        terminal.println()

        val options = mutableListOf<Pair<String, () -> PermissionResponse>>()
        options.add(
            "Allow this file" to {
                PermissionResponse.Granted.FileGrant()
            },
        )
        options.add(
            "Allow this directory ($parentDir/)" to {
                cache.allowFileDir(Path.of(parentDir), read = isRead, write = !isRead && !isDelete, delete = isDelete)
                PermissionResponse.Granted.FileGrant()
            },
        )
        options.add(
            "Allow all file ${if (isRead) {
                "reads"
            } else if (isDelete) {
                "deletes"
            } else {
                "writes"
            }} (this session)" to {
                if (isRead) {
                    cache.allowAllFileReads()
                } else if (isDelete) {
                    cache.allowAllFileDeletes()
                } else {
                    cache.allowAllFileWrites()
                }
                PermissionResponse.Granted.FileGrant()
            },
        )
        if (ext != null && ext.isNotEmpty()) {
            options.add(
                "Allow .$ext files only (this session)" to {
                    cache.allowFileExtension(ext)
                    PermissionResponse.Granted.FileGrant()
                },
            )
        }
        options.add(
            "Deny" to {
                PermissionResponse.Denied("Denied by user")
            },
        )
        options.add(
            "Deny all file ${if (isRead) "reads" else "writes"} (this session)" to {
                if (isRead) cache.denyAllFileReads() else cache.denyAllFileWrites()
                PermissionResponse.Denied("Denied by user")
            },
        )

        return showOptions(options)
    }

    private fun promptHttp(request: PermissionRequest.Http): PermissionResponse {
        val url =
            when (request) {
                is PermissionRequest.Http.Get -> request.url
                is PermissionRequest.Http.Post -> request.url
                is PermissionRequest.Http.Put -> request.url
                is PermissionRequest.Http.Delete -> request.url
            }
        val method =
            when (request) {
                is PermissionRequest.Http.Get -> "GET"
                is PermissionRequest.Http.Post -> "POST"
                is PermissionRequest.Http.Put -> "PUT"
                is PermissionRequest.Http.Delete -> "DELETE"
            }
        val domain = PermissionPolicy.extractDomain(url) ?: "unknown"
        val isHttps = url.startsWith("https://")

        terminal.println()
        terminal.println(cyan("HTTP $method Permission"))
        terminal.println("  URL:    ${yellow(url)}")
        terminal.println("  Domain: $domain  HTTPS: ${if (isHttps) green("yes") else red("no")}")
        terminal.println()

        val options = mutableListOf<Pair<String, () -> PermissionResponse>>()
        options.add(
            "Allow this exact URL" to {
                PermissionResponse.Granted.HttpGrant()
            },
        )
        options.add(
            "Allow all requests to $domain" to {
                cache.allowHttpDomain(domain)
                PermissionResponse.Granted.HttpGrant()
            },
        )
        options.add(
            "Allow $method only to $domain" to {
                if (request is PermissionRequest.Http.Get) {
                    cache.allowHttpGetDomain(domain)
                } else {
                    cache.allowHttpDomain(domain)
                }
                PermissionResponse.Granted.HttpGrant()
            },
        )
        options.add(
            "Allow all HTTPS requests (this session)" to {
                cache.allowAllHttps()
                PermissionResponse.Granted.HttpGrant()
            },
        )
        options.add(
            "Allow all HTTP (this session)" to {
                cache.allowAllHttp()
                PermissionResponse.Granted.HttpGrant()
            },
        )
        options.add(
            "Deny" to {
                PermissionResponse.Denied("Denied by user")
            },
        )
        options.add(
            "Deny all HTTP (this session)" to {
                cache.denyAllHttp()
                PermissionResponse.Denied("Denied by user")
            },
        )

        return showOptions(options)
    }

    private fun promptEnv(request: PermissionRequest.Env): PermissionResponse {
        when (request) {
            is PermissionRequest.Env.ReadVar -> {
                terminal.println()
                terminal.println(cyan("Environment Variable Permission"))
                terminal.println("  Variable: ${yellow(request.name)}")
                terminal.println()

                val options = mutableListOf<Pair<String, () -> PermissionResponse>>()
                options.add(
                    "Allow reading ${request.name}" to {
                        cache.allowEnvVar(request.name)
                        PermissionResponse.Granted.EnvGrant()
                    },
                )
                options.add(
                    "Allow reading all env vars (this session)" to {
                        cache.allowAllEnv()
                        PermissionResponse.Granted.EnvGrant()
                    },
                )
                options.add(
                    "Deny" to {
                        PermissionResponse.Denied("Denied by user")
                    },
                )
                options.add(
                    "Deny all env access (this session)" to {
                        cache.denyAllEnv()
                        PermissionResponse.Denied("Denied by user")
                    },
                )
                return showOptions(options)
            }
            is PermissionRequest.Env.SystemInfo -> {
                terminal.println()
                terminal.println(cyan("System Info Permission"))
                terminal.println("  Property: ${yellow(request.property)}")
                terminal.println()

                val options = mutableListOf<Pair<String, () -> PermissionResponse>>()
                options.add(
                    "Allow reading ${request.property}" to {
                        cache.allowSysinfo(request.property)
                        PermissionResponse.Granted.EnvGrant()
                    },
                )
                options.add(
                    "Allow all system info (this session)" to {
                        cache.allowAllSysinfo()
                        PermissionResponse.Granted.EnvGrant()
                    },
                )
                options.add(
                    "Deny" to {
                        PermissionResponse.Denied("Denied by user")
                    },
                )
                options.add(
                    "Deny all system info (this session)" to {
                        cache.denyAllSysinfo()
                        PermissionResponse.Denied("Denied by user")
                    },
                )
                return showOptions(options)
            }
        }
    }

    private fun promptPlugin(request: PermissionRequest.Plugin): PermissionResponse {
        terminal.println()
        terminal.println(cyan("Plugin Permission"))
        terminal.println("  Category: ${yellow(request.category)}    Action: ${yellow(request.action)}")
        if (request.details.isNotEmpty()) {
            terminal.println("  Details:")
            for ((key, value) in request.details) {
                val display =
                    when (value) {
                        is PluginValue.Text -> "\"${value.value}\""
                        is PluginValue.Number -> value.value.toString()
                        is PluginValue.Flag -> value.value.toString()
                    }
                terminal.println("    $key = $display")
            }
        }
        terminal.println()

        val options = mutableListOf<Pair<String, () -> PermissionResponse>>()
        options.add(
            "Allow this action" to {
                cache.allowPluginAction(request.category, request.action)
                PermissionResponse.Granted.PluginGrant()
            },
        )
        options.add(
            "Allow all \"${request.category}\" actions (this session)" to {
                cache.allowPluginCat(request.category)
                PermissionResponse.Granted.PluginGrant()
            },
        )
        options.add(
            "Allow all plugin actions (this session)" to {
                cache.allowAllPlugins()
                PermissionResponse.Granted.PluginGrant()
            },
        )
        options.add(
            "Deny" to {
                PermissionResponse.Denied("Denied by user")
            },
        )
        options.add(
            "Deny all plugin actions (this session)" to {
                cache.denyAllPlugins()
                PermissionResponse.Denied("Denied by user")
            },
        )

        return showOptions(options)
    }

    private fun showOptions(options: List<Pair<String, () -> PermissionResponse>>): PermissionResponse {
        for ((i, opt) in options.withIndex()) {
            terminal.println("  ${blue("[${i + 1}]")} ${opt.first}")
        }
        terminal.println()

        while (true) {
            terminal.print("  Choice: ")
            val input = readlnOrNull()?.trim() ?: return PermissionResponse.Denied("EOF")
            val choice = input.toIntOrNull()
            if (choice != null && choice in 1..options.size) {
                return options[choice - 1].second()
            }
            terminal.println(red("  Invalid choice. Enter 1-${options.size}."))
        }
    }
}
