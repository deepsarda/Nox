package nox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import nox.cli.policy.PermissionPolicy
import nox.compiler.NoxCompiler
import nox.plugin.LibraryRegistry
import nox.runtime.NoxResult
import nox.vm.ExecutionConfig
import nox.vm.NoxVM
import java.time.Duration
import kotlin.system.exitProcess

/**
 * `nox` CLI runner: compiles and executes a Nox program with interactive
 * permission/resource prompts, plugin loading, and real-time yield output.
 */
class NoxCli : CliktCommand(name = "nox") {
    private val file by argument(help = "Nox source file to run").path(mustExist = true, canBeDir = false)

    private val disassemble by option("-d", "--disassemble", help = "Show disassembly instead of executing").flag()

    private val plugins by option("--plugin", help = "Load a C plugin (.dylib/.so/.dll)").multiple()

    private val allowFileRead by option("--allow-file-read", help = "Allow reads under path").path().multiple()
    private val allowFileWrite by option("--allow-file-write", help = "Allow writes under path").path().multiple()
    private val allowFileDelete by option("--allow-file-delete", help = "Allow deletes under path").path().multiple()
    private val allowFileList by option(
        "--allow-file-list",
        help = "Allow directory listing under path",
    ).path().multiple()
    private val allowFile by option("--allow-file", help = "Allow all file ops under path").path().multiple()
    private val allowFileExt by option(
        "--allow-file-ext",
        help = "Restrict file ops to extensions (e.g. .json,.csv)",
    ).multiple()
    private val fileMaxBytes by option("--file-max-bytes", help = "Max bytes per file read/write").long()
    private val fileReadOnly by option("--file-read-only", help = "Allow reads everywhere, deny all writes").flag()

    private val allowHttp by option("--allow-http", help = "Allow all HTTP to domain").multiple()
    private val allowHttpGet by option("--allow-http-get", help = "Allow only GET to domain").multiple()
    private val allowHttpPost by option("--allow-http-post", help = "Allow only POST to domain").multiple()
    private val allowHttpPut by option("--allow-http-put", help = "Allow only PUT to domain").multiple()
    private val allowHttpDelete by option("--allow-http-delete", help = "Allow only DELETE to domain").multiple()
    private val allowHttpPort by option("--allow-http-port", help = "Restrict HTTP to specific ports").int().multiple()
    private val httpsOnly by option("--https-only", help = "Deny plain HTTP, require HTTPS").flag()
    private val httpTimeout by option("--http-timeout", help = "Max HTTP response time in milliseconds").long()
    private val httpMaxResponse by option("--http-max-response", help = "Max HTTP response body size in bytes").long()

    private val allowEnv by option("--allow-env", help = "Allow reading specific env var").multiple()
    private val allowEnvAll by option("--allow-env-all", help = "Allow reading all env vars").flag()
    private val allowSysinfo by option("--allow-sysinfo", help = "Allow reading specific system property").multiple()
    private val allowSysinfoAll by option("--allow-sysinfo-all", help = "Allow reading all system properties").flag()

    private val allowPlugin by option(
        "--allow-plugin",
        help = "Allow plugin permission category:action",
    ).multiple()
    private val allowPluginCat by option("--allow-plugin-cat", help = "Allow all actions in plugin category").multiple()

    private val allowAll by option("--allow-all", help = "Allow everything (no prompts)").flag()
    private val noPrompt by option("--no-prompt", help = "Non-interactive: deny anything not pre-allowed").flag()

    private val maxInstructions by option(
        "--max-instructions",
        help = "Instruction limit (default: 500000, 0=unlimited)",
    ).long().default(500_000L)
    private val maxTime by option(
        "--max-time",
        help = "Execution time limit in seconds (default: 60, 0=unlimited)",
    ).long().default(60L)
    private val maxDepth by option(
        "--max-depth",
        help = "Call stack depth limit (default: 1024, 0=unlimited)",
    ).int().default(1024)
    private val autoExtend by option("--auto-extend", help = "Auto-extend all resource limits").flag()

    override fun run() {
        val terminal = Terminal()
        val registry = LibraryRegistry.createDefault()

        // Load C plugins
        if (plugins.isNotEmpty()) {
            PluginLoader.loadAll(plugins, registry, terminal)
        }

        val source = file.toFile().readText()

        // Disassembly mode
        if (disassemble) {
            val result = NoxCompiler.compile(source, file.fileName.toString(), file.toAbsolutePath(), registry)
            if (result.errors.hasErrors()) {
                terminal.println(result.errors.formatAll())
                exitProcess(1)
            }
            OutputFormatter.printDisassembly(terminal, result.disassembly ?: "No disassembly generated.")
            return
        }

        // Build policy from flags
        val policy = buildPolicy()

        // Build execution config
        val config =
            ExecutionConfig(
                maxInstructions = if (maxInstructions == 0L) Long.MAX_VALUE else maxInstructions,
                maxExecutionTime =
                    if (maxTime == 0L) {
                        Duration.ofDays(365)
                    } else {
                        Duration.ofSeconds(maxTime)
                    },
                maxCallDepth = if (maxDepth == 0) Int.MAX_VALUE else maxDepth,
            )

        // Execute with real-time yield output via onYield callback
        val yields = mutableListOf<String>()
        val ctx =
            RuntimeContextFactory.create(
                policy = policy,
                terminal = terminal,
                autoExtend = autoExtend,
                onYield = { data ->
                    yields.add(data)
                    OutputFormatter.printYield(terminal, data)
                },
            )

        val compiled = NoxCompiler.compile(source, file.fileName.toString(), file.toAbsolutePath(), registry)
        if (compiled.errors.hasErrors()) {
            terminal.println(compiled.errors.formatAll())
            exitProcess(1)
        }
        val program = compiled.compiledProgram
        if (program == null) {
            terminal.println("Error: No compiled output")
            exitProcess(1)
        }

        val vm = NoxVM(program, ctx, registry, config)
        val result =
            try {
                val ret = runBlocking { vm.execute() }
                NoxResult.Success(ret, yields)
            } catch (e: nox.vm.NoxException) {
                NoxResult.Error(e.type, e.message, yields)
            }

        OutputFormatter.printResult(terminal, result)
    }

    private fun buildPolicy(): PermissionPolicy {
        val parsedExtensions =
            allowFileExt
                .flatMap { it.split(",") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        val parsedPluginActions =
            allowPlugin.mapNotNull { spec ->
                val parts = spec.split(":", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }

        return PermissionPolicy(
            allowAll = allowAll,
            noPrompt = noPrompt,
            fileReadPaths = allowFileRead.map { it.toAbsolutePath().normalize() },
            fileWritePaths = allowFileWrite.map { it.toAbsolutePath().normalize() },
            fileDeletePaths = allowFileDelete.map { it.toAbsolutePath().normalize() },
            fileListPaths = allowFileList.map { it.toAbsolutePath().normalize() },
            fileAllPaths = allowFile.map { it.toAbsolutePath().normalize() },
            fileAllowedExtensions = parsedExtensions.ifEmpty { null },
            fileMaxBytes = fileMaxBytes,
            fileReadOnly = fileReadOnly,
            httpAllDomains = allowHttp,
            httpGetDomains = allowHttpGet,
            httpPostDomains = allowHttpPost,
            httpPutDomains = allowHttpPut,
            httpDeleteDomains = allowHttpDelete,
            httpAllowedPorts = allowHttpPort.ifEmpty { null },
            httpsOnly = httpsOnly,
            httpTimeoutMs = httpTimeout,
            httpMaxResponse = httpMaxResponse,
            envAllowedVars = allowEnv,
            envAllowAll = allowEnvAll,
            sysinfoAllowed = allowSysinfo,
            sysinfoAllowAll = allowSysinfoAll,
            pluginAllowed = parsedPluginActions,
            pluginCatsAllowed = allowPluginCat,
        )
    }
}

fun main(args: Array<String>) = NoxCli().main(args)
