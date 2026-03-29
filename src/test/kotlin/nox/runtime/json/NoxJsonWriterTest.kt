package nox.runtime.json

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NoxJsonWriterTest :
    FunSpec({

        val writer = NoxJsonWriter(prettyPrint = false)

        // Primitives

        test("write null") {
            writer.write(null) shouldBe "null"
        }

        test("write true") {
            writer.write(true) shouldBe "true"
        }

        test("write false") {
            writer.write(false) shouldBe "false"
        }

        test("write Long") {
            writer.write(42L) shouldBe "42"
        }

        test("write negative Long") {
            writer.write(-17L) shouldBe "-17"
        }

        test("write zero Long") {
            writer.write(0L) shouldBe "0"
        }

        test("write Double") {
            writer.write(3.14) shouldBe "3.14"
        }

        test("write whole Double preserves .0") {
            writer.write(42.0) shouldBe "42.0"
        }

        test("write NaN as null") {
            writer.write(Double.NaN) shouldBe "null"
        }

        test("write positive Infinity as null") {
            writer.write(Double.POSITIVE_INFINITY) shouldBe "null"
        }

        test("write negative Infinity as null") {
            writer.write(Double.NEGATIVE_INFINITY) shouldBe "null"
        }

        test("write Int coerced to Long") {
            writer.write(42) shouldBe "42"
        }

        // Strings

        test("write simple string") {
            writer.write("hello") shouldBe "\"hello\""
        }

        test("write empty string") {
            writer.write("") shouldBe "\"\""
        }

        test("write string with escapes") {
            writer.write("a\"b\\c\n") shouldBe "\"a\\\"b\\\\c\\n\""
        }

        test("write string with all escape types") {
            writer.write("\"\\\b\u000C\n\r\t") shouldBe "\"\\\"\\\\\\b\\f\\n\\r\\t\""
        }

        test("write string with control character") {
            writer.write("a\u0001b") shouldBe "\"a\\u0001b\""
        }

        test("write string with unicode") {
            writer.write("日本語") shouldBe "\"日本語\""
        }

        // Objects

        test("write empty object") {
            writer.write(emptyMap<String, Any?>()) shouldBe "{}"
        }

        test("write simple object") {
            val map = linkedMapOf<String, Any?>("name" to "Alice", "age" to 30L)
            writer.write(map) shouldBe """{"name":"Alice","age":30}"""
        }

        test("write nested object") {
            val inner = linkedMapOf<String, Any?>("value" to 42L)
            val outer = linkedMapOf<String, Any?>("inner" to inner)
            writer.write(outer) shouldBe """{"inner":{"value":42}}"""
        }

        test("write object with null value") {
            val map = linkedMapOf<String, Any?>("key" to null)
            writer.write(map) shouldBe """{"key":null}"""
        }

        // Arrays

        test("write empty array") {
            writer.write(emptyList<Any?>()) shouldBe "[]"
        }

        test("write integer array") {
            writer.write(listOf(1L, 2L, 3L)) shouldBe "[1,2,3]"
        }

        test("write mixed array") {
            writer.write(listOf(1L, "two", true, null)) shouldBe """[1,"two",true,null]"""
        }

        test("write nested arrays") {
            writer.write(listOf(listOf(1L, 2L), listOf(3L, 4L))) shouldBe "[[1,2],[3,4]]"
        }

        // Depth limit

        test("depth limit emits null for deeply nested structures") {
            val limited = NoxJsonWriter(maxDepth = 2, prettyPrint = false)
            val deep = mapOf("a" to mapOf("b" to mapOf("c" to 1L)))
            // Depth 0: outer map, depth 1: middle map, depth 2: inner map (at maxDepth) → null
            limited.write(deep) shouldBe """{"a":{"b":null}}"""
        }

        // Round-trip

        test("round-trip: parse then stringify simple object") {
            val json = """{"name":"Alice","age":30,"scores":[95,87,92]}"""
            val parsed = NoxJsonParser(json).parse()
            val written = writer.write(parsed)
            // Re-parse to verify structural equality
            val reparsed = NoxJsonParser(written).parse()
            reparsed shouldBe parsed
        }

        test("round-trip: parse then stringify complex nested structure") {
            val json =
                """{"users":[{"name":"Alice","active":true,"meta":null},
                |{"name":"Bob","active":false,"score":3.14}]}
                """.trimMargin()
            val parsed = NoxJsonParser(json).parse()
            val reparsed = NoxJsonParser(writer.write(parsed)).parse()
            reparsed shouldBe parsed
        }

        test("round-trip: preserves Long vs Double distinction") {
            val json = """{"int":42,"double":42.0}"""
            val parsed = NoxJsonParser(json).parse() as Map<String, Any?>
            val written = writer.write(parsed)
            val reparsed = NoxJsonParser(written).parse() as Map<String, Any?>
            reparsed["int"] shouldBe 42L
            reparsed["double"] shouldBe 42.0
        }

        test("round-trip: string escapes preserved") {
            val json = """{"text":"line1\nline2\ttab\\back\"quote"}"""
            val parsed = NoxJsonParser(json).parse()
            val reparsed = NoxJsonParser(writer.write(parsed)).parse()
            reparsed shouldBe parsed
        }

        // Unknown types

        test("write unknown type as null") {
            writer.write(Object()) shouldBe "null"
        }

        // Pretty-printing

        val pretty = NoxJsonWriter()

        test("pretty-print simple object") {
            val map = linkedMapOf<String, Any?>("name" to "Alice", "age" to 30L)
            pretty.write(map) shouldBe "{\n  \"name\": \"Alice\",\n  \"age\": 30\n}"
        }

        test("pretty-print nested object") {
            val inner = linkedMapOf<String, Any?>("value" to 42L)
            val outer = linkedMapOf<String, Any?>("inner" to inner)
            pretty.write(outer) shouldBe "{\n  \"inner\": {\n    \"value\": 42\n  }\n}"
        }

        test("pretty-print empty object stays compact") {
            pretty.write(emptyMap<String, Any?>()) shouldBe "{}"
        }

        test("pretty-print integer array") {
            pretty.write(listOf(1L, 2L, 3L)) shouldBe "[\n  1,\n  2,\n  3\n]"
        }

        test("pretty-print nested arrays") {
            pretty.write(listOf(listOf(1L, 2L), listOf(3L, 4L))) shouldBe
                "[\n  [\n    1,\n    2\n  ],\n  [\n    3,\n    4\n  ]\n]"
        }

        test("pretty-print empty array stays compact") {
            pretty.write(emptyList<Any?>()) shouldBe "[]"
        }

        test("pretty-print object with array value") {
            val map = linkedMapOf<String, Any?>("nums" to listOf(1L, 2L))
            pretty.write(map) shouldBe "{\n  \"nums\": [\n    1,\n    2\n  ]\n}"
        }

        test("pretty-print depth limit still works") {
            val limited = NoxJsonWriter(maxDepth = 2, prettyPrint = true)
            val deep = mapOf("a" to mapOf("b" to mapOf("c" to 1L)))
            limited.write(deep) shouldBe "{\n  \"a\": {\n    \"b\": null\n  }\n}"
        }

        test("pretty-print round-trip preserves data") {
            val json = """{"name":"Alice","scores":[95,87]}"""
            val parsed = NoxJsonParser(json).parse()
            val prettyStr = pretty.write(parsed)
            val reparsed = NoxJsonParser(prettyStr).parse()
            reparsed shouldBe parsed
        }
    })
