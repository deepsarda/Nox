package nox.plugin.annotations

/**
 * Marks a Kotlin class/object as a Nox plugin module providing a namespace of functions.
 *
 * All public methods annotated with [NoxFunction] within this class are
 * registered as callable functions under the given [namespace].
 *
 * ```kotlin
 * @NoxModule(namespace = "Math")
 * object MathModule {
 *     @NoxFunction(name = "sqrt")
 *     @JvmStatic
 *     fun sqrt(x: Double): Double = kotlin.math.sqrt(x)
 * }
 * ```
 *
 * See docs/extensibility/plugin-guide.md.
 *
 * @property namespace the NSL namespace (e.g. "Math", "File", "Http")
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoxModule(
    val namespace: String,
)
