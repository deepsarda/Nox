package nox.compiler.codegen

import nox.compiler.ast.typed.*
import nox.compiler.types.*

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
    params: List<TypedParam>,
    returnType: TypeRef = TypeRef.VOID,
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
        // Offset parameter registers if there's a return value (result slot at 0)
        if (returnType.isPrimitive()) {
            nextPrim = 1
        } else if (returnType != TypeRef.VOID) {
            nextRef = 1
        }

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
    fun paramIsRef(param: TypedParam): Boolean = !param.type.isPrimitive()

    /**
     * Allocate and assign a register for a local variable declaration.
     * Sets both [TypedVarDeclStmt.register] and [VarSymbol.register] (via resolvedSymbol back-link).
     */
    fun allocVar(stmt: TypedVarDeclStmt): Int {
        val reg = if (stmt.type.isPrimitive()) allocPrim() else allocRef()
        stmt.register = reg
        (stmt.resolvedSymbol as? VarSymbol)?.register = reg
        return reg
    }

    /**
     * Allocate a register for the exception variable in a [TypedCatchClause].
     * Sets both [TypedCatchClause.register] and [VarSymbol.register].
     */
    fun allocCatchVar(clause: TypedCatchClause): Int {
        val reg = allocRef() // Exception is always string (or object later)
        clause.register = reg
        (clause.resolvedSymbol as? VarSymbol)?.register = reg
        return reg
    }

    /**
     * Write pre-allocated param registers into [ParamSymbol.register] for each param.
     * Call this after constructing the allocator, before emitting the function body.
     */
    fun setParamSymbols(params: List<TypedParam>) {
        for (param in params) {
            val sym = param.resolvedSymbol as? ParamSymbol ?: continue
            val reg = primParamRegister(param.name) ?: refParamRegister(param.name) ?: continue
            sym.register = reg
        }
    }

    /**
     * Allocate a register for the loop variable in a [TypedForEachStmt].
     * Sets [TypedForEachStmt.elementRegister] in-place and returns the register number.
     */
    fun allocElement(stmt: TypedForEachStmt): Int {
        val reg = if (stmt.elementType.isPrimitive()) allocPrim() else allocRef()
        stmt.elementRegister = reg
        (stmt.resolvedSymbol as? VarSymbol)?.register = reg
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
        }
    }

    /** Allocate a temp register for [type] (dispatches to pMem or rMem). */
    fun allocTemp(type: TypeRef): Int = if (type.isPrimitive()) allocTempPrim() else allocTempRef()

    /**
     * Allocate a contiguous block of registers in pMem for function arguments.
     */
    fun allocArgBlockPrim(size: Int): Int {
        val start = nextPrim
        nextPrim += size
        if (nextPrim > maxPrim) maxPrim = nextPrim
        return start
    }

    /**
     * Allocate a contiguous block of registers in rMem for function arguments.
     */
    fun allocArgBlockRef(size: Int): Int {
        val start = nextRef
        nextRef += size
        if (nextRef > maxRef) maxRef = nextRef
        for (i in start until start + size) {
            _allRefRegs.add(i)
        }
        return start
    }

    fun freeArgBlockPrim(
        start: Int,
        size: Int,
    ) {
        if (nextPrim == start + size) nextPrim = start
    }

    fun freeArgBlockRef(
        start: Int,
        size: Int,
    ) {
        if (nextRef == start + size) nextRef = start
    }

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
