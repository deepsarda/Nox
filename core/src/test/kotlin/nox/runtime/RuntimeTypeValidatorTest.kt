package nox.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import nox.compiler.NoxCompiler
import nox.compiler.types.TypeRef
import nox.vm.NoxException

class RuntimeTypeValidatorTest :
    FunSpec({

        test("coerces strings to primitives") {
            RuntimeTypeValidator.validateAndCoerce("42", TypeRef.INT, null) shouldBe 42L
            RuntimeTypeValidator.validateAndCoerce("-1", TypeRef.INT, null) shouldBe -1L
            RuntimeTypeValidator.validateAndCoerce("3.14", TypeRef.DOUBLE, null) shouldBe 3.14
            RuntimeTypeValidator.validateAndCoerce("true", TypeRef.BOOLEAN, null) shouldBe true
            RuntimeTypeValidator.validateAndCoerce("false", TypeRef.BOOLEAN, null) shouldBe false
            RuntimeTypeValidator.validateAndCoerce("hello", TypeRef.STRING, null) shouldBe "hello"
        }

        test("throws TypeError for invalid primitive conversions") {
            shouldThrow<NoxException> {
                RuntimeTypeValidator.validateAndCoerce("notanint", TypeRef.INT, null)
            }.type shouldBe NoxError.TypeError

            shouldThrow<NoxException> {
                RuntimeTypeValidator.validateAndCoerce("notadouble", TypeRef.DOUBLE, null)
            }.type shouldBe NoxError.TypeError

            shouldThrow<NoxException> {
                RuntimeTypeValidator.validateAndCoerce("yes", TypeRef.BOOLEAN, null)
            }.type shouldBe NoxError.TypeError
        }

        test("coerces json strings to Map/List") {
            val list = RuntimeTypeValidator.validateAndCoerce("[1, 2, 3]", TypeRef.INT.arrayOf(), null) as List<*>
            list.size shouldBe 3
            list[0] shouldBe 1L

            val map = RuntimeTypeValidator.validateAndCoerce("""{"key": "value"}""", TypeRef.JSON, null) as Map<*, *>
            map["key"] shouldBe "value"
        }

        test("throws TypeError for invalid JSON") {
            shouldThrow<NoxException> {
                RuntimeTypeValidator.validateAndCoerce("{invalid", TypeRef.JSON, null)
            }.type shouldBe NoxError.TypeError
        }

        test("validates structs strictly") {
            val source =
                """
                type Point { int x; int y; }
                """.trimIndent()
            val result = NoxCompiler.compile(source, "test.nox")
            val program = result.typedProgram!!

            val validJson = """{"x": 10, "y": 20}"""
            val validMap = RuntimeTypeValidator.validateAndCoerce(validJson, TypeRef("Point"), program) as Map<*, *>
            validMap["x"] shouldBe 10L
            validMap["y"] shouldBe 20L

            // Missing field
            val missingJson = """{"x": 10}"""
            shouldThrow<NoxException> {
                RuntimeTypeValidator.validateAndCoerce(missingJson, TypeRef("Point"), program)
            }.message shouldBe "Missing required field 'y' of type 'int' for struct 'Point'"

            // Type mismatch
            val typeMismatchJson = """{"x": 10, "y": "notanumber"}"""
            shouldThrow<NoxException> {
                RuntimeTypeValidator.validateAndCoerce(typeMismatchJson, TypeRef("Point"), program)
            }.message shouldBe "Expected int, got String: 'notanumber'"
        }

        test("throws TypeError for missing struct definitions") {
            val source =
                """
                type Point { int x; int y; }
                """.trimIndent()
            val result = NoxCompiler.compile(source, "test.nox")
            val program = result.typedProgram!!

            shouldThrow<NoxException> {
                RuntimeTypeValidator.validateAndCoerce("""{"x": 1}""", TypeRef("Unknown"), program)
            }.type shouldBe NoxError.CompilationError
        }
    })
