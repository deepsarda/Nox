package nox.plugin.stdlib

import nox.plugin.annotations.NoxFunction
import nox.plugin.annotations.NoxModule
import nox.plugin.annotations.NoxType

/**
 * Nox standard library: `Date` namespace.
 *
 * Pure function (no permissions required).
 *
 * NSL usage:
 * ```
 * int timestamp = Date.now();  // Unix epoch milliseconds
 * ```
 *
 * See docs/language/stdlib.md.
 */
@NoxModule(namespace = "Date")
object DateModule {
    @NoxFunction(name = "now")
    @NoxType("int")
    @JvmStatic
    fun now(): Long = System.currentTimeMillis()
}
