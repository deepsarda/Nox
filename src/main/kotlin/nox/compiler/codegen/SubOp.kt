package nox.compiler.codegen

/**
 * Sub-opcode constants used by the Nox VM's super-instructions.
 *
 * Sub-opcodes occupy bits 55–48 of the 64-bit instruction layout.
 * They are only meaningful for [Opcode.HACC] and [Opcode.HMOD].
 *
 * See docs/vm/super-instructions.md for the full reference.
 */
object SubOp {
    // HACC / HMOD field access sub-ops

    /** Read/write an `int` field from a json/struct object. */
    const val GET_INT = 0x01

    /** Read/write a `double` field. */
    const val GET_DBL = 0x02

    /** Read/write a `string` field. */
    const val GET_STR = 0x03

    /** Read/write a `boolean` field. */
    const val GET_BOOL = 0x04

    /** Read/write a nested json/struct/array field. */
    const val GET_OBJ = 0x05

    const val SET_INT = 0x11
    const val SET_DBL = 0x12
    const val SET_STR = 0x13
    const val SET_BOOL = 0x14
    const val SET_OBJ = 0x15

    // Reverse Lookup

    private val names: Map<Int, String> by lazy {
        buildMap {
            put(GET_INT, "GET_INT")
            put(GET_DBL, "GET_DBL")
            put(GET_STR, "GET_STR")
            put(GET_BOOL, "GET_BOOL")
            put(GET_OBJ, "GET_OBJ")
            put(SET_INT, "SET_INT")
            put(SET_DBL, "SET_DBL")
            put(SET_STR, "SET_STR")
            put(SET_BOOL, "SET_BOOL")
            put(SET_OBJ, "SET_OBJ")
        }
    }

    /** Returns the mnemonic string for [subOp], or `"SUB_0x%02X".format(subOp)` if unknown. */
    fun name(subOp: Int): String = names[subOp] ?: "SUB_0x%02X".format(subOp)
}
