package nox.compiler.codegen

import nox.compiler.ast.CatchClause
import nox.compiler.ast.ForEachStmt
import nox.compiler.ast.Param
import nox.compiler.ast.VarDeclStmt
import nox.compiler.types.ParamSymbol
import nox.compiler.types.Symbol
import nox.compiler.types.TypeRef
import nox.compiler.types.VarSymbol

/**
 * Dual-bank register allocator for a single function or init block.
 *
 * The Nox VM has two separate register banks:
 * - **pMem** (primitives): int, double, boolean
 * - **rMem** (references): string, json, struct, array
 *
 * Parameters are pre-assigned in declaration order as registers 0, 1, 2, …
 * in whichever bank matches their type. All locals start after the parameters.
 *
 * See docs/compiler/codegen.md.
 */
class RegisterAllocator(
    params: List<Param>,
) {
    // Bank pools

    private var nextPrim = 0
    private var nextRef = 0

    /** Free pMem slots available for reuse. */
    private val freePrim = ArrayDeque<Int>()

    /** Free rMem slots available for reuse. */
    private val freeRef = ArrayDeque<Int>()

    /** Peak register counts (frame sizes). */
    var maxPrim = 0
        private set
    var maxRef = 0
        private set

    /** All rMem registers that were ever allocated (for KILL_REF). */
    private val _allRefRegs = mutableSetOf<Int>()

    /** Immutable view of all rMem registers ever allocated. */
    val allRefRegs: Set<Int> get() = _allRefRegs

    private val paramPrimReg = mutableMapOf<String, Int>() // paramName to pMem reg
    private val paramRefReg = mutableMapOf<String, Int>() // paramName to rMem reg

    init {
        // Pre-assign parameter registers in declaration order.
        for (param in params) {
            if (param.type.isPrimitive()) {
                paramPrimReg[param.name] = newPrimReg()
            } else {
                paramRefReg[param.name] = newRefReg()
            }
        }
    }

    /** Register number for a primitive parameter by name. */
    fun primParamRegister(name: String): Int? = paramPrimReg[name]

    /** Register number for a reference parameter by name. */
    fun refParamRegister(name: String): Int? = paramRefReg[name]

    /** Whether [param] lives in rMem. */
    fun paramIsRef(param: Param): Boolean = !param.type.isPrimitive()

    /**
     * Allocate and assign a register for a local variable declaration.
     * Sets both [VarDeclStmt.register] and [VarSymbol.register] (via resolvedSymbol back-link).
     */
    fun allocVar(stmt: VarDeclStmt): Int {
        val reg = if (stmt.type.isPrimitive()) allocPrim() else allocRef()
        stmt.register = reg
        (stmt.resolvedSymbol as? VarSymbol)?.register = reg
        return reg
    }

    /**
     * Allocate a register for the exception variable in a [CatchClause].
     * Sets both [CatchClause.register] and [VarSymbol.register].
     */
    fun allocCatchVar(clause: CatchClause): Int {
        val reg = allocRef() // Exception is always string (or object later)
        clause.register = reg
        (clause.resolvedSymbol as? VarSymbol)?.register = reg
        return reg
    }

    /**
     * Write pre-allocated param registers into [ParamSymbol.register] for each param.
     * Call this after constructing the allocator, before emitting the function body.
     */
    fun setParamSymbols(params: List<Param>) {
        for (param in params) {
            val sym = param.resolvedSymbol as? ParamSymbol ?: continue
            val reg = primParamRegister(param.name) ?: refParamRegister(param.name) ?: continue
            sym.register = reg
        }
    }

    /**
     * Allocate a register for the loop variable in a [ForEachStmt].
     * Sets [ForEachStmt.elementRegister] in-place and returns the register number.
     */
    fun allocElement(stmt: ForEachStmt): Int {
        val reg = if (stmt.elementType.isPrimitive()) allocPrim() else allocRef()
        stmt.elementRegister = reg
        return reg
    }

    /** Allocate a temporary primitive register. Pair with [freeTempPrim]. */
    fun allocTempPrim(): Int = allocPrim()

    /** Allocate a temporary reference register. Pair with [freeTempRef]. */
    fun allocTempRef(): Int = allocRef()

    /** Return a temporary primitive register to the pool. */
    fun freeTempPrim(reg: Int) {
        freePrim.addFirst(reg)
    }

    /** Return a temporary reference register to the pool. */
    fun freeTempRef(reg: Int) {
        freeRef.addFirst(reg)
    }

    /** Free a named symbol (local variable or parameter) so its register can be reused. */
    fun freeVar(sym: Symbol) {
        val (reg, type) =
            when (sym) {
                is VarSymbol -> sym.register to sym.type
                is ParamSymbol -> sym.register to sym.type
                else -> return
            }
        if (reg == -1) return

        if (type.isPrimitive()) {
            freePrim.addFirst(reg)
        } else {
            freeRef.addFirst(reg)
        }

        // Prevent double-freeing
        when (sym) {
            is VarSymbol -> sym.register = -1
            is ParamSymbol -> sym.register = -1
            else -> {}
        }
    }

    /** Allocate a temp register for [type] (dispatches to pMem or rMem). */
    fun allocTemp(type: TypeRef): Int = if (type.isPrimitive()) allocTempPrim() else allocTempRef()

    /** Free a temp register for [type]. */
    fun freeTemp(
        type: TypeRef,
        reg: Int,
    ) {
        if (type.isPrimitive()) freeTempPrim(reg) else freeTempRef(reg)
    }

    private fun allocPrim(): Int =
        if (freePrim.isNotEmpty()) {
            freePrim.removeFirst()
        } else {
            newPrimReg()
        }

    private fun allocRef(): Int =
        if (freeRef.isNotEmpty()) {
            freeRef.removeFirst()
        } else {
            newRefReg()
        }

    private fun newPrimReg(): Int {
        val r = nextPrim++
        if (nextPrim > maxPrim) maxPrim = nextPrim
        return r
    }

    private fun newRefReg(): Int {
        val r = nextRef++
        if (nextRef > maxRef) maxRef = nextRef
        _allRefRegs.add(r)
        return r
    }
}
