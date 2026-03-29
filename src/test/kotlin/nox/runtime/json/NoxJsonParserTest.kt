package nox.runtime.json

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class NoxJsonParserTest :
    FunSpec({

        // Primitives

        test("parse null") {
            NoxJsonParser("null").parse().shouldBeNull()
        }

        test("parse true") {
            NoxJsonParser("true").parse() shouldBe true
        }

        test("parse false") {
            NoxJsonParser("false").parse() shouldBe false
        }

        test("parse integer as Long") {
            val result = NoxJsonParser("42").parse()
            result.shouldBeInstanceOf<Long>()
            result shouldBe 42L
        }

        test("parse negative integer") {
            NoxJsonParser("-17").parse() shouldBe -17L
        }

        test("parse zero") {
            NoxJsonParser("0").parse() shouldBe 0L
        }

        test("parse decimal as Double") {
            val result = NoxJsonParser("3.14").parse()
            result.shouldBeInstanceOf<Double>()
            result shouldBe 3.14
        }

        test("parse integer with exponent as Double") {
            val result = NoxJsonParser("1e3").parse()
            result.shouldBeInstanceOf<Double>()
            result shouldBe 1000.0
        }

        test("parse negative exponent") {
            val result = NoxJsonParser("1.5e-2").parse()
            result.shouldBeInstanceOf<Double>()
            result shouldBe 0.015
        }

        test("parse Long overflow falls back to Double") {
            val huge = "99999999999999999999"
            val result = NoxJsonParser(huge).parse()
            result.shouldBeInstanceOf<Double>()
        }

        test("parse simple string") {
            NoxJsonParser("\"hello\"").parse() shouldBe "hello"
        }

        test("parse empty string") {
            NoxJsonParser("\"\"").parse() shouldBe ""
        }

        // String Escapes

        test("parse string with basic escapes") {
            NoxJsonParser("\"a\\nb\\tc\"").parse() shouldBe "a\nb\tc"
        }

        test("parse string with all escape types") {
            NoxJsonParser("\"\\\"\\\\\\b\\f\\n\\r\\t\\/\"").parse() shouldBe "\"\\\b\u000C\n\r\t/"
        }

        test("parse unicode escape") {
            NoxJsonParser("\"\\u0041\"").parse() shouldBe "A"
        }

        test("parse surrogate pair") {
            // U+1F600 (grinning face) = \uD83D\uDE00
            val result = NoxJsonParser("\"\\uD83D\\uDE00\"").parse()
            result shouldBe "\uD83D\uDE00"
        }

        test("parse string with unicode characters") {
            NoxJsonParser("\"日本語\"").parse() shouldBe "日本語"
        }

        // Objects

        test("parse empty object") {
            val result = NoxJsonParser("{}").parse()
            result.shouldBeInstanceOf<LinkedHashMap<*, *>>()
            (result as Map<*, *>) shouldHaveSize 0
        }

        test("parse simple object") {
            val result = NoxJsonParser("""{"name":"Alice","age":30}""").parse()
            result.shouldBeInstanceOf<LinkedHashMap<*, *>>()
            val map = result as Map<String, Any?>
            map["name"] shouldBe "Alice"
            map["age"] shouldBe 30L
        }

        test("parse object preserves insertion order") {
            val result = NoxJsonParser("""{"z":1,"a":2,"m":3}""").parse()
            val keys = (result as Map<*, *>).keys.toList()
            keys shouldBe listOf("z", "a", "m")
        }

        test("parse nested objects") {
            val json = """{"outer":{"inner":{"value":42}}}"""
            val result = NoxJsonParser(json).parse() as Map<String, Any?>
            val outer = result["outer"] as Map<String, Any?>
            val inner = outer["inner"] as Map<String, Any?>
            inner["value"] shouldBe 42L
        }

        // Arrays

        test("parse empty array") {
            val result = NoxJsonParser("[]").parse()
            result.shouldBeInstanceOf<ArrayList<*>>()
            (result as List<*>) shouldHaveSize 0
        }

        test("parse integer array") {
            val result = NoxJsonParser("[1, 2, 3]").parse() as List<*>
            result shouldBe listOf(1L, 2L, 3L)
        }

        test("parse mixed-type array") {
            val result = NoxJsonParser("""[1, "two", true, null, 3.14]""").parse() as List<*>
            result[0] shouldBe 1L
            result[1] shouldBe "two"
            result[2] shouldBe true
            result[3].shouldBeNull()
            result[4] shouldBe 3.14
        }

        test("parse nested arrays") {
            val result = NoxJsonParser("[[1, 2], [3, 4]]").parse() as List<*>
            result[0] shouldBe listOf(1L, 2L)
            result[1] shouldBe listOf(3L, 4L)
        }

        test("parse array of objects") {
            val json = """[{"id":1},{"id":2}]"""
            val result = NoxJsonParser(json).parse() as List<*>
            result shouldHaveSize 2
            (result[0] as Map<String, Any?>)["id"] shouldBe 1L
        }

        // Complex

        test("parse realistic API response") {
            val json =
                """
                {
                    "status": "ok",
                    "count": 2,
                    "data": [
                        {"name": "Alice", "score": 95.5, "active": true},
                        {"name": "Bob", "score": 87.0, "active": false}
                    ],
                    "meta": null
                }
                """.trimIndent()
            val result = NoxJsonParser(json).parse() as Map<String, Any?>
            result["status"] shouldBe "ok"
            result["count"] shouldBe 2L
            result["meta"].shouldBeNull()
            val data = result["data"] as List<*>
            data shouldHaveSize 2
            val alice = data[0] as Map<String, Any?>
            alice["name"] shouldBe "Alice"
            alice["score"] shouldBe 95.5
            alice["active"] shouldBe true
        }

        // Whitespace

        test("parse with leading/trailing whitespace") {
            NoxJsonParser("  42  ").parse() shouldBe 42L
        }

        test("parse object with whitespace") {
            NoxJsonParser(" { \"a\" : 1 , \"b\" : 2 } ")
                .parse()
                .shouldBeInstanceOf<Map<*, *>>()
        }

        // Error Cases

        test("reject empty input") {
            shouldThrow<IllegalArgumentException> {
                NoxJsonParser("").parse()
            }
        }

        test("reject trailing garbage") {
            shouldThrow<IllegalArgumentException> {
                NoxJsonParser("42 xyz").parse()
            }
        }

        test("reject unterminated string") {
            shouldThrow<IllegalArgumentException> {
                NoxJsonParser("\"hello").parse()
            }
        }

        test("reject unterminated object") {
            shouldThrow<IllegalArgumentException> {
                NoxJsonParser("{\"a\":1").parse()
            }
        }

        test("reject unterminated array") {
            shouldThrow<IllegalArgumentException> {
                NoxJsonParser("[1, 2").parse()
            }
        }

        test("reject invalid literal") {
            shouldThrow<IllegalArgumentException> {
                NoxJsonParser("tru").parse()
            }
        }

        test("reject leading zeros") {
            shouldThrow<IllegalArgumentException> {
                NoxJsonParser("01").parse()
            }
        }

        test("reject unescaped control characters in string") {
            shouldThrow<IllegalArgumentException> {
                NoxJsonParser("\"hello\u0001world\"").parse()
            }
        }

        test("reject invalid escape") {
            shouldThrow<IllegalArgumentException> {
                NoxJsonParser("\"\\x\"").parse()
            }
        }

        test("reject incomplete unicode escape") {
            shouldThrow<IllegalArgumentException> {
                NoxJsonParser("\"\\u00\"").parse()
            }
        }

        // Security Limits

        test("reject excessive nesting depth") {
            val deep = "[".repeat(100) + "1" + "]".repeat(100)
            shouldThrow<IllegalArgumentException> {
                NoxJsonParser(deep, NoxJsonLimits(maxDepth = 64)).parse()
            }
        }

        test("reject excessive string length") {
            val long = "\"" + "a".repeat(101) + "\""
            shouldThrow<IllegalArgumentException> {
                NoxJsonParser(long, NoxJsonLimits(maxStringLength = 100)).parse()
            }
        }

        test("reject excessive key count") {
            val keys = (1..11).joinToString(",") { "\"k$it\":$it" }
            val json = "{$keys}"
            shouldThrow<IllegalArgumentException> {
                NoxJsonParser(json, NoxJsonLimits(maxKeys = 10)).parse()
            }
        }

        test("depth limit allows exactly maxDepth") {
            val json = "[".repeat(3) + "1" + "]".repeat(3)
            val result = NoxJsonParser(json, NoxJsonLimits(maxDepth = 3)).parse()
            // Should succeed since it's exactly 3 levels deep
            result.shouldBeInstanceOf<List<*>>()
        }

        // Number edge cases

        test("parse -0") {
            NoxJsonParser("-0").parse() shouldBe 0L
        }

        test("parse decimal without leading zero") {
            // RFC 8259 requires leading zero: 0.5, not .5
            shouldThrow<IllegalArgumentException> {
                NoxJsonParser(".5").parse()
            }
        }

        test("parse number with positive exponent sign") {
            val result = NoxJsonParser("1e+2").parse()
            result.shouldBeInstanceOf<Double>()
            result shouldBe 100.0
        }

        // Object edge cases

        test("duplicate keys - last value wins") {
            val result = NoxJsonParser("""{"a":1,"a":2}""").parse() as Map<String, Any?>
            result["a"] shouldBe 2L
        }

        test("object with null value") {
            val result = NoxJsonParser("""{"key":null}""").parse() as Map<String, Any?>
            ("key" in result) shouldBe true
            result["key"].shouldBeNull()
        }
    })
