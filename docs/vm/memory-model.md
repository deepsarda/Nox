# Memory Model

## Design Philosophy

Traditional interpreters allocate memory dynamically, creating `HashMap`s for scopes, boxing primitives into `Object` wrappers, and relying on garbage collection for cleanup. This is safe but slow.

Nox takes a fundamentally different approach: **pre-allocate everything, try and copy nothing** inspired by languages like Lua and WebAssembly.
 
## The Dual-Bank Register File

The VM's memory is split into two massive, pre-allocated arrays. This separation eliminates boxing/unboxing overhead and enables cache-friendly access patterns.

```
┌──────────────────────────────────────────────────────────┐
│                    REGISTER FILE                         │
│                                                          │
│  Primitive Bank (pMem)                                   │
│  ┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐     │
│  │ R0 │ R1 │ R2 │ R3 │ R4 │ R5 │ R6 │ R7 │... │ Rn │     │
│  │long│long│long│long│long│long│long│long│    │long│     │
│  └────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘     │
│                                                          │
│  Reference Bank (rMem)                                   │
│  ┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐     │
│  │ R0 │ R1 │ R2 │ R3 │ R4 │ R5 │ R6 │ R7 │... │ Rn │     │
│  │Obj │Obj │Obj │Obj │Obj │Obj │Obj │Obj │    │Obj │     │
│  └────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘     │
└──────────────────────────────────────────────────────────┘
```

### Primitive Bank: `long[] pMem`

| Property | Detail |
|---|---|
| **Type** | `long[]` a flat array of 64-bit integers |
| **Stores** | `int`, `boolean` (as 0/1), `double` (via `doubleToRawLongBits`) |
| **Performance** | Blazingly fast. No object overhead, no boxing/unboxing. Raw bit manipulation in a tight loop. |
| **Initialization** | Memory is **"dirty"**, we never zero-fill the array. Old values are simply overwritten. This saves time on function entry. |

#### Encoding Conventions

```
int value     ->  pMem[i] = (long) value
boolean true  ->  pMem[i] = 1L
boolean false ->  pMem[i] = 0L
double value  ->  pMem[i] = Double.doubleToRawLongBits(value)

// Reading back:
int    <-  (int) pMem[i]
bool   <-  pMem[i] != 0
double <-  Double.longBitsToDouble(pMem[i])
```

### Reference Bank: `Object[] rMem`

| Property | Detail |
|---|---|
| **Type** | `Array<Any?>` is a flat array of JVM object references |
| **Stores** | `String`, `NoxObject` (JSON objects backed by `HashMap<String, Object>`), `NoxArray` (JSON arrays backed by `ArrayList<Object>`), typed array wrappers |
| **Role** | The **Host Bridge**. The VM doesn't know what a "JSON object" is, it just knows there's a pointer at `rMem[10]`. All complex logic is offloaded to safe Kotlin code operating on these objects. |
| **Cleanup** | Critical for memory safety. The compiler emits `KILL_REF` instructions at scope exits to `null` out slots, making objects eligible for garbage collection. |

#### Why Two Banks?

```
Single-bank approach (rejected):
  Object[] mem = new Object[65536];
  mem[0] = Integer.valueOf(42);    <- Boxing! Creates garbage!
  mem[1] = "hello";
  int x = (Integer) mem[0];       <- Unboxing! Type cast overhead!

Dual-bank approach (Nox):
  pMem[0] = 42L;                  <- Direct. No objects. No GC.
  rMem[0] = "hello";              <- Only objects that ARE objects.
  long x = pMem[0];               <- Direct read. Zero overhead.
```

**Impact:** For arithmetic-heavy code, the dual-bank approach eliminates millions of object allocations per second.
 
## The Sliding Window (Function Call Frames)

Nox doesn't copy arguments when calling functions. Instead, it **slides a pointer** over the same arrays.

### The Base Pointer

Each bank has a **Base Pointer** (`bp` for `pMem`, `bpRef` for `rMem`) that marks where the current function's registers begin.

```
pMem (absolute view):
┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐
│  0 │  1 │  2 │  3 │  4 │  5 │  6 │  7 │  8 │  9 │ 10 │ 11 │
│ a  │ b  │ c  │ x  │ y  │ -- │ -- │ -- │ -- │ -- │ -- │ -- │
└────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘
 ▲ bp=0 (main's frame)      ▲ bp=5 (func's frame, after CALL)
 │                          │
 main sees:                 func sees:
   Reg 0 = a                  Reg 0 = arg1
   Reg 1 = b                  Reg 1 = arg2
   Reg 2 = c                  Reg 2 = local1
```

### The Call Sequence

**Step 1: Caller Setup (The "Landing Zone")**

Before calling a function, the caller places arguments in the registers **immediately after** its own variables:

```
main's frame (bp=0, uses regs 0-4):

pMem: [a][b][c][x][y][ arg1 ][ arg2 ][ ??? ][ ??? ]
                       ▲
                       Landing Zone starts here (reg 5)
```

**Step 2: The `CALL` Instruction**

The `CALL` instruction:
1. Pushes the current `bp`, `pc` (program counter), and return metadata onto the **call stack**
2. Slides `bp` forward to the landing zone
3. Jumps to the target function's first instruction

```
After CALL (bp slides to 5):

pMem: [a][b][c][x][y][ arg1 ][ arg2 ][ local1 ][ local2 ]
                       ▲ bp=5
                       func sees these as Reg 0, Reg 1, Reg 2, Reg 3
```

**Step 3: Inside the Callee**

The called function operates on registers relative to `bp`. It has **no awareness** of the caller's data. Register 0 is `pMem[bp + 0]`, Register 1 is `pMem[bp + 1]`, etc.

**Step 4: The `RET` Instruction**

The `RET` instruction:
1. Conventionally places the return value in the first slot of the landing zone
2. Pops the call stack to restore the previous `bp` and `pc`
3. The caller reads the return value from the known landing zone offset

```
After RET (bp slides back to 0):

pMem: [a][b][c][x][y][ retval ][ stale ][ stale ][ stale ]
 ▲ bp=0                ▲
                        Caller picks up return value from here
```

### Zero-Copy Advantage

The key insight: **no data is copied during function calls.** Arguments are pre-placed by the caller, and the base pointer simply shifts. This makes function calls extremely cheap, just a pointer adjustment and a stack push.
 
## Global Memory Space

In addition to the per-function register window, there is a dedicated **global memory space**.

### The Problem with Copying Globals

An early design considered copying global values into function-local registers. This approach was rejected because:

- If `funcA` modifies a copy, `funcB` doesn't see the change
- State becomes desynchronized across call boundaries
- Copies waste memory and CPU cycles

### The Solution: `gMem`

Global variables live in a separate memory region. Instructions carry an `is_global` flag on each operand to indicate whether to read from the local frame or global memory:

```
IADD [G]0, [G]0, [L]5
       │     │     │
       │     │     └── Source 2: Local register 5 (pMem[bp + 5])
       │     └── Source 1: Global register 0 (gMem[0])
       └── Destination: Global register 0 (gMem[0])
```

This allows a single instruction to mix global and local operands without any special machinery.
 
## Memory Lifecycle

### Allocation

Registers are **never allocated at runtime**. The compiler determines the exact number of registers needed per function at compile time. The arrays are pre-sized at VM startup.

### Reference Cleanup

To prevent memory leaks (e.g., a 1GB string living in `rMem` forever), the compiler emits explicit cleanup instructions:

```c
{
    string data = File.read("big_file.txt");   // rMem[bp+3] = <huge string>
    // ... use data ...
}   // Compiler emits: KILL_REF 3  ->  rMem[bp+3] = null  ->  GC eligible
```

### Size Limits

| Resource | Limit | Enforced By |
|---|---|---|
| Primitive registers per VM | ~65,536 | Array size |
| Reference registers per VM | ~65,536 | Array size |
| Call stack depth | ~1,024 frames | Fixed-size call stack array |
| Registers per function | ~32,768 | 16-bit operand address space |
 
## Next Steps

- [**Instruction Set**](instruction-set.md)
- [**Super-Instructions**](super-instructions.md)
- [**Resource Guards**](resource-guards.md)
