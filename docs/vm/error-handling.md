# Error Handling

## Design Goals

1. **Zero overhead** when no exception is thrown
2. **Precise source mapping** for debuggable error messages
3. **Robust unwinding** that correctly handles nested `try-catch` blocks
4. **Clean integration** with host (JVM) exceptions from FFI calls
 
## The Exception Table

Instead of maintaining a runtime stack of exception handlers (push on `try`, pop on exit), Nox uses a **table-driven approach**, the same technique used by modern JVMs and C++ compilers.

### How It Works

The compiler generates a **metadata table** separate from the bytecode. Each row maps a range of instructions to a catch handler:

| Column | Description |
|---|---|
| **Start PC** | First instruction of the `try` block |
| **End PC** | Last instruction of the `try` block |
| **Exception Type** | The specific error type to catch (or `ANY` for catch-all) |
| **Target PC** | The instruction address of the `catch` block |
| **Message Register** | The register where the error message should be stored |

### Example

```c
try {                          // PC 100
    json data = Http.get(url); // PC 110
    process(data);             // PC 120
} catch (NetworkError e) {     // PC 200
    yield "Network failed: " + e;
} catch (TypeError e) {        // PC 250
    yield "Bad data: " + e;
}
```

**Compiled Exception Table:**

| Start PC | End PC | Type | Jump Target | Message Reg |
|---|---|---|---|---|
| 100 | 150 | `NetworkError` | 200 | Reg 5 |
| 100 | 150 | `TypeError` | 250 | Reg 6 |
 
## The Happy Path (Zero Cost)

When code inside a `try` block executes **without errors**, the Exception Table is never consulted. The VM simply executes instructions sequentially through the `try` block and continues past the `catch` blocks (which are skipped via a `JMP` instruction the compiler places at the end of the `try` block).

```
PC 100: [try block starts]
PC 110: GET_JSON ...
PC 120: CALL process
PC 148: JMP 300           Skips catch blocks entirely
PC 150: [try block ends]

PC 200: [catch NetworkError]  Never reached on happy path
PC 250: [catch TypeError]     Never reached on happy path

PC 300: [code continues]
```

**There are no `PUSH_HANDLER` or `POP_HANDLER` instructions.** Allowing the happy path to execute exactly as it would have if there were no `try-catch` at all.
 
## Exception Handling Flow

When an exception occurs (from a `THROW` opcode, a failed `SCALL`, or a host JVM exception):

```
  Exception occurs at PC 120
         │
         ▼
  ┌─────────────────────────┐
  │ 1. VM pauses execution  │
  │    Save current PC      │
  └──────────┬──────────────┘
             │
             ▼
  ┌──────────────────────────────────┐
  │ 2. Scan Exception Table          │
  │    Find row where:               │
  │      Start PC ≤ 120 ≤ End PC     │
  │      AND exception type matches  │
  └──────────┬───────────────────────┘
             │
         ┌───┴───┐
     Match?    No Match?
         │         │
         ▼         ▼
  ┌────────────┐  ┌──────────────────┐
  │ 3a. Store  │  │ 3b. Unwind       │
  │ error msg  │  │ Pop call frame   │
  │ in Message │  │ Check parent     │
  │ Register   │  │ function's table │
  └─────┬──────┘  └────────┬─────────┘
        │                  │
        ▼               (repeat until
  ┌────────────┐     caught or top-level)
  │ 4. Jump to │
  │ Target PC  │
  └─────┬──────┘
        │
        ▼
  ┌────────────┐
  │ 5. Resume  │
  │ execution  │
  │ in catch   │
  └────────────┘
```

### Step-by-Step

1. **Pause:** The VM saves the current program counter and exception details
2. **Scan:** The VM iterates through the Exception Table, looking for a matching row:
   - The current PC must fall within the row's `[Start PC, End PC]` range
   - The exception type must match (or the row catches `ANY`)
3. **Match Found:**
   - The error message is written to the designated register: `rMem[bp + messageReg] = exception.getMessage()`
   - The program counter is set to the `Target PC`
   - Execution resumes inside the `catch` block
4. **No Match (Unwinding):**
   - The current function's call frame is popped
   - The exception is re-thrown in the **caller's** context
   - The caller's Exception Table is scanned
   - This continues until either a handler is found or the exception reaches `main`, causing the program to terminate with an error

### Chaining Catch Blocks

When multiple `catch` blocks exist for the same `try`, the table contains multiple rows with the same `Start PC`/`End PC` but different types and targets:

| Start PC | End PC | Type | Target | Reg |
|---|---|---|---|---|
| 100 | 150 | `NetworkError` | 200 | R5 |
| 100 | 150 | `TypeError` | 250 | R6 |
| 100 | 150 | `ANY` | 300 | R7 |

The VM checks rows **in order**. The first match wins. A catch-all (`ANY`) always goes last (except resource guard exceptions).
 
## Error Message Population

The `catch` variable is populated through a simple register write:

```c
catch (NetworkError errMsg) {
    // errMsg is available as a string in the designated register
    yield "Failed: " + errMsg;
}
```

**Compiler mapping:**
- `errMsg` to Reg 5 (as declared in the Exception Table)
- When the exception is caught: `rMem[bp + 5] = exception.getMessage()`
- Inside the catch block, `errMsg` reads from Reg 5 like any other local variable
 
## Host Exception Containment

When a system call (`SCALL`) or super-instruction (`HMOD`) triggers a JVM exception, the VM **must** contain it:

```kotlin
SCALL -> {
    try {
        nativeFunc.invoke(pMem, rMem, bp, bpRef, args, destReg)
    } catch (t: Throwable) {
        // Convert ANY JVM exception into a Nox exception
        val noxEx = NoxException(
            classifyException(t),   // Map to Nox error type
            t.message,
            currentPC
        )
        handleException(noxEx)     // Triggers the table scan
    }
}
```

This ensures:
- A buggy plugin **cannot crash** the Host
- JVM exceptions are translated into Nox's error type system
- Stack traces map back to NSL source locations, not JVM internals
 
## Error Reporting Format

All errors are reported with full source context, using the line/column metadata preserved from the ANTLR parse:

```json
{
  "error": {
    "type": "RuntimeError",
    "subtype": "NetworkError",
    "message": "Connection refused: http://api.example.com/data",
    "location": {
      "file": "data_fetcher.nox",
      "line": 12,
      "column": 20,
      "snippet": "11 |    yield \"Fetching data...\";\n12 |    json data = Http.get(url);\n   |                    ^\n13 |    process(data);"
    },
    "suggestion": "Verify the URL is reachable and the server is running."
  }
}
```
 
## Exception Types

Every exception in Nox has a **type** string used for matching in the Exception Table. The type system is flat i.e. there is no inheritance hierarchy (saves me from a lot of pain). A catch-all (`ANY`) catches everything (except resource guard exceptions).

### Runtime Exceptions

These are thrown by VM operations during normal execution. All are **catchable** with `try-catch`.

| Type | Thrown When | Example |
|---|---|---|
| `NullAccessError` | Accessing a property or method on a `null` reference | `null.length()`, `null.upper()` |
| `DivisionByZeroError` | Division by zero | Integer division or modulo division by zero |
| `TypeError` | Type mismatch during extraction or conversion | `json.getInt("key")` on a string value |
| `IndexOutOfBoundsError` | Array index is negative or ≥ array length | `arr[arr.length()]`, `arr[-1]` |
| `KeyNotFoundError` | Accessing a missing key on `json` without a default | `data.missingKey` via `AGET_KEY` on a non-existent key |
| `CastError` | An `as` cast fails structural validation | `rawJson as ApiConfig` when fields are missing or mistyped |
| `TypeError` | Runtime type mismatch in a dynamic context | `json` value is a string but code expects int |
| `ArithmeticError` | Numeric overflow or invalid arithmetic operation | Integer overflow on multiplication |

### Host / I/O Exceptions

These are thrown by standard library functions (via `SCALL`) when external operations fail. All are **catchable**.

| Type | Thrown When | Example |
|---|---|---|
| `NetworkError` | HTTP request fails (connection refused, timeout, DNS failure, non-2xx status) | `Http.getJson("https://bad.url")` |
| `FileError` | File operation fails (not found, OS-level permission denied, I/O error) | `File.read("/nonexistent.txt")` |
| `ParseError` | Data parsing fails (malformed JSON, invalid number format) | `Http.getJson(url)` when response isn't valid JSON |
| `SecurityError` | Permission denied by the Host's permission handler | `File.write(path)` when `file.write` permission is denied |

### Resource Guard Exceptions

These are thrown by the VM's watchdog system when execution exceeds configured limits. They are **catchable** (except by catch-all `catch (err)` blocks) but intended to terminate execution. 

When a limit is reached and denied, the VM temporarily bumps the quota by a small "grace period" (using a capped exponential backoff, e.g., `new_quota = old_quota + min(old_quota, 10000)`). This allows the VM to transition to the catch block and perform cleanup. The program will terminate the moment the catch block finishes, as such catch blocks are automatically terminated with a special `KILL` instruction.

| Type | Thrown When | Default Limit |
|---|---|---|
| `QuotaExceededError` | Instruction counter exceeds `maxInstructions` | 500,000 instructions |
| `TimeoutError` | Wall-clock time exceeds `maxExecutionTime` | 60 seconds |
| `MemoryLimitError` | Allocated memory exceeds `maxMemory` | Configurable |
| `StackOverflowError` | Call stack depth exceeds `maxCallDepth` | 256 frames |

### User Exceptions

Thrown explicitly in NSL code via the `throw` keyword.

```c
throw "Something went wrong";                    // Type: Error
throw `User ${name} not found`;                  // Type: Error
```

| Type | Thrown When |
|---|---|
| `Error` | Any `throw` statement in user code. The thrown expression becomes the error message. |

### JVM Exception Mapping

When a plugin (FFI call) throws a JVM exception, the VM translates it into a Nox exception type using a `classifyException()` function:

| JVM Exception | Nox Type |
|---|---|
| `NullPointerException` | `NullAccessError` |
| `ArithmeticException` | `ArithmeticError` |
| `IndexOutOfBoundsException` | `IndexOutOfBoundsError` |
| `ClassCastException` | `CastError` |
| `IOException` | `FileError` |
| `java.net.*` exceptions | `NetworkError` |
| `SecurityException` | `SecurityError` |
| `IllegalArgumentException` | `TypeError` |
| Any other `Throwable` | `Error` |

### Catch Matching Rules

1. The VM scans the Exception Table **in order** for the current `pc`
2. A typed catch matches if `exceptionType.equals(row.type)`
3. A catch-all (`ANY`) matches any exception type (except resource guard exceptions)
4. If no match is found in the current function, the exception **propagates** to the caller
5. If no match is found in `main`, the program terminates and the error is reported to the Host

```c
try {
    json data = Http.getJson(url);
    ApiConfig config = data as ApiConfig;
} catch (NetworkError e) {
    // Catches only NetworkError
} catch (CastError e) {
    // Catches only CastError
} catch (err) {
    // Catches everything else (NullAccessError, TypeError, etc.)
}
```

## Next Steps

- [**Resource Guards**](resource-guards.md)
- [**Instruction Set**](instruction-set.md)
- [**Compilation Pipeline**](../architecture/pipeline.md)
