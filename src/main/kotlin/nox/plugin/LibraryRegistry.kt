package nox.plugin

import nox.compiler.types.CallTarget
import nox.compiler.types.NoxParam
import nox.compiler.types.TypeRef
import nox.plugin.annotations.*
import nox.plugin.external.NoxPluginManifest
import nox.plugin.external.NoxTypeTag
import nox.runtime.RuntimeContext
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.valueParameters

/**
 * Central compile-time and runtime registry for all plugin functions.
 *
 * A dynamically populated registry driven by annotations (`@NoxModule`, `@NoxFunction`, `@NoxTypeMethod`)
 * and external plugin manifests.
 *
 * **Registration flow:**
 * 1. `registerModule(instance)` scans annotations and populates compile-time
 *    `CallTarget` entries + runtime `NoxNativeFunc` adapters via the [Linker].
 * 2. `registerExternalPlugin(manifest)` registers Tier 1 compile-time signatures.
 * 3. `createDefault()` registers all stdlib modules.
 *
 * **Query API** (used by the compiler during semantic analysis):
 * - `isBuiltinNamespace(name)`: check namespace existence
 * - `lookupNamespaceFunc(namespace, funcName)`: resolve namespace function
 * - `lookupBuiltinMethod(targetType, methodName)`: resolve type-bound method
 * - `lookupTypeMethod(targetType, methodName)`: resolve conversion method
 *
 * **Runtime API** (used by the VM during SCALL dispatch):
 * - `lookupNativeFunc(scallName)`: get the linked NoxNativeFunc adapter
 *
 * See docs/extensibility/plugin-guide.md for the full architecture.
 */
class LibraryRegistry {
    // Compile-time data (CallTargets for semantic analysis)
    private val namespaceFunctions = mutableMapOf<String, MutableMap<String, CallTarget>>()
    private val builtinMethods = mutableMapOf<String, MutableMap<String, CallTarget>>()
    private val typeMethods = mutableMapOf<String, MutableMap<String, CallTarget>>()
    private val arrayMethodNames = mutableSetOf("push", "pop", "length")

    private data class TemplateSignature(
        val genericParams: List<String>,
        val baseScallName: String,
        val kotlinMethod: KFunction<*>,
        val instance: Any,
        val targetTypeStr: String?,
        val methodName: String,
        val namespace: String?,
    )

    private val templates = mutableListOf<TemplateSignature>()

    // Runtime data (NoxNativeFuncs for VM SCALL dispatch)
    private val nativeFuncs = mutableMapOf<String, NoxNativeFunc>()

    // Tier 1 namespace tracking
    private val _externalPluginNamespaces = mutableSetOf<String>()

    /** All built-in (Tier 0) namespace names. */
    val builtinNamespaceNames: Set<String> get() = namespaceFunctions.keys

    /** All external (Tier 1) plugin namespace names. */
    val externalPluginNamespaces: Set<String> get() = _externalPluginNamespaces

    /** Whether [name] is a built-in (Tier 0) or external (Tier 1) namespace. */
    fun isBuiltinNamespace(name: String): Boolean = name in namespaceFunctions || name in _externalPluginNamespaces

    /** Look up a namespace function by [namespace] and [funcName]. */
    fun lookupNamespaceFunc(
        namespace: String,
        funcName: String,
    ): CallTarget? {
        namespaceFunctions[namespace]?.get(funcName)?.let { return it }

        // Template match
        for (template in templates) {
            if (template.namespace == namespace && template.methodName == funcName) {
                // Not yet fully supported for standalone namespace generics unless type inferences exist
                // but we can return null for now if they aren't instantiated explicitly.
            }
        }
        return null
    }

    /**
     * Look up a built-in method on [targetType] (e.g. `string.upper()`, `arr.length()`).
     */
    fun lookupBuiltinMethod(
        targetType: TypeRef,
        methodName: String,
    ): CallTarget? {
        // Direct type match (e.g. string.upper(), json.size())
        builtinMethods[targetType.toString()]?.get(methodName)?.let { return it }
        // Struct types can call json methods (implicit upcast)
        if (targetType.isStructType()) {
            builtinMethods["json"]?.get(methodName)?.let { return it }
        }

        // Template match
        for (template in templates) {
            if (template.targetTypeStr != null && template.methodName == methodName) {
                val mapping = targetType.match(template.targetTypeStr)
                if (mapping != null) {
                    val scallName =
                        template.baseScallName + "!" +
                            template.genericParams.joinToString("!") { mapping[it]?.toString() ?: "unknown" }
                    val returnType = resolveInstantiatedReturnType(template.kotlinMethod, mapping)
                    val params = buildInstantiatedParams(template.kotlinMethod, mapping).drop(1)
                    return CallTarget(scallName, params, returnType)
                }
            }
        }
        return null
    }

    /** Look up a type-bound conversion method (e.g. `int.toDouble()`). */
    fun lookupTypeMethod(
        targetType: TypeRef,
        methodName: String,
    ): CallTarget? = typeMethods[targetType.toString()]?.get(methodName)

    /** All built-in method names for [targetType] (for diagnostics). */
    fun getBuiltinMethodNames(targetType: TypeRef): Set<String>? {
        val names = mutableSetOf<String>()
        builtinMethods[targetType.toString()]?.keys?.let { names.addAll(it) }
        if (targetType.isStructType()) builtinMethods["json"]?.keys?.let { names.addAll(it) }
        for (template in templates) {
            if (template.targetTypeStr != null && targetType.match(template.targetTypeStr) != null) {
                names.add(template.methodName)
            }
        }
        return names.ifEmpty { null }
    }

    /** All type-bound conversion method names for [targetType] (for diagnostics). */
    fun getTypeMethodNames(targetType: TypeRef): Set<String>? = typeMethods[targetType.toString()]?.keys

    /** Look up a linked native function by SCALL name (for VM dispatch). */
    fun lookupNativeFunc(scallName: String): NoxNativeFunc? {
        nativeFuncs[scallName]?.let { return it }
        if (!scallName.contains("!")) return null

        // JIT Linking for Generics
        val parts = scallName.split("!")
        val baseName = parts[0]
        val template = templates.find { it.baseScallName == baseName } ?: return null
        val mapping = template.genericParams.zip(parts.drop(1).map { parseTypeRefString(it) }).toMap()

        val linked = Linker.link(template.kotlinMethod, template.instance, scallName, mapping)
        nativeFuncs[scallName] = linked.nativeFunc
        return linked.nativeFunc
    }

    /**
     * Register a Tier 0 (Kotlin) plugin module.
     *
     * Scans the class of [instance] for `@NoxModule`, `@NoxFunction`, and
     * `@NoxTypeMethod` annotations. Populates compile-time `CallTarget` entries
     * and links runtime `NoxNativeFunc` adapters via the [Linker].
     *
     * @param instance the module object (typically a Kotlin `object`)
     * @throws IllegalArgumentException if the class is not annotated with `@NoxModule`
     */
    fun registerModule(instance: Any) {
        val klass = instance::class
        val moduleAnnotation =
            klass.findAnnotation<NoxModule>()
                ?: throw IllegalArgumentException(
                    "Class ${klass.qualifiedName} is not annotated with @NoxModule",
                )

        val namespace = moduleAnnotation.namespace

        for (func in klass.declaredFunctions) {
            val noxFunc = func.findAnnotation<NoxFunction>()
            val noxTypeMethod = func.findAnnotation<NoxTypeMethod>()
            val noxGeneric = func.findAnnotation<NoxGeneric>()

            if (noxGeneric != null) {
                val genericParams = noxGeneric.params.toList()
                if (noxFunc != null) {
                    val noxName = noxFunc.name.ifBlank { func.name }
                    val scallName = "${namespace}__$noxName"
                    templates.add(
                        TemplateSignature(
                            genericParams,
                            scallName,
                            func,
                            instance,
                            null,
                            noxName,
                            namespace,
                        ),
                    )
                } else if (noxTypeMethod != null) {
                    val targetTypeStr = noxTypeMethod.targetType
                    val noxName = noxTypeMethod.name.ifBlank { func.name }
                    val scallName = "__${targetTypeStr}_$noxName"
                    templates.add(
                        TemplateSignature(
                            genericParams,
                            scallName,
                            func,
                            instance,
                            targetTypeStr,
                            noxName,
                            null,
                        ),
                    )
                }
            } else {
                when {
                    noxFunc != null -> registerNamespaceFunction(namespace, func, instance, noxFunc)
                    noxTypeMethod != null -> registerTypeMethod(func, instance, noxTypeMethod)
                }
            }
        }
    }

    /**
     * Register a Tier 1 (external C ABI) plugin manifest.
     *
     * Registers compile-time signatures only. The actual native function
     * pointers are managed by the native bridge (not yet implemented).
     */
    fun registerExternalPlugin(manifest: NoxPluginManifest) {
        _externalPluginNamespaces.add(manifest.namespace)
        val funcMap = namespaceFunctions.getOrPut(manifest.namespace) { mutableMapOf() }

        for (func in manifest.functions) {
            val params =
                func.paramTypes.mapIndexed { i, tag ->
                    NoxParam("arg$i", typeTagToTypeRef(tag))
                }
            funcMap[func.name] = CallTarget(func.name, params, typeTagToTypeRef(func.returnType))
        }
    }

    /**
     * Validates that optional parameters (those with `@NoxDefault`) come after all
     * required parameters, mirroring the rule enforced for user-defined functions.
     */
    private fun validateParamOrder(
        scallName: String,
        params: List<NoxParam>,
    ) {
        var optionalSeen = false
        for (p in params) {
            if (p.defaultLiteral != null) {
                optionalSeen = true
            } else if (optionalSeen) {
                throw IllegalArgumentException(
                    "Plugin function '$scallName': required parameter '${p.name}' " +
                        "must come before optional parameters",
                )
            }
        }
    }

    private fun registerNamespaceFunction(
        namespace: String,
        func: KFunction<*>,
        instance: Any,
        annotation: NoxFunction,
    ) {
        val noxName = annotation.name.ifBlank { func.name }
        val params = buildParamList(func)
        val returnType = resolveReturnType(func)
        val scallName = "${namespace}__$noxName"
        validateParamOrder(scallName, params)

        val funcMap = namespaceFunctions.getOrPut(namespace) { mutableMapOf() }
        funcMap[noxName] = CallTarget(scallName, params, returnType)

        // Link the runtime adapter
        try {
            val linked = Linker.link(func, instance, scallName)
            nativeFuncs[linked.scallName] = linked.nativeFunc
        } catch (e: Exception) {
            // Linking may fail if method signatures aren't compatible
            // TODO: Handle this
            System.err.println("Warning: Failed to link $scallName: ${e.message}")
        }
    }

    private fun registerTypeMethod(
        func: KFunction<*>,
        instance: Any,
        annotation: NoxTypeMethod,
    ) {
        val targetType = annotation.targetType
        val noxName = annotation.name.ifBlank { func.name }

        // For type-bound methods, the first Kotlin param is the receiver (target value).
        // The remaining params are the Nox-visible arguments.
        val allParams = buildParamList(func)
        val noxVisibleParams = if (allParams.isNotEmpty()) allParams.drop(1) else allParams
        val returnType = resolveReturnType(func)

        // Generate internal SCALL name: __<type>_<method>
        val scallName = "__${targetType}_$noxName"
        validateParamOrder(scallName, noxVisibleParams)

        // Determine which map to use:
        // builtinMethods: for methods on built-in types (string.upper, json.size, etc.)
        // typeMethods: for conversion methods (int.toDouble, string.toInt, etc.)
        val isConversionMethod = isConversionLike(noxName)
        val targetMap =
            if (isConversionMethod) {
                typeMethods.getOrPut(targetType) { mutableMapOf() }
            } else {
                builtinMethods.getOrPut(targetType) { mutableMapOf() }
            }

        targetMap[noxName] = CallTarget(scallName, noxVisibleParams, returnType)

        // Link the runtime adapter
        try {
            val linked = Linker.link(func, instance, scallName)
            nativeFuncs[linked.scallName] = linked.nativeFunc
        } catch (e: Exception) {
            // TODO: handle this
            System.err.println("Warning: Failed to link $scallName: ${e.message}")
        }
    }

    /**
     * Build the Nox-visible parameter list from a Kotlin function.
     *
     * Skips `RuntimeContext` parameters (auto-injected by VM).
     * Detects `@NoxDefault` annotations to populate [NoxParam.defaultLiteral].
     */
    private fun buildParamList(func: KFunction<*>): List<NoxParam> =
        func.valueParameters
            .filter { !isRuntimeContext(it) }
            .map { p ->
                val noxType = resolveParamType(p)
                val default = p.findAnnotation<NoxDefault>()?.value
                NoxParam(p.name ?: "arg", noxType, default)
            }

    /** Resolve a Kotlin parameter to its Nox TypeRef. */
    private fun resolveParamType(param: KParameter): TypeRef {
        // Check for @NoxType annotation override
        val noxType = param.findAnnotation<NoxType>()
        if (noxType != null) return parseTypeRefString(noxType.value)

        // Map from Kotlin type
        val javaType =
            param.type.classifier?.let {
                (it as? kotlin.reflect.KClass<*>)?.java
            } ?: return TypeRef.JSON // fallback for unknown types

        return kotlinTypeToTypeRef(javaType)
    }

    private fun buildInstantiatedParams(
        func: KFunction<*>,
        mapping: Map<String, TypeRef>,
    ): List<NoxParam> =
        func.valueParameters
            .filter { !isRuntimeContext(it) }
            .map { p ->
                val noxType = p.findAnnotation<NoxType>()
                val resolvedType =
                    if (noxType != null) {
                        mapping[noxType.value] ?: parseTypeRefString(noxType.value)
                    } else {
                        val javaType =
                            p.type.classifier?.let {
                                (it as? kotlin.reflect.KClass<*>)?.java
                            } ?: Any::class.java
                        kotlinTypeToTypeRef(javaType)
                    }
                val default = p.findAnnotation<NoxDefault>()?.value
                NoxParam(p.name ?: "arg", resolvedType, default)
            }

    /** Resolve a Kotlin function's return type to its Nox TypeRef. */
    private fun resolveReturnType(func: KFunction<*>): TypeRef {
        // Check for @NoxType annotation on function (return type override)
        val noxType = func.findAnnotation<NoxType>()
        if (noxType != null) return parseTypeRefString(noxType.value)

        val returnType =
            func.returnType.classifier?.let {
                (it as? kotlin.reflect.KClass<*>)?.java
            } ?: return TypeRef.VOID

        return kotlinTypeToTypeRef(returnType)
    }

    private fun resolveInstantiatedReturnType(
        func: KFunction<*>,
        mapping: Map<String, TypeRef>,
    ): TypeRef {
        val noxType = func.findAnnotation<NoxType>()
        if (noxType != null) {
            return mapping[noxType.value] ?: parseTypeRefString(noxType.value)
        }
        val returnType =
            func.returnType.classifier?.let {
                (it as? kotlin.reflect.KClass<*>)?.java
            } ?: return TypeRef.VOID
        return kotlinTypeToTypeRef(returnType)
    }

    /** Whether a parameter is a RuntimeContext (auto-injected, not Nox-visible). */
    private fun isRuntimeContext(param: KParameter): Boolean {
        val javaType =
            param.type.classifier?.let {
                (it as? kotlin.reflect.KClass<*>)?.java
            } ?: return false
        return RuntimeContext::class.java.isAssignableFrom(javaType)
    }

    /** Whether a method name looks like a conversion (toX, fromX). */
    private fun isConversionLike(name: String): Boolean =
        (name.startsWith("to") && name.length > 2 && name[2].isUpperCase()) ||
            (name.startsWith("from") && name.length > 4 && name[4].isUpperCase())

    companion object {
        /**
         * Create a [LibraryRegistry] by auto-discovering all `@NoxModule`
         * annotated classes on the classpath using ClassGraph.
         *
         * For Kotlin `object` declarations, the singleton `INSTANCE` is used.
         * For regular classes, a no-arg constructor is invoked.
         *
         * @param scanPackages packages to scan (defaults to `nox.plugin.stdlib`)
         */
        fun createDefault(vararg scanPackages: String = arrayOf("nox.plugin.stdlib")): LibraryRegistry {
            val registry = LibraryRegistry()

            io.github.classgraph
                .ClassGraph()
                .acceptPackages(*scanPackages)
                .enableAnnotationInfo()
                // Allow working on graalvm
                .enableClassInfo()
                .ignoreClassVisibility()
                .scan()
                .use { scanResult ->
                    val annotationName = NoxModule::class.java.name
                    for (classInfo in scanResult.getClassesWithAnnotation(annotationName)) {
                        val clazz = classInfo.loadClass()
                        val instance =
                            try {
                                // Kotlin object: access INSTANCE field
                                clazz.getField("INSTANCE").get(null)
                            } catch (_: NoSuchFieldException) {
                                // Regular class: no-arg constructor
                                clazz.getDeclaredConstructor().newInstance()
                            }
                        registry.registerModule(instance)
                    }
                }

            return registry
        }

        /** Map a Kotlin/Java type to the corresponding Nox [TypeRef]. */
        fun kotlinTypeToTypeRef(javaType: Class<*>): TypeRef =
            when {
                javaType == Long::class.java || javaType == java.lang.Long::class.java -> TypeRef.INT
                javaType == Int::class.java || javaType == java.lang.Integer::class.java -> TypeRef.INT
                javaType == Double::class.java || javaType == java.lang.Double::class.java -> TypeRef.DOUBLE
                javaType == Float::class.java || javaType == java.lang.Float::class.java -> TypeRef.DOUBLE
                javaType == Boolean::class.java || javaType == java.lang.Boolean::class.java -> TypeRef.BOOLEAN
                javaType == String::class.java -> TypeRef.STRING
                javaType == Void.TYPE || javaType == Unit::class.java -> TypeRef.VOID
                javaType == List::class.java || javaType.isArray -> TypeRef("string", 1) // default array
                else -> TypeRef.JSON // NoxObject, etc.
            }

        /** Map a [NoxTypeTag] to a Nox [TypeRef]. */
        fun typeTagToTypeRef(tag: NoxTypeTag): TypeRef =
            when (tag) {
                NoxTypeTag.INT -> TypeRef.INT
                NoxTypeTag.DOUBLE -> TypeRef.DOUBLE
                NoxTypeTag.BOOLEAN -> TypeRef.BOOLEAN
                NoxTypeTag.STRING -> TypeRef.STRING
                NoxTypeTag.JSON -> TypeRef.JSON
                NoxTypeTag.VOID -> TypeRef.VOID
                NoxTypeTag.STRING_ARRAY -> TypeRef("string", 1)
                NoxTypeTag.INT_ARRAY -> TypeRef("int", 1)
                NoxTypeTag.DOUBLE_ARRAY -> TypeRef("double", 1)
            }

        /**
         * Parse a type string like "int", "string[]", "double[]" to a [TypeRef].
         */
        fun parseTypeRefString(typeStr: String): TypeRef {
            var name = typeStr
            var depth = 0
            while (name.endsWith("[]")) {
                name = name.removeSuffix("[]")
                depth++
            }
            return TypeRef(name, depth)
        }
    }
}
