package nox.runtime

/**
 * Host's response to a [ResourceRequest].
 */
sealed class ResourceResponse {
    /**
     * Grant extension. [newLimit] meaning depends on the request type:
     * - InstructionQuota: new max instruction count
     * - ExecutionTimeout: new timeout in ms from now
     * - CallDepth: new max call depth
     */
    data class Granted(
        val newLimit: Long,
    ) : ResourceResponse()

    /** Deny extension. The VM throws the corresponding guard exception. */
    data class Denied(
        val reason: String? = null,
    ) : ResourceResponse()
}
