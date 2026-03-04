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
│  │  │(VThread) │  │(VThread) │  │(VThread) │               │ │
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
| **Lightweight** | Built on JVM Virtual Threads (Project Loom). An application can run thousands of Sandboxes concurrently with minimal OS overhead. |
| **Role** | Hosts the VM execution loop that runs the compiled bytecode of a `.nox` script. |

### Why Virtual Threads?

Virtual Threads (Project Loom) are the ideal fit for Nox Sandboxes:

- **Cheap blocking:** When a Sandbox calls `requestPermission()`, its Virtual Thread **parks** without consuming an OS thread. This makes synchronous permission checks essentially free.
- **Massive concurrency:** The JVM can manage millions of Virtual Threads, allowing applications to run thousands of sandboxed scripts simultaneously.
- **Simple programming model:** Sandboxes can use straightforward blocking code without callback complexity.
 
## The RuntimeContext: The Air Gap Bridge

The only communication channel between a Sandbox and its Host is the `RuntimeContext` interface. This is a direct Kotlin interface: no queues, no serialization, no network calls.

```kotlin
interface RuntimeContext {
    /** Send streaming output */
    fun yield(data: String)

    /** Send final result and terminate the Sandbox */
    fun returnResult(data: String)

    /** Request permission for a sensitive operation (blocks the Virtual Thread) */
    fun requestPermission(action: String): PermissionResponse
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

#### `requestPermission(action)` -- Capability Request

A **synchronous, blocking** call. The Sandbox's Virtual Thread parks (at near-zero cost) while the Host makes a decision.

**Flow:**
1. Sandbox encounters a restricted operation (e.g., `File.read("path")`)
2. The VM calls `context.requestPermission("file.read", {path: "/data/file.txt"})`
3. The Virtual Thread **blocks** so no OS resources are consumed
4. The Host evaluates the request (auto-policy, user prompt, etc.)
5. Returns `PermissionResponse` (granted/denied + optional constraints)
6. Sandbox unblocks, inspects the response, proceeds or throws `SecurityException`
 
## Execution Lifecycle

The complete lifecycle of a `.nox` execution:

```
  Host                                    Sandbox (VThread)
    │                                          │
    │── Create Sandbox ───────────────────────▶│
    │                                          │── Compile .nox
    │                                          │── Validate
    │                                          │── Begin VM execution
    │                                          │
    │◀── yield("progress...") ─────────────────│
    │   (Host prints / buffers / streams)      │── Continue executing...
    │                                          │
    │◀── requestPermission("file.read") ───────│
    │   (Host evaluates policy)                │   (VThread parked)
    │── GRANTED ──────────────────────────────▶│
    │                                          │── Resume execution...
    │                                          │
    │◀── returnResult("done") ─────────────────│
    │                                          ╳ (Thread terminates)
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
runtime.setPermissionHandler { action, _ ->
    if (action.startsWith("file.")) PermissionResponse.DENIED
    else PermissionResponse.GRANTED
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
