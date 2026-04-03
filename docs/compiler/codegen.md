# Code Generation

Code generation is the final compiler phase. It takes the annotated AST (every `Expr` has `.resolvedType`, every identifier is resolved) and produces a `CompiledProgram`.

```
Annotated AST
    │
    ├── Register Allocation
    │   • Liveness analysis per function
    │   • Dual-bank assignment (pMem vs rMem)
    │   • Register reuse when lifetimes don't overlap
    │
    ├── Bytecode Emission
    │   • Switch-based walk over the AST
    │   • Opcode selection based on .resolvedType
    │   • Super-instruction selection for json/struct access
    │   • Forward-reference backpatching for jumps
    │   • RegNameEvent recording (params, locals, loop vars)
    │
    ├── Constant Pool Construction
    │   • Deduplicated strings, doubles, large ints, type IDs
    │
    ├── Exception Table Generation
    │   • PC range -> catch handler mapping
    │   • Nested try-catch support
    ▼
CompiledProgram {
    long[]        bytecode;              // Packed 64-bit instructions
    Object[]      constantPool;          // Strings, doubles, type metadata
    ExEntry[]     exceptionTable;        // try-catch PC ranges
    FuncMeta[]    functions;             // Per-function metadata (frame sizes, entry PCs, name events)
    ModuleMeta[]  modules;               // Per-module metadata (global offsets, exports)
    int           totalGlobalSlots;      // Total global slots across all modules
}
```
 
## Output Structure

### CompiledProgram

```kotlin
class CompiledProgram(
    val bytecode: LongArray,
    val constantPool: Array<Any?>,
    val exceptionTable: Array<ExEntry>,
    val functions: Array<FuncMeta>,
    val modules: Array<ModuleMeta>,          // Per-module metadata
    val mainFuncIndex: Int,
    val totalGlobalPrimitiveSlots: Int,      // Total gMem size across all modules
    val totalGlobalReferenceSlots: Int       // Total gMemRef size across all modules
)
```

### FuncMeta

```kotlin
data class FuncMeta(
    val name: String,
    val entryPC: Int,                // First instruction of this function
    val paramCount: Int,
    val primitiveFrameSize: Int,     // Number of pMem registers needed
    val referenceFrameSize: Int,     // Number of rMem registers needed
    val sourceLines: IntArray,       // Source-line number per instruction (parallel to bytecode slice)
    val labels: Map<Int, String>,    // localPC → label name (loop_start, loop_exit, catch_*, end)
    val regNameEvents: List<RegNameEvent>, // Register↔name timeline for the disassembler
    val sourcePath: String,          // Source file this function was defined in
    val globalVarNames: List<String> // Init blocks only: "g0=name (type)" entries
)

/** Records when a register is first assigned to a named variable. */
data class RegNameEvent(
    val localPC: Int,    // Instruction index relative to entryPC
    val isPrim: Boolean, // true = pMem, false = rMem
    val register: Int,   // Register number within the bank
    val name: String,    // Source variable name
)
```

The disassembler rebuilds a live `register → name` map by walking `regNameEvents` in `localPC` order. Parameters are recorded at `localPC = 0`; locals are recorded at the instruction where their declaration is emitted.

### ExEntry

```kotlin
data class ExEntry(
    val startPC: Int,                // First instruction of the try block
    val endPC: Int,                  // Last instruction of the try block (exclusive)
    val exceptionType: String?,      // "NetworkError", "TypeError", etc. (null = catch-all)
    val handlerPC: Int,              // First instruction of the catch block
    val messageRegister: Int         // rMem register for the error message string
)
```

### ModuleMeta

Each imported module gets a `ModuleMeta` entry tracking its global memory segment and exports:

```kotlin
data class ModuleMeta(
    val namespace: String,              // User-chosen: "helpers", "m"
    val sourcePath: String,             // Resolved absolute path
    val globalBaseOffset: Int,          // Start of this module's global segment
    val globalPrimitiveCount: Int,      // Primitive global slots for this module
    val globalReferenceCount: Int,      // Reference global slots for this module
    val exportedFunctions: List<Int>,   // Indices into functions array
    val exportedTypes: List<String>     // Type names visible as namespace.TypeName
)
```

Global memory is a single flat array, partitioned by module:

```
Global Memory (gMem / gMemRef):
┌─────────────────────┐  slot 0
│ main.nox globals    │  slots 0..N-1
├─────────────────────┤  slot N
│ math.nox globals    │  slots N..N+M-1   (private to "m" module)
├─────────────────────┤  slot N+M
│ helpers.nox globals │  slots N+M..end   (private to "helpers" module)
└─────────────────────┘
```

The compiler assigns each module's `GlobalVarDecl.globalSlot` with the module's `globalBaseOffset` added.
 
## Register Allocation

### Dual-Bank Model

Every variable lives in exactly one bank, determined by its type:

| Type | Bank | Storage |
|---|---|---|
| `int` | `pMem` | Direct `long` value |
| `double` | `pMem` | `Double.doubleToRawLongBits(value)` |
| `boolean` | `pMem` | `0` (false) or `1` (true) |
| `string` | `rMem` | `String` object reference |
| `json` | `rMem` | `NoxObject` or `NoxArray` reference |
| Structs | `rMem` | `NoxObject` reference |
| Arrays | `rMem` | `ArrayList` reference |

### Frame Layout

Each function call creates a **register window** within the two banks:

```
pMem (primitives):
┌─────────────────────────────────────────────────┐
│ Global │ Func A frame  │ Func B frame  │ ...    │
│ vars   │ bp────────▶│ bp────────▶│        │
└─────────────────────────────────────────────────┘

rMem (references):
┌─────────────────────────────────────────────────┐
│ Global │ Func A frame  │ Func B frame  │ ...    │
│ vars   │ bpRef─────▶│ bpRef─────▶│        │
└─────────────────────────────────────────────────┘
```

Registers within a frame are numbered starting from 0. Actual memory access is `pMem[bp + register]` or `rMem[bpRef + register]`.

### Linear Scan Allocation

For each function, the register allocator:

```
1. Collect all variables and temporaries
2. Compute liveness intervals (first use -> last use)
3. Sort by start position
4. Greedily assign registers, reusing freed ones

Two independent allocators run: one for pMem, one for rMem.
```

#### Liveness Example

```c
main(string url) {                    // url: rMem[0]
    int count = 0;                    // count: pMem[0], live [2..8]
    string label = "items";           // label: rMem[1], live [3..7]
    
    for (int i = 0; i < 10; i++) {    // i: pMem[1], live [4..6]
        count = count + 1;
    }                                 // i is dead -> pMem[1] free
    
    int result = count * 2;           // result: pMem[1] (REUSED!)
    return `${result} ${label}`;
}
```

Result:
- `pMem` frame size = 2 (slots 0 and 1)
- `rMem` frame size = 2 (slots 0 and 1)

Without reuse, `result` would need `pMem[2]` thus wasting a slot.

### Temporary Registers

Subexpressions need temporary registers. For `a + b * c`:

```
Step 1: Evaluate b -> temp pMem[T0]
Step 2: Evaluate c -> temp pMem[T1]
Step 3: IMUL T0, T0, T1            // T0 = b * c
Step 4: Evaluate a -> temp pMem[T1]  // T1 reused (c is dead)
Step 5: IADD dest, T1, T0           // dest = a + (b * c)
```

Temporaries are allocated from the same pool as locals, tracked with very short lifetimes.

### KILL_REF Emission

When a reference variable goes out of scope, the compiler emits `KILL_REF` to null its `rMem` slot, enabling garbage collection:

```c
{
    string temp = "hello";      // rMem[2] = "hello"
    yield temp;
}                               // KILL_REF 2  <- emitted here at scope exit
// rMem[2] is now null, "hello" is eligible for GC
```

**Rules:**

1. **Scope exit:** At the end of a block, emit `KILL_REF` for each `rMem` register first allocated inside that block. Skipped if the block ends with a `return` statement (rule 2 covers it).

2. **Before `return`:** `emitReturn` emits `KILL_REF` for **all** live `rMem` registers before the `RET` instruction.
 
## Bytecode Emission

### The Emitter

```kotlin
class BytecodeEmitter {
    private val instructions = mutableListOf<Long>()
    private val constantPool = ConstantPool()
    private val exceptionTable = mutableListOf<ExEntry>()

    // Emit a single instruction
    fun emit(opcode: Int, subOp: Int, a: Int, b: Int, c: Int): Int {
        val inst = ((opcode.toLong() and 0xFF) shl 56) or
                   ((subOp.toLong()  and 0xFF) shl 48) or
                   ((a.toLong()      and 0xFFFF) shl 32) or
                   ((b.toLong()      and 0xFFFF) shl 16) or
                   (c.toLong()       and 0xFFFF)
        instructions.add(inst)
        return instructions.size - 1     // Return PC for backpatching
    }

    // Current program counter
    fun pc(): Int = instructions.size

    // Backpatch a jump target
    fun patch(instrPC: Int, newTarget: Int) {
        var inst = instructions[instrPC]
        // Clear and set the target field (operand B for JMP/JIF/JIT)
        inst = (inst and -0x10000L) or (newTarget.toLong() and 0xFFFF)
        instructions[instrPC] = inst
    }
}
```

### Expression Emission

Each expression emits instructions that leave the result in a register. The caller specifies which register (`dest`).

```kotlin
fun emitExpr(expr: Expr, dest: Int) {
    when (expr) {
        // Literals
        is IntLiteralExpr      -> emitIntLiteral(expr, dest)
        is DoubleLiteralExpr   -> emitDoubleLiteral(expr, dest)
        is BoolLiteralExpr     -> emit(LDI, 0, dest, if (expr.value) 1 else 0, 0)
        is StringLiteralExpr   -> emitStringLiteral(expr, dest)
        is NullLiteralExpr     -> emit(KILL_REF, 0, dest, 0, 0)  // null
        is TemplateLiteralExpr -> emitTemplate(expr, dest)

        // Operators
        is BinaryExpr          -> emitBinary(expr, dest)
        is UnaryExpr           -> emitUnary(expr, dest)
        is PostfixExpr         -> emitPostfix(expr, dest)
        is CastExpr            -> emitCast(expr, dest)

        // References
        is IdentifierExpr      -> emitLoad(expr, dest)
        is FieldAccessExpr     -> emitFieldAccess(expr, dest)
        is IndexAccessExpr     -> emitIndexAccess(expr, dest)

        // Calls
        is FuncCallExpr        -> emitFuncCall(expr, dest)
        is MethodCallExpr      -> emitMethodCall(expr, dest)

        // Composites
        is ArrayLiteralExpr    -> emitArrayLiteral(expr, dest)
        is StructLiteralExpr   -> emitStructLiteral(expr, dest)
    }
}
```

### Opcode Selection By Type

The `resolvedType` annotation determines which opcode variant to emit:

```kotlin
fun emitBinary(expr: BinaryExpr, dest: Int) {
    val leftReg = allocTemp(expr.left.resolvedType!!)
    val rightReg = allocTemp(expr.right.resolvedType!!)
    emitExpr(expr.left, leftReg)
    emitExpr(expr.right, rightReg)

    val type = expr.resolvedType!!
    val opcode = when (expr.op) {
        BinaryOp.ADD -> if (type == TypeRef.INT) IADD else DADD
        BinaryOp.SUB -> if (type == TypeRef.INT) ISUB else DSUB
        BinaryOp.MUL -> if (type == TypeRef.INT) IMUL else DMUL
        BinaryOp.DIV -> if (type == TypeRef.INT) IDIV else DDIV
        BinaryOp.MOD -> if (type == TypeRef.INT) IMOD else DMOD

        BinaryOp.EQ  -> when (type) { TypeRef.INT -> IEQ; TypeRef.DOUBLE -> DEQ; else -> SEQ }
        BinaryOp.NE  -> when (type) { TypeRef.INT -> INE; TypeRef.DOUBLE -> DNE; else -> SNE }
        BinaryOp.LT  -> if (type == TypeRef.INT) ILT else DLT
        BinaryOp.LE  -> if (type == TypeRef.INT) ILE else DLE
        BinaryOp.GT  -> if (type == TypeRef.INT) IGT else DGT
        BinaryOp.GE  -> if (type == TypeRef.INT) IGE else DGE

        BinaryOp.AND -> AND_OP
        BinaryOp.OR  -> OR_OP

        BinaryOp.BIT_AND -> BAND
        BinaryOp.BIT_OR  -> BOR
        BinaryOp.BIT_XOR -> BXOR
        BinaryOp.SHL  -> SHL_OP
        BinaryOp.SHR  -> SHR_OP
        BinaryOp.USHR -> USHR_OP
    }

    emit(opcode, 0, dest, leftReg, rightReg)
    freeTemp(leftReg)
    freeTemp(rightReg)
}
```

### Implicit Widening

When `int + double` is detected during semantic analysis, codegen inserts a conversion:

```kotlin
// If left is int and right is double, widen left
if (expr.left.resolvedType == TypeRef.INT && expr.resolvedType == TypeRef.DOUBLE) {
    // Emit left as int, then convert to double
    emitExpr(expr.left, leftReg)
    emit(I2D, 0, leftReg, leftReg, 0)  // int-to-double conversion
}
```

### Cast Emission

A `json as StructType` cast requires runtime validation. The compiler builds a `TypeDescriptor` from the target struct's AST `TypeDef`, adding it to the constant pool.

1. **Allocate** slot via `ConstantPool.addPlaceholder()` (to handle recursive/self-referencing structs)
2. **Build** the descriptor fields (`FieldSpec` objects)
3. **Commit** the descriptor via `ConstantPool.replace()`
4. **Emit** `CAST_STRUCT dest, src, poolIdx`

SubOp values:
- `0`: scalar cast (`json as Config`)
- `1`: array cast (`json as Config[]`), validates each element
 
### Statement Emission

```kotlin
fun emitStmt(stmt: Stmt) {
    when (stmt) {
        is VarDeclStmt    -> emitVarDecl(stmt)
        is AssignStmt     -> emitAssign(stmt)
        is IncrementStmt  -> emitIncrement(stmt)
        is IfStmt         -> emitIf(stmt)
        is WhileStmt      -> emitWhile(stmt)
        is ForStmt        -> emitFor(stmt)
        is ForEachStmt    -> emitForEach(stmt)
        is ReturnStmt     -> emitReturn(stmt)
        is YieldStmt      -> emitYield(stmt)
        is BreakStmt      -> emitBreak()
        is ContinueStmt   -> emitContinue()
        is ThrowStmt      -> emitThrow(stmt)
        is TryCatchStmt   -> emitTryCatch(stmt)
        is ExprStmt       -> emitExpr(stmt.expression, allocTemp(stmt.expression.resolvedType!!))
        is Block          -> emitBlock(stmt)
    }
}
```
 
## Control Flow Patterns

### If / Else If / Else

```c
if (cond1) { A } else if (cond2) { B } else { C }
```

Compiles to:

```
    [eval cond1]
    JIF cond1_reg, ELSE_IF_LABEL
    [A]
    JMP END_LABEL
ELSE_IF_LABEL:
    [eval cond2]
    JIF cond2_reg, ELSE_LABEL
    [B]
    JMP END_LABEL
ELSE_LABEL:
    [C]
END_LABEL:
    ...
```

```kotlin
fun emitIf(stmt: IfStmt) {
    val condReg = allocTemp(TypeRef.BOOLEAN)
    emitExpr(stmt.condition, condReg)

    val jumpToElse = emit(JIF, 0, condReg, 0 /*patch*/, 0)
    emitBlock(stmt.thenBlock)
    var jumpToEnd = emit(JMP, 0, 0 /*patch*/, 0, 0)

    patch(jumpToElse, pc())    // ELSE_IF or ELSE starts here

    for (elseIf in stmt.elseIfs) {
        emitExpr(elseIf.condition, condReg)
        val jumpNext = emit(JIF, 0, condReg, 0 /*patch*/, 0)
        emitBlock(elseIf.body)
        jumpToEnd = emit(JMP, 0, 0 /*patch*/, 0, 0)  // chain
        patch(jumpNext, pc())
    }

    stmt.elseBlock?.let { emitBlock(it) }

    patch(jumpToEnd, pc())     // END_LABEL
    freeTemp(condReg)
}
```

### While Loop

```c
while (cond) { body }
```

```
LOOP_START:
    [eval cond]
    JIF cond_reg, LOOP_EXIT
    [body]
    JMP LOOP_START
LOOP_EXIT:
    ...
```

`break` -> `JMP LOOP_EXIT`, `continue` -> `JMP LOOP_START`

```kotlin
fun emitWhile(stmt: WhileStmt) {
    val loopStart = pc()
    pushLoopContext(loopStart)           // For break/continue

    val condReg = allocTemp(TypeRef.BOOLEAN)
    emitExpr(stmt.condition, condReg)
    val jumpToExit = emit(JIF, 0, condReg, 0 /*patch*/, 0)

    emitBlock(stmt.body)
    emit(JMP, 0, loopStart, 0, 0)       // Back to condition

    patch(jumpToExit, pc())
    patchBreaks(pc())                    // All break JMPs -> here
    popLoopContext()
    freeTemp(condReg)
}
```

### For Loop

```c
for (int i = 0; i < 10; i++) { body }
```

Desugars to the same while pattern with init and update:

```
    [init: int i = 0]
LOOP_START:
    [eval condition: i < 10]
    JIF cond_reg, LOOP_EXIT
    [body]
CONTINUE_TARGET:
    [update: i++]
    JMP LOOP_START
LOOP_EXIT:
    ...
```

Note: `continue` in a for loop jumps to `CONTINUE_TARGET` (the update), not `LOOP_START` (the condition).

### ForEach Loop

```c
foreach (ReportItem item in items) { body }
```

Compiles to an index-based loop:

```
    LDI  idx_reg, 0                    // int __idx = 0
    MOVR len_arg, items_reg             // pass arr as arg
    SCALL len_reg, __arr_length, len_arg // int __len = items.length()
LOOP_START:
    ILT  cond_reg, idx_reg, len_reg    // __idx < __len
    JIF  cond_reg, LOOP_EXIT
    AGET_IDX item_reg, items_reg, idx_reg  // item = items[__idx]
    [body]
    IINC idx_reg                       // __idx++
    JMP  LOOP_START
LOOP_EXIT:
    KILL_REF item_reg                   // item out of scope
```
 
## Function Calls

### User-Defined Function Call

```c
int result = add(x, y);
```

```
    MOV   argStart+0, x_reg       // Copy arg 1
    MOV   argStart+1, y_reg       // Copy arg 2
    CALL  [subOp] funcId, primArgStart, refArgStart // Push frame, jump to function
    MOV   dest_reg, retReg        // Extract return value
```

The `CALL` instruction:
1. Pushes a new call frame (saves `bp`, `bpRef`, return `pc`)
2. Slides `bp` and `bpRef` forward by the caller's frame size (`primArgStart` and `refArgStart`)
3. Arguments are already in the new frame's register 0, 1, 2, ...
4. Jumps to the function's `entryPC`

### System Call (Plugin/Namespace Function)

```c
double root = Math.sqrt(144);
```

```
    LDI   arg_reg, 144
    SCALL [subOp] funcId, primArgStart, refArgStart
```

`SCALL` invokes the linked `NoxNativeFunc` directly via `MethodHandle`. No frame push, the Kotlin function runs on the same coroutine. `subOp` determines if the result is primitive (1) or reference (0).

### Method Call (Resolution-Dependent)

The semantic analyzer already resolved what kind of call this is:

```kotlin
fun emitMethodCall(call: MethodCallExpr, dest: Int) {
    when (call.resolution) {
        MethodCallExpr.Resolution.NAMESPACE -> {
            if (call.isImportNamespace) {
                // helpers.formatDate(ts) -> CALL (compiled Nox function)
                emitArgs(call.args)
                emit(CALL, 0, funcId, argStart, 0)
            } else {
                // Math.sqrt(x) -> SCALL (native Kotlin function)
                emitArgs(call.args)
                emit(SCALL, 0, dest, funcId, argStart)
            }
        }
        MethodCallExpr.Resolution.TYPE_BOUND -> {
            // text.upper() -> SCALL (receiver is first arg)
            val argStart = allocTemp()
            emitExpr(call.target, argStart)
            emitArgs(call.args, argStart + 1)
            emit(SCALL, 0, dest, funcId, argStart)
        }
        MethodCallExpr.Resolution.UFCS -> {
            // point.distance(other) -> CALL distance(point, other)
            // Prepend target as first argument
            emitExpr(call.target, argStart)
            emitArgs(call.args, argStart + 1)
            emit(CALL, 0, funcId, argStart, 0)
        }
        else -> error("Unexpected resolution: ${call.resolution}")
    }
}
```
 
## Super-Instruction Selection

### When to Use HMOD / HACC

The code generator pattern-matches on field access and mutation to select the most efficient instruction:

```kotlin
fun emitFieldAccess(expr: FieldAccessExpr, dest: Int) {
    val targetType = expr.target.resolvedType!!

    if (targetType == TypeRef.JSON || isStructType(targetType)) {
        // json/struct property -> HACC super-instruction
        val targetReg = allocTemp(targetType)
        emitExpr(expr.target, targetReg)

        val subOp = when (expr.resolvedType?.name) {
            "int"     -> GET_INT
            "double"  -> GET_DBL
            "boolean" -> GET_BOOL
            "string"  -> GET_STR
            else      -> GET_OBJ      // Nested json/struct
        }

        val keyIdx = constantPool.add(expr.fieldName)
        emit(HACC, subOp, dest, targetReg, keyIdx)
    }
}
```

### Deep Path Optimization (AGET_PATH)

When the compiler detects a chain of field accesses on json with static keys, it collapses them into a single `AGET_PATH`:

```c
string dbHost = config.server.db.host;
```

Instead of three `HACC` instructions:
```
HACC GET_OBJ, t1, config_reg, "server"
HACC GET_OBJ, t2, t1, "db"
HACC GET_STR, dest, t2, "host"
```

The compiler emits:
```
AGET_PATH dest, config_reg, "server.db.host"    // One instruction
```

**Detection rule:** A chain of `FieldAccessExpr` nodes where every target is also a `FieldAccessExpr` (or an `IdentifierExpr` for the root), and the root type is `json`.
 
## Constant Pool

### Structure

```kotlin
class ConstantPool {
    private val entries = mutableListOf<Any?>()
    private val dedup = mutableMapOf<Any?, Int>()

    // Returns the pool index (creates entry if not already present)
    fun add(value: Any?): Int =
        dedup.getOrPut(value) {
            entries.add(value)
            entries.size - 1
        }
}
```

### What Goes In The Pool

| Value Type | When | Example |
|---|---|---|
| `String` | String literals, field names, template text, error messages | `"hello"`, `"name"`, `"NetworkError"` |
| `Double` | Double literals (can't fit in 16-bit immediate) | `3.14159` |
| `Long` | Integer literals > 16 bits | `100000` |
| `String` | Cached access paths | `"server.db.host"` |
| `TypeDescriptor` | Struct shape descriptors for `CAST_STRUCT` validation | `TypeDescriptor("ApiConfig", fields)` |

### Loading Constants: LDC

```
LDC  dest_reg, poolIndex
```

The VM reads `constantPool[poolIndex]` and stores it in the appropriate bank:
- `String` -> `rMem[bpRef + dest]`
- `Double` -> `pMem[bp + dest]` (as raw long bits)
- `Long` -> `pMem[bp + dest]`

### Small Integers: LDI

Integers that fit in 16 bits are loaded directly without touching the constant pool:

```
LDI  dest_reg, 42       // pMem[bp + dest] = 42
```

This avoids a pool lookup for common values like `0`, `1`, `10`, loop bounds, etc.
 
## Exception Table Generation

### Compilation of try-catch

```kotlin
fun emitTryCatch(stmt: TryCatchStmt) {
    val tryStart = pc()

    emitBlock(stmt.tryBlock)

    val tryEnd = pc()
    val jumpPastCatches = emit(JMP, 0, 0 /*patch*/, 0, 0)

    // Emit each catch handler
    for (clause in stmt.catchClauses) {
        val handlerPC = pc()
        val msgReg = allocRef()  // Register for error message

        // Register this handler in the exception table
        exceptionTable.add(ExEntry(
            tryStart, tryEnd,
            clause.exceptionType,       // null for catch-all
            handlerPC, msgReg
        ))

        // The error message is placed in msgReg by the VM
        emitBlock(clause.body)
        emit(JMP, 0, 0 /*patch end*/, 0, 0)

        freeRef(msgReg)
    }

    val afterCatches = pc()
    patch(jumpPastCatches, afterCatches)
    // Patch all catch-end jumps to afterCatches
}
```

### Nested try-catch

```c
try {                       // Entry [A]
    try {                   // Entry [B]
        risky();
    } catch (TypeError e) { // Handler for [B]
        recover();
    }
    moreWork();
} catch (err) {             // Handler for [A]
    yield "Fatal: " + err;
}
```

Exception Table (scanned in order):

| Start | End | Type | Handler |
|---|---|---|---|
| B_start | B_end | `TypeError` | B_handler |
| A_start | A_end | `ANY` | A_handler |

The VM scans the table for the current `pc`. **Inner entries appear first** so they take priority.
 
## Compound Assignment & Increment

### Increment/Decrement

```c
i++;    // IncrementStmt
```

```kotlin
fun emitIncrement(stmt: IncrementStmt) {
    val reg = resolveRegister(stmt.target)
    val type = stmt.target.resolvedType!!

    val opcode = when (stmt.op) {
        PostfixOp.INCREMENT -> if (type == TypeRef.INT) IINC else DINC
        PostfixOp.DECREMENT -> if (type == TypeRef.INT) IDEC else DDEC
    }
    emit(opcode, 0, reg, 0, 0)    // Single instruction, no temporaries
}
```

### Compound Assignment

```c
count += 5;     // AssignStmt with ADD_ASSIGN
```

```kotlin
fun emitCompoundAssign(stmt: AssignStmt) {
    val targetReg = resolveRegister(stmt.target)
    val valueReg = allocTemp(stmt.value.resolvedType!!)
    emitExpr(stmt.value, valueReg)

    // Optimization: i += N -> IINCN (single instruction)
    if (stmt.op == AssignOp.ADD_ASSIGN && stmt.target.resolvedType == TypeRef.INT) {
        emit(IINCN, 0, targetReg, valueReg, 0)
    } else if (stmt.op == AssignOp.SUB_ASSIGN && stmt.target.resolvedType == TypeRef.INT) {
        emit(IDECN, 0, targetReg, valueReg, 0)
    } else {
        // General case: compute result, store back
        val opcode = when (stmt.op) {
            AssignOp.ADD_ASSIGN -> if (stmt.target.resolvedType == TypeRef.INT) IADD else DADD
            AssignOp.SUB_ASSIGN -> if (stmt.target.resolvedType == TypeRef.INT) ISUB else DSUB
            AssignOp.MUL_ASSIGN -> if (stmt.target.resolvedType == TypeRef.INT) IMUL else DMUL
            AssignOp.DIV_ASSIGN -> if (stmt.target.resolvedType == TypeRef.INT) IDIV else DDIV
            AssignOp.MOD_ASSIGN -> if (stmt.target.resolvedType == TypeRef.INT) IMOD else DMOD
            else -> error("Unexpected op: ${stmt.op}")
        }
        emit(opcode, 0, targetReg, targetReg, valueReg)
    }
    freeTemp(valueReg)
}
```
 
## Template Literal Emission

```c
yield `Hello ${name}, you have ${count} items`;
```

Compiles to a sequence of string concatenation operations:

```
    LDC    t0, "Hello "                      // Constant pool
    MOVR   result, t0                         // result = "Hello "
    SCONCAT result, result, name_reg           // result += name
    LDC    t0, ", you have "
    SCONCAT result, result, t0                 // result += ", you have "
    I2S    t1, count_reg                      // int tostring conversion
    SCONCAT result, result, t1                 // result += count
    LDC    t0, " items"
    SCONCAT result, result, t0                 // result += " items"
    YIELD  result
```

**Optimization opportunity:** For templates with many parts, emit a `TEMPLATE` super-instruction that takes a parts array from the constant pool, avoiding N separate `SCONCAT` calls.
 
## Global Variables

Globals live in dedicated memory areas outside any function frame:

```
gMem[slot]       // Global primitives (int, double, boolean)
gMemRef[slot]    // Global references (string, json, structs, arrays)
```

Accessing a global uses the `[G]` flag (bit 15) on instruction operands. When set, the operand reads from or writes to `gMem`/`gMemRef` instead of the local frame:

```
LDI    [G]slot, 42                 // gMem[slot] = 42  (direct global write)
MOV    localReg, [G]slot           // pMem[bp+localReg] = gMem[slot]  (global → local)
IADD   [G]slot, [G]slot, localReg  // gMem[slot] += pMem[bp+localReg]
```
 
## Module Initialization

Global variables with initializers require code that runs **before `main()`**. The compiler emits a synthetic function called `<module_init>` for each module that has at least one global with a non-trivial initializer.

### What `<module_init>` Is

A `<module_init>` block is a **compiler-generated function** that:
- Has no parameters (`paramCount = 0`)
- Returns nothing (void)
- Appears as a regular `FuncMeta` entry in `CompiledProgram.functions`
- Is **never callable from user code** instead, it's invoked by the VM startup sequence

```kotlin
// Generated FuncMeta for a module_init block
FuncMeta(
    name = "<module_init>",       // Or "<module_init:helpers>" for imported modules
    entryPC = ...,
    paramCount = 0,
    primitiveFrameSize = ...,     // Scratch registers needed for init expressions
    referenceFrameSize = ...
)
```

### Emission Rules

For each `GlobalVarDecl` in the module, **in declaration order**:

| Initializer | Action | Example |
|---|---|---|
| **None** | Skip since `gMem`/`gMemRef` are zero/null-filled at allocation | `int count;` → already `0` |
| **Literal** | Emit `LDI [G]slot, value` or `LDC [G]slot, poolIdx` | `int x = 42;` → `LDI [G]0, 42` |
| **Expression** | Emit expression with global-flagged dest | `int x = add(1,2);` becomes `LDI p0,1; LDI p1,2; CALL add,p0; MOV [G]0,p0` |

When **all** globals in a module either have no initializer or are primitives with the value `0`, no `<module_init>` is emitted because the zero-filled memory is sufficient.

### Declaration-Order Constraint

Within a single file, globals are initialized **top-to-bottom**. A global may reference only globals declared **before** it in the same file:

```c
// VALID: b references a, which is already initialized
int a = 10;
int b = a + 5;

// INVALID: a references b, which hasn't been initialized yet
int a = b + 5;     // Compile error: forward reference to global 'b'
int b = 10;
```

The compiler enforces this in Pass 2: when resolving a global's initializer, only globals with a lower `globalSlot` index are visible.

### Cross-Module Execution Order

Init blocks execute in **depth-first import order** and a module's init runs only after all of its imports' inits have completed:

```
main.nox imports:
  ├─ helpers.nox (no imports)
  └─ math.nox imports:
       └─ constants.nox (no imports)

Execution order:
  1. constants.nox  <module_init>    (leaf)
  2. math.nox       <module_init>    (depends on constants)
  3. helpers.nox    <module_init>    (leaf)
  4. main.nox       <module_init>    (depends on helpers, math)
  5. main()
```

This order is determined by the `ImportResolver` during compilation and stored as the ordering of `CompiledProgram.modules`. The VM iterates `modules` in order and calls each `<module_init>` function before jumping to `main`.

### FuncMeta Integration

Init blocks are included in the `functions` array alongside user functions. They are distinguished by their naming convention:

```
Functions array:
  [0]  <module_init>            (main module)
  [1]  <module_init:helpers>    (helpers.nox)
  [2]  <module_init:math>       (math.nox)
  [3]  double_it                (user function)
  [4]  main                     (entry point)
```

The VM knows the init block indices from `ModuleMeta`. `CompiledProgram.mainFuncIndex` still points to `main`, and the VM runs all init blocks first.

### Default Values

When a global has no explicit initializer, its value comes from the memory allocation itself:

| Type | Default | Mechanism |
|---|---|---|
| `int` | `0` | `gMem` is zero-filled at VM startup |
| `double` | `0.0` | `0L` bits = `0.0` under IEEE 754 |
| `boolean` | `false` | `0L` = false |
| `string` | `null` | `gMemRef` is null-filled at VM startup |
| `json` | `null` | `gMemRef` is null-filled |
| Struct | `null` | `gMemRef` is null-filled |
| Array | `null` | `gMemRef` is null-filled |

Because both `gMem` and `gMemRef` are pre-allocated and zeroed, **no code is emitted for uninitialized globals**. This is free.

### Bytecode Example

```c
// constants.nox
double PI = 3.14159;
int MAX_RETRIES = 5;
```

```c
// main.nox
import "constants.nox" as c;

string PREFIX = "item_";
int counter = 0;                    // no init needed (default is 0)

main() {
    return `${PREFIX}${counter}`;
}
```

**Generated init blocks:**

```
.init c
  ; globals: g0=PI (double)  g1=MAX_RETRIES (int)
  ; source:  constants.nox
  ;
  ; constants.nox:1  double PI = 3.14159;
  0000:  LDC       g0, #0                   ; g0 = 3.14159
  ;
  ; constants.nox:2  int MAX_RETRIES = 5;
  0001:  LDI       g1, 5                    ; g1 = MAX_RETRIES
  0002:  RET                                ; return (void)

.init main
  ; globals: gr0=PREFIX (string)
  ; source:  main.nox
  ; note:    g2=counter (int) skipped, default is 0
  ;
  ; main.nox:5  string PREFIX = "item_";
  0003:  LDC       gr0, #1                  ; gr0 = "item_"
  0004:  RET                                ; return (void)

.func main
  ;
  ; main.nox:8  return `${PREFIX}${counter}`;
  0005:  MOV       r0, gr0                  ; r0 = gr0  (via [G] flag)
  0006:  I2S       r1, g2                   ; r1 = toString(g2)  (via [G] flag)
  0011:  SCONCAT   r0, r0, r1               ; r0 = r0 + r1
  0012:  KILL_REF  r1                       ; r1 = null (GC)
  0013:  KILL_REF  r0                       ; r0 = null (GC)
  0014:  RET       r0                       ; return r0
```

**Execution sequence:**
1. VM calls `<module_init:c>` (instructions 0–4)
2. VM calls `<module_init>` (instructions 5–7)
3. VM calls `main` (instructions 8–12)
 
## Full Compilation Example

### Source

```c
@tool:name "adder"
@tool:description "Adds two numbers and scales the result."

int MULTIPLIER = 2;

int double_it(int x) {
    return x * MULTIPLIER;
}

main(int a = 1, int b = 2) {
    int sum = a + b;
    int result = double_it(sum);
    return `Result: ${result}`;
}
```

### Constant Pool

```
[0] "Result: "    (String)
```

### Function Metadata

```
Init 0: "<module_init>"  entry=0   params=0  pFrame=1  rFrame=0
Func 1: "double_it"      entry=3   params=1  pFrame=2  rFrame=0
Func 2: "main"           entry=6   params=2  pFrame=4  rFrame=2
```

### Bytecode

```
.init main
  ; globals: g0=MULTIPLIER (int)
  ;
  ; adder.nox:4  int MULTIPLIER = 2;
  0000:  LDI       g0, 2                    ; g0 = MULTIPLIER
  0001:  RET                                ; return (void)

.func double_it
  ; params: p0=x
  ;
  ; adder.nox:7  return x * MULTIPLIER;
  0002:  IMUL      p1, p0, g0               ; p1 = x:p0 * g0  (g0 via [G] flag)
  0005:  RET       p1                       ; return p1

.func main
  ; params: p0=a  p1=b
  ;
  ; adder.nox:11  int sum = a + b;
  0006:  IADD      p2, p0, p1               ; sum:p2 = a:p0 + b:p1
  ;
  ; adder.nox:12  int result = double_it(sum);
  0007:  MOV       p3, p2                   ; p3 = sum:p2
  0008:  CALL      double_it, p3            ; call double_it(p3...)
  0009:  MOV       p2, p3                   ; result:p2 = p3
  ;
  ; adder.nox:13  return `Result: ${result}`;
  0010:  LDC       r0, #0                   ; r0 = "Result: "
  0011:  I2S       r1, p2                   ; r1 = toString(result:p2)
  0012:  SCONCAT   r0, r0, r1               ; r0 = r0 + r1
  0013:  KILL_REF  r1                       ; r1 = null (GC)
  0014:  KILL_REF  r0                       ; r0 = null (GC)
  0015:  RET       r0                       ; return r0
```

**Execution sequence:** VM calls `<module_init>` (0–1), then `main`. `main` calls `double_it` which reads the global `MULTIPLIER` directly via the `[G]` flag on the operand.
 
## Compilation Metrics

After compilation, the compiler can report:

```
Compiled "adder" successfully.
  Init blocks: 1
  Functions:   2
  Bytecode:    13 instructions (104 bytes)
  Constants:   1 entry
  Globals:     1p + 0r
  Max frame:   pMem=4, rMem=2 (main)
  Estimated memory: ~300 bytes
```
 
## Next Steps

- [**Instruction Set**](../vm/instruction-set.md)
- [**Memory Model**](../vm/memory-model.md)
- [**Super-Instructions**](../vm/super-instructions.md)
- [**Semantic Analysis**](semantic-analysis.md)
