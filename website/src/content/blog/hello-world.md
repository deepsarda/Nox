---
title: "Nox 0.1.0-alpha: The AI-Native Scripting Language"
date: 2026-05-01
description: "We are thrilled to announce the first alpha release of Nox. Fast, sandboxed, and ready for agents."
author: "The Nox Team"
tags: ["Release", "Alpha"]
---

Welcome to the very first release of Nox!

Nox was born out of a simple necessity: we needed a language that AI agents could generate and execute *safely* within a host application, without sacrificing the performance of a native bytecode virtual machine.

## What makes Nox different?

1. **Resource Guards**: Nox guarantees execution safety. You can limit CPU cycles and memory allocations natively.
2. **Blazing Fast**: The Nox VM boots in microseconds and executes with zero garbage collection overhead.
3. **Seamless Interop**: Native integration with Kotlin allows for zero-copy data sharing between the host and the script.

### Getting Started

To get started, head over to the [Downloads](/downloads) page or read our [Getting Started Guide](/guides/getting-started).

```rust
// A quick taste of Nox

@tool:name "hello_world"
@tool:description "A simple greeting program."

main(string name = "AI era") {
    return \`Hello, \${name}!\`;
}
```

Join our GitHub Discussions to let us know what you build!
