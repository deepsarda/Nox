# Bytecode Disassembly (.noxc)

## Overview

The `noxc` format is a **human-readable disassembly** of a compiled Nox program. It is the primary debugging tool for the compiler that shows exactly what bytecode was emitted, how registers are allocated, and how the constant pool and exception tables are structured.

```bash
# Generate disassembly
$ nox compile --emit-noxc hello.nox          # Writes hello.noxc
$ nox compile --emit-noxc --stdout hello.nox  # Prints to terminal

# Compile and run with disassembly side-by-side
$ nox run --debug hello.nox                   # Runs + prints noxc on error
```

The `.noxc` format is **not executable** for security reasons and exists solely for human inspection and test assertions.
 
## Format Specification

A `.noxc` file has six sections, in order:

```
1. File Header         (source file, compilation timestamp)
2. Constant Pool       (all pooled values with indices)
3. Module Init Blocks  (global variable initialization, one per module)
4. Function Listing    (one block per function, with bytecode)
5. Exception Table     (try-catch mappings)
6. Summary             (metrics)
```
 
## Complete Example

### Source: `adder.nox`

```c
@tool:name "adder"
@tool:description "Adds two numbers and doubles the result."

int double_it(int x) {
    return x * 2;
}

main(int a = 1, int b = 2) {
    int sum = a + b;
    int result = double_it(sum);
    return `Result: ${result}`;
}
```

### Disassembly: `adder.noxc`

```
;  Nox Bytecode Disassembly
;  Source:   adder.nox
;  Program:  "adder"
;  Compiled: 2026-02-22T15:55:00+05:30


; Constant Pool

.constants
  #0  str   "Result: "


;  Function: double_it
;    Signature:  int double_it(int x)
;    Entry PC:   0
;    Params:     1
;    Frame:      pMem=2  rMem=0

.func double_it
  ; params: p0=x
  ;
  ; adder.nox:5  return x * 2;
  0000:  LDI       p1, 2                    ; p1 = 2
  0001:  IMUL      p1, p0, p1               ; p1 = x * 2
  0002:  RET       p1                       ; return p1


;  Function: main
;    Signature:  main(int a = 1, int b = 2)
;    Entry PC:   3
;    Params:     2
;    Frame:      pMem=4  rMem=2

.func main
  ; params: p0=a  p1=b
  ;
  ; adder.nox:9  int sum = a + b;
  0003:  IADD      p2, p0, p1               ; p2 = a + b
  ;
  ; adder.nox:10  int result = double_it(sum);
  0004:  MOV       p3, p2                   ; arg0 = sum
  0005:  CALL      double_it, p3            ; call double_it(sum)
  0006:  MOV       p2, p3                   ; p2 = result (reused reg)
  ;
  ; adder.nox:11  return `Result: ${result}`;
  0007:  LDC       r0, #0                   ; r0 = "Result: "
  0008:  I2S       r1, p2                   ; r1 = toString(result)
  0009:  SCONCAT   r0, r0, r1               ; r0 = "Result: " + result
  0010:  RET       r0                       ; return r0


; Exception Table

.exceptions
  (none)


; Summary

.summary
  modules:      1
  init_blocks:  0
  functions:    2
  instructions: 11
  constants:    1
  exceptions:   0
  globals:      0p + 0r
  bytecode:     88 bytes
```
 
## Format Details

### File Header

```
;  Nox Bytecode Disassembly
;  Source:   <filename>.nox
;  Program:  "<@tool:name value>"
;  Compiled: <ISO 8601 timestamp>
;  Modules:  <count> (<name1>, <name2>, ...)
```

The `Modules:` line lists the total number of modules followed by a parenthesized, comma-separated list of module names. Module names appear in depth-first import order, with the root module listed last (e.g., `2 (main, c)`).

Module names are bare identifiers taken from the `import ... as <name>` alias; the root module is always called `main`. Names are not quoted or escaped (they are valid Nox identifiers and cannot contain commas or parentheses).

### Constant Pool

```
.constants
  #<index>  <type>  <value>
```

Types:
| Tag | Meaning | Display |
|---|---|---|
| `str` | String constant | `"hello world"` (escaped) |
| `dbl` | Double constant | `3.14159` |
| `lng` | Long constant (> 16 bits) | `100000` |
| `type` | Struct type descriptor | `ApiConfig { count: int, url: string }` |

Example:
```
.constants
  #0   str   "version"
  #1   type  ApiConfig { endpoint: string, timeout_seconds: int }
```

### Module Init Block

Each module with non-trivial global initializers gets an init block that runs before `main()`:

```
.init <module_name>
  ; globals: <register>=<name>  ...
  ; source:  <filename>
  ;
  ; <file>:<line>  <global declaration>
  <PC>:  <OPCODE>  <operands>  ; <comment>
```

The `<module_name>` is the module's namespace (e.g., `helpers`, `math`) or `main` for the root module. Init blocks appear **before** function blocks, ordered by execution sequence (depth-first import order).

The `; source: <filename>` annotation indicates the originating source file for the init block. It is emitted for readability and debugging so that readers can trace each init block back to the file whose global declarations it initializes (e.g., `; source:  constants.nox`). The annotation appears on its own comment line immediately after the `globals:` listing and before the first source-line annotation. It is always present in multi-module programs and may be omitted for single-module programs where the source is unambiguous.

#### Example

```
.init c
  ; globals: g0=PI (double)  g1=MAX_RETRIES (int)
  ; source: constants.nox
  ; constants.nox:1  double PI = 3.14159;
  0000:  LDC       g0, #0                   ; g0 = 3.14159
  ;
  ; constants.nox:2  int MAX_RETRIES = 5;
  0001:  LDI       g1, 5                    ; g1 = MAX_RETRIES
  0002:  RET                                ; return (void)
```

Notice that init blocks:
- Write directly into global memory via the `[G]` flag on the destination operand (`g0`, `g1`, `gr0`)
- Have no parameters (no `params:` line, replaced by `globals:` listing)
- Can have jumps, loops, or labels (allows for complex initializer expressions)

### Function Block

```
.func <name>
  ; params: <register>=<name>  ...
  ;
  ; <file>:<line>  <source line>
  <PC>:  <OPCODE>  <operands>  ; <comment>
```

#### Register Naming

Registers use a prefix to show their bank:

| Prefix | Bank | Meaning |
|---|---|---|
| `p0`, `p1`, ... | `pMem` | Primitive register (int, double, boolean) |
| `r0`, `r1`, ... | `rMem` | Reference register (string, json, struct, array) |
| `g0`, `g1`, ... | `gMem` | Global primitive |
| `gr0`, `gr1`, ... | `gMemRef` | Global reference |

This immediately tells the reader what type of value a register holds.

#### Source Line Annotations

When the source line changes, a comment shows the original code:

```
  ; data_processor.nox:15 json data = Http.getJson(url);
  0042:  MOVR      r3, r1                   ; arg0 = url
  0043:  SCALL     r2, Http.getJson, r3     ; r2 = Http.getJson(url)
```

Multiple instructions from the same source line share the annotation. The annotation only appears once at the start of a new source line.

#### Instruction Format

```
  <PC>:  <OPCODE>  <operands>  ; <comment>
```

- **PC:** 4-digit zero-padded program counter
- **OPCODE:** mnemonic, left-padded to 10 characters for alignment
- **Operands:** comma-separated, using register names and constant pool references
- **Comment:** after `;`, explains the operation in human-readable pseudocode

#### Comment Style

When a register holds a named variable, the comment uses `name:pN` / `name:rN` format:

```
  ; a:p0 = 10            <- named variable in pMem
  ; url:r0 = "http..."   <- named variable in rMem
  ; p3 = 42              <- unnamed temporary, register only
```

Binary operators are shown with their **source-level symbol** rather than the opcode mnemonic:

| Opcodes | Symbol |
|---|---|
| `IADD`, `DADD` | `+` |
| `ISUB`, `DSUB` | `-` |
| `IMUL`, `DMUL` | `*` |
| `IDIV`, `DDIV` | `/` |
| `IMOD`, `DMOD` | `%` |
| `IEQ`, `DEQ` | `==` |
| `INE`, `DNE` | `!=` |
| `ILT`, `DLT` | `<` |
| `ILE`, `DLE` | `<=` |
| `IGT`, `DGT` | `>` |
| `IGE`, `DGE` | `>=` |
| `AND` | `&&` |
| `OR` | `\|\|` |
| `BAND` | `&` |
| `BOR` | `\|` |
| `BXOR` | `^` |
| `SHL` | `<<` |
| `SHR` | `>>` |
| `USHR` | `>>>` |
| `SEQ`, `SNE` | `==`, `!=` |

Example:
```
  0002:  IADD      p1, p0, p1               ; b:p1 = a:p0 + b:p1
  0005:  ILT       p2, p1, p0               ; p2 = i:p1 < count:p0
  0010:  SEQ       p2, r3, r4               ; p2 = name:r3 == r4
```

For structural casts, the comment clarifies the target type name using `as` (where `#2` is a `TypeDescriptor` in the constant pool):
```
  0007:  CAST_STRUCT  r1, r0, #2            ; config:r1 = r0 as ApiConfig
  0008:  CAST_STRUCT  r1, r0, #3            ; configs:r1 = r0 as ApiConfig[]
```

#### Operand Formatting

| Operand Type | Format | Example |
|---|---|---|
| Primitive register | `p<N>` | `p0`, `p3` |
| Reference register | `r<N>` | `r0`, `r1` |
| Immediate value | plain number | `42`, `0` |
| Constant pool ref | `#<N>` | `#0`, `#5` |
| Function name | symbolic name | `double_it`, `Http.getJson` |
| Sub-opcode | symbolic name | `STR_UPPER`, `GET_INT`, `ADD_INT` |
| Jump target | `@<PC>` | `@0015`, `@0042` |

### Exception Table

```
.exceptions
  [<startPC>..<endPC>] <type> -> @<handlerPC>  msg=<register>
```

Example:
```
.exceptions
  [0010..0025] NetworkError -> @0030  msg=r5
  [0010..0025] TypeError   -> @0045  msg=r6
  [0010..0025] ANY         -> @0060  msg=r7
```

### Summary

```
.summary
  modules:      <count>
  init_blocks:  <count>
  functions:    <count>
  instructions: <count>
  constants:    <count>
  exceptions:   <count>
  globals:      <prim_count>p + <ref_count>r
  bytecode:     <bytes> bytes
```

The `modules` count includes the root module. `init_blocks` counts only modules that required init code (modules with all-default globals are excluded). `globals` shows the total primitive and reference global slots across all modules.
 
## Complex Example: Control Flow + Exceptions

### Source

```c
main(string url) {
    yield "Starting...";

    try {
        json data = Http.getJson(url);
        int count = data.size();

        for (int i = 0; i < count; i++) {
            json item = data[i];
            string name = item.getString("name", "unknown");

            if (name == "skip") {
                continue;
            }

            yield `Processing: ${name}`;
        }

        return `Done. Processed ${count} items.`;
    } catch (NetworkError e) {
        return `Network failed: ${e}`;
    }
}
```

### Disassembly

```
;  Nox Bytecode Disassembly
;  Source:   processor.nox

.constants
  #0  str   "Starting..."
  #1  str   "name"
  #2  str   "unknown"
  #3  str   "skip"
  #4  str   "Processing: "
  #5  str   "Done. Processed "
  #6  str   " items."
  #7  str   "Network failed: "


.func main
  ; params: r0=url
  ;
  ; processor.nox:2  yield "Starting...";
  0000:  LDC       r1, #0                   ; r1 = "Starting..."
  0001:  YIELD     r1                       ; yield r1
  ;
  ; processor.nox:5  json data = Http.getJson(url);
  0002:  SCALL     r1, Http.getJson, r0     ; data:r1 = Http.getJson(url:r0)
  ;
  ; processor.nox:6  int count = data.size();
  0003:  MOVR      r2, r1                   ; arg0 = data:r1
  0004:  SCALL     p0, __json_size, r2      ; count:p0 = data.size()
  ;
  ; processor.nox:8  for (int i = 0; i < count; i++) {
  0004:  LDI       p1, 0                    ; i:p1 = 0
  .loop_start:
  0005:  ILT       p2, p1, p0               ; p2 = i:p1 < count:p0
  0006:  JIF       p2, @0020                ; if p2==0 -> loop_exit
  ;
  ; processor.nox:9  json item = data[i];
  0007:  AGET_IDX  r2, r1, p1               ; item:r2 = data:r1[i:p1]
  ;
  ; processor.nox:10  string name = item.getString("name", "unknown");
  0008:  HACC      GET_STR, r3, r2, #1      ; name:r3 = item:r2.name
  ;  (default "unknown" handled by GET_STR sub-op with fallback)
  ;
  ; processor.nox:12  if (name == "skip") {
  0009:  LDC       r4, #3                   ; r4 = "skip"
  0010:  SEQ       p2, r3, r4               ; p2 = name:r3 == r4
  0011:  JIF       p2, @0013                ; if p2==0 -> skip_continue
  ;
  ; processor.nox:13  continue;
  0012:  JMP       @0017                    ; -> loop_update
  ;
  ; processor.nox:16  yield `Processing: ${name}`;
  .skip_continue:
  0013:  LDC       r4, #4                   ; r4 = "Processing: "
  0014:  SCONCAT   r4, r4, r3               ; r4 = r4 + name:r3
  0015:  YIELD     r4                       ; yield r4
  ;
  ; processor.nox:8  i++
  .loop_update:
  0016:  KILL_REF  r2                       ; item:r2 = null (GC)
  0017:  KILL_REF  r3                       ; name:r3 = null (GC)
  0018:  IINC      p1                       ; i:p1 = i:p1 + 1
  0019:  JMP       @0005                    ; -> loop_start
  ;
  .loop_exit:
  0020:  KILL_REF  r2                       ; item:r2 = null (GC)
  0021:  KILL_REF  r3                       ; name:r3 = null (GC)
  ;
  ; processor.nox:19  return `Done. Processed ${count} items.`;
  0022:  LDC       r2, #5                   ; r2 = "Done. Processed "
  0023:  I2S       r3, p0                   ; r3 = toString(count:p0)
  0024:  SCONCAT   r2, r2, r3               ; r2 = r2 + r3
  0025:  LDC       r3, #6                   ; r3 = " items."
  0026:  SCONCAT   r2, r2, r3               ; r2 = r2 + r3
  0027:  KILL_REF  r1                       ; data:r1 = null (GC)
  0028:  KILL_REF  r2                       ; r2 = null (GC)
  0029:  KILL_REF  r3                       ; r3 = null (GC)
  0030:  RET       r2                       ; return r2
  0031:  JMP       @0037                    ; skip catch block
  ;
  ; processor.nox:20  catch (NetworkError e) {
  .catch_NetworkError:
  0032:  ; r5 = exception message (populated by VM)
  ;
  ; processor.nox:21  return `Network failed: ${e}`;
  0033:  LDC       r2, #7                   ; r2 = "Network failed: "
  0034:  SCONCAT   r2, r2, r5               ; r2 = r2 + r5
  0035:  KILL_REF  r5                       ; r5 = null (GC)
  0036:  RET       r2                       ; return r2
  ;
  .end:
  0037:


.exceptions
  [0002..0031] NetworkError -> @0032  msg=r5


.summary
  modules:      1
  init_blocks:  0
  functions:    1
  instructions: 38
  constants:    8
  exceptions:   1
  globals:      0p + 0r
  bytecode:     304 bytes
```
 
## Labels

Labels appear on their own line with a `.` prefix and `:` suffix. They are **not part of the bytecode** and they are symbolic markers for readability:

```
  .loop_start:
  0005:  ILT       p2, p1, p0
```

The emitter generates labels for:

| Pattern | Label Format | Example |
|---|---|---|
| Init block start | `.init <name>` | Module initialization (no trailing colon; this is a section directive, not a jump label) |
| Loop start | `.loop_start:` | `for`, `while` condition |
| Loop exit | `.loop_exit:` | After loop body |
| Loop update | `.loop_update:` | For-loop increment |
| Else branch | `.else:` or `.else_if_N:` | If-else chains |
| Catch handler | `.catch_<Type>:` | Exception handlers |
| Catch-all | `.catch_all:` | Untyped catch |
| End of function | `.end:` | After last instruction |

When multiple loops or if-blocks exist, labels are numbered: `.loop_start_1:`, `.loop_start_2:`, etc.
 
## Jump Formatting

Jump targets reference PCs with the `@` prefix. When a label exists at the target PC, both are shown:

```
  0006:  JIF       p2, @0020                ; if false -> loop_exit
  0012:  JMP       @0017                    ; -> loop_update
  0019:  JMP       @0005                    ; -> loop_start
```

The comment shows the label name for quick scanning.
 
## Multi-Module Example

### Source

```c
// constants.nox
double PI = 3.14159;
int MAX = 100;
```

```c
// main.nox
import "constants.nox" as c;

string PREFIX = "item_";

int circleArea(int radius) {
    return c.PI * radius * radius;
}

main(int r = 5) {
    double area = circleArea(r);
    return `${PREFIX}area = ${area}`;
}
```

### Disassembly: `main.noxc`

```
;  Nox Bytecode Disassembly
;  Source:   main.nox
;  Program:  (unnamed)
;  Compiled: 2026-03-06T17:00:00+05:30
;  Modules:  2 (main, c)


; Constant Pool

.constants
  #0  dbl   3.14159
  #1  str   "item_"
  #2  str   "area = "


; Module Initialization

.init c
  ; globals: g0=PI (double)  g1=MAX (int)
  ; source:  constants.nox
  ;
  ; constants.nox:1  double PI = 3.14159;
  0000:  LDC       g0, #0                   ; g0 = 3.14159
  ;
  ; constants.nox:2  int MAX = 100;
  0001:  LDI       g1, 100                  ; g1 = 100
  0002:  RET                                ; return (void)

.init main
  ; globals: gr0=PREFIX (string)
  ; source:  main.nox
  ;
  ; main.nox:3  string PREFIX = "item_";
  0003:  LDC       gr0, #1                  ; gr0 = "item_"
  0004:  RET                                ; return (void)


; Functions

;  Function: circleArea
;    Entry PC:   8
;    Params:     1
;    Frame:      pMem=2  rMem=0

.func circleArea
  ; params: p0=radius
  ;
  ; main.nox:6  return c.PI * radius * radius;
  0005:  DMUL      p1, g0, p0               ; p1 = g0 * radius:p0  (g0 via [G] flag)
  0006:  DMUL      p1, p1, p0               ; p1 = p1 * radius:p0
  0011:  RET       p1                       ; return p1


;  Function: main
;    Entry PC:   12
;    Params:     1
;    Frame:      pMem=3  rMem=3

.func main
  ; params: p0=r
  ;
  ; main.nox:10  double area = circleArea(r);
  0012:  MOV       p1, p0                   ; p1 = r:p0
  0013:  CALL      circleArea, p1           ; call circleArea(p1...)
  0014:  MOV       p1, p1                   ; area:p1 = p1
  ;
  ; main.nox:11  return `${PREFIX}area = ${area}`;
  0012:  MOVR      r0, gr0                  ; r0 = gr0  (via [G] flag)
  0016:  LDC       r1, #2                   ; r1 = "area = "
  0017:  SCONCAT   r0, r0, r1               ; r0 = r0 + r1
  0018:  D2S       r1, p1                   ; r1 = toString(area:p1)
  0019:  SCONCAT   r0, r0, r1               ; r0 = r0 + r1
  0020:  KILL_REF  r0                       ; r0 = null (GC)
  0021:  KILL_REF  r1                       ; r1 = null (GC)
  0022:  RET       r0                       ; return r0


; Exception Table

.exceptions
  (none)


; Summary

.summary
  modules:      2
  init_blocks:  2
  functions:    2
  instructions: 23
  constants:    3
  exceptions:   0
  globals:      2p + 1r
  bytecode:     184 bytes
```

NOTE:
- **Init blocks appear before functions**, in depth-first import order (`c` before `main`)
- **Global registers** use `g0`/`g1` (primitive) and `gr0` (reference) prefixes, indicated by the `[G]` flag (bit 15) on operands
- Instructions read/write global memory directly via the `[G]` flag
- `circleArea` accesses `c.PI` directly as `g0` via the `[G]` flag
 
## Implementation

### NoxcEmitter

```kotlin
class NoxcEmitter {

    fun emit(
        program: CompiledProgram,
        sourceFile: String,
        programName: String = "(unnamed)",
        sourceLines: List<String> = emptyList(),
        sourcesByFile: Map<String, List<String>> = emptyMap(),
        timestamp: OffsetDateTime = OffsetDateTime.now(),
    ): String {
        val sb = StringBuilder()
        // Merge root file lines into the per-file map
        val allSources = buildMap {
            putAll(sourcesByFile)
            if (sourceFile.isNotEmpty() && sourceLines.isNotEmpty()) put(sourceFile, sourceLines)
        }
        emitHeader(sb, sourceFile, programName, program, timestamp)
        emitConstantPool(sb, program.constantPool)
        emitInitBlocks(sb, program, allSources)
        emitFunctions(sb, program, allSources)
        emitExceptionTable(sb, program)
        emitSummary(sb, program)
        return sb.toString()
    }

    // Inside emitFunctionBody:
    // Rebuilds register→name map from meta.regNameEvents as instructions are emitted.
    // pn(r) / rn(r) produce "name:pN" when a name is known, else just "pN".
    // opcodeSymbol(op) maps IADD→"+", ILT→"<", etc.
}
```

### Integration with CLI

```bash
# Compile only (no execution), outputs .noxc
$ nox compile hello.nox
  -> hello.noxc

# Compile with both binary and disassembly
$ nox compile --emit-noxc --emit-bin hello.nox
  -> hello.noxb (binary bytecode)
  -> hello.noxc (disassembly)

# Run with debug tracing (prints noxc + execution trace)
$ nox run --trace hello.nox
  -> Executes and prints each instruction as it runs
```
 
## Next Steps

- [**Instruction Set**](../vm/instruction-set.md) Opcode mnemonics used in disassembly
- [**Code Generation**](codegen.md) How bytecode is emitted
- [**Testing**](testing.md) Using `.noxc` output as test assertions
