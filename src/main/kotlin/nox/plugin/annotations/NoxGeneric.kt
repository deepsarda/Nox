package nox.plugin.annotations

/**
 * Declares generic type parameters for a plugin function.
 * These type parameters can be used in `@NoxType` and `@NoxTypeMethod(targetType)`.
 *
 * @property params the names of the generic type parameters (e.g., ["T"], ["K", "V"])
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoxGeneric(
    val params: Array<String>
)