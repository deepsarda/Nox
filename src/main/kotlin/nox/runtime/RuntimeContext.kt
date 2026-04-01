package nox.runtime

/**
 * The single communication channel between a Sandbox and its Host.
 *
 * Every interaction a `.nox` script has with the outside world is mediated
 * through this interface. The Host provides an implementation that controls
 * permissions, handles output, and enforces security policies.
 *
 * See docs/architecture/overview.md for the full design.
 */
interface RuntimeContext {
    /**
     * Send streaming/intermediate output from the script.
     *
     * Called by the `yield` keyword in NSL. The sandbox continues
     * executing after this call returns.
     */
    fun yield(data: String)

    /**
     * Send the final result and signal script completion.
     *
     * Called by explicit `return` from `main()`. After this call,
     * the sandbox is terminated and its resources are released.
     */
    fun returnResult(data: String)

    /**
     * Request permission for a sensitive operation.
     *
     * This is a **suspending** call, the sandbox's coroutine suspends
     * while the Host evaluates its security policy.
     *
     * @param request the specific operation being requested
     * @return the Host's decision, potentially with constraints
     */
    suspend fun requestPermission(request: PermissionRequest): PermissionResponse

    /**
     * Request resource limit extension when a guard trips.
     *
     * This is a **suspending** call and the sandbox's coroutine suspends
     * while the Host evaluates whether to grant more resources.
     *
     * @param request the specific resource limit that was exceeded
     * @return the Host's decision: grant a new limit or deny
     */
    suspend fun requestResourceExtension(request: ResourceRequest): ResourceResponse
}
