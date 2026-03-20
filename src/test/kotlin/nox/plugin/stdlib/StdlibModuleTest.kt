package nox.plugin.stdlib

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe

class StdlibModuleTest :
    FunSpec({

        // MathModule

        test("Math.sqrt computes square root") {
            MathModule.sqrt(16.0) shouldBe 4.0
            MathModule.sqrt(0.0) shouldBe 0.0
        }

        test("Math.abs returns absolute value") {
            MathModule.abs(-5.0) shouldBe 5.0
            MathModule.abs(3.0) shouldBe 3.0
        }

        test("Math.min and max") {
            MathModule.min(3.0, 7.0) shouldBe 3.0
            MathModule.max(3.0, 7.0) shouldBe 7.0
        }

        test("Math.floor and ceil") {
            MathModule.floor(3.7) shouldBe 3L
            MathModule.ceil(3.2) shouldBe 4L
        }

        test("Math.round") {
            MathModule.round(3.5) shouldBe 4L
            MathModule.round(3.4) shouldBe 3L
        }

        test("Math.random returns value in [0, 1)") {
            val r = MathModule.random()
            r shouldBeGreaterThanOrEqual 0.0
            r shouldBeLessThan 1.0
        }

        test("Math.pow") {
            MathModule.pow(2.0, 10.0) shouldBe 1024.0
            MathModule.pow(3.0, 0.0) shouldBe 1.0
        }

        // DateModule

        test("Date.now returns current timestamp in millis") {
            val before = System.currentTimeMillis()
            val now = DateModule.now()
            val after = System.currentTimeMillis()
            (now in before..after) shouldBe true
        }

        // StringMethods

        test("string.upper and lower") {
            StringMethods.upper("hello") shouldBe "HELLO"
            StringMethods.lower("HELLO") shouldBe "hello"
        }

        test("string.contains") {
            StringMethods.contains("hello world", "world") shouldBe true
            StringMethods.contains("hello world", "xyz") shouldBe false
        }

        test("string.split") {
            StringMethods.split("a,b,c", ",") shouldBe listOf("a", "b", "c")
            StringMethods.split("hello", ",") shouldBe listOf("hello")
        }

        test("string.length") {
            StringMethods.length("hello") shouldBe 5L
            StringMethods.length("") shouldBe 0L
        }

        // TypeConversionMethods

        test("int.toDouble") {
            TypeConversionMethods.intToDouble(42L) shouldBe 42.0
        }

        test("int.toString") {
            TypeConversionMethods.intToString(42L) shouldBe "42"
        }

        test("double.toInt truncates") {
            TypeConversionMethods.doubleToInt(3.9) shouldBe 3L
        }

        test("double.toString") {
            TypeConversionMethods.doubleToString(3.14) shouldBe "3.14"
        }

        test("string.toInt parses valid integer") {
            TypeConversionMethods.stringToInt("42", 0L) shouldBe 42L
        }

        test("string.toInt returns 0 for invalid input") {
            TypeConversionMethods.stringToInt("abc", 0L) shouldBe 0L
        }

        test("string.toDouble parses valid double") {
            TypeConversionMethods.stringToDouble("3.14", 0.0) shouldBe 3.14
        }

        test("string.toDouble returns 0.0 for invalid input") {
            TypeConversionMethods.stringToDouble("abc", 0.0) shouldBe 0.0
        }

        test("bool.toString") {
            TypeConversionMethods.boolToString(true) shouldBe "true"
            TypeConversionMethods.boolToString(false) shouldBe "false"
        }

        // JsonMethods

        test("json.getString extracts string or returns default") {
            val obj = mapOf("name" to "Alice", "age" to 30)
            JsonMethods.getString(obj, "name", "?") shouldBe "Alice"
            JsonMethods.getString(obj, "missing", "default") shouldBe "default"
        }

        test("json.getInt extracts int or returns default") {
            val obj = mapOf("count" to 42L)
            JsonMethods.getInt(obj, "count", 0L) shouldBe 42L
            JsonMethods.getInt(obj, "missing", -1L) shouldBe -1L
        }

        test("json.getBool extracts boolean or returns default") {
            val obj = mapOf("active" to true)
            JsonMethods.getBool(obj, "active", false) shouldBe true
            JsonMethods.getBool(obj, "missing", false) shouldBe false
        }

        test("json.has checks key existence") {
            val obj = mapOf("key" to "value")
            JsonMethods.has(obj, "key") shouldBe true
            JsonMethods.has(obj, "other") shouldBe false
        }

        test("json.keys returns all keys") {
            val obj = mapOf("a" to 1, "b" to 2, "c" to 3)
            JsonMethods.keys(obj).toSet() shouldBe setOf("a", "b", "c")
        }

        test("json.size returns element count") {
            JsonMethods.size(mapOf("a" to 1, "b" to 2)) shouldBe 2L
            JsonMethods.size(listOf(1, 2, 3)) shouldBe 3L
            JsonMethods.size(null) shouldBe 0L
        }

        test("json methods handle null gracefully") {
            JsonMethods.getString(null, "key", "fallback") shouldBe "fallback"
            JsonMethods.getInt(null, "key", 99L) shouldBe 99L
            JsonMethods.has(null, "key") shouldBe false
            JsonMethods.keys(null) shouldBe emptyList()
        }

        // ArrayMethods

        test("array.push appends element") {
            val list = mutableListOf(1, 2, 3)
            ArrayMethods.push(list, 4)
            list shouldBe listOf(1, 2, 3, 4)
        }

        test("array.pop removes and returns last element") {
            val list = mutableListOf("a", "b", "c")
            val popped = ArrayMethods.pop(list)
            popped shouldBe "c"
            list shouldBe listOf("a", "b")
        }

        test("array.length returns size") {
            ArrayMethods.length(listOf(1, 2, 3)) shouldBe 3L
            ArrayMethods.length(emptyList<Any>()) shouldBe 0L
            ArrayMethods.length(null) shouldBe 0L
        }
    })
