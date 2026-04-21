# FFI Internals

## The Bridging Problem

The VM operates on two flat arrays (`LongArray pMem`, `Array<Any?> rMem`) with integer register indices. Plugin developers write clean Kotlin methods with typed parameters. These two worlds are incompatible:

```
VM World:                          Developer World:
  pMem[110] = 0x400921FB54442D18     double a = 3.14159
  pMem[111] = 0x4005BF0A8B145769     double b = 2.71828

  // The VM has no idea these are doubles.
  // It just sees 64-bit integers.
```

To bridge this gap without runtime reflection overhead, Nox uses **Compile-Time Code Generation** via KSP (Kotlin Symbol Processing).
 
## Why Code Generation?

Historically, runtimes use `MethodHandle` or Reflection to bridge these worlds at load time. However, reflection requires extensive metadata in GraalVM Native Image.

By using KSP, Nox generates a unique `PluginRegistryProvider` implementation at compile-time that contains raw, direct Kotlin calls. This achieves:
1. **Zero runtime reflection:** No `MethodHandle` or `Method.invoke`.
2. **Zero linking overhead:** Registries are loaded instantly.
3. **GraalVM Native Image optimization:** Dead code elimination .

## The `NoxNativeFunc` Interface

Every compiled plugin function is wrapped in this functional interface by the code generator:

```kotlin
fun interface NoxNativeFunc {
    @Throws(Throwable::class)
    fun invoke(ctx: RuntimeContext, pMem: LongArray, rMem: Array<Any?>, bp: Int, bpRef: Int, primArgStart: Int, refArgStart: Int, destReg: Int)
}
```

| Parameter | Description |
|---|---|
| `ctx` | The sandbox runtime context (injected automatically) |
| `pMem` | The primitive register bank |
| `rMem` | The reference register bank |
| `bp` | Base pointer for primitives (current function's frame start) |
| `bpRef` | Base pointer for references |
| `primArgStart` | Offset where primitive arguments begin |
| `refArgStart` | Offset where reference arguments begin |
| `destReg` | Register where the result should be stored |
 
## The Generation Process

The `nox-ksp` processor runs **during the Kotlin compilation phase**.

### Step 1: Discover the Target Method

```kotlin
// The developer wrote:
@NoxFunction(name = "hypot")
@JvmStatic
fun hypot(a: Double, b: Double): Double = ...
```
KSP scans the codebase for `@NoxModule` classes and extracts the parameter types, return type, and annotations.

### Step 2: Generate the Adapter

KSP generates a hardcoded `NoxNativeFunc` lambda that extracts arguments directly from the VM memory arrays, calls the target function, and stores the result:

```kotlin
NoxNativeFunc { ctx, pMem, rMem, bp, bpRef, primArgStart, refArgStart, destReg ->
    // Extract arg 0 (Double)
    val arg0 = java.lang.Double.longBitsToDouble(pMem[bp + primArgStart + 0])
    // Extract arg 1 (Double)
    val arg1 = java.lang.Double.longBitsToDouble(pMem[bp + primArgStart + 1])
    
    // Direct, strongly-typed Kotlin method call!
    val result = MathExtension.hypot(arg0, arg1)
    
    // Store result (Double)
    pMem[bp + destReg] = java.lang.Double.doubleToRawLongBits(result)
}
```

The extraction logic depends entirely on the parameter type:

| Kotlin Type | Source | Extraction |
|---|---|---|
| `Long` / `Int` | `pMem` | `pMem[bp + argStart + i].toInt()` |
| `Double` | `pMem` | `java.lang.Double.longBitsToDouble(pMem[bp + argStart + i])` |
| `Boolean` | `pMem` | `pMem[bp + argStart + i] != 0L` |
| `String` | `rMem` | `rMem[bpRef + argStart + i] as String` |
| `Any?` | `rMem` | `rMem[bpRef + argStart + i]` |
| `RuntimeContext` | injected | Uses the `ctx` lambda parameter |

### Step 3: Generate the ServiceLoader Metadata

KSP generates a uniquely named class (e.g., `GeneratedRegistry_8f2a...`) implementing `PluginRegistryProvider`, and automatically creates `META-INF/services/nox.plugin.PluginRegistryProvider`. 

At runtime, `LibraryRegistry.createDefault()` uses `ServiceLoader` to discover and load all generated registries across the entire classpath instantly.

## VM Integration
### The `SCALL` Opcode

System calls use the `SCALL` instruction:

```
┌────────┬──────────┬──────────┬──────────┬──────────┐
│ SCALL  │  subOp   │ func ID  │ pArgStart│ rArgStart│
└────────┴──────────┴──────────┴──────────┴──────────┘
```

### Execution

```kotlin
SCALL -> {
    val subOp = Instruction.subOp(inst)
    val funcId = opA      // Look up the linked function
    val pArgStart = opB   // Primitive argument start
    val rArgStart = opC   // Reference argument start

    // O(1) array lookup
    val func = systemLibrary[funcId]

    try {
        // subOp 1 = Primitive result, 0 = Reference result
        val destReg = if (subOp == 1) pArgStart else rArgStart
        func.invoke(ctx, pMem, rMem, bp, bpRef, pArgStart, rArgStart, destReg)
    } catch (t: Throwable) {
        // Convert ANY JVM exception to a Nox exception
        handleException(NoxException(classify(t), t.message, pc))
    }
}
```
 
## RuntimeContext Injection

When a plugin method's first parameter is `RuntimeContext`, KSP detects this and passes the `ctx` lambda parameter directly to the method call. It skips advancing the VM memory extraction indices for that parameter.

```kotlin
// The developer wrote: 
// fun log(ctx: RuntimeContext, msg: String)

// KSP generates:
val linked = NoxNativeFunc { ctx, pMem, rMem, bp, bpRef, primArgStart, refArgStart, destReg ->
    val arg0 = ctx                                     // Injected by KSP
    val arg1 = rMem[bpRef + refArgStart + 0] as String // First NSL arg
    SecureIO.log(arg0, arg1)
}
```

From the NSL side, `secure_io.log("hello")` passes one argument. The `RuntimeContext` is completely invisible to the script.
 
## Type Safety During Code Generation

Because KSP operates at compile-time, it immediately throws compilation errors if a developer attempts to use unsupported parameter types, preventing invalid plugins from ever being built.
 
## Architecture Diagram

```
┌────────────────────────────────────────────────────────────────┐
│                     COMPILE TIME (KSP)                         │
│                                                                │
│  @NoxModule ──▶ KSP Processor ──▶ GeneratedRegistry_XXX.kt     │
│                                   (Hardcoded Kotlin calls)     │
│                                                                │
│                                   META-INF/services/...        │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│                        RUN TIME                                │
│                                                                │
│  Startup:                                                      │
│    LibraryRegistry.createDefault() -> ServiceLoader            │
│                                                                │
│  VM Loop:                                                      │
│    SCALL -> {                                                  │
│      val func = systemLibrary[funcId]  // Array lookup         │
│      func.invoke(ctx, pMem, rMem, ...) // Direct Kotlin call   │
│    }                                                           │
└────────────────────────────────────────────────────────────────┘
```
 
## Tier 1 External Plugins & The Opaque Context Pointer

For Tier 1 plugins (shared C libraries), the FFI bridge automatically injects an opaque context pointer as the **first parameter** to every C function. This allows C code to execute callbacks back into the JVM sandbox safely.

### The `NoxContext` Struct

When the JVM calls a Tier 1 C function, it allocates a scoped memory segment representing a `NoxContext` struct and passes it as `void*`. The C plugin can cast this pointer and use the provided function pointers.

```c
struct NoxContext {
    int64_t internal_id;                                       // Identifier for the sandbox context
    void (*yield_func)(int64_t internal_id, const char* data); // Upcall to JVM's yield
};
```

### Performing an Upcall

The C function can use the struct to communicate with the Host:

```c
const char* process_data(void* ctx_ptr, const char* input) {
    struct NoxContext* ctx = (struct NoxContext*)ctx_ptr;
    
    // Check if the context and upcall function are available
    if (ctx && ctx->yield_func) {
        // Suspend C execution, jump into Kotlin, execute yield, then resume C
        ctx->yield_func(ctx->internal_id, "Processing started...");
    }

    // ... handle input
    return "done";
}
```

## Next Steps

- [**Plugin Development Guide**](plugin-guide.md)
- [**Instruction Set**](../vm/instruction-set.md)
- [**Security Model**](../architecture/security.md)
