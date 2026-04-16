package nox.lsp.features

import nox.compiler.ast.RawProgram
import nox.compiler.ast.typed.TypedFuncCallExpr
import nox.compiler.ast.typed.TypedFuncDef
import nox.compiler.ast.typed.TypedProgram
import nox.lsp.conversions.Positions
import nox.lsp.protocol.*

object CallHierarchyProvider {
    fun prepare(
        raw: RawProgram,
        uri: String,
        position: Position,
    ): List<CallHierarchyItem> {
        // We need to find the function definition at the position
        val func = raw.functionsByName.values.find { 
            val loc = it.loc
            loc.line == position.line + 1 && position.character >= loc.column && position.character <= loc.column + it.name.length
        } ?: return emptyList()

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
    ): List<CallHierarchyIncomingCall> {
        val results = mutableListOf<CallHierarchyIncomingCall>()
        return results
    }

    fun outgoingCalls(
        item: CallHierarchyItem,
        typed: TypedProgram?,
    ): List<CallHierarchyOutgoingCall> {
        if (typed == null) return emptyList()
        val func = typed.functionsByName[item.name] ?: return emptyList()
        val calls = mutableMapOf<String, MutableList<Range>>()

        TypedWalker.walkProgram(typed, onExpr = { expr ->
            // Only consider calls inside this function's body
            if (expr is TypedFuncCallExpr) {
                val range = Positions.toLspRange(expr.loc, length = expr.name.length)
                val funcRange = Positions.toLspRange(func.loc)
                // Loose check for "inside"
                if (range.start.line >= funcRange.start.line && range.end.line <= Positions.toLspPosition(func.body.loc).line + 100) {
                     calls.getOrPut(expr.name) { mutableListOf() }.add(range)
                }
            }
        })

        return calls.map { (name, ranges) ->
            val calleeItem = CallHierarchyItem(
                name = name,
                kind = SymbolKind.Function,
                uri = item.uri, // We don't resolve the exact URI of the callee here yet
                range = ranges.first(),
                selectionRange = ranges.first()
            )
            CallHierarchyOutgoingCall(calleeItem, ranges)
        }
    }
}
