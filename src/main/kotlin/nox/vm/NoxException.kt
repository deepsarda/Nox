package nox.vm

import nox.runtime.NoxError
import java.io.IOException

/**
 * An exception thrown during VM execution.
 *
 * Carries a Nox-level error type ([NoxError])
 * and the program counter where it occurred, enabling source-mapped error reporting.
 */
class NoxException(
    val type: NoxError,
    override val message: String?,
    val pc: Int,
) : RuntimeException(message)

/**
 * Classify a JVM throwable into a Nox error type.
 *
 * Used when a native function (SCALL) throws an unexpected JVM exception,
 * the VM wraps it as a [NoxException] with the classified type.
 */
fun classifyException(t: Throwable): NoxError =
    when (t) {
        is NoxException -> t.type
        is NullPointerException -> NoxError.NullAccessError
        is ArithmeticException -> NoxError.DivisionByZeroError
        is IndexOutOfBoundsException -> NoxError.IndexOutOfBoundsError
        is ClassCastException -> NoxError.CastError
        is IOException -> NoxError.FileError
        is java.net.ConnectException,
        is java.net.UnknownHostException,
        is java.net.SocketTimeoutException,
        is java.net.http.HttpTimeoutException,
        -> NoxError.NetworkError
        is SecurityException -> NoxError.SecurityError
        is IllegalArgumentException -> NoxError.TypeError
        else -> NoxError.Error
    }
