package nox.plugin.stdlib

import nox.plugin.annotations.NoxModule
import nox.plugin.annotations.NoxType
import nox.plugin.annotations.NoxTypeMethod

/**
 * Nox standard library: type conversion methods.
 *
 * Bound to primitives and string for cross-type conversions:
 * - `int.toDouble()`, `int.toString()`
 * - `double.toInt()`, `double.toString()`
 * - `boolean.toString()`
 * - `string.toInt(default)`, `string.toDouble(default)`
 *
 * See docs/language/stdlib.md.
 */
@NoxModule(namespace = "_TypeConversions")
object TypeConversionMethods {
    @NoxTypeMethod(targetType = "int", name = "toDouble")
    @JvmStatic
    fun intToDouble(value: Long): Double = value.toDouble()

    @NoxTypeMethod(targetType = "int", name = "toString")
    @JvmStatic
    fun intToString(value: Long): String = value.toString()

    @NoxTypeMethod(targetType = "double", name = "toInt")
    @NoxType("int")
    @JvmStatic
    fun doubleToInt(value: Double): Long = value.toLong()

    @NoxTypeMethod(targetType = "double", name = "toString")
    @JvmStatic
    fun doubleToString(value: Double): String = value.toString()

    @NoxTypeMethod(targetType = "boolean", name = "toString")
    @JvmStatic
    fun boolToString(value: Boolean): String = value.toString()

    @NoxTypeMethod(targetType = "string", name = "toInt")
    @NoxType("int")
    @JvmStatic
    fun stringToInt(
        value: String,
        default: Long,
    ): Long = value.toLongOrNull() ?: default

    @NoxTypeMethod(targetType = "string", name = "toDouble")
    @JvmStatic
    fun stringToDouble(
        value: String,
        default: Double,
    ): Double = value.toDoubleOrNull() ?: default
}
