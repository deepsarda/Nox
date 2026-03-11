# Resource Guards

## Why Resource Guards?

Even within a perfectly sandboxed environment where external access is gated behind permissions, untrusted code can still attack the host through **resource exhaustion**:

- An infinite loop consumes CPU forever
- A recursive function overflows the stack
- A string concatenation in a loop builds a multi-gigabyte object
- A JSON parse of a malicious payload fills all available memory

Nox employs multiple independent **Watchdogs** to prevent multiple categories of resource exhaustion.
 
## Guard 1: Instruction Counter (CPU Limit)

### Threat
Infinite loops, excessive computation, denial-of-service via CPU monopolization.

### Mechanism
The VM increments a counter **every iteration** of the main execution loop:

```kotlin
while (running) {
    val inst = bytecode[pc++]

    if (++instructionCount > MAX_INSTRUCTIONS) {
        throw QuotaExceededException(
            "Execution limit exceeded: $MAX_INSTRUCTIONS instructions"
        )
    }

    when (opcode) { /* ... */ }
}
```

### Configuration

| Parameter | Default | Description |
|---|---|---|
| `maxInstructions` | 500,000 | Maximum VM instructions per execution |

### Characteristics

- **Overhead:** Minimal. A single `long` increment and comparison per cycle.
- **Deterministic:** Unlike wall-clock timeouts, instruction counting is consistent regardless of host load.
- **Granular:** Each execution gets its own counter, reset to zero.

### What Gets Counted

Every VM loop iteration = 1 instruction. This includes:
- Arithmetic operations (`IADD`, `DMUL`, etc.)
- Data movement (`MOV`, `LDC`)
- Control flow (`JMP`, `JIF`, `CALL`, `RET`)
- System calls (`SCALL`)
- Host operations (`HMOD`, `HACC`, `SCONCAT`)

This means a potentially blocking call like `Http.get()` will be counted as an single instruction, however, if it takes too long, it will be terminated by the execution timeout guard.

### What About `while(true) { yield "spam"; }`?

Each `yield` counts as an instruction. The loop condition check counts. The jump-back counts. The instruction counter will catch this after `MAX_INSTRUCTIONS / ~3` iterations.
 
## Guard 2: Execution Timeout (Wall-Clock Limit)

### Threat
Scripts that are stuck waiting (e.g., on a slow network call), or scripts that circumvent the instruction counter through long-running system calls.

### Mechanism
The Host tracks the wall-clock time of each Sandbox coroutine. If a Sandbox exceeds the maximum duration, the Host cancels it.

```
Host:
  workerStartTime = System.nanoTime()
  
  // Periodic check (or scheduled task):
  if (now - workerStartTime > MAX_DURATION) {
      worker.interrupt();
      reportTimeout(workerId);
  }
```

### Configuration

| Parameter | Default | Description |
|---|---|---|
| `maxExecutionTime` | 60 seconds | Maximum wall-clock time per execution |

### Characteristics

- **Complementary:** Catches cases the instruction counter cannot (e.g., a blocking `SCALL` that takes forever).
- **Non-deterministic:** Results may vary based on host system load.
- **Coarse-grained:** Checks happen at intervals, not per-instruction.
 
## Guard 3: Memory Cap

### Threat
Memory bombs like allocating enormous strings, arrays, or JSON objects that cause `OutOfMemoryError` on the host JVM.

### Mechanism
The VM monitors the size of objects **when they enter the system**:

- **System call results:** When `File.read()` or `Http.get()` returns data, the size is checked before it's placed in `rMem`.
- **String interpolation:** When a template literal produces a large string, the result size is validated.
- **JSON parsing:** Results of JSON operations are measured.

```kotlin
// Inside File.read implementation:
val content = readFileFromDisk(path)
if (content.length * 2 > MAX_OBJECT_SIZE) {  // *2 for UTF-16 char size
    throw MemoryLimitException(
        "Object size ${content.length} exceeds limit"
    )
}
rMem[bp + destReg] = content
```

### Configuration

| Parameter | Default | Description |
|---|---|---|
| `maxObjectSize` | 100 MB | Maximum size for any single object entering the VM |
| `maxReferenceSlots` | 65,536 | Maximum number of reference slots (limits total object count) |

### What's Measured

| Source | Measurement |
|---|---|
| `File.read()` result | `string.length() * 2` (UTF-16 bytes) |
| `Http.get()` response | Response body size |
| JSON parse result | Estimated NoxObject/NoxArray size |
| Array growth | Element count against max |
| String interpolation | Result string length |

### What's NOT Measured (And Why)

Exact memory measurement on the JVM is expensive. We use **proxy metrics** (string length, element count) rather than attempting to measure deep object graph sizes with `Instrumentation.getObjectSize()`. This is a deliberate trade-off: the caps are set conservatively to compensate for measurement imprecision.
 
## Guard 4: Recursion Limit (Stack Depth)

### Threat
Stack overflow attacks like deeply recursive functions that exhaust the host JVM's stack.

### Mechanism
The VM maintains an internal **call stack** as a fixed-size array:

```kotlin
val callStack = IntArray(MAX_CALL_DEPTH * FRAME_SIZE)
var callStackPointer = 0

// In CALL handler:
if (callStackPointer >= callStack.size) {
    throw StackOverflowException(
        "Maximum recursion depth exceeded: $MAX_CALL_DEPTH"
    )
}
```

### Configuration

| Parameter | Default | Description |
|---|---|---|
| `maxCallDepth` | 1,024 | Maximum number of nested function calls |

### Why Not Rely on the JVM Stack?

The JVM's default thread stack size (typically 512KB–1MB) would also catch infinite recursion. But:

1. A `StackOverflowError` in the JVM is **unrecoverable** and can corrupt internal state
2. The error message would reference JVM internals, not NSL source code
3. Coroutines rely on heap-allocated continuation frames, but unchecked recursion still risks excessive memory use
4. We want to throw a **Nox exception** with proper source mapping, not a JVM error
 
## Guard 5: Register File Limits

### Threat
Scripts that declare an absurd number of variables, overwhelming the pre-allocated register arrays.

### Mechanism
The compiler enforces limits during compilation:

| Limit | Value | Enforced At |
|---|---|---|
| Max registers per function | ~32,768 | Compile time (16-bit operand) |
| Max total primitive registers | ~65,536 | VM startup (array allocation) |
| Max total reference registers | ~65,536 | VM startup (array allocation) |

A script that exceeds these limits receives a `CompilationError` before any code runs.
 
## Guard Summary

```
┌────────────────────────────────────────────┐
│                    RESOURCE GUARDS         │
│                                            │
│  ┌─────────────────┐  ┌─────────────────┐  │
│  │ Instruction     │  │ Wall-Clock      │  │
│  │ Counter         │  │ Timeout         │  │
│  │ (CPU)           │  │ (Time)          │  │
│  │ 500K ops max    │  │ 60s max         │  │
│  └─────────────────┘  └─────────────────┘  │
│                                            │
│  ┌─────────────────┐  ┌─────────────────┐  │
│  │ Memory Cap      │  │ Recursion       │  │
│  │ (RAM)           │  │ Limit           │  │
│  │ 100MB per obj   │  │ 1024 frames     │  │
│  └─────────────────┘  └─────────────────┘  │
│                                            │
│  ┌─────────────────┐                       │
│  │ Register File   │  All limits are       │
│  │ (Variables)     │  configurable         │
│  │ 65K slots       │  per-execution        │
│  └─────────────────┘                       │
└────────────────────────────────────────────┘
```

All guards are **independent**, a failure in any single guard triggers program termination regardless of the state of other guards.
 
## Error Reporting

Resource guard violations produce clear, actionable error messages:

```json
{
  "error": {
    "type": "QuotaExceededException",
    "message": "Execution limit exceeded: 500000 instructions.",
    "suggestion": "Your script may contain an infinite loop. Check while/for loop conditions.",
    "details": {
      "instructionsExecuted": 500000,
      "lastPC": 1247,
      "lastOpcode": "JMP",
      "file": "data_processor.nox",
      "approximateLine": 34
    }
  }
}
```
 
## Next Steps

- [**Security Model**](../architecture/security.md)
- [**Error Handling**](error-handling.md)
- [**Memory Model**](memory-model.md)
