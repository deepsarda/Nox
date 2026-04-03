# Architecture Overview

## System Topology

Nox is divided into two primary layers that **never share mutable state**:

```
┌──────────────────────────────────────────────────────────────┐
│                     HOST APPLICATION                         │
│              (CLI, Server, Game Engine, etc.)                │
│                                                              │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────┐   │
│  │  Permission │  │   Output     │  │   Execution        │   │
│  │  Handler    │  │   Handler    │  │   Config           │   │
│  └─────────────┘  └──────────────┘  └────────────────────┘   │
│                                                              │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │                   Sandbox Manager                       │ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐               │ │
│  │  │Sandbox 1 │  │Sandbox 2 │  │Sandbox N │               │ │
│  │  │(Coroutn) │  │(Coroutn) │  │(Coroutn) │               │ │
│  │  └──────────┘  └──────────┘  └──────────┘               │ │
│  └─────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```
 
## The Host

The Host is the **trusted application** that embeds and drives the Nox runtime. It is responsible for configuring execution policies, handling output, and adjudicating permissions.

**The Host never executes untrusted code directly.** It delegates all `.nox` execution to isolated Sandboxes and interacts with them only through the `RuntimeContext` interface.

### What Constitutes a Host?

The runtime is **host-agnostic**. Any Kotlin/JVM application can act as a Host. In native mode, any application that links to the Nox shared library via C ABI can also be a Host:

| Host Type | Description |
|---|---|
| **CLI** | A command-line tool that runs `.nox` files directly (like `node script.js`) |
| **MCP** | A simple MCP server that allows tools calls to happen in a secured environment. |
| **Server** | An application that accepts execution requests and runs them concurrently |
| **Game Engine** | A game that exposes a `Game` namespace for modder scripts |
| **SaaS Platform** | A product that lets customers write custom business logic |
| **Test Harness** | A testing framework that validates `.nox` programs |

### Host Responsibilities

The Host provides **three pluggable handlers** that control runtime behavior:

| Handler | Role | Default Behavior |
|---|---|---|
| **Permission Handler** | Decides whether to grant or deny capability requests | Prompt the user interactively (like `sudo`) |
| **Output Handler** | Receives `yield` and `return` output from scripts | Print to `stdout` |
| **Execution Config** | Sets resource limits (max instructions, timeout, memory cap) | Sensible defaults (500K ops, 60s, 100MB) |
 
## The Sandbox

The Sandbox is an **ephemeral, isolated execution environment** created to run exactly one `.nox` program. Once execution completes, the Sandbox is discarded and its resources are garbage collected.

### Characteristics

| Property | Detail |
|---|---|
| **Isolation** | No direct access to the file system, network, system clock, or any host resource. All capabilities are proxied through the `RuntimeContext`. |
| **Single-Use** | One Sandbox per execution request. No state carries over between invocations. |
| **Lightweight** | Built on Kotlin Coroutines. An application can run thousands of Sandboxes concurrently with minimal OS overhead. |
| **Role** | Hosts the VM execution loop that runs the compiled bytecode of a `.nox` script. |


## The RuntimeContext: The Air Gap Bridge

The only communication channel between a Sandbox and its Host is the `RuntimeContext` interface. This is a direct Kotlin interface: no queues, no serialization, no network calls.

```kotlin
interface RuntimeContext {
    /** Send streaming output */
    fun yield(data: String)

    /** Send final result and terminate the Sandbox */
    fun returnResult(data: String)

    /** Request permission for a sensitive operation (suspends the coroutine) */
    suspend fun requestPermission(request: PermissionRequest): PermissionResponse

    /** Request resource limit extension when a guard trips (suspends the coroutine) */
    suspend fun requestResourceExtension(request: ResourceRequest): ResourceResponse
}
```

### PermissionRequest

A sealed class hierarchy representing every type of permission the sandbox can request. The type itself encodes the category and action, and constructor parameters carry typed details:

```kotlin
sealed class PermissionRequest {

    /** File system operations */
    sealed class File : PermissionRequest() {
        data class Read(val path: String) : File()
        data class Write(val path: String) : File()
        data class Append(val path: String) : File()
        data class Delete(val path: String) : File()
        data class List(val directory: String) : File()
        data class Metadata(val path: String) : File()         // Size, timestamps, exists
        data class CreateDirectory(val path: String) : File()
    }

    /** Network operations */
    sealed class Http : PermissionRequest() {
        data class Get(val url: String) : Http()
        data class Post(val url: String, val contentType: String? = null) : Http()
        data class Put(val url: String, val contentType: String? = null) : Http()
        data class Delete(val url: String) : Http()
    }

    /** Environment and system information */
    sealed class Env : PermissionRequest() {
        data class ReadVar(val name: String) : Env()           // e.g. "API_KEY", "HOME"
        data class SystemInfo(val property: String) : Env()    // e.g. "os.name", "arch"
    }

    /** Escape hatch for plugin-defined permissions */
    data class Plugin(
        val category: String,
        val action: String,
        val details: Map<String, Any> = emptyMap()
    ) : PermissionRequest()
}
```

### PermissionResponse

A sealed class hierarchy returned by the Host. Simple hosts can return `Granted.Unconstrained`; sophisticated policy engines can return typed constraints:

```kotlin
sealed class PermissionResponse {

    sealed class Granted : PermissionResponse() {
        /** No restrictions, full access approved */
        object Unconstrained : Granted()

        /** File operation approved with optional limits */
        data class FileGrant(
            val maxBytes: Long? = null,
            val rewrittenPath: String? = null,
            val allowedDirectories: List<String>? = null,  // e.g. ["/safe/", "/tmp/"]
            val allowedExtensions: List<String>? = null,   // e.g. [".json", ".txt"]
            val readOnly: Boolean = false                   // Write/Delete/Append is denied
        ) : Granted()

        /** HTTP operation approved with optional limits */
        data class HttpGrant(
            val maxResponseSize: Long? = null,
            val timeoutMs: Long? = null,
            val allowedDomains: List<String>? = null,      // e.g. ["api.example.com"]
            val allowedPorts: List<Int>? = null,           // e.g. [443]
            val httpsOnly: Boolean = false
        ) : Granted()

        /** Env operation approved with optional limits */
        data class EnvGrant(
            val allowedVarNames: List<String>? = null      // Restrict which vars are readable
        ) : Granted()

        /** Plugin-defined constraints */
        data class PluginGrant(
            val values: Map<String, Any> = emptyMap()
        ) : Granted()
    }

    /** Permission denied with optional reason */
    data class Denied(val reason: String? = null) : PermissionResponse()
}
```

### Method Semantics

#### `yield(data)` -- Interim Output

Sends an intermediate result to the Host. The Sandbox continues executing.

**What the Host does with it is entirely up to the Host:**

| Host Type | Behavior on `yield` |
|---|---|
| **CLI** | Prints to `stdout` |
| **Server / API** | Buffers in a response stream, or sends via SSE/WebSocket |
| **Embedded** | Appends to a callback list for the caller to consume |
| **Game Engine** | Displays as in-game console output |

#### `returnResult(data)` -- Final Result

Sends the final result and terminates the Sandbox. The Host receives the value and cleans up the Sandbox's resources.

#### `requestPermission(request)` -- Capability Request

A **suspending** call. The Sandbox's coroutine suspends (at near-zero cost) while the Host makes a decision.

**Flow:**
1. Sandbox encounters a restricted operation (e.g., `File.read("path")`)
2. The VM constructs `PermissionRequest.File.Read("/data/file.txt")` and calls `context.requestPermission(request)`
3. The coroutine **suspends** so no OS resources are consumed
4. The Host pattern-matches on the request type and evaluates its policy
5. Returns a typed `PermissionResponse` (`Granted` with optional constraints, or `Denied` with reason)
6. Sandbox inspects the response, proceeds or throws `SecurityException`
 
## Execution Lifecycle

The complete lifecycle of a `.nox` execution:

```
  Host                                    Sandbox (Coroutine)
    │                                          │
    │── Create Sandbox ───────────────────────▶│
    │                                          │── Compile .nox
    │                                          │── Validate
    │                                          │── Begin VM execution
    │                                          │
    │◀── yield("progress...") ─────────────────│
    │   (Host prints / buffers / streams)      │── Continue executing...
    │                                          │
    │◀── requestPermission(File.Read(path)) ───│
    │   (Host pattern-matches request)         │   (Coroutine suspended)
    │── Granted.Unconstrained ────────────────▶│
    │                                          │── Resume execution...
    │                                          │
    │◀── requestResourceExtension(Instruction)─│
    │   (Host evaluates resource policy)       │   (Coroutine suspended)
    │── Granted(1_000_000) ───────────────────▶│
    │                                          │── Resume with new limit...
    │                                          │
    │◀── returnResult("done") ─────────────────│
    │                                          ╳ (Coroutine completes)
    │── Cleanup ──▶ (GC)                      
```
 
## Default Execution (CLI Mode)

Out of the box, Nox behaves like a standard language runtime, similar to how you'd run a Node.js or Python script:

```bash
$ nox run my_script.nox --name "Alice" --count 5
```

In CLI mode:
- **`yield`** prints to `stdout` line-by-line
- **`return`** prints the final result to `stdout` and exits with code `0`
- **Permission requests** prompt the user interactively via `stdin`
- **Errors** print to `stderr` with source location and exit with code `1`

```
$ nox run data_fetcher.nox --url "https://api.example.com/data"
Fetching data...                              ← yield
[Permission] Allow http.get to api.example.com? (y/n): y
Downloaded 42 records.                        ← yield
Processing complete. Total: 1,247.50          ← return (exit 0)
```
 
## Embedding API

For applications that host Nox as a scripting engine, the embedding API provides full control:

```kotlin
// 1. Create a runtime with custom configuration
val runtime = NoxRuntime.builder()
    .maxInstructions(500_000)
    .maxExecutionTime(Duration.ofSeconds(30))
    .maxMemory(100_000_000)
    .build()

// 2. Register plugins (optional)
runtime.registerModule(MathExtension::class)
runtime.registerModule(GameAPI::class)

// 3. Set permission policy
runtime.setPermissionHandler { request ->
    when (request) {
        is PermissionRequest.File.Read ->
            PermissionResponse.Granted.FileGrant(
                allowedDirectories = listOf("/data/"),
                allowedExtensions = listOf(".json", ".txt"),
                maxBytes = 1_048_576
            )
        is PermissionRequest.File ->
            PermissionResponse.Denied("Only File.Read is permitted")

        is PermissionRequest.Http.Get ->
            PermissionResponse.Granted.HttpGrant(
                allowedDomains = listOf("api.example.com"),
                httpsOnly = true,
                maxResponseSize = 10_000_000
            )
        is PermissionRequest.Http ->
            PermissionResponse.Denied("Only GET requests are permitted")

        is PermissionRequest.Env ->
            PermissionResponse.Denied("Environment access disabled")

        is PermissionRequest.Plugin ->
            PermissionResponse.Denied()
    }
}

// 4. Execute a script
val result = runtime.execute(
    Path.of("scripts/my_script.nox"),
    mapOf("name" to "Alice", "count" to 5)
)

// 5. Read results
val interimOutputs: List<String> = result.yieldedOutputs
val finalResult: String = result.finalResult
val success: Boolean = result.isSuccess
```

### Output Callbacks

For real-time streaming of `yield` output (e.g., in a server context):

```kotlin
runtime.execute(scriptPath, args, object : OutputHandler {
    override fun onYield(data: String) {
        webSocket.send(data)  // Stream to client in real-time
    }

    override fun onReturn(data: String) {
        webSocket.send(data)
        webSocket.close()
    }

    override fun onError(error: NoxException) {
        webSocket.sendError(error.toJson())
    }
})
```
 
## Distribution Modes

Nox can be distributed in three modes from the same Kotlin codebase:

| Mode | Command | Use Case |
|---|---|---|
| **JVM Library** | Add `nox-runtime.jar` as a dependency | Kotlin/Java applications |
| **Native CLI** | `nox run script.nox` | Standalone tool, <10ms startup |
| **Shared Library** | `libnox.so` / `libnox.dylib` | Embed in C, Rust, Python, Go, Swift, etc. |

Native binaries are produced via **GraalVM Native Image**, which compiles the Kotlin/JVM code ahead-of-time into a standalone executable with no JVM dependency.
 
## Next Steps

- [**Security Model**](security.md)
- [**Compilation Pipeline**](pipeline.md)
- [**Memory Model**](../vm/memory-model.md)
