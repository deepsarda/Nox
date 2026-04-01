package nox.plugin.stdlib

import nox.plugin.annotations.NoxModule
import nox.plugin.annotations.NoxType
import nox.plugin.annotations.NoxTypeMethod

/**
 * Nox standard library: `json` type-bound methods.
 *
 * These operate on NoxObject (json) values at runtime. The first parameter
 * is the receiver (the json value itself).
 *
 * NSL usage:
 * ```
 * json data = Http.getJson(url);
 * string name = data.getString("name", "unknown");
 * int count = data.getInt("count", 0);
 * boolean has = data.has("key");
 * string[] keys = data.keys();
 * int size = data.size();
 * ```
 *
 * See docs/language/stdlib.md.
 */
@Suppress("UNCHECKED_CAST")
@NoxModule(namespace = "_JsonMethods")
object JsonMethods {
    @NoxTypeMethod(targetType = "json", name = "getString")
    @JvmStatic
    fun getString(
        obj: Any?,
        key: String,
        default: String,
    ): String {
        val map = obj as? Map<String, Any?> ?: return default
        return map[key] as? String ?: default
    }

    @NoxTypeMethod(targetType = "json", name = "getInt")
    @NoxType("int")
    @JvmStatic
    fun getInt(
        obj: Any?,
        key: String,
        default: Long,
    ): Long {
        val map = obj as? Map<String, Any?> ?: return default
        val value = map[key] ?: return default
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            else -> default
        }
    }

    @NoxTypeMethod(targetType = "json", name = "getBool")
    @JvmStatic
    fun getBool(
        obj: Any?,
        key: String,
        default: Boolean,
    ): Boolean {
        val map = obj as? Map<String, Any?> ?: return default
        return map[key] as? Boolean ?: default
    }

    @NoxTypeMethod(targetType = "json", name = "getDouble")
    @JvmStatic
    fun getDouble(
        obj: Any?,
        key: String,
        default: Double,
    ): Double {
        val map = obj as? Map<String, Any?> ?: return default
        val value = map[key] ?: return default
        return when (value) {
            is Double -> value
            is Number -> value.toDouble()
            else -> default
        }
    }

    @NoxTypeMethod(targetType = "json", name = "getJSON")
    @NoxType("json")
    @JvmStatic
    fun getJSON(
        obj: Any?,
        key: String,
        @NoxType("json") default: Any?,
    ): Any? {
        val map = obj as? Map<String, Any?> ?: return default
        return map[key] ?: default
    }

    @NoxTypeMethod(targetType = "json", name = "has")
    @JvmStatic
    fun has(
        obj: Any?,
        key: String,
    ): Boolean {
        val map = obj as? Map<String, Any?> ?: return false
        return key in map
    }

    @NoxTypeMethod(targetType = "json", name = "keys")
    @NoxType("string[]")
    @JvmStatic
    fun keys(obj: Any?): List<String> {
        val map = obj as? Map<String, Any?> ?: return emptyList()
        return map.keys.toList()
    }

    @NoxTypeMethod(targetType = "json", name = "size")
    @NoxType("int")
    @JvmStatic
    fun size(obj: Any?): Long =
        when (obj) {
            is Map<*, *> -> obj.size.toLong()
            is List<*> -> obj.size.toLong()
            else -> 0L
        }

    // Setters

    @NoxTypeMethod(targetType = "json", name = "setString")
    @JvmStatic
    fun setString(
        obj: Any?,
        key: String,
        value: String,
    ) {
        val map =
            obj as? MutableMap<String, Any?>
                ?: throw IllegalArgumentException("Cannot set property on non-object json value")
        map[key] = value
    }

    @NoxTypeMethod(targetType = "json", name = "setInt")
    @JvmStatic
    fun setInt(
        obj: Any?,
        key: String,
        value: Long,
    ) {
        val map =
            obj as? MutableMap<String, Any?>
                ?: throw IllegalArgumentException("Cannot set property on non-object json value")
        map[key] = value
    }

    @NoxTypeMethod(targetType = "json", name = "setBool")
    @JvmStatic
    fun setBool(
        obj: Any?,
        key: String,
        value: Boolean,
    ) {
        val map =
            obj as? MutableMap<String, Any?>
                ?: throw IllegalArgumentException("Cannot set property on non-object json value")
        map[key] = value
    }

    @NoxTypeMethod(targetType = "json", name = "setDouble")
    @JvmStatic
    fun setDouble(
        obj: Any?,
        key: String,
        value: Double,
    ) {
        val map =
            obj as? MutableMap<String, Any?>
                ?: throw IllegalArgumentException("Cannot set property on non-object json value")
        map[key] = value
    }

    @NoxTypeMethod(targetType = "json", name = "setJson")
    @JvmStatic
    fun setJson(
        obj: Any?,
        key: String,
        @NoxType("json") value: Any?,
    ) {
        val map =
            obj as? MutableMap<String, Any?>
                ?: throw IllegalArgumentException("Cannot set property on non-object json value")
        map[key] = value
    }

    @NoxTypeMethod(targetType = "json", name = "remove")
    @JvmStatic
    fun remove(
        obj: Any?,
        key: String,
    ) {
        val map =
            obj as? MutableMap<String, Any?>
                ?: throw IllegalArgumentException("Cannot remove property on non-object json value")
        map.remove(key)
    }

    // Typed array getters

    @NoxTypeMethod(targetType = "json", name = "getIntArray")
    @NoxType("int[]")
    @JvmStatic
    fun getIntArray(
        obj: Any?,
        key: String,
        @NoxType("int[]") default: Any?,
    ): Any? {
        val map = obj as? Map<String, Any?> ?: return default
        return map[key] ?: default
    }

    @NoxTypeMethod(targetType = "json", name = "getStringArray")
    @NoxType("string[]")
    @JvmStatic
    fun getStringArray(
        obj: Any?,
        key: String,
        @NoxType("string[]") default: Any?,
    ): Any? {
        val map = obj as? Map<String, Any?> ?: return default
        return map[key] ?: default
    }

    @NoxTypeMethod(targetType = "json", name = "getDoubleArray")
    @NoxType("double[]")
    @JvmStatic
    fun getDoubleArray(
        obj: Any?,
        key: String,
        @NoxType("double[]") default: Any?,
    ): Any? {
        val map = obj as? Map<String, Any?> ?: return default
        return map[key] ?: default
    }
}
