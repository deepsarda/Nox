package nox.plugin.external

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import nox.plugin.LibraryRegistry
import nox.plugin.NoxNativeFunc
import nox.runtime.json.NoxJsonParser
import nox.runtime.json.NoxJsonWriter

interface YieldCallback : Callback {
    fun callback(
        contextId: Long,
        dataPtr: Pointer?,
    )
}

object ExternalPluginBridge {
    private val activeContexts = java.util.concurrent.ConcurrentHashMap<Long, nox.runtime.RuntimeContext>()
    private val nextContextId =
        java.util.concurrent.atomic
            .AtomicLong(1)

    class YieldCallbackImpl : YieldCallback {
        override fun callback(
            contextId: Long,
            dataPtr: Pointer?,
        ) {
            val ctx = activeContexts[contextId] ?: return
            val data = dataPtr?.getString(0) ?: return
            ctx.yield(data)
        }
    }

    private val yieldCallback = YieldCallbackImpl()
    private val yieldFuncPtr: Pointer = CallbackReference.getFunctionPointer(yieldCallback)

    private val loadedLibraries = mutableListOf<NativeLibrary>()

    fun loadPlugin(
        libraryPath: String,
        registry: LibraryRegistry,
    ) {
        val jnaLibrary = NativeLibrary.getInstance(libraryPath)
        loadedLibraries.add(jnaLibrary)

        val initFunc = jnaLibrary.getFunction("nox_plugin_init")
        val manifestPtr =
            initFunc.invokePointer(emptyArray())
                ?: throw IllegalStateException("nox_plugin_init returned NULL")

        val namespaceStr = manifestPtr.getPointer(0).getString(0)
        val funcCount = manifestPtr.getInt(8)
        val funcsArrayPtr = manifestPtr.getPointer(16)

        val externalFuncs = mutableListOf<NoxExternalFunc>()

        for (i in 0 until funcCount) {
            val funcPtr = funcsArrayPtr.share(i * 40L)

            val name = funcPtr.getPointer(0).getString(0)
            val paramCount = funcPtr.getInt(8)
            val paramTypesPtr = funcPtr.getPointer(16)
            val paramTypes = mutableListOf<NoxTypeTag>()
            for (j in 0 until paramCount) {
                val tagInt = paramTypesPtr.getInt(j * 4L)
                paramTypes.add(NoxTypeTag.entries.first { it.ordinal == tagInt })
            }
            val returnType = NoxTypeTag.entries.first { it.ordinal == funcPtr.getInt(24) }
            val nativeFuncPtr = funcPtr.getPointer(32)

            val jnaFunc = Function.getFunction(nativeFuncPtr)

            val scallName = "${namespaceStr}__$name"
            val noxNativeFunc = createFunc(jnaFunc, paramTypes, returnType)
            registry.registerNativeFunc(scallName, noxNativeFunc)

            val apiParamTypes = paramTypes.map { NoxTypeTag.entries[it.ordinal] }
            val apiReturnType = NoxTypeTag.entries[returnType.ordinal]
            externalFuncs.add(NoxExternalFunc(name, apiParamTypes, apiReturnType))
        }

        val manifest = NoxPluginManifest(namespaceStr, externalFuncs)
        registry.registerExternalPlugin(manifest)
    }

    private fun createFunc(
        jnaFunc: Function,
        paramTypes: List<NoxTypeTag>,
        returnType: NoxTypeTag,
    ): NoxNativeFunc =
        NoxNativeFunc { context, pMem, rMem, bp, bpRef, primArgStart, refArgStart, destReg ->
            val contextId = nextContextId.getAndIncrement()
            activeContexts[contextId] = context

            val ctxStruct = Memory(16)
            ctxStruct.setLong(0, contextId)
            ctxStruct.setPointer(8, yieldFuncPtr)

            val args = arrayOfNulls<Any>(paramTypes.size + 1)
            args[0] = ctxStruct

            var pIdx = 0
            var rIdx = 0

            val memoryToFree = mutableListOf<Memory>()

            try {
                for ((i, pt) in paramTypes.withIndex()) {
                    val argIndex = i + 1
                    when (pt) {
                        NoxTypeTag.INT -> {
                            args[argIndex] = pMem[bp + primArgStart + pIdx]
                            pIdx++
                        }
                        NoxTypeTag.DOUBLE -> {
                            args[argIndex] = java.lang.Double.longBitsToDouble(pMem[bp + primArgStart + pIdx])
                            pIdx++
                        }
                        NoxTypeTag.BOOLEAN -> {
                            args[argIndex] = pMem[bp + primArgStart + pIdx] != 0L
                            pIdx++
                        }
                        NoxTypeTag.STRING -> {
                            val str = rMem[bpRef + refArgStart + rIdx] as String
                            val strBytes = str.toByteArray(Charsets.UTF_8)
                            val strMem = Memory((strBytes.size + 1).toLong())
                            strMem.write(0, strBytes, 0, strBytes.size)
                            strMem.setByte(strBytes.size.toLong(), 0)
                            memoryToFree.add(strMem)
                            args[argIndex] = strMem
                            rIdx++
                        }
                        NoxTypeTag.JSON -> {
                            val value = rMem[bpRef + refArgStart + rIdx]
                            val jsonStr = NoxJsonWriter(prettyPrint = false).write(value)
                            val strBytes = jsonStr.toByteArray(Charsets.UTF_8)
                            val strMem = Memory((strBytes.size + 1).toLong())
                            strMem.write(0, strBytes, 0, strBytes.size)
                            strMem.setByte(strBytes.size.toLong(), 0)
                            memoryToFree.add(strMem)
                            args[argIndex] = strMem
                            rIdx++
                        }
                        NoxTypeTag.INT_ARRAY -> {
                            @Suppress("UNCHECKED_CAST")
                            val list = rMem[bpRef + refArgStart + rIdx] as List<Long>
                            val cArray = Memory(list.size * 8L)
                            for (j in list.indices) cArray.setLong(j * 8L, list[j])
                            memoryToFree.add(cArray)
                            args[argIndex] = cArray
                            rIdx++
                        }
                        NoxTypeTag.DOUBLE_ARRAY -> {
                            @Suppress("UNCHECKED_CAST")
                            val list = rMem[bpRef + refArgStart + rIdx] as List<Double>
                            val cArray = Memory(list.size * 8L)
                            for (j in list.indices) cArray.setDouble(j * 8L, list[j])
                            memoryToFree.add(cArray)
                            args[argIndex] = cArray
                            rIdx++
                        }
                        NoxTypeTag.STRING_ARRAY -> {
                            @Suppress("UNCHECKED_CAST")
                            val list = rMem[bpRef + refArgStart + rIdx] as List<String>
                            val cArray = Memory(list.size * 8L)
                            for (j in list.indices) {
                                val strBytes = list[j].toByteArray(Charsets.UTF_8)
                                val strMem = Memory((strBytes.size + 1).toLong())
                                strMem.write(0, strBytes, 0, strBytes.size)
                                strMem.setByte(strBytes.size.toLong(), 0)
                                memoryToFree.add(strMem)
                                cArray.setPointer(j * 8L, strMem)
                            }
                            memoryToFree.add(cArray)
                            args[argIndex] = cArray
                            rIdx++
                        }
                        NoxTypeTag.VOID -> {
                            throw IllegalStateException("VOID cannot be a parameter type")
                        }
                    }
                }

                when (returnType) {
                    NoxTypeTag.INT -> {
                        pMem[bp + destReg] = (jnaFunc.invoke(Long::class.java, args) as Number).toLong()
                    }
                    NoxTypeTag.DOUBLE -> {
                        pMem[bp + destReg] =
                            java.lang.Double.doubleToRawLongBits(
                                (jnaFunc.invoke(Double::class.java, args) as Number).toDouble(),
                            )
                    }
                    NoxTypeTag.BOOLEAN -> {
                        val result = jnaFunc.invoke(Boolean::class.java, args) as Boolean
                        pMem[bp + destReg] = if (result) 1L else 0L
                    }
                    NoxTypeTag.STRING -> {
                        val resultPtr = jnaFunc.invoke(Pointer::class.java, args) as Pointer?
                        rMem[bpRef + destReg] = resultPtr?.getString(0) ?: ""
                    }
                    NoxTypeTag.JSON -> {
                        val resultPtr = jnaFunc.invoke(Pointer::class.java, args) as Pointer?
                        val cStr = resultPtr?.getString(0) ?: "null"
                        rMem[bpRef + destReg] = NoxJsonParser(cStr).parse()
                    }
                    NoxTypeTag.INT_ARRAY, NoxTypeTag.DOUBLE_ARRAY, NoxTypeTag.STRING_ARRAY -> {
                        throw UnsupportedOperationException(
                            "Returning arrays from C is not safely supported without a length protocol.",
                        ) // TODO: Implement a safe protocol for returning arrays (e.g. return struct)
                    }
                    NoxTypeTag.VOID -> {
                        jnaFunc.invoke(Void::class.java, args)
                    }
                }
            } finally {
                activeContexts.remove(contextId)
            }
        }
}
