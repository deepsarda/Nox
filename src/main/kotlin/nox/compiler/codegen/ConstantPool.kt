package nox.compiler.codegen

/**
 * A deduplicated constant pool for compiled Nox programs.
 *
 * Stores strings, doubles, longs, and access-path strings. Each unique value
 * gets a stable index. Multiple references to the same value reuse the same index,
 * keeping the pool (and instruction operands) as compact as possible.
 *
 * Values that can be represented as a 16-bit immediate (i.e. ints in 0..65535) [basically 90% of usecases]
 * don't need to be pooled and are instead loaded via [Opcode.LDI] directly.
 * TODO: Is this actually useful? Do we optimize for constant pool or for instruction count?
 *
 * See docs/compiler/codegen.md.
 */
class ConstantPool {

    private val entries = mutableListOf<Any?>()
    private val dedup = mutableMapOf<Any?, Int>()

    /**
     * Intern [value] into the pool and return its index.
     *
     * If [value] has not been seen before, it is appended; otherwise the
     * existing index is returned.
     *
     * Supported value types:
     * - [String] string literals, key names, template text, access paths
     * - [Double] double literals
     * - [Long]   integer literals that don't fit in a 16-bit immediate
     * - [TypeDescriptor] struct shape descriptors for `CAST_STRUCT` validation
     * - `null`   represents the null constant (rarely used)
     */
    fun add(value: Any?): Int = dedup.getOrPut(value) {
        entries.add(value)
        entries.size - 1
    }

    /**
     * Reserves a new index in the pool without an initial value, bypassing deduplication.
     * Returns the allocated index. Use [replace] to populate it later.
     */
    fun addPlaceholder(): Int {
        entries.add(null)
        return entries.size - 1
    }

    /** Finds the pool index of an existing TypeDescriptor by its name, if any. */
    fun getTypeDescriptorId(typeName: String): Int? {
        for ((i, entry) in entries.withIndex()) {
            if (entry is TypeDescriptor && entry.name == typeName) return i
        }
        return null
    }

    /**
     * Replace the entry at [index] in-place, keeping the index stable.
     *
     * Used by the descriptor builder for recursive struct types: a `null`
     * placeholder is reserved via [addPlaceholder], the descriptor is constructed
     * (which may reference this same index), then the placeholder is
     * replaced with the finished [TypeDescriptor].
     */
    fun replace(index: Int, value: Any?) {
        val old = entries[index]
        entries[index] = value
        if (old != null) dedup.remove(old)
        if (value != null) dedup[value] = index
    }

    /** Number of entries currently in the pool. */
    val size: Int get() = entries.size

    /** Snapshot the pool as an array, suitable for [CompiledProgram.constantPool]. */
    fun toArray(): Array<Any?> = entries.toTypedArray()
}
