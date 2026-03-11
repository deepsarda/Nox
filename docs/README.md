# Nox Runtime Documentation

## What is Nox?

Nox is a purpose-built sandbox runtime designed to execute untrusted code safely and efficiently. It combines the security guarantees of a capability-based, zero-trust architecture with the performance of a register-based virtual machine. Written in Kotlin and targeting JVM 21+, Nox can be distributed as a **JVM library (JAR)**, a **standalone native binary** (via GraalVM Native Image), or a **shared library** (C ABI) for embedding in any language.

Unlike traditional sandboxes that rely on OS-level isolation (containers, VMs), Nox allows for permission to be granted on a per-call basis, with no implicit permissions. This provides a more secure and flexible environment for executing untrusted code. Compared to other sandbox runtimes, Nox is easily extensible with a powerful three-tier plugin system and can be easily adapted into any existing application.

## Documentation Index

### Architecture & Design

| Document | Description |
|---|---|
| [**Architecture Overview**](architecture/overview.md) | The Host-Sandbox model and system topology |
| [**Security Model**](architecture/security.md) | Zero-trust, capability-based security philosophy |
| [**Compilation Pipeline**](architecture/pipeline.md) | From source code to bytecode execution |

### The Virtual Machine

| Document | Description |
|---|---|
| [**Memory Model**](vm/memory-model.md) | Dual-bank registers, sliding window frames, and memory lifecycle |
| [**Instruction Set**](vm/instruction-set.md) | The 64-bit instruction layout and opcode reference |
| [**Super-Instructions**](vm/super-instructions.md) | Intent-based opcodes: `HMOD`, `HACC`, `SCONCAT` |
| [**Error Handling**](vm/error-handling.md) | Table-driven zero-cost exception handling |
| [**Resource Guards**](vm/resource-guards.md) | Watchdogs, instruction limits, and memory caps |

### The NSL Language

| Document | Description |
|---|---|
| [**Language Overview**](language/overview.md) | Introduction to NSL syntax and semantics |
| [**Type System**](language/type-system.md) | Primitives, structs, arrays, and the `json` type |
| [**Functions & Control Flow**](language/functions.md) | Functions, UFCS, default params, varargs, and streaming |
| [**Standard Library**](language/stdlib.md) | Namespaced libraries and built-in type methods |

### Extensibility

| Document | Description |
|---|---|
| [**Plugin Development Guide**](extensibility/plugin-guide.md) | Three-tier plugin model: built-in, native (C ABI), and Nox imports |
| [**FFI Internals**](extensibility/ffi-internals.md) | `MethodHandle` linking (JVM) and C ABI bridging (native) |

### Compiler

| Document | Description |
|---|---|
| [**Compiler Overview**](compiler/overview.md) | Four-phase pipeline, design decisions, file map |
| [**AST Design**](compiler/ast.md) | Kotlin sealed class hierarchy with 47 node types for expressions, statements, and declarations |
| [**Semantic Analysis**](compiler/semantic-analysis.md) | Three-pass type resolution, UFCS chain, null checks, control flow validation |
| [**Code Generation**](compiler/codegen.md) | Register allocation, bytecode emission, constant pools, exception tables |
| [**Bytecode Disassembly**](compiler/disassembly.md) | The `.noxc` pretty-printed format for debugging and test assertions |

### Testing
See [**Testing Strategy**](testing.md) for info on unit tests, integration tests, E2E golden tests, coverage targets.

### Reference

| Document | Description |
|---|---|
| [**Program File Format**](reference/file-format.md) | The `.nox` file structure and metadata headers |
| [**Glossary**](reference/glossary.md) | Terminology and definitions |

## Quick Start

```c
@tool:name "hello_world"
@tool:description "A simple greeting program."

main(string name = "World") {
    return `Hello, ${name}!`;
}
```

Save as `hello_world.nox` and run:

```bash
$ nox run hello_world.nox --name "Alice"
Hello, Alice!
```

## Design Principles

1. **Zero Trust:** All code is assumed potentially malicious. No implicit permissions.
2. **Do the Heavy Thinking Once:** The compiler handles all analysis; the VM is a fast executor.
3. **Opcode Compression:** One complex instruction beats ten simple ones.
4. **Sandbox Everything:** No reflection, no direct host access, no escape hatches.
5. **Extensibility Without Compromise:** Plugins get the same performance as built-ins.

<p align="center">
  <em>Safe execution for code you didn't write, whether it came from a developer, a plugin, or an AI.</em>
</p>
