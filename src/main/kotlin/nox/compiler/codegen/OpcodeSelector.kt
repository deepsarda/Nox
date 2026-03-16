package nox.compiler.codegen

import nox.compiler.types.AssignOp
import nox.compiler.types.BinaryOp
import nox.compiler.types.TypeRef

/**
 * Pure-function mapping from source-level operators to VM opcodes.
 *
 * Centralises opcode-selection logic that was previously scattered
 * through [BytecodeEmitter].  Every method is stateless.
 */
object OpcodeSelector {
    /** Select the arithmetic / comparison opcode for a binary expression. */
    fun binaryOpcode(
        op: BinaryOp,
        result: TypeRef,
        left: TypeRef,
        right: TypeRef,
    ): Int {
        val isDbl = result == TypeRef.DOUBLE
        return when (op) {
            BinaryOp.ADD -> if (isDbl) Opcode.DADD else Opcode.IADD
            BinaryOp.SUB -> if (isDbl) Opcode.DSUB else Opcode.ISUB
            BinaryOp.MUL -> if (isDbl) Opcode.DMUL else Opcode.IMUL
            BinaryOp.DIV -> if (isDbl) Opcode.DDIV else Opcode.IDIV
            BinaryOp.MOD -> if (isDbl) Opcode.DMOD else Opcode.IMOD
            BinaryOp.EQ ->
                when {
                    left == TypeRef.DOUBLE || right == TypeRef.DOUBLE -> Opcode.DEQ
                    left == TypeRef.STRING || right == TypeRef.STRING -> Opcode.SEQ
                    left.isNullable() || right.isNullable() -> Opcode.SEQ
                    else -> Opcode.IEQ
                }

            BinaryOp.NE ->
                when {
                    left == TypeRef.DOUBLE || right == TypeRef.DOUBLE -> Opcode.DNE
                    left == TypeRef.STRING || right == TypeRef.STRING -> Opcode.SNE
                    left.isNullable() || right.isNullable() -> Opcode.SNE
                    else -> Opcode.INE
                }

            BinaryOp.LT -> if (left == TypeRef.DOUBLE || right == TypeRef.DOUBLE) Opcode.DLT else Opcode.ILT
            BinaryOp.LE -> if (left == TypeRef.DOUBLE || right == TypeRef.DOUBLE) Opcode.DLE else Opcode.ILE
            BinaryOp.GT -> if (left == TypeRef.DOUBLE || right == TypeRef.DOUBLE) Opcode.DGT else Opcode.IGT
            BinaryOp.GE -> if (left == TypeRef.DOUBLE || right == TypeRef.DOUBLE) Opcode.DGE else Opcode.IGE
            BinaryOp.AND -> Opcode.AND
            BinaryOp.OR -> Opcode.OR
            BinaryOp.BIT_AND -> Opcode.BAND
            BinaryOp.BIT_OR -> Opcode.BOR
            BinaryOp.BIT_XOR -> Opcode.BXOR
            BinaryOp.SHL -> Opcode.SHL
            BinaryOp.SHR -> Opcode.SHR
            BinaryOp.USHR -> Opcode.USHR
        }
    }

    /** Select the opcode for a compound assignment (`+=`, `-=`, etc.). */
    fun compoundAssignOpcode(
        op: AssignOp,
        targetType: TypeRef,
    ): Int =
        when (op) {
            AssignOp.ADD_ASSIGN -> if (targetType == TypeRef.DOUBLE) Opcode.DADD else Opcode.IADD
            AssignOp.SUB_ASSIGN -> if (targetType == TypeRef.DOUBLE) Opcode.DSUB else Opcode.ISUB
            AssignOp.MUL_ASSIGN -> if (targetType == TypeRef.DOUBLE) Opcode.DMUL else Opcode.IMUL
            AssignOp.DIV_ASSIGN -> if (targetType == TypeRef.DOUBLE) Opcode.DDIV else Opcode.IDIV
            AssignOp.MOD_ASSIGN -> if (targetType == TypeRef.DOUBLE) Opcode.DMOD else Opcode.IMOD
            else -> Opcode.IADD
        }

    /** Select the [SubOp] for reading a field of the given type. */
    fun subOpForGet(fieldType: TypeRef): Int =
        when {
            fieldType == TypeRef.INT -> SubOp.GET_INT
            fieldType == TypeRef.DOUBLE -> SubOp.GET_DBL
            fieldType == TypeRef.BOOLEAN -> SubOp.GET_BOOL
            fieldType == TypeRef.STRING -> SubOp.GET_STR
            else -> SubOp.GET_OBJ
        }

    /** Select the [SubOp] for writing a field of the given type. */
    fun subOpForSet(valType: TypeRef): Int =
        when {
            valType == TypeRef.INT -> SubOp.SET_INT
            valType == TypeRef.DOUBLE -> SubOp.SET_DBL
            valType == TypeRef.BOOLEAN -> SubOp.SET_BOOL
            valType == TypeRef.STRING -> SubOp.SET_STR
            else -> SubOp.SET_OBJ
        }
}
