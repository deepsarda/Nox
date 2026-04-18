package nox.lsp.features

import nox.compiler.ast.RawProgram
import nox.compiler.ast.typed.TypedFuncCallExpr
import nox.compiler.ast.typed.TypedFuncDef
import nox.compiler.ast.typed.TypedMainDef
import nox.compiler.ast.typed.TypedProgram
import nox.lsp.conversions.Positions
import nox.lsp.protocol.*

object CallHierarchyProvider {
    fun prepare(
        raw: RawProgram,
        uri: String,
        position: Position,
    ): List<CallHierarchyItem> {
        val compilerLine = position.line + 1
        val func =
            raw.functionsByName.values.find { it.loc.line == compilerLine }
                ?: return emptyList()

        val range = Positions.toLspRange(func.loc, length = func.name.length)
        return listOf(
            CallHierarchyItem(
                name = func.name,
                kind = SymbolKind.Function,
                uri = uri,
                range = range,
                selectionRange = range,
            ),
        )
    }

    fun incomingCalls(
        item: CallHierarchyItem,
        typed: TypedProgram?,
    ): List<CallHierarchyIncomingCall> {
        if (typed == null) return emptyList()
        val targetName = item.name
        val results = mutableListOf<CallHierarchyIncomingCall>()

        for (decl in typed.declarations) {
            val callerName: String =
                when (decl) {
                    is TypedFuncDef -> decl.name
                    is TypedMainDef -> "main"
                    else -> continue
                }

            val callSites = mutableListOf<Range>()
            TypedWalker.walkDecls(listOf(decl)) { expr ->
                if (expr is TypedFuncCallExpr && expr.name == targetName) {
                    callSites.add(Positions.toLspRange(expr.loc, length = expr.name.length))
                }
            }

            if (callSites.isNotEmpty()) {
                val callerRange = Positions.toLspRange(decl.loc, length = callerName.length)
                val callerItem =
                    CallHierarchyItem(
                        name = callerName,
                        kind = SymbolKind.Function,
                        uri = item.uri,
                        range = callerRange,
                        selectionRange = callerRange,
                    )
                results.add(CallHierarchyIncomingCall(callerItem, callSites))
            }
        }
        return results
    }

    fun outgoingCalls(
        item: CallHierarchyItem,
        typed: TypedProgram?,
    ): List<CallHierarchyOutgoingCall> {
        if (typed == null) return emptyList()

        val decl =
            typed.declarations.find {
                (it is TypedFuncDef && it.name == item.name) || (it is TypedMainDef && item.name == "main")
            } ?: return emptyList()

        val calls = mutableMapOf<String, MutableList<Range>>()
        TypedWalker.walkDecls(listOf(decl)) { expr ->
            if (expr is TypedFuncCallExpr) {
                calls
                    .getOrPut(expr.name) { mutableListOf() }
                    .add(Positions.toLspRange(expr.loc, length = expr.name.length))
            }
        }

        return calls.map { (name, ranges) ->
            val calleeFunc = typed.functionsByName[name]
            val calleeRange =
                if (calleeFunc != null) {
                    Positions.toLspRange(calleeFunc.loc, length = name.length)
                } else {
                    ranges.first()
                }
            val calleeItem =
                CallHierarchyItem(
                    name = name,
                    kind = SymbolKind.Function,
                    uri = item.uri,
                    range = calleeRange,
                    selectionRange = calleeRange,
                )
            CallHierarchyOutgoingCall(calleeItem, ranges)
        }
    }
}
