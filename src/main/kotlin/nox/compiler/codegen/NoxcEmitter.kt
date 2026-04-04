package nox.compiler.codegen

import nox.compiler.types.FieldSpec
import nox.compiler.types.TypeDescriptor
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Generates human-readable `.noxc` disassembly from a [CompiledProgram].
 *
 * The output format:
 * 1. File header (source, program name, timestamp, modules)
 * 2. Constant pool
 * 3. Module init blocks
 * 4. Function blocks with source annotations and labels
 * 5. Exception table
 * 6. Summary
 *
 * See docs/compiler/disassembly.md for the full specification.
 */
class NoxcEmitter {
    /**
     * Generates the full `.noxc` disassembly string from a compiled program.
     *
     * @param program       the compiled program
     * @param sourceFile    the root `.nox` source file name (for the header)
     * @param programName   the `@tool:name` annotation value, or `"(unnamed)"`
     * @param sourceLines   source lines of the root file (for instruction annotations)
     * @param sourcesByFile map of `sourcePath -> lines` for all source files in the program;
     *                      used to annotate instructions from imported modules with their own lines
     * @param timestamp     ISO-8601 timestamp; defaults to now
     */
    fun emit(
        program: CompiledProgram,
        sourceFile: String,
        programName: String = "(unnamed)",
        sourceLines: List<String> = emptyList(),
        sourcesByFile: Map<String, List<String>> = emptyMap(),
        timestamp: OffsetDateTime = OffsetDateTime.now(),
    ): String {
        val sb = StringBuilder()

        // Merge supplied sourceLines under the sourceFile key so that functions from
        // the root module also resolve correctly via meta.sourcePath.
        val allSources: Map<String, List<String>> =
            buildMap {
                putAll(sourcesByFile)
                if (sourceFile.isNotEmpty() && sourceLines.isNotEmpty()) put(sourceFile, sourceLines)
            }

        emitHeader(sb, sourceFile, programName, program, timestamp)
        emitConstantPool(sb, program.constantPool)
        emitInitBlocks(sb, program, allSources)
        emitFunctions(sb, program, allSources)
        emitExceptionTable(sb, program)
        emitSummary(sb, program)

        return sb.toString()
    }

    private fun emitHeader(
        sb: StringBuilder,
        sourceFile: String,
        programName: String,
        program: CompiledProgram,
        timestamp: OffsetDateTime,
    ) {
        val ts = timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        sb.appendLine(";  Nox Bytecode Disassembly")
        sb.appendLine(";  Source:   $sourceFile")
        sb.appendLine(";  TypedProgram:  \"$programName\"")
        sb.appendLine(";  Compiled: $ts")

        val names = program.modules.joinToString(", ") { it.namespace }
        sb.appendLine(";  Modules:  ${program.modules.size} ($names)")

        sb.appendLine()
    }

    private fun emitConstantPool(
        sb: StringBuilder,
        pool: Array<Any?>,
    ) {
        sb.appendLine()
        sb.appendLine("; Constant Pool")
        sb.appendLine()
        sb.appendLine(".constants")
        if (pool.isEmpty()) {
            sb.appendLine("  (none)")
        } else {
            for ((i, entry) in pool.withIndex()) {
                val (tag, display) = formatPoolEntry(entry)
                sb.appendLine("  #%-3d %-5s %s".format(i, tag, display))
            }
        }
    }

    private fun formatPoolEntry(entry: Any?): Pair<String, String> =
        when (entry) {
            is String -> "str" to "\"${entry.escape()}\""
            is Double -> "dbl" to entry.toBigDecimal().stripTrailingZeros().toPlainString()
            is Long -> "lng" to entry.toString()
            is TypeDescriptor ->
                "type" to
                    "${entry.name} { ${entry.fields.entries.joinToString(
                        ", ",
                    ) { "${it.key}: ${formatFieldSpec(it.value)}" }} }"
            null -> "null" to "null"
            else -> "???" to entry.toString()
        }

    private fun formatFieldSpec(spec: FieldSpec): String =
        when (spec) {
            is FieldSpec.INT -> "int"
            is FieldSpec.DOUBLE -> "double"
            is FieldSpec.BOOLEAN -> "boolean"
            is FieldSpec.STRING -> "string"
            is FieldSpec.JSON -> "json"
            is FieldSpec.Struct -> "Struct(#${spec.descriptorIdx})"
            is FieldSpec.TypedArray -> "${formatFieldSpec(spec.element)}[]"
        }

    private fun emitInitBlocks(
        sb: StringBuilder,
        program: CompiledProgram,
        sources: Map<String, List<String>> = emptyMap(),
    ) {
        val initFuncs = program.functions.filter { it.name.startsWith("<module_init") }
        if (initFuncs.isEmpty()) return

        sb.appendLine()
        sb.appendLine()
        sb.appendLine("; Module Initialization")

        for (meta in initFuncs) {
            sb.appendLine()
            val ns =
                if (meta.name == "<module_init>") "main" else meta.name.removePrefix("<module_init:").removeSuffix(">")
            sb.appendLine(".init $ns")

            // Show global variable names from the stored event list
            if (meta.globalVarNames.isNotEmpty()) {
                sb.appendLine(";  globals: ${meta.globalVarNames.joinToString("  ")}")
            }
            // Show source file if multi-module
            if (program.modules.size > 1 && meta.sourcePath.isNotEmpty()) {
                sb.appendLine(";  source:  ${meta.sourcePath.substringAfterLast('/')}")
            }

            val srcLines = sources[meta.sourcePath] ?: emptyList()
            emitFunctionBody(sb, meta, program.bytecode, program.constantPool, srcLines)
        }
    }

    private fun emitGlobalsComment(
        sb: StringBuilder,
        mod: ModuleMeta,
    ) {
        if (mod.globalPrimitiveCount == 0 && mod.globalReferenceCount == 0) return
        val parts = mutableListOf<String>()
        var p = mod.globalBaseOffset
        var r = mod.globalBaseOffset
        repeat(mod.globalPrimitiveCount) {
            parts.add("g$p (int)")
            p++
        }
        repeat(mod.globalReferenceCount) {
            parts.add("gr$r (ref)")
            r++
        }
        if (parts.isNotEmpty()) sb.appendLine(";  globals: ${parts.joinToString("  ")}")
    }

    private fun emitFunctions(
        sb: StringBuilder,
        program: CompiledProgram,
        sources: Map<String, List<String>> = emptyMap(),
    ) {
        val userFuncs = program.functions.filter { !it.name.startsWith("<module_init") }
        if (userFuncs.isEmpty()) return

        sb.appendLine()
        sb.appendLine()
        sb.appendLine("; Functions")

        for (meta in userFuncs) {
            sb.appendLine()
            sb.appendLine(";  Function: ${meta.name}")
            sb.appendLine(";    Entry PC:   ${meta.entryPC}")
            sb.appendLine(";    Params:     ${meta.paramCount}")
            sb.appendLine(";    Frame:      pMem=${meta.primitiveFrameSize}  rMem=${meta.referenceFrameSize}")
            sb.appendLine()
            sb.appendLine(".func ${meta.name}")
            val srcLines = sources[meta.sourcePath] ?: emptyList()
            emitFunctionBody(sb, meta, program.bytecode, program.constantPool, srcLines)
        }
    }

    private fun emitFunctionBody(
        sb: StringBuilder,
        meta: FuncMeta,
        bytecode: LongArray,
        pool: Array<Any?>,
        srcLines: List<String> = emptyList(),
    ) {
        // Build a name map from the RegNameEvent timeline for params (localPC == 0)
        val primNames = mutableMapOf<Int, String>()
        val refNames = mutableMapOf<Int, String>()
        var nextEventIdx = 0

        // Apply all events at localPC=0 (params) to build the initial state
        val events = meta.regNameEvents.sortedBy { it.localPC }
        while (nextEventIdx < events.size && events[nextEventIdx].localPC == 0) {
            val ev = events[nextEventIdx++]
            if (ev.isPrim) primNames[ev.register] = ev.name else refNames[ev.register] = ev.name
        }

        // Emit params comment
        if (meta.paramCount > 0 && (primNames.isNotEmpty() || refNames.isNotEmpty())) {
            val paramEntries =
                events
                    .filter { it.localPC == 0 }
                    .sortedWith(compareBy({ it.register }, { !it.isPrim }))
                    .take(meta.paramCount)
                    .map { ev ->
                        if (ev.isPrim) "p${ev.register}=${ev.name}" else "r${ev.register}=${ev.name}"
                    }
            if (paramEntries.isNotEmpty()) {
                sb.appendLine("  ; params: ${paramEntries.joinToString("  ")}")
            }
        }

        var lastLine = -1
        val numInstructions =
            meta.sourceLines.size.coerceAtMost(
                (bytecode.size - meta.entryPC).coerceAtLeast(0),
            )

        for (i in 0 until numInstructions) {
            val absolutePc = meta.entryPC + i
            if (absolutePc >= bytecode.size) break

            // Apply any events that fire at this localPC (new variable comes into scope)
            while (nextEventIdx < events.size && events[nextEventIdx].localPC == i) {
                val ev = events[nextEventIdx++]
                if (ev.isPrim) primNames[ev.register] = ev.name else refNames[ev.register] = ev.name
            }

            val inst = bytecode[absolutePc]
            val srcLine = meta.sourceLines.getOrElse(i) { -1 }

            // Source line annotation
            if (srcLine >= 0 && srcLine != lastLine) {
                val lineText = srcLines.getOrElse(srcLine - 1) { "" }.trim()
                sb.appendLine("  ;")
                if (lineText.isNotEmpty()) {
                    sb.appendLine("  ; line $srcLine  $lineText")
                } else {
                    sb.appendLine("  ; line $srcLine")
                }
                lastLine = srcLine
            }

            // Label
            val label = meta.labels[i]
            if (label != null) sb.appendLine("  .$label:")

            emitInstruction(sb, absolutePc, inst, pool, meta.labels, i, primNames, refNames)
        }
    }

    private fun emitInstruction(
        sb: StringBuilder,
        pc: Int,
        inst: Long,
        pool: Array<Any?>,
        labels: Map<Int, String>,
        localPc: Int,
        primNames: Map<Int, String> = emptyMap(),
        refNames: Map<Int, String> = emptyMap(),
    ) {
        val opcode = Instruction.opcode(inst)
        val subOp = Instruction.subOp(inst)
        val a = Instruction.opA(inst)
        val b = Instruction.opB(inst)
        val c = Instruction.opC(inst)

        val mnemonic = Opcode.name(opcode)
        val (operands, comment) = formatInstruction(opcode, subOp, a, b, c, pool, labels, primNames, refNames)

        sb.appendLine("  %04d:  %-10s%-28s; %s".format(pc, mnemonic, operands, comment))
    }

    private fun formatInstruction(
        opcode: Int,
        subOp: Int,
        a: Int,
        b: Int,
        c: Int,
        pool: Array<Any?>,
        labels: Map<Int, String>,
        primNames: Map<Int, String> = emptyMap(),
        refNames: Map<Int, String> = emptyMap(),
    ): Pair<String, String> {
        fun pr(r: Int) = "p$r"

        fun rr(r: Int) = "r$r"

        // Named variants for comments, shows "name:pN" when known, else just "pN"
        fun pn(r: Int) = primNames[r]?.let { "$it:p$r" } ?: "p$r"

        fun rn(r: Int) = refNames[r]?.let { "$it:r$r" } ?: "r$r"

        fun gr(r: Int) = "g$r"

        fun grr(r: Int) = "gr$r"

        fun pool(idx: Int) = "#$idx"

        fun jump(target: Int): String {
            val lbl = labels[target]
            return "@%04d".format(target) + (if (lbl != null) "  ; -> $lbl" else "")
        }

        /** Maps a binary opcode to its source-level operator symbol. */
        fun opcodeSymbol(op: Int) = Opcode.symbol(op) ?: Opcode.name(op)

        return when (opcode) {
            // Arithmetic & comparison (pMem only)
            Opcode.IADD, Opcode.ISUB, Opcode.IMUL, Opcode.IDIV, Opcode.IMOD,
            Opcode.DADD, Opcode.DSUB, Opcode.DMUL, Opcode.DDIV, Opcode.DMOD,
            Opcode.AND, Opcode.OR,
            Opcode.IEQ, Opcode.INE, Opcode.ILT, Opcode.ILE, Opcode.IGT, Opcode.IGE,
            Opcode.DEQ, Opcode.DNE, Opcode.DLT, Opcode.DLE, Opcode.DGT, Opcode.DGE,
            Opcode.BAND, Opcode.BOR, Opcode.BXOR, Opcode.SHL, Opcode.SHR, Opcode.USHR,
            -> "${pr(a)}, ${pr(b)}, ${pr(c)}" to "${pn(a)} = ${pn(b)} ${opcodeSymbol(opcode)} ${pn(c)}"

            Opcode.SEQ, Opcode.SNE,
            -> "${pr(a)}, ${rr(b)}, ${rr(c)}" to "${pn(a)} = ${rn(b)} ${opcodeSymbol(opcode)} ${rn(c)}"

            Opcode.INEG, Opcode.DNEG, Opcode.NOT, Opcode.BNOT,
            -> "${pr(a)}, ${pr(b)}" to "${pn(a)} = -${pn(b)}"

            Opcode.MOV,
            -> "${pr(a)}, ${pr(b)}" to "${pn(a)} = ${pn(b)}"

            Opcode.MOVR,
            -> "${rr(a)}, ${rr(b)}" to "${rn(a)} = ${rn(b)}"

            Opcode.LDI,
            -> "${pr(a)}, $b" to "${pn(a)} = $b"

            Opcode.LDC -> {
                val value = pool.getOrNull(b)
                when (value) {
                    is String -> "${rr(a)}, ${pool(b)}" to "${rn(a)} = \"${value.escape()}\""
                    is Double -> "${pr(a)}, ${pool(b)}" to "${pn(a)} = $value"
                    is Long -> "${pr(a)}, ${pool(b)}" to "${pn(a)} = $value"
                    else -> "${pr(a)}, ${pool(b)}" to "${pn(a)} = <pool[$b]>"
                }
            }

            Opcode.KILL_REF,
            -> "${rr(a)}" to "${rn(a)} = null (GC)"

            Opcode.I2D -> "${pr(a)}, ${pr(b)}" to "${pn(a)} = (double)${pn(b)}"
            Opcode.I2S -> "${rr(a)}, ${pr(b)}" to "${rn(a)} = toString(${pn(b)})"
            Opcode.D2S -> "${rr(a)}, ${pr(b)}" to "${rn(a)} = toString(${pn(b)})"
            Opcode.B2S -> "${rr(a)}, ${pr(b)}" to "${rn(a)} = toString(${pn(b)})"
            Opcode.D2I -> "${pr(a)}, ${pr(b)}" to "${pn(a)} = (int)${pn(b)}"

            Opcode.JMP -> {
                val lbl = labels[b]
                "@%04d".format(b) to "-> ${lbl ?: "@%04d".format(b)}"
            }

            Opcode.JIF -> {
                val lbl = labels[b]
                "${pr(a)}, @%04d".format(b) to "if ${pn(a)}==0 -> ${lbl ?: "@%04d".format(b)}"
            }

            Opcode.JIT -> {
                val lbl = labels[b]
                "${pr(a)}, @%04d".format(b) to "if ${pn(a)}!=0 -> ${lbl ?: "@%04d".format(b)}"
            }

            Opcode.CALL -> {
                val funcName = pool.getOrNull(a) as? String ?: "func_$a"
                "$funcName, ${pr(b)}" to "call $funcName(${pn(b)}...)"
            }

            Opcode.RET -> {
                if (a == 0) {
                    "" to "return (void)"
                } else {
                    pr(a) to "return ${pn(a)}"
                }
            }

            Opcode.SCALL -> {
                val funcName = pool.getOrNull(b) as? String ?: "sfunc_$b"
                "${pr(a)}, $funcName, ${pr(c)}" to "${pn(a)} = $funcName(${pn(c)}...)"
            }

            Opcode.HACC -> {
                val key = pool.getOrNull(c) as? String ?: "#$c"
                val tag = SubOp.name(subOp)
                "$tag, ${rr(a)}, ${rr(b)}, \"$key\"" to "${rn(a)} = ${rn(b)}.$key"
            }

            Opcode.HMOD -> {
                val key = pool.getOrNull(b) as? String ?: "#$b"
                val tag = SubOp.name(subOp)
                "$tag, ${rr(a)}, \"$key\", ${pr(c)}" to "${rn(a)}.$key = ${pn(c)}"
            }

            Opcode.SCONCAT -> {
                "${rr(a)}, ${rr(b)}, ${rr(c)}" to "${rn(a)} = ${rn(b)} + ${rn(c)}"
            }

            Opcode.AGET_IDX -> "${rr(a)}, ${rr(b)}, ${pr(c)}" to "${rn(a)} = ${rn(b)}[${pn(c)}]"
            Opcode.AGET_PATH -> {
                val path = pool.getOrNull(c) as? String ?: "#$c"
                "${rr(a)}, ${rr(b)}, \"$path\"" to "${rn(a)} = ${rn(b)}.$path"
            }

            Opcode.ASET_IDX -> "${rr(a)}, ${pr(b)}, ${pr(c)}" to "${rn(a)}[${pn(b)}] = ${pn(c)}"

            Opcode.YIELD -> rr(a) to "yield ${rn(a)}"

            Opcode.IINC -> pr(a) to "${pn(a)} = ${pn(a)} + 1"
            Opcode.IDEC -> pr(a) to "${pn(a)} = ${pn(a)} - 1"
            Opcode.IINCN -> "${pr(a)}, ${pr(b)}" to "${pn(a)} = ${pn(a)} + ${pn(b)}"
            Opcode.IDECN -> "${pr(a)}, ${pr(b)}" to "${pn(a)} = ${pn(a)} - ${pn(b)}"
            Opcode.DINC -> pr(a) to "${pn(a)} = ${pn(a)} + 1.0"
            Opcode.DDEC -> pr(a) to "${pn(a)} = ${pn(a)} - 1.0"
            Opcode.DINCN -> "${pr(a)}, ${pr(b)}" to "${pn(a)} = ${pn(a)} + ${pn(b)}"
            Opcode.DDECN -> "${pr(a)}, ${pr(b)}" to "${pn(a)} = ${pn(a)} - ${pn(b)}"

            Opcode.GLOAD -> "${pr(a)}, ${gr(b)}" to "${pn(a)} = ${gr(b)}"
            Opcode.GSTORE -> "${gr(a)}, ${pr(b)}" to "${gr(a)} = ${pn(b)}"
            Opcode.GLOADR -> "${rr(a)}, ${grr(b)}" to "${rn(a)} = ${grr(b)}"
            Opcode.GSTORER -> "${grr(a)}, ${rr(b)}" to "${grr(a)} = ${rn(b)}"

            Opcode.THROW -> rr(a) to "throw ${rn(a)}"
            Opcode.KILL -> "" to "KILL (terminate)"

            Opcode.NEW_ARRAY -> rr(a) to "${rn(a)} = []"
            Opcode.ARR_PUSH -> "${rr(a)}, ${pr(b)}" to "${rn(a)}.push(${pn(b)})"
            Opcode.NEW_OBJ -> rr(a) to "${rn(a)} = {}"
            Opcode.OBJ_SET -> {
                val key = pool.getOrNull(b) as? String ?: "#$b"
                "${rr(a)}, \"$key\", ${pr(c)}" to "${rn(a)}.$key = ${pn(c)}"
            }

            Opcode.CAST_STRUCT -> {
                val desc = pool.getOrNull(c) as? TypeDescriptor
                val typeName = desc?.name ?: "#$c"
                "${rr(a)}, ${rr(b)}, #$c" to "${rn(a)} = ${rn(b)} as ${if (subOp == 1) "$typeName[]" else typeName}"
            }

            else -> "a=$a, b=$b, c=$c" to "(unknown opcode ${Opcode.name(opcode)})"
        }
    }

    // Exception Table

    private fun emitExceptionTable(
        sb: StringBuilder,
        program: CompiledProgram,
    ) {
        sb.appendLine()
        sb.appendLine()
        sb.appendLine("; Exception Table")
        sb.appendLine()
        sb.appendLine(".exceptions")
        if (program.exceptionTable.isEmpty()) {
            sb.appendLine("  (none)")
        } else {
            for (ex in program.exceptionTable) {
                val type = ex.exceptionType ?: "ANY"
                sb.appendLine(
                    "  [%04d..%04d] %-20s -> @%04d  msg=r%d".format(
                        ex.startPC,
                        ex.endPC,
                        type,
                        ex.handlerPC,
                        ex.messageRegister,
                    ),
                )
            }
        }
    }

    // Summary

    private fun emitSummary(
        sb: StringBuilder,
        program: CompiledProgram,
    ) {
        val initCount = program.functions.count { it.name.startsWith("<module_init") }
        val funcCount = program.functions.count { !it.name.startsWith("<module_init") }
        val bytesTotal = program.bytecode.size * 8
        sb.appendLine()
        sb.appendLine()
        sb.appendLine("; Summary")
        sb.appendLine()
        sb.appendLine(".summary")
        sb.appendLine("  modules:      ${program.modules.size}")
        sb.appendLine("  init_blocks:  $initCount")
        sb.appendLine("  functions:    $funcCount")
        sb.appendLine("  instructions: ${program.bytecode.size}")
        sb.appendLine("  constants:    ${program.constantPool.size}")
        sb.appendLine("  exceptions:   ${program.exceptionTable.size}")
        sb.appendLine("  globals:      ${program.totalGlobalPrimitiveSlots}p + ${program.totalGlobalReferenceSlots}r")
        sb.appendLine("  bytecode:     $bytesTotal bytes")
    }

    // Helpers

    private fun String.escape(): String =
        this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
