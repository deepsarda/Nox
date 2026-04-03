package nox.runtime

/**
 * Resource extension requests from the VM to the Host.
 *
 * When a resource guard trips, the VM suspends and asks the Host
 * whether to extend the limit. The Host can grant more resources
 * or deny (causing the guard exception to fire).
 */
sealed class ResourceRequest {
    /** Instruction counter exceeded. */
    data class InstructionQuota(
        val used: Long,
        val currentLimit: Long,
    ) : ResourceRequest()

    /** Wall-clock timeout exceeded. */
    data class ExecutionTimeout(
        val elapsedMs: Long,
        val currentLimitMs: Long,
    ) : ResourceRequest()

    /** Call stack depth exceeded. */
    data class CallDepth(
        val current: Int,
        val currentLimit: Int,
    ) : ResourceRequest()
}
