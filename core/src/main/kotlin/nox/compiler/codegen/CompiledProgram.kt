package nox.compiler.codegen

/**
 * The result of a full compilation pass over a Nox program.
 *
 * An immutable snapshot of everything the VM needs to execute, plus
 * metadata for the disassembler and run-time initialization sequence.
 *
 * @property bytecode               packed 64-bit instructions (flat array)
 * @property constantPool           strings, doubles, longs, path strings, type IDs
 * @property exceptionTable         try-catch PC range mappings
 * @property functions              per-function metadata, in emission order
 * @property modules                per-module metadata, in depth-first import order
 * @property mainFuncIndex          index into [functions] for the `main` entry point
 * @property totalGlobalPrimitiveSlots total `gMem` slots across all modules
 * @property totalGlobalReferenceSlots total `gMemRef` slots across all modules
 *
 * See docs/compiler/codegen.md for the full design.
 */
class CompiledProgram(
    val bytecode: LongArray,
    val constantPool: Array<Any?>,
    val exceptionTable: Array<ExEntry>,
    val functions: Array<FuncMeta>,
    val modules: Array<ModuleMeta>,
    val mainFuncIndex: Int,
    val totalGlobalPrimitiveSlots: Int,
    val totalGlobalReferenceSlots: Int,
)

/**
 * Metadata for a single compiled function (or `<module_init>` block).
 *
 * @property name              function name, or `"<module_init>"` / `"<module_init:ns>"`
 * @property entryPC           index into [CompiledProgram.bytecode] of the first instruction
 * @property paramCount        number of declared parameters (0 for init blocks)
 * @property primitiveFrameSize size of the `pMem` call frame (number of registers)
 * @property referenceFrameSize size of the `rMem` call frame (number of registers)
 * @property sourceLines       source-line numbers parallel to [CompiledProgram.bytecode],
 *                             indexed from [entryPC]. `-1` = no annotation for that instruction.
 * @property labels            map of `(pc - entryPC) to labelName` for loop/catch/end labels
 * @property regNameEvents     timeline of register↔variable name assignments, sorted by [RegNameEvent.localPC].
 *                             Used by the disassembler to show variable names in comments.
 * @property sourcePath        absolute or relative path of the source file this function was defined in.
 *                             Used by the disassembler to look up the correct source lines.
 * @property globalVarNames    for init blocks only: list of `"gN=name (type)"` strings describing globals.
 */
data class FuncMeta(
    val name: String,
    val entryPC: Int,
    val paramCount: Int,
    val primitiveFrameSize: Int,
    val referenceFrameSize: Int,
    val sourceLines: IntArray = IntArray(0),
    val labels: Map<Int, String> = emptyMap(),
    val regNameEvents: List<RegNameEvent> = emptyList(),
    val sourcePath: String = "",
    val globalVarNames: List<String> = emptyList(),
)

/**
 * Records when a register is assigned to a named variable.
 *
 * Events are sorted by [localPC] (relative to [FuncMeta.entryPC]).
 * The disassembler walks these events to maintain a running
 * `register -> varName` map for each instruction.
 *
 * @property localPC  instruction index relative to [FuncMeta.entryPC] where the assignment occurs
 * @property isPrim   `true` for pMem registers, `false` for rMem registers
 * @property register the register number within the bank
 * @property name     the source variable name (parameter or local)
 */
data class RegNameEvent(
    val localPC: Int,
    val isPrim: Boolean,
    val register: Int,
    val name: String,
)

/**
 * Metadata for a single module (the root `main` module and each import).
 *
 * Global memory is a flat contiguous array partitioned by module.
 * `globalBaseOffset` marks the start of this module's segment.
 *
 * @property namespace               user-chosen alias (`"helpers"`, `"m"`) or `"main"`
 * @property sourcePath              resolved absolute path of the source file
 * @property globalBaseOffset        first slot index in the global primitive/reference arrays
 * @property globalPrimitiveCount    primitive global slots owned by this module
 * @property globalReferenceCount    reference global slots owned by this module
 * @property exportedFunctions       indices into [CompiledProgram.functions] array
 * @property exportedTypes           type names visible as `namespace.TypeName`
 * @property initFuncIndex           index into [CompiledProgram.functions] for the module init block,
 *                                   or `-1` if no init block was emitted
 */
data class ModuleMeta(
    val namespace: String,
    val sourcePath: String,
    val globalBaseOffset: Int,
    val globalPrimitiveCount: Int,
    val globalReferenceCount: Int,
    val exportedFunctions: List<Int>,
    val exportedTypes: List<String>,
    val initFuncIndex: Int = -1,
)

/**
 * One row in the exception table.
 *
 * The VM scans this table in order for the current PC on every thrown exception.
 * Inner try-catch blocks must appear **before** enclosing ones.
 *
 * @property startPC       first instruction of the protected try block (inclusive)
 * @property endPC         first instruction after the try block (exclusive)
 * @property exceptionType the error type name to match (`null` = catch-all)
 * @property handlerPC     first instruction of the catch handler
 * @property messageRegister `rMem` register index for the error message string
 */
data class ExEntry(
    val startPC: Int,
    val endPC: Int,
    val exceptionType: String?,
    val handlerPC: Int,
    val messageRegister: Int,
)
