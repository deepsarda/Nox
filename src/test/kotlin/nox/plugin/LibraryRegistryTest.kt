package nox.plugin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import nox.compiler.types.TypeRef
import nox.plugin.annotations.NoxFunction
import nox.plugin.annotations.NoxModule
import nox.plugin.annotations.NoxType
import nox.plugin.annotations.NoxTypeMethod
import nox.plugin.external.NoxExternalFunc
import nox.plugin.external.NoxPluginManifest
import nox.plugin.external.NoxTypeTag

class LibraryRegistryTest :
    FunSpec({

        // Auto-Discovery via ClassGraph

        test("createDefault discovers all stdlib namespace modules") {
            val registry = LibraryRegistry.createDefault()
            registry.builtinNamespaceNames shouldContainAll setOf("Math", "Date", "File", "Http", "Env")
        }

        test("createDefault discovers type-bound method modules") {
            val registry = LibraryRegistry.createDefault()
            // String methods should be registered
            registry.lookupBuiltinMethod(TypeRef.STRING, "upper").shouldNotBeNull()
            registry.lookupBuiltinMethod(TypeRef.STRING, "lower").shouldNotBeNull()
            registry.lookupBuiltinMethod(TypeRef.STRING, "length").shouldNotBeNull()
        }

        test("createDefault discovers type conversion methods") {
            val registry = LibraryRegistry.createDefault()
            registry.lookupTypeMethod(TypeRef.INT, "toDouble").shouldNotBeNull()
            registry.lookupTypeMethod(TypeRef.INT, "toString").shouldNotBeNull()
            registry.lookupTypeMethod(TypeRef.DOUBLE, "toInt").shouldNotBeNull()
            registry.lookupTypeMethod(TypeRef.STRING, "toInt").shouldNotBeNull()
        }

        // Manual Module Registration

        test("registerModule discovers @NoxFunction annotated methods") {
            val registry = LibraryRegistry()
            registry.registerModule(TestNamespaceModule)

            registry.isBuiltinNamespace("TestNS") shouldBe true
            val target = registry.lookupNamespaceFunc("TestNS", "add")
            target.shouldNotBeNull()
            target.params.size shouldBe 2
            target.returnType shouldBe TypeRef.INT
        }

        test("registerModule discovers @NoxTypeMethod annotated methods") {
            val registry = LibraryRegistry()
            registry.registerModule(TestTypeMethods)

            val method = registry.lookupBuiltinMethod(TypeRef.STRING, "reverse")
            method.shouldNotBeNull()
            method.params.size shouldBe 0 // receiver is not a visible param
            method.returnType shouldBe TypeRef.STRING
        }

        test("registerModule discovers conversion methods and puts them in typeMethods") {
            val registry = LibraryRegistry()
            registry.registerModule(TestTypeMethods)

            val method = registry.lookupTypeMethod(TypeRef.STRING, "toUpperCase")
            method.shouldNotBeNull()
            method.returnType shouldBe TypeRef.STRING
        }

        // SCALL Name Generation

        test("namespace functions get namespace-qualified SCALL names") {
            val registry = LibraryRegistry()
            registry.registerModule(TestNamespaceModule)

            val target = registry.lookupNamespaceFunc("TestNS", "add")
            target.shouldNotBeNull()
            target.name shouldBe "TestNS__add"
        }

        test("type-bound methods get type-qualified SCALL names") {
            val registry = LibraryRegistry()
            registry.registerModule(TestTypeMethods)

            val method = registry.lookupBuiltinMethod(TypeRef.STRING, "reverse")
            method.shouldNotBeNull()
            method.name shouldBe "__string_reverse"
        }

        test("no collisions between same-named functions in different namespaces") {
            val registry = LibraryRegistry()
            registry.registerModule(TestNamespaceModule)
            registry.registerModule(TestNamespaceModule2)

            val target1 = registry.lookupNamespaceFunc("TestNS", "add")
            val target2 = registry.lookupNamespaceFunc("TestNS2", "add")
            target1.shouldNotBeNull()
            target2.shouldNotBeNull()
            target1.name shouldBe "TestNS__add"
            target2.name shouldBe "TestNS2__add"
        }

        // Namespace Query API

        test("isBuiltinNamespace returns false for unknown namespaces") {
            val registry = LibraryRegistry()
            registry.isBuiltinNamespace("NonExistent") shouldBe false
        }

        test("lookupNamespaceFunc returns null for unknown function") {
            val registry = LibraryRegistry()
            registry.registerModule(TestNamespaceModule)
            registry.lookupNamespaceFunc("TestNS", "nonexistent").shouldBeNull()
        }

        test("lookupNamespaceFunc returns null for unknown namespace") {
            val registry = LibraryRegistry()
            registry.lookupNamespaceFunc("NonExistent", "add").shouldBeNull()
        }

        // Type-Bound Method Query API

        test("array methods return correct CallTargets") {
            val registry = LibraryRegistry.createDefault()
            val intArray = TypeRef("int", 1)

            val push = registry.lookupBuiltinMethod(intArray, "push")
            push.shouldNotBeNull()
            push.name shouldBe "__T[]_push!int"
            push.params.size shouldBe 1
            push.params[0].type shouldBe TypeRef.INT // element type

            val pop = registry.lookupBuiltinMethod(intArray, "pop")
            pop.shouldNotBeNull()
            pop.returnType shouldBe TypeRef.INT // element type

            val length = registry.lookupBuiltinMethod(intArray, "length")
            length.shouldNotBeNull()
            length.returnType shouldBe TypeRef.INT
        }

        test("string array push expects string elements") {
            val registry = LibraryRegistry.createDefault()
            val strArray = TypeRef("string", 1)
            val push = registry.lookupBuiltinMethod(strArray, "push")
            push.shouldNotBeNull()
            push.params[0].type shouldBe TypeRef.STRING
        }

        test("lookupBuiltinMethod returns null for non-existent method") {
            val registry = LibraryRegistry.createDefault()
            registry.lookupBuiltinMethod(TypeRef.STRING, "nonexistent").shouldBeNull()
        }

        test("getBuiltinMethodNames includes array methods for arrays") {
            val registry = LibraryRegistry.createDefault()
            val names = registry.getBuiltinMethodNames(TypeRef("int", 1))
            names.shouldNotBeNull()
            names shouldContainAll setOf("push", "pop", "length")
        }

        test("getBuiltinMethodNames returns null for type with no methods") {
            val registry = LibraryRegistry.createDefault()
            registry.getBuiltinMethodNames(TypeRef.BOOLEAN).shouldBeNull()
        }

        // Generics Template System

        test("lookupBuiltinMethod generates mangled name for generic template") {
            val registry = LibraryRegistry.createDefault()
            val targetType = TypeRef("int", 2) // int[][]
            val callTarget = registry.lookupBuiltinMethod(targetType, "push")
            
            callTarget.shouldNotBeNull()
            callTarget.name shouldBe "__T[]_push!int[]"
            callTarget.params.size shouldBe 1
            callTarget.params[0].type shouldBe TypeRef("int", 1) // parameter is T, so int[]
            callTarget.returnType shouldBe TypeRef.VOID
        }

        test("lookupBuiltinMethod generates mangled name for complex generic mappings") {
            val registry = LibraryRegistry.createDefault()
            val targetType = TypeRef("string", 3) // string[][][]
            val callTarget = registry.lookupBuiltinMethod(targetType, "pop")
            
            callTarget.shouldNotBeNull()
            callTarget.name shouldBe "__T[]_pop!string[][]"
            callTarget.params.size shouldBe 0
            callTarget.returnType shouldBe TypeRef("string", 2) // returns T, so string[][]
        }

        test("lookupNativeFunc caches and returns correctly linked adapter for generic JIT requests") {
            val registry = LibraryRegistry.createDefault()
            val scallName = "__T[]_push!double"
            
            // First lookup misses cache and triggers JIT linkage
            val func1 = registry.lookupNativeFunc(scallName)
            func1.shouldNotBeNull()

            // Second lookup hits cache directly
            val func2 = registry.lookupNativeFunc(scallName)
            func1 shouldBe func2
        }

        test("lookupNativeFunc parses multidimensional JIT generic requests") {
            val registry = LibraryRegistry.createDefault()
            val scallName = "__T[]_pop!json[][]"
            
            val func = registry.lookupNativeFunc(scallName)
            func.shouldNotBeNull()
        }

        // Tier 1 External Plugin Registration

        test("registerExternalPlugin adds namespace functions") {
            val registry = LibraryRegistry()
            val manifest =
                NoxPluginManifest(
                    namespace = "GameAPI",
                    functions =
                        listOf(
                            NoxExternalFunc(
                                "spawn",
                                listOf(NoxTypeTag.STRING, NoxTypeTag.DOUBLE, NoxTypeTag.DOUBLE),
                                NoxTypeTag.INT,
                            ),
                            NoxExternalFunc("destroy", listOf(NoxTypeTag.INT), NoxTypeTag.VOID),
                        ),
                )
            registry.registerExternalPlugin(manifest)

            registry.isBuiltinNamespace("GameAPI") shouldBe true
            registry.externalPluginNamespaces shouldContainExactlyInAnyOrder setOf("GameAPI")

            val spawn = registry.lookupNamespaceFunc("GameAPI", "spawn")
            spawn.shouldNotBeNull()
            spawn.params.size shouldBe 3
            spawn.params[0].type shouldBe TypeRef.STRING
            spawn.params[1].type shouldBe TypeRef.DOUBLE
            spawn.params[2].type shouldBe TypeRef.DOUBLE
            spawn.returnType shouldBe TypeRef.INT

            val destroy = registry.lookupNamespaceFunc("GameAPI", "destroy")
            destroy.shouldNotBeNull()
            destroy.returnType shouldBe TypeRef.VOID
        }

        // Type Mapping Utilities

        test("kotlinTypeToTypeRef maps Kotlin types correctly") {
            LibraryRegistry.kotlinTypeToTypeRef(Long::class.java) shouldBe TypeRef.INT
            LibraryRegistry.kotlinTypeToTypeRef(Int::class.java) shouldBe TypeRef.INT
            LibraryRegistry.kotlinTypeToTypeRef(Double::class.java) shouldBe TypeRef.DOUBLE
            LibraryRegistry.kotlinTypeToTypeRef(Boolean::class.java) shouldBe TypeRef.BOOLEAN
            LibraryRegistry.kotlinTypeToTypeRef(String::class.java) shouldBe TypeRef.STRING
            LibraryRegistry.kotlinTypeToTypeRef(Unit::class.java) shouldBe TypeRef.VOID
        }

        test("parseTypeRefString handles simple and array types") {
            LibraryRegistry.parseTypeRefString("int") shouldBe TypeRef.INT
            LibraryRegistry.parseTypeRefString("string[]") shouldBe TypeRef("string", 1)
            LibraryRegistry.parseTypeRefString("int[][]") shouldBe TypeRef("int", 2)
            LibraryRegistry.parseTypeRefString("json") shouldBe TypeRef.JSON
        }

        // Parameter Handling

        test("RuntimeContext parameters are excluded from Nox-visible params") {
            val registry = LibraryRegistry.createDefault()
            // File.read takes (ctx: RuntimeContext, path: String) in Kotlin
            // but should only expose (path: string) to Nox
            val read = registry.lookupNamespaceFunc("File", "read")
            read.shouldNotBeNull()
            read.params.size shouldBe 1
            read.params[0].type shouldBe TypeRef.STRING
        }

        test("@NoxType annotation overrides return type") {
            val registry = LibraryRegistry.createDefault()
            // Math.floor returns Long in Kotlin but is annotated @NoxType("int")
            val floor = registry.lookupNamespaceFunc("Math", "floor")
            floor.shouldNotBeNull()
            floor.returnType shouldBe TypeRef.INT
        }

        // Full stdlib regression: verify Math namespace

        test("Math namespace has all expected functions") {
            val registry = LibraryRegistry.createDefault()
            val mathFuncs = listOf("sqrt", "abs", "min", "max", "floor", "ceil", "round", "random", "pow")
            for (func in mathFuncs) {
                registry.lookupNamespaceFunc("Math", func).shouldNotBeNull()
            }
        }

        test("Math.sqrt has correct signature") {
            val registry = LibraryRegistry.createDefault()
            val sqrt = registry.lookupNamespaceFunc("Math", "sqrt")
            sqrt.shouldNotBeNull()
            sqrt.params.size shouldBe 1
            sqrt.params[0].type shouldBe TypeRef.DOUBLE
            sqrt.returnType shouldBe TypeRef.DOUBLE
            sqrt.name shouldContain "Math__sqrt"
        }
    })

// Test fixtures

@NoxModule(namespace = "TestNS")
object TestNamespaceModule {
    @NoxFunction(name = "add")
    @NoxType("int")
    @JvmStatic
    fun add(
        a: Long,
        b: Long,
    ): Long = a + b

    @NoxFunction(name = "greet")
    @JvmStatic
    fun greet(name: String): String = "Hello, $name!"
}

@NoxModule(namespace = "TestNS2")
object TestNamespaceModule2 {
    @NoxFunction(name = "add")
    @NoxType("int")
    @JvmStatic
    fun add(
        a: Long,
        b: Long,
    ): Long = a + b
}

@NoxModule(namespace = "_TestTypeMethods")
object TestTypeMethods {
    @NoxTypeMethod(targetType = "string", name = "reverse")
    @JvmStatic
    fun reverse(s: String): String = s.reversed()

    @NoxTypeMethod(targetType = "string", name = "toUpperCase")
    @JvmStatic
    fun toUpperCase(s: String): String = s.uppercase()
}
