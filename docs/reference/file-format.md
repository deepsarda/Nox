# Program File Format
 
## File Extension

All Nox programs use the `.nox` extension.

Each `.nox` file represents a **single program** that can optionally import other `.nox` files as namespaced libraries. One file = one program.
 
## File Structure

A `.nox` file follows a strict top-to-bottom structure:

```
┌───────────────────────────────────────────────────────┐
│  METADATA HEADERS            (optional)               │
│  @tool:name "..."                                     │
│  @tool:description "..."                              │
│  @tool:author "..."         (optional)                │
│  @tool:permission "..."     (optional, repeatable)    │
├───────────────────────────────────────────────────────┤
│  IMPORT DECLARATIONS         (optional)               │
│  import "path.nox" as namespace;                      │
├───────────────────────────────────────────────────────┤
│  TYPE DEFINITIONS            (optional)               │
│  type MyStruct { ... }                                │
├───────────────────────────────────────────────────────┤
│  HELPER FUNCTIONS            (optional)               │
│  ReturnType funcName(...) { ... }                     │
├───────────────────────────────────────────────────────┤
│  MAIN ENTRY POINT            (optional for libraries) │
│  main(...) { ... }                                    │
└───────────────────────────────────────────────────────┘
```
 
## Metadata Headers

Metadata headers enable **static program discovery**, the runtime can read program names and descriptions without parsing or executing any code. Headers are **optional**.

### Syntax

```
@tool:key "value"
```

- Headers **must** appear at the top of the file, before any code
- Values **must** be double-quoted string literals
- Order of headers does not matter
- Multiple headers of the same key are allowed (e.g., multiple `@tool:permission`)

> **Why `@tool:`?** The `@tool:` prefix is a convention carried over from Nox's origins as a language for building LLM tools. It serves as a recognizable namespace for metadata and remains the standard header prefix regardless of how the program is used.

### Required Headers (when running standalone)

Headers are **optional** for library files that are only used via `import`. When a file is executed directly (has a `main()`), the following headers should be provided:

| Header | Description | Constraints |
|---|---|---|
| `@tool:name` | Snake_case identifier for the program | Max 32 characters, `[a-z0-9_]` only |
| `@tool:description` | Human-readable, one-sentence description | Should describe what the program does |

### Optional Headers

| Header | Description |
|---|---|
| `@tool:author` | Author or owner of the program |
| `@tool:permission` | A permission hint (informational only; does not grant access) |

### Example

```c
@tool:name "image_resizer"
@tool:description "Resizes a local image file to specified dimensions and saves the output."
@tool:author "Graphics Team"
@tool:permission "file.read"
@tool:permission "file.write"
```

### Invalid Headers

```c
@tool:name image_resizer            // INVALID: Missing quotes
@tool:name "Image Resizer!"        // INVALID: Invalid characters (uppercase, space, !)
"@tool:name" "resizer"            // INVALID: Key must not be quoted
```
 
## Import Declarations

Import declarations allow a `.nox` file to use functions and types from other `.nox` files.

### Syntax

```c
import "path/to/file.nox" as namespace;
```

### Rules

| Rule | Detail |
|---|---|
| **Placement** | After headers, before type definitions and functions |
| **Path** | Relative to the importing file's directory |
| **Namespace** | Explicitly chosen by the developer; must be a valid identifier |
| **Collision** | Namespace must not clash with built-in namespaces (Math, File, Http, etc.) or native plugin namespaces |
| **Duplicates** | Two imports cannot use the same namespace name |
| **Cycles** | Circular imports are a compile-time error |

### What Gets Imported

| Element | Imported? |
|---|---|
| Functions | Accessible as `namespace.funcName()` |
| Type definitions | Accessible as `namespace.TypeName` |
| Global variables | Private to the imported module |
| `main()` | Not exported (allows standalone testing) |
| `@tool` headers | Ignored when importing |

### Example

```c
// utils/string_helpers.nox - a library file

string capitalize(string s) {
    // ...
}

string repeat(string s, int times) {
    // ...
}

main() {
    // This main() is for standalone testing only.
    // When imported, it is NOT visible.
    yield capitalize("hello");
    yield repeat("ab", 3);
}
```

```c
// main_program.nox - imports the library
@tool:name "formatter"
@tool:description "Formats user data."

import "utils/string_helpers.nox" as str;

main(string name) {
    string greeting = str.capitalize(name);
    return str.repeat(greeting, 2);
}
```
 
## Complete File Example

```c
@tool:name "weather_checker"
@tool:description "Fetches current weather data for a city and formats a summary."
@tool:author "Climate Tools"
@tool:permission "http.get"

// Type Definitions

type WeatherData {
    string city;
    double temperature;
    string condition;
    int humidity;
}

// Helper Functions

string formatTemperature(double temp) {
    if (temp > 30.0) {
        return `${temp}°C (Hot 🔥)`;
    } else if (temp < 10.0) {
        return `${temp}°C (Cold ❄️)`;
    }
    return `${temp}°C`;
}

boolean isGoodWeather(WeatherData data) {
    return data.temperature > 15.0 && data.humidity < 80;
}

// Main Entry Point

main(string city, boolean includeHumidity = true) {
    yield `Fetching weather for ${city}...`;
    
    try {
        json raw = Http.getJson(`https://api.weather.com/v1/${city}`);
        WeatherData weather = raw as WeatherData;
        
        string tempStr = formatTemperature(weather.temperature);
        
        string result = `Weather in ${weather.city}: ${tempStr}, ${weather.condition}`;
        
        if (includeHumidity) {
            result = `${result}, Humidity: ${weather.humidity}%`;
        }
        
        if (weather.isGoodWeather()) {
            result = `${result}, Great day to go outside!`;
        }
        
        return result;
        
    } catch (err) {
        return `Failed to fetch weather for ${city}: ${err}`;
    }
}
```
 
## How the Runtime Reads Program Files

### Phase 1: Header Scanning

The runtime can read **only** the metadata headers from each `.nox` file without parsing the code body. This is a simple line-by-line scan that stops at the first non-header, non-comment, non-empty line.

```
Programs discovered:
  scripts/
  ├── weather_checker.nox   { name: "weather_checker", desc: "Fetches current..." }
  ├── image_resizer.nox     { name: "image_resizer", desc: "Resizes a local..." }
  └── data_processor.nox    { name: "data_processor", desc: "Downloads a JSON..." }
```

This enables hosts to build registries of available programs — useful for CLI help menus, server-side tool catalogs, or IDE autocompletion — without any compilation overhead.

### Phase 2: Full Compilation (On Demand)

When a program is actually executed, the full file is parsed, validated, and compiled. See [Compilation Pipeline](../architecture/pipeline.md).
 
## Naming Conventions

| Element | Convention | Example |
|---|---|---|
| Program name | `snake_case` | `data_processor` |
| File name | `snake_case.nox` | `data_processor.nox` |
| Type names | `PascalCase` | `ApiConfig`, `ReportItem` |
| Function names | `camelCase` | `calculateDistance`, `isSignificant` |
| Variable names | `camelCase` | `totalCount`, `minThreshold` |
| Constants | `UPPER_SNAKE_CASE` | `MAX_CONNECTIONS` |
 
## Next Steps

- [**Language Overview**](../language/overview.md)
- [**Compilation Pipeline**](../architecture/pipeline.md)
- [**Glossary**](glossary.md)
