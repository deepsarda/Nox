package nox.plugin.annotations

/**
 * Specifies the Nox type for a parameter or return value when
 * the Kotlin type is ambiguous.
 *
 * For example, both `json` and `string` map to Kotlin `Any?` or `String`
 * in some contexts, and array element types cannot be inferred from `List<*>`:
 *
 * ```kotlin
 * @NoxFunction(name = "getJson")
 * @NoxType("json")  // Return type override
 * @JvmStatic
 * fun getJson(url: String): Any? { ... }
 *
 * @NoxFunction(name = "list")
 * @NoxType("string[]")  // Return type override
 * @JvmStatic
 * fun list(@NoxType("string") dir: String): List<*> { ... }
 * ```
 *
 * @property value the Nox type string (e.g. "json", "string[]", "int[]")
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoxType(
    val value: String,
)
