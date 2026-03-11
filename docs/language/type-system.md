# Type System

NSL employs a **strong, static type system**. Every variable, parameter, and return value has a type known at compile time. Type mismatches are caught during semantic validation, before any bytecode is generated or executed.

```
                    ┌─────────────┐
                    │    Types    │
                    └──────┬──────┘
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │Primitives│ │ Complex  │ │ Special  │
        │(by value)│ │(by ref)  │ │          │
        └────┬─────┘ └────┬─────┘ └────┬─────┘
          ┌──┼──┬──┐     ─┼──────┐   ┌─┼────┐
          ▼  ▼  ▼  ▼      ▼      ▼   ▼      ▼
        int dbl bool str struct arr  json void
```
 
## Primitive Types (Pass-by-Value)

Primitives are **immutable** and passed to functions by creating a copy of their value. They are stored directly in the primitive register bank (`pMem`) as 64-bit `long` values.

### `int`

A 64-bit signed integer.

```c
int count = 42;
int negative = -100;
int max = 9223372036854775807;  // Long.MAX_VALUE
```

**Internal representation:** Stored directly as `long` in `pMem`.

### `double`

A 64-bit IEEE 754 floating-point number.

```c
double pi = 3.14159;
double rate = 0.05;
double large = 1.7e308;
```

**Internal representation:** Stored as `Double.doubleToRawLongBits(value)` in `pMem`. Read back with `Double.longBitsToDouble(pMem[i])`.

### `boolean`

A logical `true` or `false`.

```c
boolean active = true;
boolean deleted = false;
```

**Internal representation:** Stored as `1L` (true) or `0L` (false) in `pMem`.

### `string`

An immutable sequence of UTF-8 characters.

```c
string name = "Alice";
string greeting = `Hello, ${name}!`;  // Interpolation
string empty = "";
```

**Internal representation:** Stored as a JVM `String` object in the reference bank (`rMem`).

> **Note:** Despite being conceptually "primitive" in NSL, strings are stored in `rMem` because they are JVM objects. However, they are **immutable** and exhibit value-like semantics, modifying a string always creates a new one.
 
## Complex Types (Pass-by-Reference)

Complex types are passed by reference. Modifying a complex type inside a function **affects the original object** in the caller's scope.

### Structs (User-Defined Types)

Structs are **pure data schemas**. They define the expected shape of a JSON object. They contain **no methods or logic**, only field declarations.

#### Definition

```c
type ApiConfig {
    string endpoint;
    int timeout_seconds;
    boolean enable_retries;
}
```

#### Instantiation

Structs are instantiated using object literal syntax:

```c
ApiConfig config = {
    endpoint: "https://api.example.com",
    timeout_seconds: 30,
    enable_retries: true
};
```

#### Validation

The compiler validates struct instantiations at compile time:

```c
// SemanticError: Missing field 'enable_retries' for type 'ApiConfig'
ApiConfig bad = {
    endpoint: "https://api.example.com",
    timeout_seconds: 30
};

// SemanticError: Unknown field 'extra' for type 'ApiConfig'
ApiConfig also_bad = {
    endpoint: "https://api.example.com",
    timeout_seconds: 30,
    enable_retries: true,
    extra: "oops"
};
```

#### Field Access

```c
string url = config.endpoint;
config.timeout_seconds = 60;  // Modifies the original (pass-by-reference)
```

**Internal representation:** A `NoxObject` (backed by `HashMap<String, Object>`). Field access compiles to `HACC`/`HMOD` super-instructions.

#### Nested and Recursive Structs

Structs can contain fields of other struct types, including their own type:

```c
type Address {
    string city;
    string zip;
}

type User {
    string name;
    Address address;   // Nested struct
}

type TreeNode {
    string value;
    TreeNode left;     // Recursive, nullable (null = leaf)
    TreeNode right;
}
```

Recursive struct fields are always nullable. A `null` value indicates the absence of the nested object (e.g., a leaf node in a tree).

### Arrays

Homogeneous, ordered lists of a single type.

#### Syntax

```c
int[] numbers = [1, 2, 3, 4, 5];
string[] names = ["Alice", "Bob", "Charlie"];
ApiConfig[] configs = [];  // Empty typed array
```

#### Operations

```c
int len = numbers.length();        // Method call
int first = numbers[0];          // Index access
numbers[0] = 99;                 // Index assignment
numbers.push(6);                 // Append element
```

**Internal representation:** A Kotlin `ArrayList` (or `NoxArray` for JSON-derived data).
 
## The `json` Type (Generic Object)

The `json` type is a flexible, dynamic type that represents an arbitrary JSON object. It is the bridge between the typed NSL world and the unstructured data that comes from external sources.

### Declaration

```c
json data = Http.getJson("/api/data");
json config = { key: "value", count: 42 };
```

### Auto-Casting: Struct to `json`

Any user-defined struct can be **implicitly** cast to `json`:

```c
ApiConfig config = { endpoint: "url", timeout_seconds: 30, enable_retries: true };
json generic = config;  // Implicit upcast (always safe)
```

### Explicit Casting: `json` to Struct

Going the other direction requires an **explicit cast** using `as`. The VM (`CAST_STRUCT` opcode) performs deep structural validation during the cast, checking all primitive fields, nested structs, and typed arrays against a compile-time `TypeDescriptor`:

```c
json rawConfig = Http.getJson("/config");

// Cast will fail at runtime if rawConfig is missing required fields
ApiConfig config = rawConfig as ApiConfig;
```

If the cast fails (missing or mistyped fields), a `CastError` is thrown that can be caught with `try-catch`.

### Safe Access Methods

The `json` type provides safety-first methods for accessing data without knowing the schema. Every method takes a **default value** that is returned if the key is missing or the wrong type:

| Method | Return Type | Description |
|---|---|---|
| `json.getString(key, default)` | `string` | Get string value, or `default` |
| `json.getInt(key, default)` | `int` | Get integer value, or `default` |
| `json.getBool(key, default)` | `boolean` | Get boolean value, or `default` |
| `json.getDouble(key, default)` | `double` | Get double value, or `default` |
| `json.getJSON(key, default)` | `json` | Get nested JSON object, or `default` |
| `json.getObject(key, default)` | `type` | Get and typecheck against default's type |
| `json.has(key)` | `boolean` | Check if key exists |
| `json.keys()` | `string[]` | Get all top-level keys |
| `json.size()` | `int` | Number of keys (object) or elements (array) |
| `json.getIntArray(key, default)` | `int[]` | Extract a typed integer array, or `default` |
| `json.getStringArray(key, default)` | `string[]` | Extract a typed string array, or `default` |
| `json.getDoubleArray(key, default)` | `double[]` | Extract a typed double array, or `default` |
| `json.getArray(key, StructType, default)` | `StructType[]` | Extract and cast each element to a struct type |

#### Example: Safe Data Extraction

```c
json user = Http.getJson("/api/user/123");

string name = user.getString("name", "Unknown");
int age = user.getInt("age", 0);
boolean active = user.getBool("is_active", false);

if (user.has("preferences")) {
    json prefs = user.getJSON("preferences", {});
    string theme = prefs.getString("theme", "dark");
}
```
 
## String Handling Rules

NSL enforces strict rules for string manipulation to prevent common type-related bugs.

### String Interpolation (Recommended)

Template literals using backticks are the preferred way to build strings. Expressions inside `${...}` are evaluated and safely converted to strings:

```c
int count = 42;
double rate = 3.14;
string msg = `Found ${count} items at rate ${rate}.`;
// Result: "Found 42 items at rate 3.14."
```

Any expression is valid inside `${...}`:

```c
string result = `Sum is ${a + b}, product is ${a * b}.`;
```

### String Concatenation (Strict)

The `+` operator is **only** defined for `string + string`. Concatenating a string with any other type is a `SemanticError`:

```c
string a = "hello";
string b = " world";
string c = a + b;        //  OK since string + string

int num = 10;
string d = a + num;      // SemanticError: Operator '+' not defined for (string, int)

// Use interpolation instead:
string e = `${a}${num}`; // OK gives "hello10"
```

### Rationale

This strictness prevents subtle bugs where automatic coercion produces unexpected results (e.g., `"Count: " + 1 + 2` producing `"Count: 12"` instead of `"Count: 3"`).
 
## Null and Nullable Types

Reference types in NSL are **nullable**. Primitive types are not.

| Type | Nullable? | Default Value |
|---|---|---|
| `int` | No | `0` |
| `double` | No | `0.0` |
| `boolean` | No | `false` |
| `string` | Yes | - |
| `json` | Yes | - |
| Structs | Yes | - |
| Arrays | Yes | - |

### Null Literals

```c
string name = null;          // Valid
json data = null;            // Valid
int[] items = null;          // Valid
int count = null;            // SemanticError: Cannot assign null to primitive 'int'
```

### Null Comparison

```c
if (name == null) { ... }    // Check for null
if (name != null) { ... }    // Check for non-null
```

### Null Access

Accessing a property or calling a method on a `null` reference throws a `NullAccessError`:

```c
string name = null;
int len = name.length();       // NullAccessError: Cannot access 'length()' on null
```

This is a runtime exception and can be caught with `try-catch`.
 
## Type Conversion

NSL uses **type-bound methods** for explicit conversions between types. There are no implicit narrowing conversions, only `int` to `double` is implicit (widening).

### Numeric Conversions

```c
int x = 42;
double d = x.toDouble();        // 42.0

double pi = 3.14;
int truncated = pi.toInt();     // 3 (truncation toward zero)
```

### String Conversions

```c
int x = 42;
string s = x.toString();        // "42"

double rate = 3.14;
string r = rate.toString();     // "3.14"

string numStr = "123";
int parsed = numStr.toInt(0);       // 123 (0 is the default if parsing fails)
double val = numStr.toDouble(0.0);  // 123.0
```

### Conversion Summary

| Method | On Type | Returns | Notes |
|---|---|---|---|
| `.toDouble()` | `int` | `double` | Widening, lossless |
| `.toInt()` | `double` | `int` | Truncation toward zero |
| `.toString()` | `int`, `double`, `boolean` | `string` | String representation |
| `.toInt(default)` | `string` | `int` | Parses string; returns `default` on failure |
| `.toDouble(default)` | `string` | `double` | Parses string; returns `default` on failure |
 
## Type Compatibility Matrix

| From → To | `int` | `double` | `boolean` | `string` | `json` | Struct | Array |
|---|---|---|---|---|---|---|---|
| **`int`** | ✓ | Implicit | ✗ | `.toString()` | ✗ | ✗ | ✗ |
| **`double`** | `.toInt()` | ✓ | ✗ | `.toString()` | ✗ | ✗ | ✗ |
| **`boolean`** | ✗ | ✗ | ✓ | `.toString()` | ✗ | ✗ | ✗ |
| **`string`** | `.toInt(d)` | `.toDouble(d)` | ✗ | ✓ | ✗ | ✗ | ✗ |
| **`json`** | ✗ | ✗ | ✗ | ✗ | ✓ | Explicit cast | ✗ |
| **Struct** | ✗ | ✗ | ✗ | ✗ | Implicit | Same type | ✗ |
| **Array** | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | Same element type |
 
## Next Steps

- [**Functions & Control Flow**](functions.md)
- [**Standard Library**](stdlib.md)
- [**Language Overview**](overview.md)
