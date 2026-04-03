# Instruction Set

## Opcode Compression

**One complex instruction (a Kotlin function call) is always faster than ten simple instructions (ten VM loop iterations).**

In a JVM-based VM, the fetch-decode-execute loop has a fixed overhead. Every iteration of the `while(running)` loop costs CPU cycles just to read an instruction and dispatch to the right handler. The strategy is to **compress as much work as possible into single instructions**, minimizing the time spent in the dispatch loop and maximizing the time spent in HotSpot-optimized Kotlin code.

**Example `obj.a += 1`:**

| Approach | Opcodes Used | VM Loop Iterations |
|---|---|---|
| Naive VM | `GET_FIELD` → `LDC` → `ADD` → `SET_FIELD` | **4** |
| Nox VM | `HMOD [SubOp: ADD_INT]` | **1** |
 
## The 64-Bit Instruction Layout

Every instruction is encoded as a single `long` (64 bits) with a fixed layout:

```
 63       56 55       48 47              32 31              16 15               0
┌───────────┬───────────┬─────────────────┬─────────────────┬─────────────────┐
│  Opcode   │ Sub-Opcode│    Operand A    │    Operand B    │    Operand C    │
│  (8 bits) │  (8 bits) │   (16 bits)     │   (16 bits)     │   (16 bits)     │
└───────────┴───────────┴─────────────────┴─────────────────┴─────────────────┘
```

| Field | Bits | Purpose |
|---|---|---|
| **Opcode** | 63–56 (8 bits) | The primary operation (e.g., `IADD`, `MOV`, `CALL`). Supports up to 256 unique opcodes. |
| **Sub-Opcode** | 55–48 (8 bits) | Secondary intent for "super-instructions" (e.g., `ADD_INT`, `SET_STRING`). Unused by standard opcodes. |
| **Operand A** | 47–32 (16 bits) | Typically the **destination** register. Address space: 0–65,535. |
| **Operand B** | 31–16 (16 bits) | Typically **source 1** or a constant pool index. |
| **Operand C** | 15–0 (16 bits) | Typically **source 2** or additional data. |

### Operand Flags

Each operand can carry flags to modify its interpretation:

- **Global flag `[G]`**: Read from global memory (`gMem`) instead of the local frame
- **Constant flag `[K]`**: The operand is a constant pool index, not a register
 
## Opcode Reference

### Arithmetic & Logic

| Opcode | Syntax | Description |
|---|---|---|
| `IADD` | `IADD A, B, C` | Integer add: `pMem[A] = pMem[B] + pMem[C]` |
| `ISUB` | `ISUB A, B, C` | Integer subtract: `pMem[A] = pMem[B] - pMem[C]` |
| `IMUL` | `IMUL A, B, C` | Integer multiply |
| `IDIV` | `IDIV A, B, C` | Integer divide (throws on division by zero) |
| `IMOD` | `IMOD A, B, C` | Integer modulo |
| `INEG` | `INEG A, B` | Integer negate: `pMem[A] = -pMem[B]` |
| `DADD` | `DADD A, B, C` | Double add (operands decoded via `longBitsToDouble`) |
| `DSUB` | `DSUB A, B, C` | Double subtract |
| `DMUL` | `DMUL A, B, C` | Double multiply |
| `DDIV` | `DDIV A, B, C` | Double divide |
| `DMOD` | `DMOD A, B, C` | Double modulo |
| `DNEG` | `DNEG A, B` | Double negate |
| `AND` | `AND A, B, C` | Logical AND (boolean) |
| `OR` | `OR A, B, C` | Logical OR (boolean) |
| `NOT` | `NOT A, B` | Logical NOT: `pMem[A] = pMem[B] == 0 ? 1 : 0` |

### Comparison

| Opcode | Syntax | Description |
|---|---|---|
| `IEQ` | `IEQ A, B, C` | Integer equals: `pMem[A] = (pMem[B] == pMem[C]) ? 1 : 0` |
| `INE` | `INE A, B, C` | Integer not-equals |
| `ILT` | `ILT A, B, C` | Integer less-than |
| `ILE` | `ILE A, B, C` | Integer less-than-or-equal |
| `IGT` | `IGT A, B, C` | Integer greater-than |
| `IGE` | `IGE A, B, C` | Integer greater-than-or-equal |
| `DEQ` | `DEQ A, B, C` | Double equals |
| `DNE` | `DNE A, B, C` | Double not-equals |
| `DLT` | `DLT A, B, C` | Double less-than |
| `DLE` | `DLE A, B, C` | Double less-than-or-equal |
| `DGT` | `DGT A, B, C` | Double greater-than |
| `DGE` | `DGE A, B, C` | Double greater-than-or-equal |
| `SEQ` | `SEQ A, B, C` | String value equals: `pMem[A] = rMem[B].equals(rMem[C]) ? 1 : 0` |
| `SNE` | `SNE A, B, C` | String not-equals |

### Data Movement

| Opcode | Syntax | Description |
|---|---|---|
| `MOV` | `MOV A, B` | Copy primitive: `pMem[A] = pMem[B]` |
| `MOVR` | `MOVR A, B` | Copy reference: `rMem[A] = rMem[B]` |
| `LDC` | `LDC A, PoolIdx` | Load constant from the constant pool into `pMem[A]` or `rMem[A]` |
| `LDI` | `LDI A, Imm` | Load immediate (small integer that fits in 16 bits) |
| `KILL_REF` | `KILL_REF A` | Null out `rMem[A]` to enable garbage collection |

### Control Flow

| Opcode | Syntax | Description |
|---|---|---|
| `JMP` | `JMP target` | Unconditional jump: `pc = target` |
| `JIF` | `JIF A, target` | Jump if false: `if (pMem[A] == 0) pc = target` |
| `JIT` | `JIT A, target` | Jump if true: `if (pMem[A] != 0) pc = target` |
| `CALL` | `CALL [subOp] funcId, primArgStart, refArgStart` | Push frame, slide `bp` and `bpRef`, jump to function. `subOp` indicates return type (0=REF, 1=PRIM, 2=VOID). |
| `RET` | `RET isVoid, reg` | Returns from function. If `isVoid=0`, copies `reg` to the caller's result slot. |
| `RET` | `RET isVoid, typeTag, reg` | Returns from function. If `isVoid=0`, copies `reg` to caller's result slot. `typeTag` (0=INT, 1=DBL, 2=BOOL, 3=REF) used for conversion in `main`. |

### System Calls

| Opcode | Syntax | Description |
|---|---|---|
| `SCALL` | `SCALL [subOp] funcId, primArgStart, refArgStart` | System call via FFI. `subOp` determines result type: primitive (1) or reference (0). Result overwrites the first argument register (`primArgStart` or `refArgStart`). |

### Struct Operations

| Opcode | Syntax | Description |
|---|---|---|
| `NEW_OBJ` | `NEW_OBJ A` | Creates a new empty `NoxObject` and stores it in `rMem[A]`. |
| `OBJ_SET` | `OBJ_SET A, keyId, val` | Sets property `pool[keyId]` on object `rMem[A]` to `val`. |
| `CAST_STRUCT` | `CAST_STRUCT [SubOp] A, B, typeId` | Validates `rMem[B]` against `TypeDescriptor` at `pool[typeId]`, storing result in `rMem[A]`. If `SubOp=1`, validates array of structs. |

### Host Interaction (Super-Instructions)

| Opcode | Syntax | Description |
|---|---|---|
| `HMOD` | `HMOD [SubOp] A, key, val` | Host Modify: modify a property on a host object |
| `HACC` | `HACC [SubOp] A, B, key` | Host Access: read a property from a host object |
| `AGET_KEY` | `AGET_KEY [SubOp] A, B, key` | Get a named property from object `B`, store in `A` |
| `AGET_IDX` | `AGET_IDX [SubOp] A, B, C` | Get element at index `C` from collection `B`, store in `A` |
| `AGET_PATH` | `AGET_PATH [SubOp] A, B, path` | Traverse a cached static path on object `B`, store in `A` |
| `ASET_KEY` | `ASET_KEY [SubOp] A, key, val` | Set a named property on object `A` |
| `ASET_IDX` | `ASET_IDX [SubOp] A, B, C` | Set element at index `B` in collection `A` to value `C` |
| `SCONCAT` | `SCONCAT A, B, C` | String concat: `rMem[A] = rMem[B] + rMem[C]` |

### Streaming & Output

| Opcode | Syntax | Description |
|---|---|---|
| `YIELD` | `YIELD A, typeTag` | Send intermediate output via `RuntimeContext.yield()`. `typeTag` used for string conversion. |

### Increment & Decrement

| Opcode | Syntax | Description |
|---|---|---|
| `IINC` | `IINC A` | Integer increment: `pMem[A] = pMem[A] + 1` |
| `IDEC` | `IDEC A` | Integer decrement: `pMem[A] = pMem[A] - 1` |
| `IINCN` | `IINCN A, B` | Integer increment by N: `pMem[A] = pMem[A] + pMem[B]` |
| `IDECN` | `IDECN A, B` | Integer decrement by N: `pMem[A] = pMem[A] - pMem[B]` |
| `DINC` | `DINC A` | Double increment by 1.0 |
| `DDEC` | `DDEC A` | Double decrement by 1.0 |
| `DINCN` | `DINCN A, B` | Double increment by N |
| `DDECN` | `DDECN A, B` | Double decrement by N |

These enable single-instruction compilation of `i++`, `i--`, `i += N`, and `i -= N`.

### Bitwise Operations

| Opcode | Syntax | Description |
|---|---|---|
| `BAND` | `BAND A, B, C` | Bitwise AND: `pMem[A] = pMem[B] & pMem[C]` |
| `BOR` | `BOR A, B, C` | Bitwise OR: `pMem[A] = pMem[B] \| pMem[C]` |
| `BXOR` | `BXOR A, B, C` | Bitwise XOR: `pMem[A] = pMem[B] ^ pMem[C]` |
| `BNOT` | `BNOT A, B` | Bitwise NOT: `pMem[A] = ~pMem[B]` |
| `SHL` | `SHL A, B, C` | Shift left: `pMem[A] = pMem[B] << pMem[C]` |
| `SHR` | `SHR A, B, C` | Arithmetic shift right: `pMem[A] = pMem[B] >> pMem[C]` |
| `USHR` | `USHR A, B, C` | Unsigned shift right: `pMem[A] = pMem[B] >>> pMem[C]` |

### Exception Handling

| Opcode | Syntax | Description |
|---|---|---|
| `THROW` | `THROW A` | Throws an exception with the message from register `A` |
| `KILL` | `KILL` | Terminates execution (used for resource guard exceptions) |

**NOTE:** `KILL` is a special instruction that is used to terminate execution of the current thread. It is used for resource guard exceptions and is not intended to be used by the programmer. It is inserted by the compiler at the end of a resource guard catch block that is intended to terminate execution.
 
## The Constant Pool

The 16-bit operand fields cannot hold large values (strings, big numbers, doubles). These are stored in a separate **Constant Pool**, an array generated at compile time.

### Structure

```
Index 0: "user_name"          (String)
Index 1: 3.14159265358979     (Double)
Index 2: "status"             (String)  
Index 3: 100000               (Large Integer)
Index 4: TypeDescriptor("ApiConfig") (Struct Schema)
```

### Deduplication

If the script references `"user_name"` in 50 different places, the constant pool stores it **once**. All 50 instructions reference the same pool index.

**Impact:** Dramatically reduces the memory footprint of the bytecode.

### Loading Constants: `LDC`

```
LDC R3, #5     // Load constant pool entry 5 into register 3

Execution:
  1. Read pool index (5)
  2. Look up ConstantPool[5] -> "hello world"
  3. Write to rMem[bp + 3] = "hello world"
```
 
## Instruction Decoding

The VM decodes instructions using bitwise operations for maximum speed:

```kotlin
val inst = bytecode[pc]

val opcode = ((inst ushr 56) and 0xFF).toInt()
val subOp  = ((inst ushr 48) and 0xFF).toInt()
val opA    = ((inst ushr 32) and 0xFFFF).toInt()
val opB    = ((inst ushr 16) and 0xFFFF).toInt()
val opC    = (inst and 0xFFFF).toInt()
```

This is a single array read followed by five bit-shift operations, among the fastest operations a CPU can perform.
 
## The Execution Loop

The VM's core is a `while` loop with a `switch` dispatch:

```kotlin
while (running) {
    val inst = bytecode[pc++]
    val opcode = ((inst ushr 56) and 0xFF).toInt()

    // Watchdog: instruction counter
    if (++instructionCount > MAX_INSTRUCTIONS) {
        throw QuotaExceededException()
    }

    when (opcode) {
        IADD  -> { /* ... */ }
        CALL  -> { /* ... */ }
        SCALL -> { /* ... */ }
        HMOD  -> { /* ... */ }
        // ...
    }
}
```

The JVM's JIT compiler aggressively optimizes this pattern, inlining handler code and eliminating bounds checks where safe.
 
## Next Steps

- [**Super-Instructions**](super-instructions.md)
- [**Memory Model**](memory-model.md)
- [**Error Handling**](error-handling.md)
