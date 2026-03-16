package nox.compiler.codegen

/**
 * 64-bit instruction encoding and decoding utilities.
 *
 * Instruction layout (64 bits):
 * ```
 *  63       56 55       48 47              32 31              16 15               0
 * ┌───────────┬───────────┬─────────────────┬─────────────────┬─────────────────┐
 * │  Opcode   │ Sub-Opcode│    Operand A    │    Operand B    │    Operand C    │
 * │  (8 bits) │  (8 bits) │   (16 bits)     │   (16 bits)     │   (16 bits)     │
 * └───────────┴───────────┴─────────────────┴─────────────────┴─────────────────┘
 * ```
 *
 * - **Opcode** (bits 63–56): primary operation
 * - **Sub-Opcode** (bits 55–48): secondary intent for super-instructions
 * - **Operand A** (bits 47–32): typically the destination register
 * - **Operand B** (bits 31–16): typically source 1 or a constant pool index
 * - **Operand C** (bits 15–0): typically source 2 or additional data
 *
 * See docs/vm/instruction-set.md for the full reference.
 */
object Instruction {
    /**
     * Pack five fields into a single 64-bit instruction.
     *
     * @param opcode  primary opcode (0–255)
     * @param subOp   sub-opcode (0–255)
     * @param a       operand A (0–65535)
     * @param b       operand B (0–65535)
     * @param c       operand C (0–65535)
     * @return the packed instruction word
     */
    fun encode(
        opcode: Int,
        subOp: Int,
        a: Int,
        b: Int,
        c: Int,
    ): Long =
        ((opcode.toLong() and 0xFF) shl 56) or
            ((subOp.toLong() and 0xFF) shl 48) or
            ((a.toLong() and 0xFFFF) shl 32) or
            ((b.toLong() and 0xFFFF) shl 16) or
            (c.toLong() and 0xFFFF)

    /** Decode the opcode field from an encoded instruction. */
    fun opcode(inst: Long): Int = ((inst ushr 56) and 0xFF).toInt()

    /** Decode the sub-opcode field from an encoded instruction. */
    fun subOp(inst: Long): Int = ((inst ushr 48) and 0xFF).toInt()

    /** Decode operand A from an encoded instruction. */
    fun opA(inst: Long): Int = ((inst ushr 32) and 0xFFFF).toInt()

    /** Decode operand B from an encoded instruction. */
    fun opB(inst: Long): Int = ((inst ushr 16) and 0xFFFF).toInt()

    /** Decode operand C from an encoded instruction. */
    fun opC(inst: Long): Int = (inst and 0xFFFF).toInt()

    /**
     * Patch the operand B field of an existing instruction.
     *
     * Used for backpatching jump targets: the first pass emits
     * `JIF/JMP` with a placeholder B, then after the jump target PC is
     * known, `patchB` updates it in-place.
     */
    fun patchB(
        inst: Long,
        newB: Int,
    ): Long = (inst and -0x10000L) or (newB.toLong() and 0xFFFF)
}
