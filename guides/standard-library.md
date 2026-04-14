# Standard Library Reference

All built-in functions in NSL are grouped into **namespaces**. 

## Capability-Gated Namespaces
These namespaces interact with the host system. Calling them triggers the **Permission Bridge**, meaning the host must explicitly grant permission before the action happens.

### `File` (File System)
- `string File.read(string path)`: Reads a file as a string.
- `void File.write(string path, string content)`: Overwrites a file.
- `void File.append(string path, string content)`: Appends to a file.
- `void File.delete(string path)`: Deletes a file.
- `boolean File.exists(string path)`: Checks if a file exists.
- `string[] File.list(string dir)`: Lists files/folders in a directory.
- `json File.metadata(string path)`: Gets size, type, timestamps.
- `void File.createDir(string path)`: Creates a directory.

### `Http` (Network)
- `string Http.get(string url)`: HTTP GET request.
- `json Http.getJson(string url)`: HTTP GET and parses response as JSON.
- `string Http.post(string url, string body)`: HTTP POST.
- `string Http.put(string url, string body)`: HTTP PUT.
- `string Http.delete(string url)`: HTTP DELETE.

### `Env` (Environment Variables)
- `string? Env.get(string name)`: Gets an environment variable (returns `null` if missing).
- `string? Env.system(string property)`: Gets a JVM system property.

---

## Pure Namespaces
These functions do not interact with the outside world and require **no permissions**.

### `Math`
- `double Math.sqrt(double x)`
- `double Math.abs(double x)`
- `double Math.min(double a, double b)`
- `double Math.max(double a, double b)`
- `int Math.floor(double x)`
- `int Math.ceil(double x)`
- `int Math.round(double x)`
- `double Math.random()`
- `double Math.pow(double base, double exp)`

### `Date`
- `int Date.now()`: Current Unix timestamp in milliseconds.

### `Json`
- `json Json.parse(string text)`: Parses text into a `json` object.
- `string Json.stringify(json value, boolean pretty = true)`: Converts a `json` object to a string.

---

## Type-Bound Methods

These methods are available on primitive/reference types via UFCS.

### `string`
- `.length()` -> `int`
- `.upper()` -> `string`
- `.lower()` -> `string`
- `.contains(string sub)` -> `boolean`
- `.split(string delim)` -> `string[]`
- `.toInt(int default)` -> `int` (Parses string, returns default on failure)
- `.toDouble(double default)` -> `double`

### `array`
- `.length()` -> `int`
- `.push(item)` -> `void`
- `.pop()` -> `item`

### `int` & `double`
- `int.toDouble()` -> `double`
- `double.toInt()` -> `int` (Truncates)
- `int.toString()` / `double.toString()` -> `string`
