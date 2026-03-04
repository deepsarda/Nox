package nox.compiler.ast

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TypeRefTest :
    FunSpec({

        test("isPrimitive correctly identifies primitive types") {
            TypeRef.INT.isPrimitive() shouldBe true
            TypeRef.DOUBLE.isPrimitive() shouldBe true
            TypeRef.BOOLEAN.isPrimitive() shouldBe true

            TypeRef.STRING.isPrimitive() shouldBe false
            TypeRef.JSON.isPrimitive() shouldBe false
            TypeRef.VOID.isPrimitive() shouldBe false
            TypeRef("Custom").isPrimitive() shouldBe false
            TypeRef("int", isArray = true).isPrimitive() shouldBe false
        }

        test("isPassByValue correctly identifies pass-by-value types") {
            TypeRef.INT.isPassByValue() shouldBe true
            TypeRef.DOUBLE.isPassByValue() shouldBe true
            TypeRef.BOOLEAN.isPassByValue() shouldBe true
            TypeRef.STRING.isPassByValue() shouldBe true

            TypeRef.JSON.isPassByValue() shouldBe false
            TypeRef.VOID.isPassByValue() shouldBe false
            TypeRef("Custom").isPassByValue() shouldBe false
            TypeRef("int", isArray = true).isPassByValue() shouldBe false
        }

        test("isNullable correctly identifies nullable types") {
            TypeRef.INT.isNullable() shouldBe false
            TypeRef.DOUBLE.isNullable() shouldBe false
            TypeRef.BOOLEAN.isNullable() shouldBe false

            TypeRef.STRING.isNullable() shouldBe true
            TypeRef.JSON.isNullable() shouldBe true
            TypeRef.VOID.isNullable() shouldBe true
            TypeRef("Custom").isNullable() shouldBe true
            TypeRef("int", isArray = true).isNullable() shouldBe true
        }

        test("toString formats type name correctly") {
            TypeRef.INT.toString() shouldBe "int"
            TypeRef("Custom").toString() shouldBe "Custom"
            TypeRef("int", isArray = true).toString() shouldBe "int[]"
            TypeRef("Point", isArray = true).toString() shouldBe "Point[]"
        }

        test("data class copy and equality") {
            val t1 = TypeRef("int")
            val t2 = TypeRef("int")
            val t3 = t1.copy(isArray = true)

            (t1 == t2) shouldBe true
            (t1 == t3) shouldBe false
            t3.isArray shouldBe true
            t3.name shouldBe "int"
        }
    })
