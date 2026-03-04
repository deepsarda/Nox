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
    │
    ├── Constant Pool Construction
    │   • Deduplicated strings, doubles, large ints, type IDs
    │
    ├── Exception Table Generation
    │   • PC range -> catch handler mapping
    │   • Nested try-catch support
    ▼
CompiledProgram {
    long[]       bytecode;       // Packed 64-bit instructions
    Object[]     constantPool;   // Strings, doubles, type metadata
    ExEntry[]    exceptionTable; // try-catch PC ranges
    FuncMeta[]   functions;      // Per-function metadata (frame sizes, entry PCs)
    ModuleMeta[] modules;        // Per-module metadata (global offsets, exports)
    int          totalGlobalSlots; // Total global slots across all modules
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
    val referenceFrameSize: Int      // Number of rMem registers needed
)
```

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
}                               // KILL_REF 2  <- emitted here
// rMem[2] is now null, "hello" is eligible for GC
```

**Rule:** At every scope exit, emit `KILL_REF` for each `rMem` register first used in that scope.
 
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
    HINV ARR_LEN, len_reg, items_reg   // int __len = items.length
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
    CALL  funcId, argStart        // Push frame, jump to function
    MOV   dest_reg, retReg        // Extract return value
```

The `CALL` instruction:
1. Pushes a new call frame (saves `bp`, `bpRef`, return `pc`)
2. Slides `bp` and `bpRef` forward by the caller's frame size
3. Arguments are already in the new frame's register 0, 1, 2, ...
4. Jumps to the function's `entryPC`

### System Call (Plugin/Namespace Function)

```c
double root = Math.sqrt(144);
```

```
    LDI   arg_reg, 144
    SCALL dest_reg, funcId, arg_reg
```

`SCALL` invokes the linked `NoxNativeFunc` directly via `MethodHandle`. No frame push, the Kotlin function runs on the same coroutine.

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
            // text.upper() -> HINV STR_UPPER
            val targetReg = allocTemp(call.target.resolvedType!!)
            emitExpr(call.target, targetReg)
            emit(HINV, subOpFor(call.methodName), dest, targetReg, 0)
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

### When to Use HMOD / HACC / HINV

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
    else if (expr.fieldName == "length") {
        // string.length or array.length -> HINV
        val targetReg = allocTemp(targetType)
        emitExpr(expr.target, targetReg)
        val subOp = if (targetType == TypeRef.STRING) STR_LEN else ARR_LEN
        emit(HINV, subOp, dest, targetReg, 0)
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
| `TypeId` | Struct type identifiers for `as` casts | `TypeId("ApiConfig")` |

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
    LDC   t0, "Hello "                     // Constant pool
    MOVR  result, t0                        // result = "Hello "
    HINV  STR_CONCAT, result, result, name_reg  // result += name
    LDC   t0, ", you have "
    HINV  STR_CONCAT, result, result, t0    // result += ", you have "
    I2S   t1, count_reg                     // int tostring conversion
    HINV  STR_CONCAT, result, result, t1    // result += count
    LDC   t0, " items"
    HINV  STR_CONCAT, result, result, t0    // result += " items"
    YIELD result
```

**Optimization opportunity:** For templates with many parts, emit a `TEMPLATE` super-instruction that takes a parts array from the constant pool, avoiding N separate `STR_CONCAT` calls.
 
## Global Variables

Globals live in dedicated memory areas outside any function frame:

```
gMem[slot]       // Global primitives (int, double, boolean)
gMemRef[slot]    // Global references (string, json, structs, arrays)
```

Accessing a global uses a different opcode or a flag on the load/store:

```
GLOAD    dest_reg, globalSlot     // Load global into register
GSTORE   globalSlot, source_reg   // Store register into global
GLOADR   dest_reg, globalSlot     // Reference variant
GSTORER  globalSlot, source_reg   // Reference variant
```
 
## Full Compilation Example

### Source

```c
@tool:name "adder"
@tool:description "Adds two numbers."

int double_it(int x) {
    return x * 2;
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
Func 0: "double_it"  entry=0  params=1  pFrame=2  rFrame=0
Func 1: "main"       entry=3  params=2  pFrame=3  rFrame=2
```

### Bytecode

```
;;─ double_it(int x)─
;; x is in pMem[bp+0], result in pMem[bp+1]
0: LDI     R1, 2                    // R1 = 2
1: IMUL    R1, R0, R1               // R1 = x * 2
2: RET     R1                       // return R1

;;─ main(int a, int b)─
;; a is in pMem[bp+0], b is in pMem[bp+1]
3: IADD    R2, R0, R1               // R2 = a + b  (sum)
4: MOV     argstart, R2             // Copy sum as arg
5: CALL    0, argstart              // call double_it and put the result in R2
6: LDC     rR0, #0                  // rR0 = "Result: "
7: I2S     rR1, R2                  // rR1 = toString(result)
8: HINV    STR_CONCAT, rR0, rR0, rR1  // rR0 = "Result: " + result
9: RET     rR0                      // return the string
```
 
## Compilation Metrics

After compilation, the compiler can report:

```
Compiled "adder" successfully.
  Functions: 2
  Bytecode:  10 instructions (80 bytes)
  Constants: 1 entry
  Max frame: pMem=3, rMem=2 (main)
  Estimated memory: ~200 bytes
```
 
## Next Steps

- [**Instruction Set**](../vm/instruction-set.md)
- [**Memory Model**](../vm/memory-model.md)
- [**Super-Instructions**](../vm/super-instructions.md)
- [**Semantic Analysis**](semantic-analysis.md)
