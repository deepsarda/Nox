package nox.plugin.stdlib

import nox.plugin.annotations.NoxGeneric
import nox.plugin.annotations.NoxModule
import nox.plugin.annotations.NoxType
import nox.plugin.annotations.NoxTypeMethod

/**
 * Nox standard library: array type-bound methods.
 *
 * These are registered as generic templates and matched at compile-time.
 *
 * The actual runtime implementations operate on `ArrayList` (the VM's
 * representation of Nox arrays).
 *
 * NSL usage:
 * ```
 * int[] arr = [1, 2, 3];
 * arr.push(4);           // arr = [1, 2, 3, 4]
 * int last = arr.pop();  // last = 4, arr = [1, 2, 3]
 * int len = arr.length(); // 3
 * ```
 *
 * See docs/language/stdlib.md.
 */
@Suppress("UNCHECKED_CAST")
@NoxModule(namespace = "_ArrayMethods")
object ArrayMethods {
    /**
     * Append an element to the end of the array.
     * The element type is checked at compile-time by the semantic analyzer.
     */
    @NoxGeneric(["T"])
    @NoxTypeMethod(targetType = "T[]", name = "push")
    @JvmStatic
    fun push(
        arr: Any?,
        @NoxType("T") item: Any?,
    ) {
        val list = arr as? MutableList<Any?> ?: throw NullPointerException("push() called on null array")
        list.add(item)
    }

    /**
     * Remove and return the last element of the array.
     * Throws if the array is empty.
     */
    @NoxGeneric(["T"])
    @NoxTypeMethod(targetType = "T[]", name = "pop")
    @NoxType("T")
    @JvmStatic
    fun pop(arr: Any?): Any? {
        val list = arr as? MutableList<Any?> ?: throw NullPointerException("pop() called on null array")
        if (list.isEmpty()) throw IndexOutOfBoundsException("pop() called on empty array")
        return list.removeAt(list.size - 1)
    }

    /**
     * Return the number of elements in the array.
     */
    @NoxGeneric(["T"])
    @NoxTypeMethod(targetType = "T[]", name = "length")
    @NoxType("int")
    @JvmStatic
    fun length(arr: Any?): Long {
        val list = arr as? List<*> ?: throw NullPointerException("length() called on null array")
        return list.size.toLong()
    }
}
