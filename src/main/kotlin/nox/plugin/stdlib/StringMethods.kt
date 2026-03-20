package nox.plugin.stdlib

import nox.plugin.annotations.NoxModule
import nox.plugin.annotations.NoxType
import nox.plugin.annotations.NoxTypeMethod

/**
 * Nox standard library: `string` type-bound methods.
 *
 * NSL usage:
 * ```
 * string s = "hello world";
 * string u = s.upper();           // "HELLO WORLD"
 * string l = s.lower();           // "hello world"
 * boolean has = s.contains("lo"); // true
 * string[] parts = s.split(" ");  // ["hello", "world"]
 * int len = s.length();           // 11
 * ```
 *
 * See docs/language/stdlib.md.
 */
@NoxModule(namespace = "_StringMethods")
object StringMethods {
    @NoxTypeMethod(targetType = "string", name = "upper")
    @JvmStatic
    fun upper(value: String): String = value.uppercase()

    @NoxTypeMethod(targetType = "string", name = "lower")
    @JvmStatic
    fun lower(value: String): String = value.lowercase()

    @NoxTypeMethod(targetType = "string", name = "contains")
    @JvmStatic
    fun contains(
        value: String,
        sub: String,
    ): Boolean = value.contains(sub)

    @NoxTypeMethod(targetType = "string", name = "split")
    @NoxType("string[]")
    @JvmStatic
    fun split(
        value: String,
        delim: String,
    ): List<String> = value.split(delim)

    @NoxTypeMethod(targetType = "string", name = "length")
    @NoxType("int")
    @JvmStatic
    fun length(value: String): Long = value.length.toLong()
}
