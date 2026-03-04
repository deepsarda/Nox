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

A `.noxc` file has five sections, in order:

```
1. File Header         (source file, compilation timestamp)
2. Constant Pool       (all pooled values with indices)
3. Function Listing    (one block per function, with bytecode)
4. Exception Table     (try-catch mappings)
5. Summary             (metrics)
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
  0009:  HINV      STR_CONCAT, r0, r0, r1   ; r0 = "Result: " + result
  0010:  RET       r0                       ; return r0


; Exception Table

.exceptions
  (none)


; Summary

.summary
  functions:    2
  instructions: 11
  constants:    1
  exceptions:   0
  bytecode:     88 bytes
```
 
## Format Details

### File Header

```
;  Nox Bytecode Disassembly
;  Source:   <filename>.nox
;  Program:  "<@tool:name value>"
;  Compiled: <ISO 8601 timestamp>
```

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
| `path` | Cached accessor path | `"server.db.host"` |
| `type` | Struct type ID | `ApiConfig` |

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
- **Comment:** after `;`, explains the operation in pseudocode

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
  functions:    <count>
  instructions: <count>
  constants:    <count>
  exceptions:   <count>
  bytecode:     <bytes> bytes
```
 
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
  0002:  SCALL     r1, Http.getJson, r0     ; r1 = Http.getJson(url)
  ;
  ; processor.nox:6  int count = data.size();
  0003:  HINV      JSON_SIZE, p0, r1        ; p0 = data.size()
  ;
  ; processor.nox:8  for (int i = 0; i < count; i++) {
  0004:  LDI       p1, 0                    ; p1 = 0  (i)
  .loop_start:
  0005:  ILT       p2, p1, p0               ; p2 = (i < count)
  0006:  JIF       p2, @0020                ; if false go to loop_exit
  ;
  ; processor.nox:9  json item = data[i];
  0007:  AGET_IDX  r2, r1, p1               ; r2 = data[i]
  ;
  ; processor.nox:10  string name = item.getString("name", "unknown");
  0008:  HACC      GET_STR, r3, r2, #1      ; r3 = item.getString("name")
  ;  (default "unknown" handled by GET_STR sub-op with fallback)
  ;
  ; processor.nox:12  if (name == "skip") {
  0009:  LDC       r4, #3                   ; r4 = "skip"
  0010:  SEQ       p2, r3, r4               ; p2 = (name == "skip")
  0011:  JIF       p2, @0013                ; if false go to skip_continue
  ;
  ; processor.nox:13  continue;
  0012:  JMP       @0017                    ; go to loop_update
  ;
  ; processor.nox:16  yield `Processing: ${name}`;
  .skip_continue:
  0013:  LDC       r4, #4                   ; r4 = "Processing: "
  0014:  HINV      STR_CONCAT, r4, r4, r3   ; r4 = "Processing: " + name
  0015:  YIELD     r4                       ; yield r4
  ;
  ; processor.nox:8  i++
  .loop_update:
  0016:  KILL_REF  r2                       ; item out of scope
  0017:  KILL_REF  r3                       ; name out of scope
  0018:  IINC      p1                       ; i++
  0019:  JMP       @0005                    ; go to loop_start
  ;
  .loop_exit:
  0020:  KILL_REF  r2                       ; cleanup
  0021:  KILL_REF  r3                       ;
  ;
  ; processor.nox:19  return `Done. Processed ${count} items.`;
  0022:  LDC       r2, #5                   ; r2 = "Done. Processed "
  0023:  I2S       r3, p0                   ; r3 = toString(count)
  0024:  HINV      STR_CONCAT, r2, r2, r3   ; r2 += count
  0025:  LDC       r3, #6                   ; r3 = " items."
  0026:  HINV      STR_CONCAT, r2, r2, r3   ; r2 += " items."
  0027:  RET       r2                       ; return r2
  0028:  JMP       @0034                    ; skip catch block
  ;
  ; processor.nox:20  catch (NetworkError e) {
  .catch_NetworkError:
  0029:  ; r5 = exception message (populated by VM)
  ;
  ; processor.nox:21  return `Network failed: ${e}`;
  0030:  LDC       r2, #7                   ; r2 = "Network failed: "
  0031:  HINV      STR_CONCAT, r2, r2, r5   ; r2 += e
  0032:  RET       r2                       ; return r2
  0033:  KILL_REF  r5                       ; e out of scope
  ;
  .end:
  0034:


.exceptions
  [0002..0028] NetworkError -> @0029  msg=r5


.summary
  functions:    1
  instructions: 35
  constants:    8
  exceptions:   1
  bytecode:     280 bytes
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
 
## Implementation

### NoxcEmitter

```kotlin
class NoxcEmitter {

    fun emit(program: CompiledProgram, sourceFile: String, sourceLines: Array<String>): String {
        val sb = StringBuilder()
        emitHeader(sb, sourceFile, program)
        emitConstantPool(sb, program.constantPool)
        for (func in program.functions) {
            emitFunction(sb, func, program.bytecode, program.constantPool, sourceLines)
        }
        emitExceptionTable(sb, program.exceptionTable)
        emitSummary(sb, program)
        return sb.toString()
    }

    private fun emitInstruction(
        sb: StringBuilder, pc: Int, instruction: Long,
        pool: Array<Any?>, labels: Map<Int, String>
    ) {
        val opcode = ((instruction ushr 56) and 0xFF).toInt()
        val subOp  = ((instruction ushr 48) and 0xFF).toInt()
        val a      = ((instruction ushr 32) and 0xFFFF).toInt()
        val b      = ((instruction ushr 16) and 0xFFFF).toInt()
        val c      = (instruction and 0xFFFF).toInt()

        val mnemonic = opcodeName(opcode)
        val operands = formatOperands(opcode, subOp, a, b, c, pool)
        val comment  = generateComment(opcode, subOp, a, b, c, pool)

        // Label (if any)
        if (pc in labels) {
            sb.append("  .${labels[pc]}:\n")
        }

        sb.append("  %04d:  %-10s%-28s; %s\n".format(pc, mnemonic, operands, comment))
    }
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
