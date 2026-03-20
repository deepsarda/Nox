package nox.plugin.stdlib

import nox.plugin.annotations.NoxModule
import nox.plugin.annotations.NoxType
import nox.plugin.annotations.NoxTypeMethod

/**
 * Nox standard library: array type-bound methods.
 *
 * These are registered as type-bound methods but are special-cased in the
 * registry since they work on any `T[]`. The [LibraryRegistry] handles
 * array methods separately through `arrayMethodNames`.
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
    @NoxTypeMethod(targetType = "array", name = "push")
    @JvmStatic
    fun push(
        arr: Any?,
        item: Any?,
    ) {
        val list = arr as? MutableList<Any?> ?: return
        list.add(item)
    }

    /**
     * Remove and return the last element of the array.
     * Throws if the array is empty.
     */
    @NoxTypeMethod(targetType = "array", name = "pop")
    @NoxType("json") // actual type is T, resolved by registry
    @JvmStatic
    fun pop(arr: Any?): Any? {
        val list = arr as? MutableList<Any?> ?: throw RuntimeException("pop() called on null array")
        if (list.isEmpty()) throw RuntimeException("pop() called on empty array")
        return list.removeAt(list.size - 1)
    }

    /**
     * Return the number of elements in the array.
     */
    @NoxTypeMethod(targetType = "array", name = "length")
    @NoxType("int")
    @JvmStatic
    fun length(arr: Any?): Long {
        val list = arr as? List<*> ?: return 0L
        return list.size.toLong()
    }
}
