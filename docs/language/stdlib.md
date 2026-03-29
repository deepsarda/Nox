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
| `File.append` | `void File.append(string path, string content)` | Append content to an existing file |
| `File.delete` | `void File.delete(string path)` | Delete a file |
| `File.exists` | `boolean File.exists(string path)` | Check whether a path exists |
| `File.list` | `string[] File.list(string dir)` | List entries in a directory |
| `File.metadata` | `json File.metadata(string path)` | Get size, timestamps, and type for a path |
| `File.createDir` | `void File.createDir(string path)` | Create a directory (and parents) |

#### Example

```c
string[] entries = File.list("/data/");
foreach (string name in entries) {
    json meta = File.metadata(`/data/${name}`);
    yield `${name}: ${meta.getInt("size", 0)} bytes`;
}

string content = File.read("/data/config.json");
File.append("/output/log.txt", `Loaded config at ${Date.now()}\n`);
```

#### Permission Flow

```
Script: File.read("/data/secret.txt")
   │
   ▼
VM: requestPermission(PermissionRequest.File.Read("/data/secret.txt"))
   │
   ▼
Host: Pattern-match on request type -> FileGrant(allowedDirectories, allowedExtensions, ...) / Denied
```

#### Grant Constraints (`FileGrant`)

| Constraint | Type | Effect |
|---|---|---|
| `maxBytes` | `Long?` | Maximum file size the operation may read/write |
| `rewrittenPath` | `String?` | Redirect the path to a safe location |
| `allowedDirectories` | `List<String>?` | Restrict access to specific directories |
| `allowedExtensions` | `List<String>?` | Restrict to specific file extensions |
| `readOnly` | `Boolean` | Deny any write/append/delete attempt |
 
### `Http` -- Network Operations

> **All `Http` operations trigger the Permission Bridge.**

| Function | Signature | Description |
|---|---|---|
| `Http.get` | `string Http.get(string url)` | Perform an HTTP GET, return response body as string |
| `Http.getJson` | `json Http.getJson(string url)` | GET and parse response as JSON |
| `Http.post` | `string Http.post(string url, string body)` | Perform an HTTP POST, return response body |
| `Http.put` | `string Http.put(string url, string body)` | Perform an HTTP PUT, return response body |
| `Http.delete` | `string Http.delete(string url)` | Perform an HTTP DELETE, return response body |

#### Example

```c
json data = Http.getJson("https://api.example.com/users");
yield `Received ${data.size()} users`;

string result = Http.put("https://api.example.com/users/42", `{"name": "Alice"}`);
```

#### Grant Constraints (`HttpGrant`)

| Constraint | Type | Effect |
|---|---|---|
| `maxResponseSize` | `Long?` | Maximum response body size in bytes |
| `timeoutMs` | `Long?` | Maximum time to wait for a response |
| `allowedDomains` | `List<String>?` | Restrict requests to specific domains |
| `allowedPorts` | `List<Int>?` | Restrict to specific ports (e.g. `[443]`) |
| `httpsOnly` | `Boolean` | Deny any plain HTTP request |
 
### `Env` -- Environment Access

> **All `Env` operations trigger the Permission Bridge.** Environment variables can carry secrets; system properties can be used for fingerprinting.

| Function | Signature | Description |
|---|---|---|
| `Env.get` | `string? Env.get(string name)` | Read an environment variable (returns `null` if not set) |
| `Env.system` | `string? Env.system(string property)` | Read a system property (e.g. `"os.name"`, `"os.arch"`) |

#### Example

```c
string? apiKey = Env.get("API_KEY");
if (apiKey == null) {
    return "Missing API_KEY environment variable";
}

string os = Env.system("os.name");
yield `Running on ${os}`;
```

#### Grant Constraints (`EnvGrant`)

| Constraint | Type | Effect |
|---|---|---|
| `allowedVarNames` | `List<String>?` | Restrict which variable/property names are readable |

---

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
 
### `Json` -- JSON Parsing & Serialization

> **No permissions required.**

| Function | Signature | Description |
|---|---|---|
| `Json.parse` | `json Json.parse(string text)` | Parse a JSON string into a json value |
| `Json.stringify` | `string Json.stringify(json value, boolean pretty = true)` | Serialize a json value to a JSON string |

#### Example

```nsl
string raw = Http.get("https://api.example.com/data");
json data = Json.parse(raw);
string name = data.getString("name", "unknown");

json response = {status: "ok", count: 42};

// Pretty-printed by default
string body = Json.stringify(response);
// {
//   "status": "ok",
//   "count": 42
// }

// Compact output
string compact = Json.stringify(response, false);
// {"status":"ok","count":42}

Http.post("https://api.example.com/submit", compact);
```

#### Round-Trip Behavior

- Integers without decimals parse as `int`, with decimals as `double`
- `Json.stringify` preserves this distinction (`42` vs `42.0`)
- `NaN` and `Infinity` serialize as `null` (per JSON spec)

## Built-in Type Methods

Primitive types and arrays have "methods" available through [UFCS](functions.md#unified-function-call-syntax-ufcs). These are defined in the Library Registry and linked to specific types rather than namespaces.

### `string` Methods

| Method | Signature | Description |
|---|---|---|
| `.length()` | `string` -> `int` | Number of characters in the string |
| `.upper()` | `string` -> `string` | Convert to uppercase |
| `.lower()` | `string` -> `string` | Convert to lowercase |
| `.contains(sub)` | `string` -> `boolean` | Check if substring exists |
| `.split(delim)` | `string` -> `string[]` | Split into array by delimiter |

#### Example

```c
string text = "Hello, World!";

int len = text.length();            // 13
string upper = text.upper();        // "HELLO, WORLD!"
string lower = text.lower();        // "hello, world!"
boolean has = text.contains("World"); // true
string[] parts = text.split(", ");  // ["Hello", "World!"]
```

### `array` Methods

| Method | Signature | Description |
|---|---|---|
| `.length()` | `T[]` -> `int` | Number of elements in the array |
| `.push(item)` | `void` | Append an element to the end |
| `.pop()` | `T` | Remove and return the last element |

#### Example

```c
int[] numbers = [1, 2, 3];

int len = numbers.length();  // 3
numbers.push(4);             // [1, 2, 3, 4]
int last = numbers.pop();    // last = 4, numbers = [1, 2, 3]
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
| `.setString(key, value)` | `void` | Set a string value |
| `.setInt(key, value)` | `void` | Set an integer value |
| `.setBool(key, value)` | `void` | Set a boolean value |
| `.setDouble(key, value)` | `void` | Set a double value |
| `.setJson(key, value)` | `void` | Set a nested json value |
| `.remove(key)` | `void` | Remove a key from the object |
| `.getIntArray(key, default)` | `int[]` | Extract typed integer array or default |
| `.getStringArray(key, default)` | `string[]` | Extract typed string array or default |
| `.getDoubleArray(key, default)` | `double[]` | Extract typed double array or default |
| `.getArray(key, Type, default)` | `Type[]` | Extract and cast each element to struct type |

#### Mutation

```c
json response = {};
response.setString("status", "ok");
response.setInt("count", 42);
response.setBool("active", true);

json meta = {};
meta.setDouble("score", 3.14);
response.setJson("meta", meta);

// Serialize: {"status": "ok", "count": 42, "active": true, "meta": {"score": 3.14}}
string body = Json.stringify(response, false);

// Remove a key
response.remove("active");
```

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

| Namespace | Requires Permission | Request Type |
|---|---|---|
| `File.*` | Yes | `PermissionRequest.File.*` |
| `Http.*` | Yes | `PermissionRequest.Http.*` |
| `Env.*` | Yes | `PermissionRequest.Env.*` |
| `Math.*` | No | — |
| `Date.*` | No | — |
| Built-in methods | No | — |
 
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
