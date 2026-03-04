# NSL Language Overview

## What is NSL?

**NSL** (Nox Scripting Language) is the programming language executed by the Nox runtime. It is intentionally familiar — borrowing syntax from C, Java, and JavaScript — but its semantics are strictly controlled by the sandbox.

Every `.nox` file represents a single **program** that can optionally import other `.nox` files as namespaced libraries.

### Design Goals

| Goal | How NSL Achieves It |
|---|---|
| **Easy to learn and generate** | C-family syntax, no unusual constructs which makes it familiar to developers and trivial for code generators |
| **Safe by default** | Static types, no implicit permissions, no escape hatches |
| **Developer-friendly** | Rich error messages with suggestions, string interpolation, UFCS for ergonomic APIs |
| **Expressive enough** | Structs, flexible `json` type, UFCS, streaming with `yield` |
| **No unnecessary complexity** | No classes, no inheritance, no generics, no closures |
 
## A Complete Example

```c
@tool:name "data_processor"
@tool:description "Downloads a JSON report, validates it, and yields progress."
@tool:author "Data Engineering"

type ReportItem {
    double value;
    string name;
}

boolean isSignificant(ReportItem item, double threshold) {
    return item.value > threshold;
}

main(string url, double minThreshold = 10.5) {
    yield `Initializing connection to ${url}`;

    try {
        json data = Http.getJson(url);
        
        int totalProcessed = 0;
        double sumValues = 0.0;

        for (int i = 0; i < data.size(); i++) {
            ReportItem item = data[i] as ReportItem;
            
            if (item.isSignificant(minThreshold)) {
                sumValues = sumValues + item.value;
                totalProcessed = totalProcessed + 1;
            }

            if (i % 10 == 0) {
                yield `Analyzed ${i + 1} items...`;
            }
        }

        return `Processed ${totalProcessed} significant items. Total value: ${sumValues}`;

    } catch (err) {
        yield `Error during processing: ${err}`;
        return "Task Failed";
    }
}
```
 
## Language Structure

A `.nox` file follows a strict top-down structure:

```
┌─────────────────────────────┐
│  1. Metadata Headers        │  @tool:name, @tool:description, etc.
│     (optional)              │
├─────────────────────────────┤
│  2. Import Declarations     │  import "math.nox" as m;
│     (optional)              │
├─────────────────────────────┤
│  3. Type Definitions        │  type Point { int x; int y; }
│     (optional)              │
├─────────────────────────────┤
│  4. Helper Functions        │  int add(int a, int b) { ... }
│     (optional)              │
├─────────────────────────────┤
│  5. main() Entry Point      │  main(string arg1) { ... }
│     (optional for libraries)│
└─────────────────────────────┘
```
 
## Key Language Features

### Static Typing

Every variable has a known type at compile time. Type mismatches are caught during semantic validation, before any code runs.

```c
int x = 42;          // Correct
int y = "hello";     // SemanticError: Cannot assign 'string' to 'int'
```

### String Interpolation

Template literals with backticks are the idiomatic way to build strings:

```c
int count = 42;
string msg = `Found ${count} items.`;  // "Found 42 items."
```

### Unified Function Call Syntax (UFCS)

Functions can be called as if they were methods of their first argument:

```c
double dist1 = calculateDistance(origin);  // Standard call
double dist2 = origin.calculateDistance(); // UFCS, identical semantics
```

### Streaming with `yield`

Programs can send intermediate results, enabling progress updates for long-running operations:

```c
yield "Step 1 complete...";
yield "Step 2 complete...";
return "All done.";
```

### Capability-Based I/O

All external operations go through namespaced libraries with permission checks:

```c
string content = File.read("/data/input.txt");   // Requires file.read permission
json response = Http.getJson("https://api.com"); // Requires http.get permission
double root = Math.sqrt(144);                     // No permission needed
```

### Script Imports (Tier 2)

Reuse code across files by importing other `.nox` files as namespaced libraries:

```c
import "utils/math_helpers.nox" as mh;
import "formatting.nox" as fmt;

main() {
    double result = mh.calculate(42);
    yield fmt.prettyPrint(result);
}
```

Imported files can have their own `main()` for standalone testing, it is simply not exported when imported. The namespace must be explicitly specified and cannot clash with built-in library names (Math, File, Http, etc.).
 
## Reserved Words

```
int, double, boolean, string, json, void,
true, false, null,
if, else, while, for, foreach, in,
return, yield, break, continue,
try, catch, throw,
type, main, as, import
```
 
## Operator Precedence

| Precedence | Operators | Associativity |
|---|---|---|
| 1 (highest) | `!`, `-`, `~` (unary), `++`, `--` | Right |
| 2 | `*`, `/`, `%` | Left |
| 3 | `+`, `-` | Left |
| 4 | `<<`, `>>`, `>>>` | Left |
| 5 | `<`, `<=`, `>`, `>=` | Left |
| 6 | `==`, `!=` | Left |
| 7 | `&` | Left |
| 8 | `^` | Left |
| 9 | `\|` | Left |
| 10 | `&&` | Left |
| 11 (lowest) | `\|\|` | Left |

Assignment operators: `=`, `+=`, `-=`, `*=`, `/=`, `%=`
 
## Comments

```c
// Single-line comment

/* 
   Multi-line
   comment
*/
```
 
## Next Steps

- [**Type System**](type-system.md)
- [**Functions & Control Flow**](functions.md)
- [**Standard Library**](stdlib.md)
