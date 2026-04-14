package nox.vm

import nox.compiler.codegen.CompiledProgram
import nox.compiler.codegen.Instruction
import nox.compiler.codegen.Opcode
import nox.compiler.codegen.SubOp
import nox.compiler.types.FieldSpec
import nox.compiler.types.TypeDescriptor
import nox.plugin.LibraryRegistry
import nox.plugin.NoxNativeFunc
import nox.runtime.*
import java.lang.Double.doubleToRawLongBits
import java.lang.Double.longBitsToDouble

/**
 * The Nox virtual machine.
 *
 * Executes a [CompiledProgram] within an isolated sandbox, communicating
 * with the host exclusively through [RuntimeContext].
 *
 * See docs/vm/ for the full design.
 */
class NoxVM(
    private val program: CompiledProgram,
    private val context: RuntimeContext,
    private val registry: LibraryRegistry,
    private val config: ExecutionConfig = ExecutionConfig(),
) {
    // Memory

    private val pMem = LongArray(config.registerFileSize)
    private val rMem = arrayOfNulls<Any?>(config.registerFileSize)
    private val gMem = LongArray(program.totalGlobalPrimitiveSlots)
    private val gMemRef = arrayOfNulls<Any?>(program.totalGlobalReferenceSlots)

    // Execution state

    private var pc = 0
    private var bp = 0
    private var bpRef = 0
    private var currentFuncIndex = -1
    private var running = true

    // Call stack: flat array [bp, bpRef, pc, funcIndex] per frame
    private var callStack = IntArray(config.maxCallDepth * 4)
    private var csp = 0

    // Resource guards

    private var instructionCount = 0L
    private var maxInstructions = config.maxInstructions
    private var maxCallDepth = config.maxCallDepth
    private var timeoutReason: String? = null

    // Output

    private var returnValue: String? = null

    // Constant pool (cached for helper access)

    private val pool: Array<Any?> = program.constantPool

    // SCALL cache

    private val scallCache: Array<NoxNativeFunc?>

    // Global flag constants

    companion object {
        private const val GLOBAL_FLAG = 0x8000
        private const val SLOT_MASK = 0x7FFF
    }

    init {
        // Build SCALL cache: for each constant pool entry that's a String,
        // try to resolve it as a native function name.
        scallCache =
            Array(pool.size) { i ->
                val entry = pool[i]
                if (entry is String) registry.lookupNativeFunc(entry) else null
            }
    }

    // Global/local operand routing

    private fun readP(operand: Int): Long =
        if (operand and GLOBAL_FLAG != 0) {
            gMem[operand and SLOT_MASK]
        } else {
            pMem[bp + operand]
        }

    private fun writeP(
        operand: Int,
        value: Long,
    ) {
        if (operand and GLOBAL_FLAG != 0) {
            gMem[operand and SLOT_MASK] = value
        } else {
            pMem[bp + operand] = value
        }
    }

    private fun readR(operand: Int): Any? =
        if (operand and GLOBAL_FLAG != 0) {
            gMemRef[operand and SLOT_MASK]
        } else {
            rMem[bpRef + operand]
        }

    private fun writeR(
        operand: Int,
        value: Any?,
    ) {
        if (operand and GLOBAL_FLAG != 0) {
            gMemRef[operand and SLOT_MASK] = value
        } else {
            rMem[bpRef + operand] = value
        }
    }

    // Dispatch helpers

    private fun readSubOpGet(
        subOp: Int,
        dest: Int,
        value: Any?,
    ) {
        when (subOp) {
            SubOp.GET_INT -> writeP(dest, (value as? Number)?.toLong() ?: 0L)
            SubOp.GET_DBL -> writeP(dest, doubleToRawLongBits((value as? Number)?.toDouble() ?: 0.0))
            SubOp.GET_STR -> writeR(dest, value as? String)
            SubOp.GET_BOOL -> writeP(dest, if (value as? Boolean == true) 1L else 0L)
            else -> writeR(dest, value)
        }
    }

    private fun readValueBySubOp(
        subOp: Int,
        reg: Int,
    ): Any? =
        when (subOp) {
            SubOp.SET_INT -> readP(reg)
            SubOp.SET_DBL -> longBitsToDouble(readP(reg))
            SubOp.SET_STR -> readR(reg)
            SubOp.SET_BOOL -> readP(reg) != 0L
            else -> readR(reg)
        }

    /** Reads integer operand C based on arithmetic subOp mode. */
    private fun readIntC(
        subOp: Int,
        c: Int,
    ): Long =
        when (subOp) {
            SubOp.REG_IMM -> c.toLong()
            SubOp.REG_POOL -> pool[c] as Long
            else -> readP(c)
        }

    /** Reads double operand C based on arithmetic subOp mode. */
    private fun readDoubleC(
        subOp: Int,
        c: Int,
    ): Double =
        when (subOp) {
            SubOp.REG_POOL -> pool[c] as Double
            else -> longBitsToDouble(readP(c))
        }

    private fun valueToString(
        typeTag: Int,
        reg: Int,
    ): String =
        if (typeTag < 3) {
            val raw = readP(reg)
            when (typeTag) {
                0 -> raw.toString()
                1 -> longBitsToDouble(raw).toString()
                2 -> if (raw != 0L) "true" else "false"
                else -> raw.toString()
            }
        } else {
            val obj = readR(reg)
            if (obj != null && obj !is String) {
                nox.runtime.json
                    .NoxJsonWriter(prettyPrint = false)
                    .write(obj)
            } else {
                obj?.toString() ?: "null"
            }
        }

    private fun valueToStringSubOp(
        subOp: Int,
        reg: Int,
    ): String =
        when (subOp) {
            SubOp.TYPE_INT -> readP(reg).toString()
            SubOp.TYPE_DBL -> longBitsToDouble(readP(reg)).toString()
            SubOp.TYPE_BOOL -> if (readP(reg) != 0L) "true" else "false"
            else -> {
                val obj = readR(reg)
                if (obj != null && obj !is String) {
                    nox.runtime.json
                        .NoxJsonWriter(prettyPrint = false)
                        .write(obj)
                } else {
                    obj?.toString() ?: "null"
                }
            }
        }

    // Entry point
    suspend fun execute(primArgs: LongArray = LongArray(0), refArgs: Array<Any?> = emptyArray()): String? {
        val vmThread = Thread.currentThread()
        val watchdog = createWatchdogThread(vmThread)
        watchdog.start()
        try {
            // Run module init blocks
            for (module in program.modules) {
                if (module.initFuncIndex != -1) {
                    enterFunction(module.initFuncIndex, 0, 0)
                    loop()
                    // Reset for next init / main
                    running = true
                }
            }
            try {
                // Run main
                if (program.mainFuncIndex == -1) {
                    throw NoxException(NoxError.CompilationError, "No main() function found", 0)
                }

                primArgs.copyInto(pMem, 0)
                refArgs.copyInto(rMem, 0)

                enterFunction(program.mainFuncIndex, 0, 0)
                loop()
                return returnValue
            } catch (e: NoxException) {
                // TODO: Move this behind flag
                dumpMemory(e)
                throw e
            }
        } finally {
            watchdog.interrupt()
            Thread.interrupted() // clear stale interrupt flag
        }
    }

    private fun createWatchdogThread(vmThread: Thread): Thread {
        val startTime = System.nanoTime()
        var timeoutMs = config.maxExecutionTime.toMillis()
        return Thread {
            try {
                while (true) {
                    Thread.sleep(timeoutMs)
                    val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                    val response =
                        kotlinx.coroutines.runBlocking {
                            context.requestResourceExtension(
                                ResourceRequest.ExecutionTimeout(elapsedMs, timeoutMs),
                            )
                        }
                    when (response) {
                        is ResourceResponse.Granted -> timeoutMs = response.newLimit
                        is ResourceResponse.Denied -> {
                            timeoutReason = response.reason ?: "Execution exceeded time limit"
                            vmThread.interrupt()
                            timeoutMs += minOf(timeoutMs, 1000L) // Exponential backoff max 1s
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // VM finished before timeout, so we need to exit cleanly
            }
        }.apply {
            isDaemon = true
            name = "nox-watchdog"
        }
    }

    // Function entry
    private fun enterFunction(
        funcIndex: Int,
        primArgStart: Int,
        refArgStart: Int,
    ) {
        val meta = program.functions[funcIndex]
        bp += primArgStart
        bpRef += refArgStart
        pc = meta.entryPC
        currentFuncIndex = funcIndex
    }

    // Resource guard: instruction quota
    private suspend fun checkInstructionQuota(): String? {
        val response =
            context.requestResourceExtension(
                ResourceRequest.InstructionQuota(instructionCount, maxInstructions),
            )
        return when (response) {
            is ResourceResponse.Granted -> {
                maxInstructions = response.newLimit
                null
            }
            is ResourceResponse.Denied -> {
                val msg = response.reason ?: "Execution limit exceeded: $maxInstructions instructions"
                maxInstructions += minOf(maxInstructions, 10000L)
                msg
            }
        }
    }

    // Resource guard: call depth
    private suspend fun checkCallDepth(): String? {
        val response =
            context.requestResourceExtension(
                ResourceRequest.CallDepth(csp / 4, maxCallDepth),
            )
        return when (response) {
            is ResourceResponse.Granted -> {
                val newLimit = response.newLimit.toInt()
                if (newLimit > maxCallDepth) {
                    callStack = callStack.copyOf(newLimit * 4)
                }
                maxCallDepth = newLimit
                null
            }
            is ResourceResponse.Denied -> {
                val msg = response.reason ?: "Maximum recursion depth exceeded: $maxCallDepth"
                val newLimit = maxCallDepth + minOf(maxCallDepth, 100)
                callStack = callStack.copyOf(newLimit * 4)
                maxCallDepth = newLimit
                msg
            }
        }
    }

    // Exception handling
    private fun handleException(ex: NoxException) {
        val table = program.exceptionTable

        val isResourceGuard =
            ex.type in
                setOf(
                    NoxError.QuotaExceededError,
                    NoxError.TimeoutError,
                    NoxError.MemoryLimitError,
                    NoxError.StackOverflowError,
                )

        while (true) {
            // Scan for matching handler in current frame
            for (entry in table) {
                if (pc - 1 in entry.startPC until entry.endPC) {
                    val match =
                        if (entry.exceptionType == null) {
                            !isResourceGuard // Catch-all matches everything EXCEPT resource guards
                        } else {
                            entry.exceptionType == "Error" || entry.exceptionType == ex.type.name
                        }

                    if (match) {
                        // Match found: store message, jump to handler
                        rMem[bpRef + entry.messageRegister] = ex.message
                        pc = entry.handlerPC
                        return
                    }
                }
            }

            // No match, unwind one frame
            if (csp == 0) {
                // Uncaught: propagate to host
                throw ex
            }

            // Pop frame
            csp -= 4
            bp = callStack[csp]
            bpRef = callStack[csp + 1]
            pc = callStack[csp + 2]
            currentFuncIndex = callStack[csp + 3]
            // Continue scanning in caller's context
        }
    }

    // Main execution loop
    @Suppress("LongMethod")
    private suspend fun loop() {
        val bytecode = program.bytecode

        while (running) {
            val inst = bytecode[pc++]
            val opcode = ((inst ushr 56) and 0xFF).toInt()

            // println("DUMPING MEMORY FOR PC: " + (pc - 1) );
            // dumpMemory(NoxException(NoxError.Error, "no err", pc - 1));

            // Resource guard: instruction counter
            if (++instructionCount > maxInstructions) {
                val err = checkInstructionQuota()
                if (err != null) {
                    handleException(NoxException(NoxError.QuotaExceededError, err, pc - 1))
                    continue
                }
            }

            // Resource guard: wall-clock timeout (set by watchdog thread)
            if (Thread.interrupted()) {
                handleException(
                    NoxException(
                        NoxError.TimeoutError,
                        timeoutReason ?: "Execution exceeded time limit",
                        pc - 1,
                    ),
                )
                continue
            }

            when (opcode) {
                // Integer Arithmetic

                Opcode.IADD -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, readP(b) + readIntC(Instruction.subOp(inst), c))
                }
                Opcode.ISUB -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, readP(b) - readIntC(Instruction.subOp(inst), c))
                }
                Opcode.IMUL -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, readP(b) * readIntC(Instruction.subOp(inst), c))
                }
                Opcode.IDIV -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    val divisor = readIntC(Instruction.subOp(inst), c)
                    if (divisor == 0L) {
                        handleException(NoxException(NoxError.DivisionByZeroError, "Division by zero", pc - 1))
                        continue
                    }
                    writeP(a, readP(b) / divisor)
                }
                Opcode.IMOD -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    val divisor = readIntC(Instruction.subOp(inst), c)
                    if (divisor == 0L) {
                        handleException(NoxException(NoxError.DivisionByZeroError, "Division by zero", pc - 1))
                        continue
                    }
                    writeP(a, readP(b) % divisor)
                }
                Opcode.INEG -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeP(a, -readP(b))
                }

                // Double Arithmetic

                Opcode.DADD -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, doubleToRawLongBits(longBitsToDouble(readP(b)) + readDoubleC(Instruction.subOp(inst), c)))
                }
                Opcode.DSUB -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, doubleToRawLongBits(longBitsToDouble(readP(b)) - readDoubleC(Instruction.subOp(inst), c)))
                }
                Opcode.DMUL -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, doubleToRawLongBits(longBitsToDouble(readP(b)) * readDoubleC(Instruction.subOp(inst), c)))
                }
                Opcode.DDIV -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, doubleToRawLongBits(longBitsToDouble(readP(b)) / readDoubleC(Instruction.subOp(inst), c)))
                }
                Opcode.DMOD -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, doubleToRawLongBits(longBitsToDouble(readP(b)) % readDoubleC(Instruction.subOp(inst), c)))
                }
                Opcode.DNEG -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeP(a, doubleToRawLongBits(-longBitsToDouble(readP(b))))
                }

                // Logic

                Opcode.AND -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (readP(b) != 0L && readP(c) != 0L) 1L else 0L)
                }
                Opcode.OR -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (readP(b) != 0L || readP(c) != 0L) 1L else 0L)
                }
                Opcode.NOT -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeP(a, if (readP(b) == 0L) 1L else 0L)
                }

                // Integer Comparison

                Opcode.IEQ -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (readP(b) == readIntC(Instruction.subOp(inst), c)) 1L else 0L)
                }
                Opcode.INE -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (readP(b) != readIntC(Instruction.subOp(inst), c)) 1L else 0L)
                }
                Opcode.ILT -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (readP(b) < readIntC(Instruction.subOp(inst), c)) 1L else 0L)
                }
                Opcode.ILE -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (readP(b) <= readIntC(Instruction.subOp(inst), c)) 1L else 0L)
                }
                Opcode.IGT -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (readP(b) > readIntC(Instruction.subOp(inst), c)) 1L else 0L)
                }
                Opcode.IGE -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (readP(b) >= readIntC(Instruction.subOp(inst), c)) 1L else 0L)
                }

                // Double Comparison

                Opcode.DEQ -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (longBitsToDouble(readP(b)) == readDoubleC(Instruction.subOp(inst), c)) 1L else 0L)
                }
                Opcode.DNE -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (longBitsToDouble(readP(b)) != readDoubleC(Instruction.subOp(inst), c)) 1L else 0L)
                }
                Opcode.DLT -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (longBitsToDouble(readP(b)) < readDoubleC(Instruction.subOp(inst), c)) 1L else 0L)
                }
                Opcode.DLE -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (longBitsToDouble(readP(b)) <= readDoubleC(Instruction.subOp(inst), c)) 1L else 0L)
                }
                Opcode.DGT -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (longBitsToDouble(readP(b)) > readDoubleC(Instruction.subOp(inst), c)) 1L else 0L)
                }
                Opcode.DGE -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (longBitsToDouble(readP(b)) >= readDoubleC(Instruction.subOp(inst), c)) 1L else 0L)
                }

                // String Comparison

                Opcode.SEQ -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (readR(b) == readR(c)) 1L else 0L)
                }
                Opcode.SNE -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, if (readR(b) != readR(c)) 1L else 0L)
                }

                // Data Movement

                Opcode.MOV -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeP(a, readP(b))
                }
                Opcode.MOVR -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeR(a, readR(b))
                }
                Opcode.LDC -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    when (val value = pool[b]) {
                        is Long -> writeP(a, value)
                        is Double -> writeP(a, doubleToRawLongBits(value))
                        else -> writeR(a, value) // String, etc.
                    }
                }
                Opcode.LDI -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeP(a, b.toLong())
                }
                Opcode.KILL_REF -> {
                    val a = Instruction.opA(inst)
                    writeR(a, null)
                }

                // Type Conversion

                Opcode.I2D -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeP(a, doubleToRawLongBits(readP(b).toDouble()))
                }
                Opcode.I2S -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeR(a, readP(b).toString())
                }
                Opcode.D2S -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val d = longBitsToDouble(readP(b))
                    writeR(a, if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString())
                }
                Opcode.B2S -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeR(a, if (readP(b) != 0L) "true" else "false")
                }
                Opcode.D2I -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeP(a, longBitsToDouble(readP(b)).toLong())
                }

                // Control Flow

                Opcode.JMP -> {
                    pc = Instruction.opA(inst)
                }
                Opcode.JIF -> {
                    val a = Instruction.opA(inst)
                    val target = Instruction.opB(inst)
                    if (readP(a) == 0L) pc = target
                }
                Opcode.JIT -> {
                    val a = Instruction.opA(inst)
                    val target = Instruction.opB(inst)
                    if (readP(a) == 1L) pc = target
                }

                // Function Calls

                Opcode.CALL -> {
                    val subOp = Instruction.subOp(inst)
                    val funcPoolIdx = Instruction.opA(inst)
                    val primArgStart = Instruction.opB(inst)
                    val refArgStart = Instruction.opC(inst)
                    val funcName = pool[funcPoolIdx] as String

                    // Find function by name
                    val funcIndex = program.functions.indexOfFirst { it.name == funcName }
                    if (funcIndex == -1) {
                        handleException(NoxException(NoxError.Error, "Unknown function: $funcName", pc - 1))
                        continue
                    }
                    val meta = program.functions[funcIndex]

                    // Guard: recursion limit
                    if (csp / 4 >= maxCallDepth) {
                        val err = checkCallDepth()
                        if (err != null) {
                            handleException(NoxException(NoxError.StackOverflowError, err, pc - 1))
                            continue
                        }
                    }
                    // Push frame
                    callStack[csp++] = bp
                    callStack[csp++] = bpRef
                    callStack[csp++] = pc
                    callStack[csp++] = currentFuncIndex

                    // Slide window
                    enterFunction(funcIndex, primArgStart, refArgStart)
                }

                Opcode.RET -> {
                    val subOp = Instruction.subOp(inst)
                    val isVoid = subOp == SubOp.TYPE_VOID
                    val isPrimitive = subOp == SubOp.TYPE_INT || subOp == SubOp.TYPE_DBL || subOp == SubOp.TYPE_BOOL
                    val retReg = Instruction.opC(inst)

                    if (csp == 0) {
                        // Returning from main/init function so end execution
                        running = false
                        if (!isVoid) returnValue = valueToStringSubOp(subOp, retReg)
                        return
                    }

                    // Copy return value to frame base (bp) before popping.
                    if (!isVoid) {
                        if (isPrimitive) {
                            writeP(0, readP(retReg))
                        } else {
                            writeR(0, readR(retReg))
                        }
                    }

                    // Pop frame
                    csp -= 4
                    bp = callStack[csp]
                    bpRef = callStack[csp + 1]
                    pc = callStack[csp + 2]
                    currentFuncIndex = callStack[csp + 3]
                }

                // System Calls

                Opcode.SCALL -> {
                    val subOp = Instruction.subOp(inst)
                    val funcPoolIdx = Instruction.opA(inst)
                    val primArgStart = Instruction.opB(inst)
                    val refArgStart = Instruction.opC(inst)

                    val nativeFunc =
                        scallCache[funcPoolIdx]
                            ?: throw NoxException(
                                NoxError.Error,
                                "Unknown system function: ${pool[funcPoolIdx]}",
                                pc - 1,
                            )

                    val pOffset = if (subOp == 1) 1 else 0
                    val rOffset = if (subOp == 0) 1 else 0

                    val localDest = if (subOp == 1) primArgStart else refArgStart

                    try {
                        nativeFunc.invoke(
                            context,
                            pMem,
                            rMem,
                            bp,
                            bpRef,
                            primArgStart + pOffset,
                            refArgStart + rOffset,
                            localDest,
                        )
                    } catch (e: NoxException) {
                        handleException(e)
                    } catch (_: InterruptedException) {
                        Thread.interrupted()
                        throw NoxException(NoxError.TimeoutError, timeoutReason, pc - 1)
                    } catch (t: Throwable) {
                        handleException(NoxException(classifyException(t), t.message, pc - 1))
                    }
                }

                // String Concatenation

                Opcode.SCONCAT -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeR(a, (readR(b) as? String ?: "") + (readR(c) as? String ?: ""))
                }

                // Host Super-Instructions

                Opcode.HACC -> {
                    val subOp = Instruction.subOp(inst)
                    val dest = Instruction.opA(inst)
                    val objReg = Instruction.opB(inst)
                    val keyPoolIdx = Instruction.opC(inst)
                    val obj =
                        readR(objReg) as? LinkedHashMap<*, *>
                            ?: throw NoxException(NoxError.NullAccessError, "Cannot read property of null", pc - 1)

                    val key = pool[keyPoolIdx] as String
                    readSubOpGet(subOp, dest, obj[key])
                }

                Opcode.HMOD -> {
                    val subOp = Instruction.subOp(inst)
                    val objReg = Instruction.opA(inst)
                    val keyPoolIdx = Instruction.opB(inst)
                    val valReg = Instruction.opC(inst)

                    @Suppress("UNCHECKED_CAST")
                    val obj =
                        readR(objReg) as? LinkedHashMap<String, Any?>
                            ?: throw NoxException(NoxError.NullAccessError, "Cannot set property of null", pc - 1)
                    val key = pool[keyPoolIdx] as String
                    obj[key] = readValueBySubOp(subOp, valReg)
                }

                // Access Instructions

                Opcode.AGET_KEY -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    val subOp = Instruction.subOp(inst)
                    val obj =
                        readR(b) as? Map<*, *>
                            ?: throw NoxException(NoxError.NullAccessError, "Cannot access property of null", pc - 1)
                    val key = pool[c] as String
                    readSubOpGet(subOp, a, obj[key])
                }

                Opcode.AGET_IDX -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    val subOp = Instruction.subOp(inst)
                    val container = readR(b)

                    val value =
                        when (container) {
                            is List<*> -> {
                                val index = readP(c).toInt()
                                if (index < 0 || index >= container.size) {
                                    throw NoxException(
                                        NoxError.IndexOutOfBoundsError,
                                        "Index $index out of bounds for length ${container.size}",
                                        pc - 1,
                                    )
                                }
                                container[index]
                            }
                            is Map<*, *> -> {
                                val key = readR(c)
                                container[key.toString()]
                            }
                            else -> throw NoxException(
                                NoxError.TypeError,
                                "Cannot index into ${container?.javaClass?.simpleName}",
                                pc - 1,
                            )
                        }

                    readSubOpGet(subOp, a, value)
                }

                Opcode.AGET_PATH -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    val subOp = Instruction.subOp(inst)
                    val root = readR(b)
                    val pathStr = pool[c] as String
                    val segments = pathStr.split(".")
                    var current: Any? = root
                    for (segment in segments) {
                        current =
                            when (current) {
                                is Map<*, *> -> current[segment]
                                else -> {
                                    handleException(
                                        NoxException(
                                            NoxError.NullAccessError,
                                            "Cannot access '$segment' on ${current?.javaClass?.simpleName}",
                                            pc - 1,
                                        ),
                                    )
                                    continue
                                } // TODO: improve error message with potential misspellings
                            }
                    }
                    readSubOpGet(subOp, a, current)
                }

                Opcode.ASET_KEY -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)

                    @Suppress("UNCHECKED_CAST")
                    val obj =
                        readR(a) as? MutableMap<String, Any?>
                            ?: throw NoxException(NoxError.NullAccessError, "Cannot set property of null", pc - 1)
                    val key = pool[b] as String
                    val subOp = Instruction.subOp(inst)
                    obj[key] = readValueBySubOp(subOp, c)
                }

                Opcode.ASET_IDX -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    val subOp = Instruction.subOp(inst)

                    @Suppress("UNCHECKED_CAST")
                    val list =
                        readR(a) as? MutableList<Any?>
                            ?: run {
                                handleException(
                                    NoxException(
                                        NoxError.TypeError,
                                        "Cannot index-assign to non-array",
                                        pc - 1,
                                    ),
                                )
                                continue
                            }
                    val index = readP(b).toInt()
                    if (index < 0 || index >= list.size) {
                        throw NoxException(
                            NoxError.IndexOutOfBoundsError,
                            "Index $index out of bounds for length ${list.size}",
                            pc - 1,
                        )
                    }
                    list[index] = readValueBySubOp(subOp, c)
                }
                // Yield

                Opcode.YIELD -> {
                    val a = Instruction.opA(inst)
                    val subOp = Instruction.subOp(inst)
                    val value = valueToStringSubOp(subOp, a)
                    context.yield(value)
                }

                // Increment / Decrement

                Opcode.IINC -> {
                    val a = Instruction.opA(inst)
                    writeP(a, readP(a) + 1)
                }
                Opcode.IDEC -> {
                    val a = Instruction.opA(inst)
                    writeP(a, readP(a) - 1)
                }
                Opcode.IINCN -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeP(a, readP(a) + readP(b))
                }
                Opcode.IDECN -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeP(a, readP(a) - readP(b))
                }
                Opcode.DINC -> {
                    val a = Instruction.opA(inst)
                    writeP(a, doubleToRawLongBits(longBitsToDouble(readP(a)) + 1.0))
                }
                Opcode.DDEC -> {
                    val a = Instruction.opA(inst)
                    writeP(a, doubleToRawLongBits(longBitsToDouble(readP(a)) - 1.0))
                }
                Opcode.DINCN -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeP(a, doubleToRawLongBits(longBitsToDouble(readP(a)) + longBitsToDouble(readP(b))))
                }
                Opcode.DDECN -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeP(a, doubleToRawLongBits(longBitsToDouble(readP(a)) - longBitsToDouble(readP(b))))
                }

                // Bitwise

                Opcode.BAND -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, readP(b) and readIntC(Instruction.subOp(inst), c))
                }
                Opcode.BOR -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, readP(b) or readIntC(Instruction.subOp(inst), c))
                }
                Opcode.BXOR -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, readP(b) xor readIntC(Instruction.subOp(inst), c))
                }
                Opcode.BNOT -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    writeP(a, readP(b).inv())
                }
                Opcode.SHL -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, readP(b) shl readIntC(Instruction.subOp(inst), c).toInt())
                }
                Opcode.SHR -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, readP(b) shr readIntC(Instruction.subOp(inst), c).toInt())
                }
                Opcode.USHR -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    writeP(a, readP(b) ushr readIntC(Instruction.subOp(inst), c).toInt())
                }

                // Exception Handling

                Opcode.THROW -> {
                    val a = Instruction.opA(inst)
                    val msg = readR(a) as? String ?: "Unknown error"
                    handleException(NoxException(NoxError.Error, msg, pc - 1))
                }

                Opcode.KILL -> {
                    running = false
                    return
                }

                // Array Construction

                Opcode.NEW_ARRAY -> {
                    val a = Instruction.opA(inst)
                    writeR(a, ArrayList<Any?>())
                }

                Opcode.ARR_PUSH -> {
                    val a = Instruction.opA(inst)
                    val c = Instruction.opC(inst)
                    val subOp = Instruction.subOp(inst)

                    @Suppress("UNCHECKED_CAST")
                    val list =
                        readR(a) as? MutableList<Any?>
                            ?: throw NoxException(NoxError.TypeError, "Cannot push to non-array", pc - 1)
                    list.add(readValueBySubOp(subOp, c))
                }

                // Struct Construction

                Opcode.NEW_OBJ -> {
                    val a = Instruction.opA(inst)
                    writeR(a, LinkedHashMap<String, Any?>())
                }

                Opcode.OBJ_SET -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    val subOp = Instruction.subOp(inst)

                    @Suppress("UNCHECKED_CAST")
                    val obj =
                        readR(a) as? LinkedHashMap<String, Any?>
                            ?: run {
                                handleException(
                                    NoxException(
                                        NoxError.TypeError,
                                        "Cannot set property on non-object",
                                        pc - 1,
                                    ),
                                )
                                continue
                            }
                    val key = pool[b] as String
                    obj[key] = readValueBySubOp(subOp, c)
                }

                Opcode.CAST_STRUCT -> {
                    val a = Instruction.opA(inst)
                    val b = Instruction.opB(inst)
                    val c = Instruction.opC(inst)
                    val subOp = Instruction.subOp(inst)

                    val value = readR(b)
                    val descriptor = pool[c] as TypeDescriptor

                    try {
                        if (subOp == 1) { // Typed array
                            if (value !is List<*>) {
                                throw NoxException(
                                    NoxError.CastError,
                                    "Expected array of '${descriptor.name}', but got ${value?.let {
                                        it::class.simpleName
                                    } ?: "null"}",
                                    pc - 1,
                                )
                            }
                            for ((i, item) in value.withIndex()) {
                                validateStruct(item, descriptor, "$[$i]", pc - 1)
                            }
                        } else { // Struct
                            validateStruct(value, descriptor, "", pc - 1)
                        }

                        writeR(a, value)
                    } catch (e: NoxException) {
                        handleException(e)
                        continue
                    }
                }

                // Unknown Opcode

                else -> {
                    handleException(
                        NoxException(
                            NoxError.Error,
                            "Unknown opcode: 0x${opcode.toString(16).padStart(2, '0')} at pc=${pc - 1}",
                            pc - 1,
                        ),
                    )
                    continue
                }
            }
        }
    }

    private fun validateStruct(
        value: Any?,
        desc: TypeDescriptor,
        path: String,
        pc: Int,
    ) {
        if (value !is LinkedHashMap<*, *>) {
            val typeStr = value?.let { it::class.simpleName } ?: "null"
            val prefix = if (path.isEmpty()) "" else "at '$path': "
            throw NoxException(NoxError.CastError, "${prefix}Expected struct '${desc.name}', but got $typeStr", pc)
        }
        for ((key, spec) in desc.fields) {
            val fieldPath = if (path.isEmpty()) key else "$path.$key"
            if (!value.containsKey(key)) {
                throw NoxException(
                    NoxError.CastError,
                    "Missing required field '$fieldPath' for struct '${desc.name}'",
                    pc,
                )
            }
            val fieldValue = value[key]
            if (fieldValue == null) {
                throw NoxException(NoxError.CastError, "Field '$fieldPath' cannot be null", pc)
            }
            validateField(fieldValue, spec, fieldPath, pc)
        }
    }

    private fun validateField(
        value: Any,
        spec: FieldSpec,
        path: String,
        pc: Int,
    ) {
        when (spec) {
            is FieldSpec.INT -> {
                if (value !is Long) {
                    throw NoxException(
                        NoxError.CastError,
                        "Expected int at '$path', but got ${value::class.simpleName}",
                        pc,
                    )
                }
            }
            is FieldSpec.DOUBLE -> {
                if (value !is Double &&
                    value !is Long
                ) {
                    throw NoxException(
                        NoxError.CastError,
                        "Expected double at '$path', but got ${value::class.simpleName}",
                        pc,
                    )
                }
            }
            is FieldSpec.BOOLEAN -> {
                if (value !is Boolean) {
                    throw NoxException(
                        NoxError.CastError,
                        "Expected boolean at '$path', but got ${value::class.simpleName}",
                        pc,
                    )
                }
            }
            is FieldSpec.STRING -> {
                if (value !is String) {
                    throw NoxException(
                        NoxError.CastError,
                        "Expected string at '$path', but got ${value::class.simpleName}",
                        pc,
                    )
                }
            }
            is FieldSpec.JSON -> {
                // Any non-null value is valid for json fields
            }
            is FieldSpec.Struct -> {
                val nestedDesc = program.constantPool[spec.descriptorIdx] as TypeDescriptor
                validateStruct(value, nestedDesc, path, pc)
            }
            is FieldSpec.TypedArray -> {
                if (value !is List<*>) {
                    throw NoxException(
                        NoxError.CastError,
                        "Expected array at '$path', but got ${value::class.simpleName}",
                        pc,
                    )
                }
                for ((i, item) in value.withIndex()) {
                    if (item ==
                        null
                    ) {
                        throw NoxException(NoxError.CastError, "Array element at '$path[$i]' cannot be null", pc)
                    }
                    validateField(item, spec.element, "$path[$i]", pc)
                }
            }
        }
    }

    private fun dumpMemory(ex: NoxException) {
        System.err.println("\n=== NOX MEMORY DUMP (UNCAUGHT EXCEPTION) ===")
        System.err.println("Exception: ${ex.type} - ${ex.message} at pc=${ex.pc}")

        // Globals
        System.err.println("\nGlobals:")
        for (module in program.modules) {
            val ns = module.namespace
            val base = module.globalBaseOffset
            if (module.globalPrimitiveCount > 0 || module.globalReferenceCount > 0) {
                System.err.println("  Module: $ns")
                for (i in 0 until module.globalPrimitiveCount) {
                    System.err.println("    gP$i: ${gMem[base + i]}")
                }
                for (i in 0 until module.globalReferenceCount) {
                    System.err.println("    gR$i: ${gMemRef[base + i] ?: "null"}")
                }
            }
        }

        // Stack Trace
        System.err.println("\nStack Frames (Bottom to Top):")

        // Helper to dump a frame
        fun dumpFrame(
            frameBp: Int,
            frameBpRef: Int,
            framePc: Int,
            fIdx: Int,
            isTop: Boolean,
        ) {
            val meta = program.functions[fIdx]
            val suffix = if (isTop) " [EXCEPTION SOURCE]" else " (Return PC)"
            System.err.println("  # ${meta.name} @ pc $framePc$suffix")

            // Resolve names from events
            val pNames = mutableMapOf<Int, String>()
            val rNames = mutableMapOf<Int, String>()
            val localPc = framePc - meta.entryPC
            for (event in meta.regNameEvents) {
                if (event.localPC <= localPc) {
                    if (event.isPrim) {
                        pNames[event.register] = event.name
                    } else {
                        rNames[event.register] = event.name
                    }
                }
            }

            System.err.println("    Primitives:")
            for (i in 0 until meta.primitiveFrameSize) {
                val varName = pNames[i]?.let { " ($it)" } ?: ""
                System.err.println("      p$i$varName: ${pMem[frameBp + i]}")
            }
            System.err.println("    References:")
            for (i in 0 until meta.referenceFrameSize) {
                val varName = rNames[i]?.let { " ($it)" } ?: ""
                System.err.println("      r$i$varName: ${rMem[frameBpRef + i]}")
            }
        }

        // Walk callStack
        for (i in 0 until csp step 4) {
            dumpFrame(callStack[i], callStack[i + 1], callStack[i + 2], callStack[i + 3], false)
        }

        // Top frame
        if (currentFuncIndex != -1) {
            dumpFrame(bp, bpRef, ex.pc, currentFuncIndex, true)
        }

        System.err.println("============================================\n")
    }
}
