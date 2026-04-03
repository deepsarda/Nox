package nox.runtime

/**
 * The result of a VM execution, returned to the Host.
 */
sealed class NoxResult {
    data class Success(
        val returnValue: String?,
        val yields: List<String>,
    ) : NoxResult()

    data class Error(
        val type: NoxError,
        val message: String?,
        val yields: List<String>,
    ) : NoxResult()
}
