# Standard Library

All system functions in NSL are organized into **namespaces**. These namespaces serve dual purposes:

1. **Organization:** Grouping related functions logically
2. **Security:** Namespace prefixes act as keywords, preventing user code from shadowing system functions

The **Library Registry** defines every available function, its namespace, parameters, return type, and the Kotlin implementation behind it. This registry is the single source of truth for both the compiler (semantic validation) and the VM (execution). It is populated at startup from built-in libraries and any registered plugins.
 
## Namespaced Libraries

### `File` -- File System Operations

> **All `File` operations trigger the Permission Bridge.** The script must receive explicit permission before any file system access.

| Function | Signature | Description |
|---|---|---|
| `File.read` | `string File.read(string path)` | Read the entire contents of a file as a string |
| `File.write` | `void File.write(string path, string content)` | Write content to a file (creates or overwrites) |
| `File.exists` | `boolean File.exists(string path)` | Check if a file exists at the given path |

#### Example

```c
if (File.exists("/data/config.json")) {
    string content = File.read("/data/config.json");
    yield `Config loaded: ${content.length} bytes`;
}

File.write("/output/result.txt", "Processing complete.");
```

#### Permission Flow

```
Script: File.read("/data/secret.txt")
   │
   ▼
VM: requestPermission("file.read", { path: "/data/secret.txt" })
   │
   ▼
Host: Evaluate policy -> Allow / Deny / Prompt user
```
 
### `Http` -- Network Operations

> **All `Http` operations trigger the Permission Bridge.**

| Function | Signature | Description |
|---|---|---|
| `Http.get` | `string Http.get(string url)` | Perform an HTTP GET, return response body as string |
| `Http.post` | `string Http.post(string url, string body)` | Perform an HTTP POST, return response body |
| `Http.getJson` | `json Http.getJson(string url)` | GET and parse response as JSON |

#### Example

```c
json data = Http.getJson("https://api.example.com/users");
string[] keys = data.keys();
yield `Received ${keys.length} fields`;
```
 
### `Math` -- Mathematical Operations

> **No permissions required.** All `Math` functions are pure computations.

| Function | Signature | Description |
|---|---|---|
| `Math.sqrt` | `double Math.sqrt(double x)` | Square root |
| `Math.abs` | `double Math.abs(double x)` | Absolute value |
| `Math.min` | `double Math.min(double a, double b)` | Minimum of two values |
| `Math.max` | `double Math.max(double a, double b)` | Maximum of two values |
| `Math.floor` | `int Math.floor(double x)` | Floor (round down) |
| `Math.ceil` | `int Math.ceil(double x)` | Ceiling (round up) |
| `Math.round` | `int Math.round(double x)` | Round to nearest integer |
| `Math.random` | `double Math.random()` | Random value in [0.0, 1.0) |
| `Math.pow` | `double Math.pow(double base, double exp)` | Exponentiation |

#### Example

```c
double hypotenuse = Math.sqrt(Math.pow(3.0, 2.0) + Math.pow(4.0, 2.0));
// hypotenuse = 5.0

int randomIndex = Math.floor(Math.random() * 100.0);
```
 
### `Date` -- Date and Time

> **No permissions required.**

| Function | Signature | Description |
|---|---|---|
| `Date.now` | `int Date.now()` | Current Unix timestamp in milliseconds |

#### Example

```c
int start = Date.now();
// ... do work ...
int elapsed = Date.now() - start;
yield `Operation took ${elapsed}ms`;
```
 
## Built-in Type Methods

Primitive types and arrays have "methods" available through [UFCS](functions.md#unified-function-call-syntax-ufcs). These are defined in the Library Registry and linked to specific types rather than namespaces.

### `string` Methods

| Method | Signature | Description |
|---|---|---|
| `.length` | `int` (property) | Number of characters in the string |
| `.upper()` | `string` -> `string` | Convert to uppercase |
| `.lower()` | `string` -> `string` | Convert to lowercase |
| `.contains(sub)` | `string` -> `boolean` | Check if substring exists |
| `.split(delim)` | `string` -> `string[]` | Split into array by delimiter |

#### Example

```c
string text = "Hello, World!";

int len = text.length;              // 13
string upper = text.upper();        // "HELLO, WORLD!"
string lower = text.lower();        // "hello, world!"
boolean has = text.contains("World"); // true
string[] parts = text.split(", ");  // ["Hello", "World!"]
```

### `array` Methods/Properties

| Method | Signature | Description |
|---|---|---|
| `.length` | `int` (property) | Number of elements in the array |
| `.push(item)` | `void` | Append an element to the end |
| `.pop()` | `T` | Remove and return the last element |

#### Example

```c
int[] numbers = [1, 2, 3];

int len = numbers.length;  // 3
numbers.push(4);            // [1, 2, 3, 4]
int last = numbers.pop();   // last = 4, numbers = [1, 2, 3]
```

### `json` Methods

See [Type System, The `json` Type](type-system.md#the-json-type-generic-object) for the full reference.

| Method | Signature | Description |
|---|---|---|
| `.getString(key, default)` | `string` | Get string value or default |
| `.getInt(key, default)` | `int` | Get integer value or default |
| `.getBool(key, default)` | `boolean` | Get boolean value or default |
| `.getDouble(key, default)` | `double` | Get double value or default |
| `.getJSON(key, default)` | `json` | Get nested JSON or default |
| `.getObject(key, default)` | typed | Get and typecheck against default |
| `.has(key)` | `boolean` | Check if key exists |
| `.keys()` | `string[]` | Get all top-level keys |
| `.size()` | `int` | Number of keys (object) or elements (array) |
| `.getIntArray(key, default)` | `int[]` | Extract typed integer array or default |
| `.getStringArray(key, default)` | `string[]` | Extract typed string array or default |
| `.getDoubleArray(key, default)` | `double[]` | Extract typed double array or default |
| `.getArray(key, Type, default)` | `Type[]` | Extract and cast each element to struct type |

#### Typed Array Extraction

```c
json data = Http.getJson("/api/report");

// Extract typed arrays safely
int[] ids = data.getIntArray("ids", []);
string[] labels = data.getStringArray("labels", []);

// Extract struct-typed arrays (each element is cast)
ReportItem[] items = data.getArray("items", ReportItem, []);

// Iterate over json as an array
json rows = data.getJSON("rows", []);
for (int i = 0; i < rows.size(); i++) {
    json row = rows[i];  // index access on json array
    string name = row.getString("name", "unknown");
}
```
 
## Permission Summary

| Namespace | Requires Permission | Permission String |
|---|---|---|
| `File.*` | Yes | `file.read`, `file.write` |
| `Http.*` | Yes | `http.get`, `http.post` |
| `Math.*` | No | - |
| `Date.*` | No | - |
| Built-in methods | No | - |
 
## Type-Bound Conversion Methods

In addition to namespace-scoped libraries, NSL provides methods bound directly to types for explicit conversion. See [Type System, Type Conversion](type-system.md#type-conversion) for full details.

### `int` Methods

| Method | Returns | Description |
|---|---|---|
| `.toDouble()` | `double` | Widening conversion |
| `.toString()` | `string` | String representation |

### `double` Methods

| Method | Returns | Description |
|---|---|---|
| `.toInt()` | `int` | Truncation toward zero |
| `.toString()` | `string` | String representation |

### Additional `string` Methods

| Method | Returns | Description |
|---|---|---|
| `.toInt(default)` | `int` | Parse as integer; return `default` on failure |
| `.toDouble(default)` | `double` | Parse as double; return `default` on failure |
 
## Extending the Library

The standard library is not fixed. Developers can create custom namespaces and functions using the `@NoxModule` Plugin API. See [Plugin Development Guide](../extensibility/plugin-guide.md) for details.
 
## Next Steps

- [**Plugin Development**](../extensibility/plugin-guide.md)
- [**Type System**](type-system.md)
- [**Functions**](functions.md)
