package nox.plugin.annotations

/**
 * Declares a default value for a plugin function parameter, making it optional in NSL.
 *
 * The compiler injects the default at the call site when the argument is omitted,
 * following the same pattern as user-defined function defaults (the VM has no
 * concept of optional parameters).
 *
 * Supported literal forms: `"true"`, `"false"`, integer (`"42"`), double (`"3.14"`),
 * string (`"\"hello\""`), `"null"`.
 *
 * Example:
 * ```kotlin
 * @NoxFunction(name = "stringify")
 * @JvmStatic
 * fun stringify(
 *     @NoxType("json") value: Any?,
 *     @NoxDefault("true") pretty: Boolean = true,
 * ): String
 * ```
 *
 * NSL callers can then omit the parameter:
 * ```c
 * Json.stringify(data);         // pretty = true (default)
 * Json.stringify(data, false);  // pretty = false (explicit)
 * ```
 *
 * @property value the default value as a literal string
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoxDefault(
    val value: String,
)
