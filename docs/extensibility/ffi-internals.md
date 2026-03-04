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

The **Linker** bridges this gap at load time, creating optimized adapter functions.
 
## Why `MethodHandle`? (JVM Mode)

The JVM offers several ways to call methods dynamically. Here's why `MethodHandle` wins for Tier 0 plugins:

| Mechanism | Access Check | Boxing | JIT Inlining | Speed |
|---|---|---|---|---|
| Direct call | Compile-time | None | Yes | Fastest |
| `MethodHandle.invokeExact` | **Once** (link time) | **None** | Yes | Near-direct |
| `Method.invoke` (Reflection) | **Every call** | **Yes** | Rarely | Slow |
| `Proxy` / Interface dispatch | Compile-time | Depends | Partial | Moderate |

`MethodHandle` is the JVM's official mechanism for language runtime implementors. It gives us the performance of a direct call with the flexibility of dynamic dispatch.
 
## The `NoxNativeFunc` Interface

Every linked plugin function is wrapped in this functional interface:

```kotlin
fun interface NoxNativeFunc {
    @Throws(Throwable::class)
    fun invoke(pMem: LongArray, rMem: Array<Any?>, bp: Int, bpRef: Int, argStart: Int, destReg: Int)
}
```

| Parameter | Description |
|---|---|
| `pMem` | The primitive register bank |
| `rMem` | The reference register bank |
| `bp` | Base pointer for primitives (current function's frame start) |
| `bpRef` | Base pointer for references |
| `argStart` | Offset where arguments begin (relative to `bp`) |
| `destReg` | Register where the result should be stored |
 
## The Linking Process

The Linker runs **once per function** at module load time. No linking code runs during VM execution.

### Step 1: Discover the Target Method

```kotlin
// The developer wrote:
@NoxFunction(name = "hypot")
@JvmStatic
fun hypot(a: Double, b: Double): Double = ...

// The Linker obtains a MethodHandle:
val lookup = MethodHandles.publicLookup()
val target = lookup.unreflect(targetMethod)
// target: (Double, Double) -> Double
```

### Step 2: Build the Argument Extraction Chain

For each parameter, the Linker generates logic to extract the argument from the VM's memory:

```
Parameter 0 (double a):
  Read pMem[bp + argStart + 0]
  Apply Double.longBitsToDouble()
  Feed into target as argument 0

Parameter 1 (double b):
  Read pMem[bp + argStart + 1]
  Apply Double.longBitsToDouble()
  Feed into target as argument 1
```

The extraction logic depends on the parameter type:

| Kotlin Type | Source | Extraction |
|---|---|---|
| `Long` / `Int` | `pMem` | `pMem[bp + argStart + i].toInt()` |
| `Double` | `pMem` | `Double.longBitsToDouble(pMem[bp + argStart + i])` |
| `Boolean` | `pMem` | `pMem[bp + argStart + i] != 0L` |
| `String` | `rMem` | `rMem[bpRef + argStart + i] as String` |
| `NoxObject` | `rMem` | `rMem[bpRef + argStart + i] as NoxObject` |
| `RuntimeContext` | injected | Auto-injected, not from registers |

### Step 3: Build the Return Value Handler

The return value must be placed into the correct register bank:

| Return Type | Destination | Conversion |
|---|---|---|
| `Int` / `Long` | `pMem[bp + destReg]` | `result.toLong()` |
| `Double` | `pMem[bp + destReg]` | `java.lang.Double.doubleToRawLongBits(result)` |
| `Boolean` | `pMem[bp + destReg]` | `if (result) 1L else 0L` |
| `String` | `rMem[bpRef + destReg]` | Direct reference store |
| `NoxObject` | `rMem[bpRef + destReg]` | Direct reference store |
| `Unit` | - | No action |

### Step 4: Compile with `LambdaMetafactory`

The Linker uses `LambdaMetafactory` to compile the entire extraction -> call -> storage chain into a concrete class implementing `NoxNativeFunc`:

```kotlin
val site = LambdaMetafactory.metafactory(
    lookup,
    "invoke",                                    // Interface method name
    MethodType.methodType(NoxNativeFunc::class.java),  // Produces this type
    genericSignature,                            // NoxNativeFunc.invoke() sig
    adaptedHandle,                               // Our adapted MethodHandle
    specificSignature                            // Exact types
)

val linked = site.target.invokeExact() as NoxNativeFunc
```

The JVM generates a synthetic class in memory. This class:
- Implements `NoxNativeFunc`
- Contains the full extraction/call/storage logic
- Is **JIT-compiled** into native code on first use
- Is treated as a **direct call** by the optimizer
 
## VM Integration

### The `SCALL` Opcode

System calls use the `SCALL` instruction:

```
┌────────┬──────────┬──────────┬──────────┬──────────┐
│ SCALL  │ (unused) │ dest reg │ func ID  │ arg start│
└────────┴──────────┴──────────┴──────────┴──────────┘
```

### Execution

```kotlin
SCALL -> {
    val funcId = opB      // Look up the linked function
    val destReg = opA     // Where to put the result
    val argStart = opC    // Where arguments begin

    // O(1) array lookup
    val func = systemLibrary[funcId]

    try {
        // This looks generic, but JIT sees it as:
        //   val result = MathExtension.hypot(a, b)
        //   pMem[bp + destReg] = doubleToRawLongBits(result)
        func.invoke(pMem, rMem, bp, bpRef, argStart, destReg)
    } catch (t: Throwable) {
        // Convert ANY JVM exception to a Nox exception
        handleException(NoxException(classify(t), t.message, pc))
    }
}
```

### The JIT Optimization Path

After the function is called enough times, HotSpot:

1. **Inlines** the `NoxNativeFunc.invoke()` call
2. **Inlines** the argument extraction logic
3. **Inlines** the actual plugin method
4. **The result:** the VM loop executes the plugin code as if it were written directly in the switch case
 
## RuntimeContext Injection

When a plugin method's first parameter is `RuntimeContext`, the Linker:

1. **Detects** the special type at link time
2. **Skips** it in argument extraction (it doesn't consume a register)
3. **Injects** the Sandbox's context object from a closure variable

```kotlin
// Conceptual adapter for: fun log(ctx: RuntimeContext, msg: String)
val linked = NoxNativeFunc { pMem, rMem, bp, bpRef, argStart, destReg ->
    val ctx = currentSandboxContext                    // Injected by linker
    val msg = rMem[bpRef + argStart + 0] as String     // First NSL arg
    SecureIO.log(ctx, msg)
}
```

From the NSL side, `secure_io.log("hello")` passes one argument. The `RuntimeContext` is invisible.
 
## Type Safety During Linking

The Linker validates type compatibility at load time:

```
Developer wrote:
  @NoxFunction(name = "process")
  @JvmStatic
  fun process(items: List<String>) { ... }

Linker checks:
  List<String> maps to NSL string[]
  Unit return type so no result storage needed
  Adapter generated successfully

If the developer wrote:
  @NoxFunction(name = "bad")
  @JvmStatic
  fun bad(t: Thread) { ... }    // Thread is not an NSL type

Linker throws:
  LinkageError: Unsupported parameter type 'Thread' in function 'bad'.
  Supported types: Int, Long, Double, Boolean, String, NoxObject, NoxArray, List, RuntimeContext
```
 
## Architecture Diagram

```
┌────────────────────────────────────────────────────────────────┐
│                        LOAD TIME                               │
│                                                                │
│  @NoxModule ──▶ Linker ──▶ MethodHandle chain ──▶ Lambda       │
│                   │              │                    │        │
│                   │   filterArguments()          NoxNativeFunc │
│                   │   adaptReturnType()           (concrete)   │
│                   │   injectContext()                │         │
│                   ▼                                  ▼         │
│              Validation                     systemLibrary[]    │
│              (type checks)                  (O(1) lookup)      │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│                        RUN TIME                                │
│                                                                │
│  VM Loop:                                                      │
│    SCALL -> {                                                  │
│      val func = systemLibrary[funcId]  // Array lookup         │
│      func.invoke(pMem, rMem, ...)      // JIT-inlined call     │
│    }                                                           │
│                                                                │
│  After JIT warmup, this is equivalent to:                      │
│    double a = Double.longBitsToDouble(pMem[bp + 0]);           │
│    double b = Double.longBitsToDouble(pMem[bp + 1]);           │
│    double r = MathExtension.hypot(a, b);                       │
│    pMem[bp + destReg] = Double.doubleToRawLongBits(r);         │
└────────────────────────────────────────────────────────────────┘
```
 
## Next Steps

- [**Plugin Development Guide**](plugin-guide.md)
- [**Instruction Set**](../vm/instruction-set.md)
- [**Security Model**](../architecture/security.md)
