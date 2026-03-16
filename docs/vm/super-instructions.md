# Super-Instructions

These are instructions that perform complex operations in a single VM cycle.
 
## The Problem

Consider the simple operation `obj.count += 5`. In a naive register-based VM, this requires:

```
1. GET_FIELD  R1, R_obj, "count"    // Load the property
2. LDC        R2, 5                 // Load the constant
3. IADD       R3, R1, R2            // Perform the addition
4. SET_FIELD  R_obj, "count", R3    // Store the result back
```

That's **4 VM loop iterations**, 4 instruction decodes, and 2 temporary registers, for an operation that a single Kotlin function call could handle in microseconds.
 
## The Solution: Intent-Based Execution

Instead of emulating a CPU, we treat the VM as a **dispatcher**. We encode the programmer's intent into a single instruction and let the JVM host execute it directly.

```
HMOD [SubOp: ADD_INT] [Obj: R_obj] [Key: "count"] [Val: 5]

JVM host executes:
    obj.addProperty("count", obj.get("count").getAsInt() + 5);
```

**One instruction. Zero temporaries. Maximum speed.**
 
## The Three Super-Instructions

### `HMOD` -- Host Modify

**Purpose:** Modify a property on a host object in-place.

**Use Cases:** `+=`, `-=`, `*=`, `=`, string append, array push, property assignment.

#### Instruction Layout

```
┌────────┬──────────┬──────────┬──────────┬──────────┐
│ HMOD   │ Sub-Op   │ Object   │ Key/Path │  Value   │
│        │ (intent) │ (reg)    │ (pool)   │  (reg)   │
└────────┴──────────┴──────────┴──────────┴──────────┘
```

#### Sub-Opcodes

| Sub-Opcode | NSL Operation | Kotlin Host Action |
|---|---|---|
| `SET_INT` | `obj.x = 42` | `obj.put("x", 42)` |
| `SET_STR` | `obj.name = "Alice"` | `obj.put("name", "Alice")` |
| `SET_BOOL` | `obj.active = true` | `obj.put("active", true)` |
| `SET_DBL` | `obj.rate = 3.14` | `obj.put("rate", 3.14)` |
| `SET_OBJ` | `obj.child = other` | `obj.put("child", other)` |
| `ADD_INT` | `obj.count += 5` | `obj.put("count", current + 5)` |
| `SUB_INT` | `obj.count -= 1` | `obj.put("count", current - 1)` |
| `ADD_DBL` | `obj.total += 1.5` | `obj.put("total", current + 1.5)` |
| `APPEND_STR` | `obj.log += "line"` | `obj.put("log", current + "line")` |

#### Example: `config.retries += 1`

```
// Compiler emits:
HMOD [SUB_OP: ADD_INT] R_config, "retries", R_one

// VM execution (single Kotlin call):
NoxObject config = (NoxObject) rMem[bp + R_config];
int current = (int) config.get("retries");
config.put("retries", current + 1);
```

**Result:** Zero register swapping, zero temporary objects, and it is done in one VM cycle.
 
### `HACC` -- Host Access

**Purpose:** Read a property from a host object into a register.

**Use Cases:** Property reads, type conversions, existence checks.

#### Instruction Layout

```
┌────────┬──────────┬──────────┬──────────┬──────────┐
│ HACC   │ Sub-Op   │  Dest    │  Object  │ Key/Path │
│        │ (intent) │  (reg)   │  (reg)   │  (pool)  │
└────────┴──────────┴──────────┴──────────┴──────────┘
```

#### Sub-Opcodes

| Sub-Opcode | NSL Operation | Kotlin Host Action |
|---|---|---|
| `GET_INT` | `int x = obj.count` | `pMem[dest] = (long) obj.get("count")` |
| `GET_STR` | `string s = obj.name` | `rMem[dest] = (String) obj.get("name")` |
| `GET_BOOL` | `boolean b = obj.active` | `pMem[dest] = (boolean) obj.get("active") ? 1 : 0` |
| `GET_DBL` | `double d = obj.rate` | `pMem[dest] = doubleToRawLongBits((double) obj.get("rate"))` |
| `GET_OBJ` | `json child = obj.data` | `rMem[dest] = obj.get("data")` |
| `HAS_KEY` | `json.has("key")` | `pMem[dest] = obj.has("key") ? 1 : 0` |
 

 
### `SCONCAT` -- String Concatenation

**Purpose:** Concatenate two strings into one.

**Use Cases:** Template literals, string `+` operator.

#### Instruction Layout

```
┌────────┬──────────┬──────────┬──────────┬──────────┐
│ SCONCAT│  (0)     │  Dest    │  Left    │  Right   │
│        │          │  (reg)   │  (reg)   │  (reg)   │
└────────┴──────────┴──────────┴──────────┴──────────┘
```

`rMem[Dest] = rMem[Left] + rMem[Right]`
 
## Deep Nesting: The Accessor Family

Complex data access patterns like `data.rows[i].value` are handled by a family of specialized accessor opcodes.

### The Challenge

Given `config.server.db.port`, a naive approach would create temporaries for every step:

```
GET_FIELD  R1, R_config, "server"    // temp1 = config.server
GET_FIELD  R2, R1, "db"             // temp2 = temp1.db  
GET_FIELD  R3, R2, "port"           // result = temp2.port
// 3 opcodes, 2 temporary registers
```

### `AGET_PATH` -- Static Path Traversal

For paths known at compile time, the compiler pre-parses the path into a cached string array:

```
AGET_PATH  R_result, R_config, "server.db"

// VM execution:
String[] path = cachedPaths[pathIndex];  // ["server", "db"]
Object current = (NoxObject) rMem[bp + R_config];
for (String key : path) {
    current = ((NoxObject) current).get(key);
}
rMem[bp + R_result] = current;
```

### `AGET_IDX` -- Dynamic Index Access

For array/map access with a runtime index or key:

```
AGET_IDX  R_result, R_collection, R_index
```

This instruction is **polymorphic**, it checks the type at runtime:

| Type of Collection | Action |
|---|---|
| `List` / `ArrayList` | `list.get((int) pMem[R_index])` |
| `NoxArray` | `noxArray.get((int) pMem[R_index])` |
| `NoxObject` | `noxObject.get(String.valueOf(pMem[R_index]))` |

### `AGET_KEY` -- Named Property Access

For a single named property lookup:

```
AGET_KEY  R_result, R_object, "name"

// VM execution:
rMem[bp + R_result] = ((NoxObject) rMem[bp + R_object]).get("name");
```

### Chaining Accessors

To resolve `data.rows[i].value`, the compiler emits a chain:

```
AGET_KEY   R1, R_data, "rows"       // R1 = data.rows (the array)
AGET_IDX   R2, R1, R_i              // R2 = rows[i] (one element)
AGET_KEY   R3, R2, "value"          // R3 = element.value
```

Three clean, linear instructions. No recursion. No complex resolution logic. The VM simply executes them in sequence.
 
## Performance Impact

### Benchmark: JSON Processing Loop

Processing 1000 JSON objects with `item.value += bonus`:

| Approach | Instructions Executed | Relative Speed |
|---|---|---|
| Naive (GET → ADD → SET) | ~4,000 | 1× |
| Super-Instructions (`HMOD`) | ~1,000 | **~3.5×** |

### Why It Works

1. **Fewer VM loop iterations:** Less dispatch overhead
2. **No temporary registers:** Less memory traffic
3. **Direct Kotlin calls:** HotSpot can inline and optimize the host code
4. **Cache-friendly:** Linear instruction stream, no tree walking
 
## Next Steps

- [**Instruction Set**](instruction-set.md)
- [**Error Handling**](error-handling.md)
- [**FFI Internals**](../extensibility/ffi-internals.md)
