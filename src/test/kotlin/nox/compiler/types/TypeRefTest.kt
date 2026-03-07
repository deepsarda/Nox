package nox.compiler.types

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
            TypeRef.VOID.isNullable() shouldBe false
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
            val t3 = t1.copy(arrayDepth = 1)

            (t1 == t2) shouldBe true
            (t1 == t3) shouldBe false
            t3.isArray shouldBe true
            t3.name shouldBe "int"
        }

        // Multi-dimensional arrays

        test("arrayDepth and elementType") {
            val scalar = TypeRef("int")
            val arr1d = TypeRef("int", 1)
            val arr2d = TypeRef("int", 2)

            scalar.arrayDepth shouldBe 0
            scalar.isArray shouldBe false
            arr1d.arrayDepth shouldBe 1
            arr1d.isArray shouldBe true
            arr2d.arrayDepth shouldBe 2
            arr2d.isArray shouldBe true

            arr2d.elementType() shouldBe arr1d
            arr1d.elementType() shouldBe scalar
            scalar.elementType() shouldBe scalar  // no-op on non-array
        }

        test("arrayOf adds dimension") {
            val scalar = TypeRef("int")
            scalar.arrayOf() shouldBe TypeRef("int", 1)
            scalar.arrayOf().arrayOf() shouldBe TypeRef("int", 2)
        }

        test("toString with multi-dimensional arrays") {
            TypeRef("int", 0).toString() shouldBe "int"
            TypeRef("int", 1).toString() shouldBe "int[]"
            TypeRef("int", 2).toString() shouldBe "int[][]"
            TypeRef("Point", 3).toString() shouldBe "Point[][][]"
        }

        test("isAssignableFrom same depth required") {
            val arr1d = TypeRef("int", 1)
            val arr2d = TypeRef("int", 2)
            arr1d.isAssignableFrom(arr2d) shouldBe false
            arr2d.isAssignableFrom(arr1d) shouldBe false
            arr2d.isAssignableFrom(arr2d) shouldBe true
        }

        test("backward compat boolean constructor") {
            TypeRef("int", true) shouldBe TypeRef("int", 1)
            TypeRef("int", false) shouldBe TypeRef("int", 0)
        }

        // isAssignableFrom

        test("isAssignableFrom same type") {
            TypeRef.INT.isAssignableFrom(TypeRef.INT) shouldBe true
            TypeRef.STRING.isAssignableFrom(TypeRef.STRING) shouldBe true
        }

        test("isAssignableFrom int to double widening") {
            TypeRef.DOUBLE.isAssignableFrom(TypeRef.INT) shouldBe true
        }

        test("isAssignableFrom double to int narrowing rejected") {
            TypeRef.INT.isAssignableFrom(TypeRef.DOUBLE) shouldBe false
        }

        test("isAssignableFrom struct to json upcast") {
            val structType = TypeRef("Point")
            TypeRef.JSON.isAssignableFrom(structType) shouldBe true
        }

        test("isAssignableFrom json to struct rejected") {
            val structType = TypeRef("Point")
            structType.isAssignableFrom(TypeRef.JSON) shouldBe false
        }

        test("isAssignableFrom null to nullable") {
            TypeRef.STRING.isAssignableFrom(null) shouldBe true
            TypeRef.JSON.isAssignableFrom(null) shouldBe true
        }

        test("isAssignableFrom null to non-nullable") {
            TypeRef.INT.isAssignableFrom(null) shouldBe false
            TypeRef.DOUBLE.isAssignableFrom(null) shouldBe false
            TypeRef.BOOLEAN.isAssignableFrom(null) shouldBe false
        }

        test("isAssignableFrom struct array to json array") {
            val structArray = TypeRef("Point", isArray = true)
            val jsonArray = TypeRef("json", isArray = true)
            jsonArray.isAssignableFrom(structArray) shouldBe true
        }

        // isComparable

        test("isComparable same type") {
            TypeRef.INT.isComparable(TypeRef.INT) shouldBe true
            TypeRef.STRING.isComparable(TypeRef.STRING) shouldBe true
        }

        test("isComparable different types rejected") {
            TypeRef.INT.isComparable(TypeRef.STRING) shouldBe false
            TypeRef.BOOLEAN.isComparable(TypeRef.INT) shouldBe false
        }

        test("isComparable struct and json") {
            val structType = TypeRef("Point")
            structType.isComparable(TypeRef.JSON) shouldBe true
            TypeRef.JSON.isComparable(structType) shouldBe true
        }

        test("isComparable null with nullable") {
            TypeRef.STRING.isComparable(null) shouldBe true
            TypeRef.JSON.isComparable(null) shouldBe true
        }

        test("isComparable null with non-nullable") {
            TypeRef.INT.isComparable(null) shouldBe false
        }

        // isValidAsVariable

        test("isValidAsVariable accepts int") {
            TypeRef.INT.isValidAsVariable() shouldBe true
        }

        test("isValidAsVariable accepts string array") {
            TypeRef("string", isArray = true).isValidAsVariable() shouldBe true
        }

        test("isValidAsVariable rejects void") {
            TypeRef.VOID.isValidAsVariable() shouldBe false
        }

        test("isValidAsVariable rejects void array") {
            TypeRef("void", isArray = true).isValidAsVariable() shouldBe false
        }
    })
