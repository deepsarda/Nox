package nox.plugin.external

import nox.plugin.LibraryRegistry
import nox.plugin.NoxNativeFunc
import nox.runtime.json.NoxJsonParser
import nox.runtime.json.NoxJsonWriter
import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

object ExternalPluginBridge {
    private val activeContexts = java.util.concurrent.ConcurrentHashMap<Long, nox.runtime.RuntimeContext>()
    private val nextContextId =
        java.util.concurrent.atomic
            .AtomicLong(1)

    @JvmStatic
    fun yieldUpcall(
        contextId: Long,
        dataPtr: MemorySegment,
    ) {
        val ctx = activeContexts[contextId] ?: return
        val data = dataPtr.reinterpret(Long.MAX_VALUE).getString(0)
        ctx.yield(data)
    }

    private val yieldStub: MemorySegment =
        Linker.nativeLinker().upcallStub(
            MethodHandles.lookup().findStatic(
                ExternalPluginBridge::class.java,
                "yieldUpcall",
                MethodType.methodType(Void.TYPE, Long::class.javaPrimitiveType, MemorySegment::class.java),
            ),
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
            Arena.global(),
        )

    private val NOX_CONTEXT_LAYOUT =
        MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("internal_id"),
            ValueLayout.ADDRESS.withName("yield_func"),
        )

    private val NOX_PLUGIN_FUNC_LAYOUT =
        MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("name"),
            ValueLayout.JAVA_INT.withName("param_count"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("param_types"),
            ValueLayout.JAVA_INT.withName("return_type"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("func_ptr"),
        )

    private val NOX_PLUGIN_MANIFEST_LAYOUT =
        MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("namespace"),
            ValueLayout.JAVA_INT.withName("func_count"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("functions"),
        )

    fun loadPlugin(
        libraryPath: String,
        registry: LibraryRegistry,
    ) {
        val arena = Arena.global() // For library loading and symbols
        val linker = Linker.nativeLinker()

        val symbolLookup = SymbolLookup.libraryLookup(libraryPath, arena)

        val initFuncSymbol =
            symbolLookup.find("nox_plugin_init").orElseThrow {
                IllegalArgumentException("Cannot find nox_plugin_init in $libraryPath")
            }

        val initHandle =
            linker.downcallHandle(
                initFuncSymbol,
                FunctionDescriptor.of(ValueLayout.ADDRESS),
            )

        val rawManifestPtr = initHandle.invokeExact() as MemorySegment
        if (rawManifestPtr == MemorySegment.NULL) {
            throw IllegalStateException("nox_plugin_init returned NULL")
        }

        val manifestPtr = rawManifestPtr.reinterpret(NOX_PLUGIN_MANIFEST_LAYOUT.byteSize())

        val namespaceStr =
            manifestPtr
                .get(
                    ValueLayout.ADDRESS,
                    NOX_PLUGIN_MANIFEST_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("namespace")),
                ).reinterpret(Long.MAX_VALUE)
                .getString(0)
        val funcCount =
            manifestPtr.get(
                ValueLayout.JAVA_INT,
                NOX_PLUGIN_MANIFEST_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("func_count")),
            )

        val externalFuncs = mutableListOf<NoxExternalFunc>()
        val funcStructSize = NOX_PLUGIN_FUNC_LAYOUT.byteSize()

        val functionsPtr =
            manifestPtr
                .get(
                    ValueLayout.ADDRESS,
                    NOX_PLUGIN_MANIFEST_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("functions")),
                ).reinterpret(
                    funcCount * funcStructSize,
                )

        val typeTags = NoxTypeTag.entries.toTypedArray()

        for (i in 0 until funcCount) {
            val funcPtr = functionsPtr.asSlice(i * funcStructSize, funcStructSize)
            val name =
                funcPtr
                    .get(
                        ValueLayout.ADDRESS,
                        NOX_PLUGIN_FUNC_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("name")),
                    ).reinterpret(Long.MAX_VALUE)
                    .getString(0)
            val paramCount =
                funcPtr.get(
                    ValueLayout.JAVA_INT,
                    NOX_PLUGIN_FUNC_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("param_count")),
                )
            val paramTypesPtr =
                funcPtr
                    .get(
                        ValueLayout.ADDRESS,
                        NOX_PLUGIN_FUNC_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("param_types")),
                    ).reinterpret(
                        paramCount * 4L,
                    )

            val paramTypes = mutableListOf<NoxTypeTag>()
            for (p in 0 until paramCount) {
                val typeTagInt = paramTypesPtr.getAtIndex(ValueLayout.JAVA_INT, p.toLong())
                paramTypes.add(typeTags[typeTagInt])
            }

            val returnTypeInt =
                funcPtr.get(
                    ValueLayout.JAVA_INT,
                    NOX_PLUGIN_FUNC_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("return_type")),
                )
            val returnType = typeTags[returnTypeInt]

            val implPtr =
                funcPtr.get(
                    ValueLayout.ADDRESS,
                    NOX_PLUGIN_FUNC_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("func_ptr")),
                )

            externalFuncs.add(NoxExternalFunc(name, paramTypes, returnType))

            val cParamLayouts =
                buildList<MemoryLayout> {
                    add(ValueLayout.ADDRESS)
                    addAll(paramTypes.mapNotNull { typeTagToValueLayout(it) })
                }.toTypedArray()
            val cReturnLayout = typeTagToValueLayout(returnType)

            val descriptor =
                if (cReturnLayout == null) {
                    FunctionDescriptor.ofVoid(*cParamLayouts)
                } else {
                    FunctionDescriptor.of(cReturnLayout, *cParamLayouts)
                }

            val downcallHandle = linker.downcallHandle(implPtr, descriptor)
            val noxNativeFunc = createNoxNativeFunc(downcallHandle, paramTypes, returnType)

            val scallName = "${namespaceStr}__$name"
            registry.registerNativeFunc(scallName, noxNativeFunc)
        }

        val manifest = NoxPluginManifest(namespaceStr, externalFuncs)
        registry.registerExternalPlugin(manifest)
    }

    private fun typeTagToValueLayout(tag: NoxTypeTag): MemoryLayout? =
        when (tag) {
            NoxTypeTag.INT -> ValueLayout.JAVA_LONG
            NoxTypeTag.DOUBLE -> ValueLayout.JAVA_DOUBLE
            NoxTypeTag.BOOLEAN -> ValueLayout.JAVA_BOOLEAN
            NoxTypeTag.STRING -> ValueLayout.ADDRESS
            NoxTypeTag.JSON -> ValueLayout.ADDRESS // Passed as JSON string
            NoxTypeTag.VOID -> null
            NoxTypeTag.INT_ARRAY, NoxTypeTag.DOUBLE_ARRAY, NoxTypeTag.STRING_ARRAY -> ValueLayout.ADDRESS
        }

    private fun createNoxNativeFunc(
        handle: MethodHandle,
        paramTypes: List<NoxTypeTag>,
        returnType: NoxTypeTag,
    ): NoxNativeFunc =
        NoxNativeFunc { context, pMem, rMem, bp, bpRef, primArgStart, refArgStart, destReg ->
            Arena.ofConfined().use { scopedArena ->
                val contextId = nextContextId.getAndIncrement()
                activeContexts[contextId] = context

                try {
                    val args = arrayOfNulls<Any>(paramTypes.size + 1)
                    val ctxStruct = scopedArena.allocate(NOX_CONTEXT_LAYOUT)
                    ctxStruct.set(
                        ValueLayout.JAVA_LONG,
                        NOX_CONTEXT_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("internal_id")),
                        contextId,
                    )
                    ctxStruct.set(
                        ValueLayout.ADDRESS,
                        NOX_CONTEXT_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("yield_func")),
                        yieldStub,
                    )
                    args[0] = ctxStruct

                    var pIdx = 0
                    var rIdx = 0

                    for (i in paramTypes.indices) {
                        val argIndex = i + 1
                        when (paramTypes[i]) {
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
                                args[argIndex] = scopedArena.allocateFrom(str)
                                rIdx++
                            }
                            NoxTypeTag.JSON -> {
                                val value = rMem[bpRef + refArgStart + rIdx]
                                val jsonStr = NoxJsonWriter(prettyPrint = false).write(value)
                                args[argIndex] = scopedArena.allocateFrom(jsonStr)
                                rIdx++
                            }
                            NoxTypeTag.INT_ARRAY -> {
                                @Suppress("UNCHECKED_CAST")
                                val list = rMem[bpRef + refArgStart + rIdx] as List<Long>
                                val cArray = scopedArena.allocate(ValueLayout.JAVA_LONG, list.size.toLong())
                                for (j in list.indices) cArray.setAtIndex(ValueLayout.JAVA_LONG, j.toLong(), list[j])
                                args[argIndex] = cArray
                                rIdx++
                            }
                            NoxTypeTag.DOUBLE_ARRAY -> {
                                @Suppress("UNCHECKED_CAST")
                                val list = rMem[bpRef + refArgStart + rIdx] as List<Double>
                                val cArray = scopedArena.allocate(ValueLayout.JAVA_DOUBLE, list.size.toLong())
                                for (j in list.indices) cArray.setAtIndex(ValueLayout.JAVA_DOUBLE, j.toLong(), list[j])
                                args[argIndex] = cArray
                                rIdx++
                            }
                            NoxTypeTag.STRING_ARRAY -> {
                                @Suppress("UNCHECKED_CAST")
                                val list = rMem[bpRef + refArgStart + rIdx] as List<String>
                                val cArray = scopedArena.allocate(ValueLayout.ADDRESS, list.size.toLong())
                                for (j in list.indices) {
                                    cArray.setAtIndex(
                                        ValueLayout.ADDRESS,
                                        j.toLong(),
                                        scopedArena.allocateFrom(list[j]),
                                    )
                                }
                                args[argIndex] = cArray
                                rIdx++
                            }
                            else -> throw UnsupportedOperationException("Type ${paramTypes[i]} not supported")
                        }
                    }

                    val result = handle.invokeWithArguments(*args)

                    when (returnType) {
                        NoxTypeTag.INT -> {
                            pMem[bp + destReg] = result as Long
                        }
                        NoxTypeTag.DOUBLE -> {
                            pMem[bp + destReg] = java.lang.Double.doubleToRawLongBits(result as Double)
                        }
                        NoxTypeTag.BOOLEAN -> {
                            pMem[bp + destReg] = if (result as Boolean) 1L else 0L
                        }
                        NoxTypeTag.STRING -> {
                            val cStr = (result as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)
                            rMem[bpRef + destReg] = cStr
                        }
                        NoxTypeTag.JSON -> {
                            val cStr = (result as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)
                            rMem[bpRef + destReg] = NoxJsonParser(cStr).parse()
                        }
                        NoxTypeTag.INT_ARRAY, NoxTypeTag.DOUBLE_ARRAY, NoxTypeTag.STRING_ARRAY -> {
                            throw UnsupportedOperationException(
                                "Returning arrays from C is not safely supported without a length protocol.",
                            ) //TODO: Implement a safe protocol for returning arrays (e.g. caller allocates and passes pointer+length, or return struct with pointer+length)
                        }
                        NoxTypeTag.VOID -> {}
                        else -> throw UnsupportedOperationException("Type $returnType not supported as return")
                    }
                } finally {
                    activeContexts.remove(contextId)
                }
            }
        }
}
