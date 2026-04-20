package nox.compiler.codegen

/**
 * Opcode constants for the Nox VM instruction set.
 *
 * Every instruction is encoded as a 64-bit long with the opcode in bits 63–56.
 * All values fit in 8 bits (0–255).
 *
 * See docs/vm/instruction-set.md for the full reference.
 */
object Opcode {
    // Arithmetic

    const val IADD = 0x00
    const val ISUB = 0x01
    const val IMUL = 0x02
    const val IDIV = 0x03
    const val IMOD = 0x04
    const val INEG = 0x05

    const val DADD = 0x08
    const val DSUB = 0x09
    const val DMUL = 0x0A
    const val DDIV = 0x0B
    const val DMOD = 0x0C
    const val DNEG = 0x0D

    const val AND = 0x10
    const val OR = 0x11
    const val NOT = 0x12

    // Comparison

    const val IEQ = 0x18
    const val INE = 0x19
    const val ILT = 0x1A
    const val ILE = 0x1B
    const val IGT = 0x1C
    const val IGE = 0x1D

    const val DEQ = 0x20
    const val DNE = 0x21
    const val DLT = 0x22
    const val DLE = 0x23
    const val DGT = 0x24
    const val DGE = 0x25

    const val SEQ = 0x28
    const val SNE = 0x29

    // Data Movement

    const val MOV = 0x30
    const val MOVR = 0x31
    const val LDC = 0x32
    const val LDI = 0x33
    const val KILL_REF = 0x34

    // Type Conversion

    const val I2D = 0x35 // int  -> double
    const val I2S = 0x36 // int  -> string
    const val D2S = 0x37 // double -> string
    const val B2S = 0x38 // boolean -> string
    const val D2I = 0x39 // double -> int  (truncate)

    // Control Flow

    const val JMP = 0x40
    const val JIF = 0x41 // jump if false (pMem[A] == 0)
    const val JIT = 0x42 // jump if true  (pMem[A] != 0)
    const val CALL = 0x43
    const val RET = 0x44

    // System Calls

    const val SCALL = 0x48

    // Host Super-Instructions

    const val HACC = 0x50 // Host Access  (read property)
    const val HMOD = 0x51 // Host Modify  (write property)
    const val SCONCAT = 0x52 // String Concatenation: rMem[A] = rMem[B] + rMem[C]
    const val AGET_IDX = 0x54
    const val AGET_PATH = 0x55
    const val ASET_IDX = 0x57

    // Streaming

    const val YIELD = 0x60

    // Increment / Decrement

    const val IINC = 0x68
    const val IDEC = 0x69
    const val IINCN = 0x6A
    const val IDECN = 0x6B
    const val DINC = 0x6C
    const val DDEC = 0x6D
    const val DINCN = 0x6E
    const val DDECN = 0x6F

    // Bitwise

    const val BAND = 0x70
    const val BOR = 0x71
    const val BXOR = 0x72
    const val BNOT = 0x73
    const val SHL = 0x74
    const val SHR = 0x75
    const val USHR = 0x76

    // Exception Handling

    const val THROW = 0x80
    const val KILL = 0x81

    // Array Construction

    const val NEW_ARRAY = 0x88 // Create a new ArrayList
    const val ARR_PUSH = 0x89 // Append element to array

    // Struct Construction

    const val NEW_OBJ = 0x90 // Create a new NoxObject (map)
    const val OBJ_SET = 0x91 // Set a field on the object

    // Struct Validation

    const val CAST_STRUCT = 0x92 // Validate rMem[B] against TypeDescriptor at pool[C], store in rMem[A]

    // Reverse Lookup

    private val names: Map<Int, String> by lazy {
        buildMap {
            put(IADD, "IADD")
            put(ISUB, "ISUB")
            put(IMUL, "IMUL")
            put(IDIV, "IDIV")
            put(IMOD, "IMOD")
            put(INEG, "INEG")
            put(DADD, "DADD")
            put(DSUB, "DSUB")
            put(DMUL, "DMUL")
            put(DDIV, "DDIV")
            put(DMOD, "DMOD")
            put(DNEG, "DNEG")
            put(AND, "AND")
            put(OR, "OR")
            put(NOT, "NOT")
            put(IEQ, "IEQ")
            put(INE, "INE")
            put(ILT, "ILT")
            put(ILE, "ILE")
            put(IGT, "IGT")
            put(IGE, "IGE")
            put(DEQ, "DEQ")
            put(DNE, "DNE")
            put(DLT, "DLT")
            put(DLE, "DLE")
            put(DGT, "DGT")
            put(DGE, "DGE")
            put(SEQ, "SEQ")
            put(SNE, "SNE")
            put(MOV, "MOV")
            put(MOVR, "MOVR")
            put(LDC, "LDC")
            put(LDI, "LDI")
            put(KILL_REF, "KILL_REF")
            put(I2D, "I2D")
            put(I2S, "I2S")
            put(D2S, "D2S")
            put(B2S, "B2S")
            put(D2I, "D2I")
            put(JMP, "JMP")
            put(JIF, "JIF")
            put(JIT, "JIT")
            put(CALL, "CALL")
            put(RET, "RET")
            put(SCALL, "SCALL")
            put(HACC, "HACC")
            put(HMOD, "HMOD")
            put(AGET_IDX, "AGET_IDX")
            put(AGET_PATH, "AGET_PATH")
            put(ASET_IDX, "ASET_IDX")
            put(SCONCAT, "SCONCAT")
            put(YIELD, "YIELD")
            put(IINC, "IINC")
            put(IDEC, "IDEC")
            put(IINCN, "IINCN")
            put(IDECN, "IDECN")
            put(DINC, "DINC")
            put(DDEC, "DDEC")
            put(DINCN, "DINCN")
            put(DDECN, "DDECN")
            put(BAND, "BAND")
            put(BOR, "BOR")
            put(BXOR, "BXOR")
            put(BNOT, "BNOT")
            put(SHL, "SHL")
            put(SHR, "SHR")
            put(USHR, "USHR")
            put(THROW, "THROW")
            put(KILL, "KILL")
            put(NEW_ARRAY, "NEW_ARRAY")
            put(ARR_PUSH, "ARR_PUSH")
            put(NEW_OBJ, "NEW_OBJ")
            put(OBJ_SET, "OBJ_SET")
            put(CAST_STRUCT, "CAST_STRUCT")
        }
    }

    /** Returns the mnemonic string for [opcode], or `"OP_0x%02X".format(opcode)` if unknown. */
    fun name(opcode: Int): String = names[opcode] ?: "OP_0x%02X".format(opcode)

    /**
     * Returns the source-level operator symbol for binary and comparison opcodes
     * (e.g. `"+"` for [IADD], `"&&"` for [AND], `"<="` for [ILE]).
     *
     * Returns `null` for opcodes that don't map to a simple infix operator.
     * Used by [nox.compiler.codegen.NoxcEmitter] to produce readable disassembly comments.
     */
    fun symbol(opcode: Int): String? =
        when (opcode) {
            IADD, DADD -> "+"
            ISUB, DSUB -> "-"
            IMUL, DMUL -> "*"
            IDIV, DDIV -> "/"
            IMOD, DMOD -> "%"
            IEQ, DEQ, SEQ -> "=="
            INE, DNE, SNE -> "!="
            ILT, DLT -> "<"
            ILE, DLE -> "<="
            IGT, DGT -> ">"
            IGE, DGE -> ">="
            AND -> "&&"
            OR -> "||"
            BAND -> "&"
            BOR -> "|"
            BXOR -> "^"
            SHL -> "<<"
            SHR -> ">>"
            USHR -> ">>>"
            else -> null
        }
}
