package nox.plugin.external

import nox.plugin.LibraryRegistry
import nox.plugin.NoxNativeFunc
import nox.runtime.json.NoxJsonParser
import nox.runtime.json.NoxJsonWriter
import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Path

object ExternalPluginBridge {
    private val activeContexts = java.util.concurrent.ConcurrentHashMap<Long, nox.runtime.RuntimeContext>()
    private val nextContextId = java.util.concurrent.atomic.AtomicLong(1)

    private val yieldCallbackHandle: MethodHandle = MethodHandles.lookup().findStatic(
        ExternalPluginBridge::class.java,
        "yieldCallback",
        MethodType.methodType(Void.TYPE, Long::class.javaPrimitiveType, MemorySegment::class.java)
    )

    private val yieldFuncStub: MemorySegment = Linker.nativeLinker().upcallStub(
        yieldCallbackHandle,
        FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
        Arena.global()
    )

    @JvmStatic
    private fun yieldCallback(contextId: Long, dataPtr: MemorySegment) {
        val ctx = activeContexts[contextId] ?: return
        if (dataPtr == MemorySegment.NULL) return
        val data = dataPtr.reinterpret(Long.MAX_VALUE).getString(0)
        ctx.yield(data)
    }

    fun loadPlugin(
        libraryPath: String,
        registry: LibraryRegistry,
    ) {
        val lib = SymbolLookup.libraryLookup(Path.of(libraryPath), Arena.global())

        val initFuncAddr = lib.find("nox_plugin_init").orElseThrow {
            IllegalStateException("nox_plugin_init symbol not found")
        }

        val initHandle = Linker.nativeLinker().downcallHandle(
            initFuncAddr,
            FunctionDescriptor.of(ValueLayout.ADDRESS)
        )

        val manifestPtr = initHandle.invokeExact() as MemorySegment
        if (manifestPtr == MemorySegment.NULL) {
            throw IllegalStateException("nox_plugin_init returned NULL")
        }

        // Reinterpret the manifest pointer so we can read its contents
        // Size: 24 bytes minimum (8 for string ptr, 4 for int, 4 padding, 8 for array ptr)
        // We reinterpret to Long.MAX_VALUE safely since we know what we're reading.
        val manifestMem = manifestPtr.reinterpret(Long.MAX_VALUE)

        val namespaceStr = manifestMem.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE).getString(0)
        val funcCount = manifestMem.get(ValueLayout.JAVA_INT, 8)
        val funcsArrayPtr = manifestMem.get(ValueLayout.ADDRESS, 16).reinterpret(Long.MAX_VALUE)

        val externalFuncs = mutableListOf<NoxExternalFunc>()

        for (i in 0 until funcCount) {
            val funcPtr = funcsArrayPtr.asSlice(i * 40L, 40L)

            val name = funcPtr.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE).getString(0)
            val paramCount = funcPtr.get(ValueLayout.JAVA_INT, 8)
            val paramTypesPtr = funcPtr.get(ValueLayout.ADDRESS, 16).reinterpret(Long.MAX_VALUE)
            val paramTypes = mutableListOf<NoxTypeTag>()
            for (j in 0 until paramCount) {
                val tagInt = paramTypesPtr.get(ValueLayout.JAVA_INT, j * 4L)
                paramTypes.add(NoxTypeTag.entries.first { it.ordinal == tagInt })
            }
            val returnTypeTagInt = funcPtr.get(ValueLayout.JAVA_INT, 24L)
            val returnType = NoxTypeTag.entries.first { it.ordinal == returnTypeTagInt }
            val nativeFuncPtr = funcPtr.get(ValueLayout.ADDRESS, 32)

            val scallName = "${namespaceStr}__$name"
            val noxNativeFunc = createFunc(nativeFuncPtr, paramTypes, returnType)
            registry.registerNativeFunc(scallName, noxNativeFunc)

            val apiParamTypes = paramTypes.map { NoxTypeTag.entries[it.ordinal] }
            val apiReturnType = NoxTypeTag.entries[returnType.ordinal]
            externalFuncs.add(NoxExternalFunc(name, apiParamTypes, apiReturnType))
        }

        val manifest = NoxPluginManifest(namespaceStr, externalFuncs)
        registry.registerExternalPlugin(manifest)
    }

    private fun createFunc(
        nativeFuncAddr: MemorySegment,
        paramTypes: List<NoxTypeTag>,
        returnType: NoxTypeTag,
    ): NoxNativeFunc {
        
        // Build the FunctionDescriptor
        // First parameter is ALWAYS the NoxContext pointer
        val argLayouts = mutableListOf<MemoryLayout>(ValueLayout.ADDRESS)
        
        for (pt in paramTypes) {
            when (pt) {
                NoxTypeTag.INT -> argLayouts.add(ValueLayout.JAVA_LONG)
                NoxTypeTag.DOUBLE -> argLayouts.add(ValueLayout.JAVA_DOUBLE)
                NoxTypeTag.BOOLEAN -> argLayouts.add(ValueLayout.JAVA_BOOLEAN)
                NoxTypeTag.STRING -> argLayouts.add(ValueLayout.ADDRESS)
                NoxTypeTag.JSON -> argLayouts.add(ValueLayout.ADDRESS)
                NoxTypeTag.INT_ARRAY -> argLayouts.add(ValueLayout.ADDRESS)
                NoxTypeTag.DOUBLE_ARRAY -> argLayouts.add(ValueLayout.ADDRESS)
                NoxTypeTag.STRING_ARRAY -> argLayouts.add(ValueLayout.ADDRESS)
                NoxTypeTag.VOID -> throw IllegalStateException("VOID cannot be a parameter type")
            }
        }
        
        val returnLayout = when (returnType) {
            NoxTypeTag.INT -> ValueLayout.JAVA_LONG
            NoxTypeTag.DOUBLE -> ValueLayout.JAVA_DOUBLE
            NoxTypeTag.BOOLEAN -> ValueLayout.JAVA_BOOLEAN
            NoxTypeTag.STRING -> ValueLayout.ADDRESS
            NoxTypeTag.JSON -> ValueLayout.ADDRESS
            NoxTypeTag.INT_ARRAY, NoxTypeTag.DOUBLE_ARRAY, NoxTypeTag.STRING_ARRAY -> ValueLayout.ADDRESS
            NoxTypeTag.VOID -> null
        }

        val descriptor = if (returnLayout != null) {
            FunctionDescriptor.of(returnLayout, *argLayouts.toTypedArray())
        } else {
            FunctionDescriptor.ofVoid(*argLayouts.toTypedArray())
        }

        val targetHandle = Linker.nativeLinker().downcallHandle(nativeFuncAddr, descriptor)

        return NoxNativeFunc { context, pMem, rMem, bp, bpRef, primArgStart, refArgStart, destReg ->
            val contextId = nextContextId.getAndIncrement()
            activeContexts[contextId] = context

            // Use an arena to manage memory for this function call
            Arena.ofConfined().use { arena ->
                val ctxStruct = arena.allocate(16L)
                ctxStruct.set(ValueLayout.JAVA_LONG, 0, contextId)
                ctxStruct.set(ValueLayout.ADDRESS, 8, yieldFuncStub)

                val invokeArgs = arrayOfNulls<Any>(paramTypes.size + 1)
                invokeArgs[0] = ctxStruct

                var pIdx = 0
                var rIdx = 0

                for ((i, pt) in paramTypes.withIndex()) {
                    val argIndex = i + 1
                    when (pt) {
                        NoxTypeTag.INT -> {
                            invokeArgs[argIndex] = pMem[bp + primArgStart + pIdx]
                            pIdx++
                        }
                        NoxTypeTag.DOUBLE -> {
                            invokeArgs[argIndex] = java.lang.Double.longBitsToDouble(pMem[bp + primArgStart + pIdx])
                            pIdx++
                        }
                        NoxTypeTag.BOOLEAN -> {
                            invokeArgs[argIndex] = pMem[bp + primArgStart + pIdx] != 0L
                            pIdx++
                        }
                        NoxTypeTag.STRING -> {
                            val str = rMem[bpRef + refArgStart + rIdx] as String
                            invokeArgs[argIndex] = arena.allocateFrom(str)
                            rIdx++
                        }
                        NoxTypeTag.JSON -> {
                            val value = rMem[bpRef + refArgStart + rIdx]
                            val jsonStr = NoxJsonWriter(prettyPrint = false).write(value)
                            invokeArgs[argIndex] = arena.allocateFrom(jsonStr)
                            rIdx++
                        }
                        NoxTypeTag.INT_ARRAY -> {
                            @Suppress("UNCHECKED_CAST")
                            val list = rMem[bpRef + refArgStart + rIdx] as List<Long>
                            val cArray = arena.allocate(ValueLayout.JAVA_LONG, list.size.toLong())
                            for (j in list.indices) cArray.setAtIndex(ValueLayout.JAVA_LONG, j.toLong(), list[j])
                            invokeArgs[argIndex] = cArray
                            rIdx++
                        }
                        NoxTypeTag.DOUBLE_ARRAY -> {
                            @Suppress("UNCHECKED_CAST")
                            val list = rMem[bpRef + refArgStart + rIdx] as List<Double>
                            val cArray = arena.allocate(ValueLayout.JAVA_DOUBLE, list.size.toLong())
                            for (j in list.indices) cArray.setAtIndex(ValueLayout.JAVA_DOUBLE, j.toLong(), list[j])
                            invokeArgs[argIndex] = cArray
                            rIdx++
                        }
                        NoxTypeTag.STRING_ARRAY -> {
                            @Suppress("UNCHECKED_CAST")
                            val list = rMem[bpRef + refArgStart + rIdx] as List<String>
                            val cArray = arena.allocate(ValueLayout.ADDRESS, list.size.toLong())
                            for (j in list.indices) {
                                val strMem = arena.allocateFrom(list[j])
                                cArray.setAtIndex(ValueLayout.ADDRESS, j.toLong(), strMem)
                            }
                            invokeArgs[argIndex] = cArray
                            rIdx++
                        }
                        NoxTypeTag.VOID -> {
                            throw IllegalStateException("VOID cannot be a parameter type")
                        }
                    }
                }

                try {
                    val resultObj = targetHandle.invokeWithArguments(*invokeArgs)
                    
                    when (returnType) {
                        NoxTypeTag.INT -> {
                            pMem[bp + destReg] = (resultObj as Number).toLong()
                        }
                        NoxTypeTag.DOUBLE -> {
                            pMem[bp + destReg] = java.lang.Double.doubleToRawLongBits((resultObj as Number).toDouble())
                        }
                        NoxTypeTag.BOOLEAN -> {
                            pMem[bp + destReg] = if (resultObj as Boolean) 1L else 0L
                        }
                        NoxTypeTag.STRING -> {
                            val resMem = resultObj as MemorySegment
                            rMem[bpRef + destReg] = if (resMem == MemorySegment.NULL) "" else resMem.reinterpret(Long.MAX_VALUE).getString(0)
                        }
                        NoxTypeTag.JSON -> {
                            val resMem = resultObj as MemorySegment
                            val cStr = if (resMem == MemorySegment.NULL) "null" else resMem.reinterpret(Long.MAX_VALUE).getString(0)
                            rMem[bpRef + destReg] = NoxJsonParser(cStr).parse()
                        }
                        NoxTypeTag.INT_ARRAY, NoxTypeTag.DOUBLE_ARRAY, NoxTypeTag.STRING_ARRAY -> {
                            throw UnsupportedOperationException("Returning arrays from C is not safely supported without a length protocol.")
                        }
                        NoxTypeTag.VOID -> {
                            // No-op
                        }
                    }
                } finally {
                    activeContexts.remove(contextId)
                }
            }
        }
    }
}
