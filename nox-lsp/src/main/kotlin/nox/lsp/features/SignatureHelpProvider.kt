package nox.lsp.features

import nox.compiler.ast.*
import nox.compiler.types.CallTarget
import nox.compiler.types.NoxParam
import nox.plugin.LibraryRegistry
import nox.lsp.protocol.*

/**
 * Signature help during argument typing. Given the open paren position, we scan the source
 * backward to find the callee identifier (possibly namespace-qualified), resolve it in
 * scope, and render its parameter list with the active parameter highlighted by comma count.
 *
 * This is intentionally textual rather than AST-based: when the user is mid-typing, the
 * source doesn't parse cleanly, so there's no [TypedProgram] for the incomplete call.
 */
object SignatureHelpProvider {
    fun help(
        raw: RawProgram,
        source: String,
        lspLine: Int,
        lspColumn: Int,
        registry: LibraryRegistry = LibraryRegistry.createDefault(),
    ): SignatureHelp? {
        val lines = source.split('\n')
        val line = lines.getOrNull(lspLine) ?: return null
        val prefix = line.substring(0, lspColumn.coerceAtMost(line.length))

        val openParen = findUnmatchedOpenParen(prefix) ?: return null
        val callPrefix = prefix.substring(0, openParen)
        val callName = callPrefix.takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '.' }
        if (callName.isEmpty()) return null

        val target = resolve(callName, raw, registry) ?: return null
        val activeParam = commaDepthAt(prefix, openParen)

        val sig = SignatureInformation(renderSignature(callName.substringAfterLast('.'), target), parameters = target.params.map { p -> ParameterInformation(renderParam(p)) })

        return SignatureHelp(listOf(sig), 0, activeParam)
    }

    private fun resolve(
        callName: String,
        raw: RawProgram,
        registry: LibraryRegistry,
    ): CallTarget? {
        if ('.' in callName) {
            val (ns, fn) = callName.substringBeforeLast('.') to callName.substringAfterLast('.')
            return registry.lookupNamespaceFunc(ns, fn)
        }
        val func = raw.functionsByName[callName] ?: return null
        return CallTarget(
            name = func.name,
            params =
                func.params.filterIsInstance<RawParamImpl>().map {
                    NoxParam(it.name, it.type)
                },
            returnType = func.returnType,
        )
    }

    private fun findUnmatchedOpenParen(prefix: String): Int? {
        var depth = 0
        var i = prefix.length - 1
        while (i >= 0) {
            when (prefix[i]) {
                ')' -> depth++
                '(' -> {
                    if (depth == 0) return i
                    depth--
                }
            }
            i--
        }
        return null
    }

    private fun commaDepthAt(
        prefix: String,
        openParen: Int,
    ): Int {
        var commas = 0
        var depth = 0
        for (i in (openParen + 1) until prefix.length) {
            when (prefix[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) commas++
            }
        }
        return commas
    }

    private fun renderSignature(
        name: String,
        target: CallTarget,
    ): String {
        val params = target.params.joinToString(", ") { renderParam(it) }
        return "${target.returnType} $name($params)"
    }

    private fun renderParam(p: NoxParam): String {
        val base = "${p.type} ${p.name}"
        return if (p.defaultLiteral != null) "$base = ${p.defaultLiteral}" else base
    }
}
