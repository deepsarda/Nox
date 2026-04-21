# Nox

**Nox** is a secure, embeddable sandbox runtime for executing untrusted scripts written in **NSL** (Nox Scripting Language). It is designed to be embedded in JVM applications as a library, enabling host applications to run arbitrary user-provided code in a safe, resource-controlled environment.

## Motivation

Modern AI agent frameworks give agents access to **tools** to read files, call APIs, run shell commands, query databases, and more. The appeal is obvious: tools extend what an agent can do. But it also opens us up to massive security risks.

### The Problem with Agent Tools

Every tool granted to an AI agent is a **permanent, unconditional capability**. If an agent has `shell_execute`, it can run any command. If it has `file_write`, it can overwrite any file. The agent does not reason about *why* it needs these capabilities, it will simply use whatever it has been given.

This creates several serious risks:

1. **Prompt injection escalates to system compromise**

2. **Tools cannot always be scoped to a task**

This blocks the logical next step (imo) for a sufficiently capable agent is to **write its own tools** to gain new capabilities at runtime. And thus allow long runnning agents or groups of agents to create a library of tools overtime.

But this directly executes host-level code with no sandbox, no resource limits, no permission model. Every framework that allows autonomous tool creation without a sandbox is, basically, an autonomous code execution vulnerability.

The result is a hard tradeoff: **either restrict agents to a fixed, conservative set of tools, or accept an unbounded security risk.** This is why AI agents need to be heavily constraine.

### Why Traditional Sandboxing Doesn't Solve This

The obvious counter-argument is: *just run the agent in a container or a sandboxed OS process.* Docker, VMs, `seccomp`, Linux namespaces, etc. These are mature, proven isolation mechanisms. But they share a critical limitation: **they are all-or-nothing black boxes configured at startup.**

When you sandbox a process with Docker, you decide its permissions before it starts. Which volumes to mount, which ports to expose, etc. 

The container then runs opaquely. You have no visibility into what the code actually does with those capabilities. Even worse, **you cannot change what it's allowed to do based on what it's trying to do.**

The container either has network access or it doesn't. There is no mechanism for the sandbox to surface a request to the outside world and receive a contextual answer. **Nox fixes that.**

### It Isn't Just an AI Problem

1. **Game modding.** 

2. **SaaS business logic.** 

3. **Data pipelines.** 

4. **Automation platforms.** 

## Features

- **Zero-trust sandbox:** scripts cannot access the host system without explicit permission
- **Capability-based security:** every I/O operation (file, HTTP, environment) requires host approval
- **Resource guards:** instruction count, time, memory, and stack depth limits prevent runaway scripts
- **Static typing:** NSL is a strongly-typed language; errors are caught at compile time
- **Custom VM:** scripts compile to a custom bytecode format and run in a purpose-built VM (not JVM bytecode)
- **UFCS:** Unified Function Call Syntax: `point.distance(other)` and `distance(point, other)` are equivalent
- **Streaming:** `yield` sends intermediate outputs to the host during execution
- **Extensible:** add new library functions with `@NoxModule` / `@NoxFunction` Kotlin annotations

## Quick Start

```kotlin
val runtime = NoxRuntime.builder()
    .maxInstructions(100_000)
    .maxExecutionTime(Duration.ofSeconds(5))
    .permissionHandler { request ->
        // Deny all permissions by default
        PermissionResponse.Denied("Not allowed")
    }
    .build()

val result = runtime.execute(
    source = """
        @tool:name "greeter"
        main(string name) {
            return `Hello, ${name}!`;
        }
    """,
    args = mapOf("name" to "World")
)

println(result.value) // Hello, World!
```

## NSL Language

```c
@tool:name      "data_processor"
@tool:description "Processes a JSON payload and returns a summary."
@tool:permission "file:read"

import "utils/format.nox" as fmt;

type Config {
    string host;
    int    port;
    int    timeout;
}

int countItems(json data) {
    return data.getInt("count", 0);
}

main(json payload, string label) {
    Config cfg = payload.config as Config;
    int total  = countItems(payload);

    foreach (string item in payload.items) {
        yield `Processing: ${item}`;
    }

    return fmt.summary(label, total, cfg.host);
}
```

## Architecture

```
.nox source
    │
    ▼  Phase 1: Lexing + Parsing (ANTLR4)
    │
    ▼  Phase 2: AST Construction (ASTBuilder visitor)
    │
    ▼  Phase 3: Semantic Analysis (type resolution, UFCS, null checks)
    │
    ▼  Phase 4: Code Generation (register allocation, bytecode emission)
    │
    ▼  CompiledProgram { bytecode, constantPool, exceptionTable, funcMeta }
    │
    ▼  VM Execution (dual-bank register file, sliding window frames)
    │
    ▼  NoxResult { value, yields, error }
```

See [`docs/`](docs/) for full documentation.

## Building

**Requirements:** JDK 25+, Gradle 9+

```bash
# Build and run tests
gradle build

# Run tests only
gradle test

# Regenerate ANTLR4 grammar sources
gradle generateGrammarSource
```

## Demo Programs

See the files at [src/test/resources/nox/programs](src/test/resources/nox/programs) for some demo programs.

## Tech Stack

| Component    | Technology                        |
|--------------|-----------------------------------|
| Language     | Kotlin 2.3.0                      |
| Grammar      | ANTLR4 4.13.2                     |
| Coroutines   | kotlinx-coroutines 1.10.2         |
| Testing      | Kotest 6.1.3                      |
| Build        | Gradle 9.3.1 (Kotlin DSL)         |
