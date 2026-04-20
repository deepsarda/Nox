package nox.plugin.annotations

/**
 * Marks a method as a type-bound method callable on Nox values.
 *
 * Type-bound methods appear as method calls on values of the target type:
 *
 * ```kotlin
 * @NoxTypeMethod(targetType = "int", name = "toDouble")
 * @JvmStatic
 * fun intToDouble(value: Long): Double = value.toDouble()
 * ```
 *
 * NSL usage: `int x = 42; double d = x.toDouble();`
 *
 * The first parameter of the annotated method receives the target value
 * (the receiver). Additional parameters become the method's Nox-visible arguments.
 *
 * @property targetType the Nox type this method is bound to
 *                      ("int", "double", "boolean", "string", "json")
 * @property name the method name visible to NSL; defaults to the Kotlin method name
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoxTypeMethod(
    val targetType: String,
    val name: String = "",
)
