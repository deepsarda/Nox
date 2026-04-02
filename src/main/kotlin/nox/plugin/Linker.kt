package nox.plugin

import nox.plugin.annotations.NoxFunction
import nox.plugin.annotations.NoxType
import nox.plugin.annotations.NoxTypeMethod
import nox.compiler.types.TypeRef
import nox.runtime.RuntimeContext
import java.lang.invoke.MethodHandles
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaMethod

/**
 * Builds [NoxNativeFunc] adapters from annotated Kotlin methods using
 * `MethodHandle` for near-direct-call performance.
 *
 * The Linker runs once per function at module load time. No linking
 * code runs during VM execution.
 *
 * See docs/extensibility/ffi-internals.md for the full design.
 */
object Linker {
    /**
     * Descriptor for a linked native function.
     *
     * @property scallName  the key used in SCALL lookups (e.g. "sqrt", "__str_upper")
     * @property nativeFunc the compiled adapter that bridges VM registers to the Kotlin method
     */
    data class LinkedFunc(
        val scallName: String,
        val nativeFunc: NoxNativeFunc,
    )

    /**
     * Links a single annotated Kotlin function to a [NoxNativeFunc] adapter.
     *
     * Inspects parameter types to determine extraction logic (pMem vs rMem),
     * detects [RuntimeContext] injection, and builds the return handler.
     *
     * @param function     the Kotlin function to link
     * @param instance     the object instance for instance methods (null for @JvmStatic)
     * @param nameOverride explicit SCALL name; if blank, derived from annotations
     * @return a linked adapter ready for VM dispatch
     */
    fun link(
        function: KFunction<*>,
        instance: Any?,
        nameOverride: String = "",
        typeMapping: Map<String, TypeRef> = emptyMap(),
    ): LinkedFunc {
        val javaMethod =
            function.javaMethod
                ?: throw IllegalArgumentException("Cannot link ${function.name}: no backing Java method")
        val isStatic =
            java.lang.reflect.Modifier
                .isStatic(javaMethod.modifiers)

        // Use privateLookupIn so unreflect succeeds for package-private/protected methods,
        // not just public ones. publicLookup() would fail for any non-public target.
        javaMethod.isAccessible = true
        val lookup = MethodHandles.privateLookupIn(javaMethod.declaringClass, MethodHandles.lookup())
        val handle = lookup.unreflect(javaMethod)

        // Determine the SCALL name
        val scallName =
            nameOverride.ifBlank {
                function.findAnnotation<NoxFunction>()?.name?.ifBlank { function.name }
                    ?: function.findAnnotation<NoxTypeMethod>()?.name?.ifBlank { function.name }
                    ?: function.name
            }

        // Analyze parameters: which are VM args vs injected RuntimeContext
        val params = function.valueParameters
        val paramDescriptors = params.map { p -> ParamDescriptor.from(p, typeMapping) }

        // Determine return type handling
        val returnDesc = ReturnDescriptor.from(function, typeMapping)

        // Build the NoxNativeFunc adapter
        val adapter = buildAdapter(handle, instance, isStatic, paramDescriptors, returnDesc)

        return LinkedFunc(scallName, adapter)
    }

    /**
     * Build a NoxNativeFunc that:
     * 1. Extracts arguments from pMem/rMem based on their types
     * 2. Injects RuntimeContext if needed.
     * 3. Calls the target method via MethodHandle
     * 4. Stores the result in the correct register bank
     */
    private fun buildAdapter(
        handle: java.lang.invoke.MethodHandle,
        instance: Any?,
        isStatic: Boolean,
        params: List<ParamDescriptor>,
        returnDesc: ReturnDescriptor,
    ): NoxNativeFunc {
        // Count only the VM-visible args (skip RuntimeContext injection)
        val vmParams = params.filter { !it.isContextInjection }

        return NoxNativeFunc { context, pMem, rMem, bp, bpRef, argStart, destReg ->
            // Build the argument array
            val args = mutableListOf<Any?>()
            if (instance != null && !isStatic) args.add(instance)

            var primArgIdx = 0
            var refArgIdx = 0

            for (param in params) {
                if (param.isContextInjection) {
                    // RuntimeContext injection
                    args.add(context)
                    continue
                }

                when (param.bank) {
                    RegisterBank.PRIMITIVE -> {
                        val raw = pMem[bp + argStart + primArgIdx]
                        args.add(param.extract(raw))
                        primArgIdx++
                    }
                    RegisterBank.REFERENCE -> {
                        val raw = rMem[bpRef + argStart + refArgIdx]
                        args.add(raw)
                        refArgIdx++
                    }
                }
            }

            // Invoke the target method
            val result = handle.invokeWithArguments(args)

            // Store result
            when (returnDesc.bank) {
                RegisterBank.PRIMITIVE -> {
                    pMem[bp + destReg] = returnDesc.pack(result)
                }
                RegisterBank.REFERENCE -> {
                    rMem[bpRef + destReg] = result
                }
                null -> { /* void return, no storage */ }
            }
        }
    }

    /**
     * Which register bank a value lives in.
     */
    private enum class RegisterBank { PRIMITIVE, REFERENCE }

    /**
     * Describes how to extract a parameter from VM registers.
     */
    private data class ParamDescriptor(
        val bank: RegisterBank,
        val isContextInjection: Boolean,
        val kotlinType: Class<*>,
        val mappedType: TypeRef? = null,
    ) {
        /**
         * Extract a typed Kotlin value from a raw `Long` (pMem value).
         */
        fun extract(raw: Long): Any =
            when {
                mappedType?.name == "int" -> raw.toInt()
                mappedType?.name == "double" -> java.lang.Double.longBitsToDouble(raw)
                mappedType?.name == "boolean" -> raw != 0L
                kotlinType == Long::class.java || kotlinType == java.lang.Long::class.java -> raw
                kotlinType == Int::class.java || kotlinType == java.lang.Integer::class.java -> raw.toInt()
                kotlinType == Double::class.java || kotlinType == java.lang.Double::class.java ->
                    java.lang.Double.longBitsToDouble(raw)
                kotlinType == Float::class.java || kotlinType == java.lang.Float::class.java ->
                    java.lang.Float.intBitsToFloat(raw.toInt())
                kotlinType == Boolean::class.java || kotlinType == java.lang.Boolean::class.java ->
                    raw != 0L
                else -> throw IllegalStateException("Cannot extract primitive type: $kotlinType for mapped type $mappedType")
            }

        companion object {
            fun from(
                param: KParameter,
                typeMapping: Map<String, TypeRef>,
            ): ParamDescriptor {
                val javaType =
                    param.type.classifier?.let {
                        (it as? kotlin.reflect.KClass<*>)?.java
                    } ?: Any::class.java

                // RuntimeContext injection
                if (RuntimeContext::class.java.isAssignableFrom(javaType)) {
                    return ParamDescriptor(RegisterBank.PRIMITIVE, true, javaType)
                }

                // Check for @NoxType annotation override
                val noxType = param.findAnnotation<NoxType>()
                if (noxType != null) {
                    val mapped = typeMapping[noxType.value]
                    if (mapped != null) {
                        val bank = if (mapped.isPrimitive()) RegisterBank.PRIMITIVE else RegisterBank.REFERENCE
                        return ParamDescriptor(bank, false, javaType, mapped)
                    }

                    val bank =
                        if (noxType.value in
                            PRIMITIVE_NOX_TYPES
                        ) {
                            RegisterBank.PRIMITIVE
                        } else {
                            RegisterBank.REFERENCE
                        }
                    return ParamDescriptor(bank, false, javaType)
                }

                val bank =
                    when {
                        javaType == Long::class.java ||
                            javaType == java.lang.Long::class.java -> RegisterBank.PRIMITIVE
                        javaType == Int::class.java ||
                            javaType == java.lang.Integer::class.java -> RegisterBank.PRIMITIVE
                        javaType == Double::class.java ||
                            javaType == java.lang.Double::class.java -> RegisterBank.PRIMITIVE
                        javaType == Float::class.java ||
                            javaType == java.lang.Float::class.java -> RegisterBank.PRIMITIVE
                        javaType == Boolean::class.java ||
                            javaType == java.lang.Boolean::class.java -> RegisterBank.PRIMITIVE
                        javaType == String::class.java -> RegisterBank.REFERENCE
                        else -> RegisterBank.REFERENCE // json, structs, arrays
                    }

                return ParamDescriptor(bank, false, javaType)
            }
        }
    }

    /**
     * Describes how to store a return value into VM registers.
     */
    private data class ReturnDescriptor(
        val bank: RegisterBank?,
        val kotlinType: Class<*>,
        val mappedType: TypeRef? = null,
    ) {
        /**
         * Pack a Kotlin return value into a raw `Long` for pMem storage.
         */
        fun pack(value: Any?): Long =
            when {
                value == null -> 0L
                mappedType?.name == "int" -> (value as Int).toLong()
                mappedType?.name == "double" -> java.lang.Double.doubleToRawLongBits(value as Double)
                mappedType?.name == "boolean" -> if (value as Boolean) 1L else 0L
                kotlinType == Long::class.java || kotlinType == java.lang.Long::class.java -> value as Long
                kotlinType == Int::class.java || kotlinType == java.lang.Integer::class.java -> (value as Int).toLong()
                kotlinType == Double::class.java || kotlinType == java.lang.Double::class.java ->
                    java.lang.Double.doubleToRawLongBits(value as Double)
                kotlinType == Float::class.java || kotlinType == java.lang.Float::class.java ->
                    java.lang.Float
                        .floatToRawIntBits(value as Float)
                        .toLong()
                kotlinType == Boolean::class.java || kotlinType == java.lang.Boolean::class.java ->
                    if (value as Boolean) 1L else 0L
                else -> throw IllegalStateException("Cannot pack primitive type: $kotlinType for mapped type $mappedType")
            }

        companion object {
            fun from(
                function: KFunction<*>,
                typeMapping: Map<String, TypeRef>,
            ): ReturnDescriptor {
                val returnType =
                    function.returnType.classifier?.let {
                        (it as? kotlin.reflect.KClass<*>)?.java
                    } ?: Void.TYPE

                // Check for @NoxType on function (return type override)
                val noxType = function.findAnnotation<NoxType>()
                if (noxType != null) {
                    val mapped = typeMapping[noxType.value]
                    if (mapped != null) {
                        val bank = if (mapped.isPrimitive()) RegisterBank.PRIMITIVE else RegisterBank.REFERENCE
                        return ReturnDescriptor(bank, returnType, mapped)
                    }

                    val bank =
                        if (noxType.value in
                            PRIMITIVE_NOX_TYPES
                        ) {
                            RegisterBank.PRIMITIVE
                        } else {
                            RegisterBank.REFERENCE
                        }
                    return ReturnDescriptor(bank, returnType)
                }

                val bank =
                    when {
                        returnType == Void.TYPE ||
                            returnType == Unit::class.java -> null
                        returnType == Long::class.java ||
                            returnType == java.lang.Long::class.java -> RegisterBank.PRIMITIVE
                        returnType == Int::class.java ||
                            returnType == java.lang.Integer::class.java -> RegisterBank.PRIMITIVE
                        returnType == Double::class.java ||
                            returnType == java.lang.Double::class.java -> RegisterBank.PRIMITIVE
                        returnType == Float::class.java ||
                            returnType == java.lang.Float::class.java -> RegisterBank.PRIMITIVE
                        returnType == Boolean::class.java ||
                            returnType == java.lang.Boolean::class.java -> RegisterBank.PRIMITIVE
                        else -> RegisterBank.REFERENCE
                    }

                return ReturnDescriptor(bank, returnType)
            }
        }
    }

    private val PRIMITIVE_NOX_TYPES = setOf("int", "double", "boolean")
}
