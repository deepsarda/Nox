/**
 * Host-facing runtime API:
 *  NoxRuntime: builder + entry point for host applications
 *  RuntimeContext: air-gap bridge between Sandbox and Host (yield / return / requestPermission)
 *  Sandbox: ephemeral, isolated coroutine-based execution environment
 *  PermissionRequest / PermissionResponse: capability-based security model
 */
package nox.runtime
