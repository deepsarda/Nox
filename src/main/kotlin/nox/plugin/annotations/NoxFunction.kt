package nox.plugin.annotations

/**
 * Marks a method within a [@NoxModule] class as a callable Nox function.
 *
 * The method is registered under the module's namespace and becomes available
 * to NSL scripts as `Namespace.functionName(args)`.
 *
 * Parameter types are mapped automatically:
 * - `Long` → `int`
 * - `Double` → `double`
 * - `Boolean` → `boolean`
 * - `String` → `string`
 * - Use [@NoxType] on parameters for ambiguous types (e.g. `json`, arrays)
 *
 * A [RuntimeContext][nox.runtime.RuntimeContext] parameter is auto-injected
 * by the VM and does not count as a Nox-visible parameter.
 *
 * @property name the function name visible to NSL; defaults to the Kotlin method name
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoxFunction(
    val name: String = "",
)
