package nox.plugin.stdlib

import nox.plugin.annotations.NoxFunction
import nox.plugin.annotations.NoxModule
import nox.plugin.annotations.NoxType

/**
 * Nox standard library: `Math` namespace.
 *
 * All functions are pure (no permissions required).
 *
 * NSL usage:
 * ```
 * double root = Math.sqrt(16.0);    // 4.0
 * int rounded = Math.round(3.7);    // 4
 * double r = Math.random();         // 0.0..1.0
 * ```
 *
 * See docs/language/stdlib.md.
 */
@NoxModule(namespace = "Math")
object MathModule {
    @NoxFunction(name = "sqrt")
    @JvmStatic
    fun sqrt(x: Double): Double = kotlin.math.sqrt(x)

    @NoxFunction(name = "abs")
    @JvmStatic
    fun abs(x: Double): Double = kotlin.math.abs(x)

    @NoxFunction(name = "min")
    @JvmStatic
    fun min(
        a: Double,
        b: Double,
    ): Double = kotlin.math.min(a, b)

    @NoxFunction(name = "max")
    @JvmStatic
    fun max(
        a: Double,
        b: Double,
    ): Double = kotlin.math.max(a, b)

    @NoxFunction(name = "floor")
    @NoxType("int")
    @JvmStatic
    fun floor(x: Double): Long = kotlin.math.floor(x).toLong()

    @NoxFunction(name = "ceil")
    @NoxType("int")
    @JvmStatic
    fun ceil(x: Double): Long = kotlin.math.ceil(x).toLong()

    @NoxFunction(name = "round")
    @NoxType("int")
    @JvmStatic
    fun round(x: Double): Long = kotlin.math.round(x).toLong()

    @NoxFunction(name = "random")
    @JvmStatic
    fun random(): Double = Math.random()

    @NoxFunction(name = "pow")
    @JvmStatic
    fun pow(
        base: Double,
        exp: Double,
    ): Double = Math.pow(base, exp)
}
