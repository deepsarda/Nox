# Plugin Development Guide

Nox is designed to be **extensible**. Any developer can create new library functions that are available to `.nox` scripts, with the same performance and safety guarantees as the built-in standard library.

Nox uses a **3-tier plugin architecture** that provides multiple ways to extend the runtime from compiled built-ins to native shared libraries to pure Nox script imports.
 
## Tier Overview

| Tier | Name | How It Works | Distribution | Performance |
|---|---|---|---|---|
| **Tier 0** | Built-in | Compiled into the binary (Kotlin annotations) | Part of the runtime | Fastest (inlined) |
| **Tier 1** | External Plugin | Loaded via C ABI (`dlopen` / `LoadLibrary`) | `.so` / `.dylib` / `.dll` | Near-native |
| **Tier 2** | Script Import | Loaded as Nox source (`import "file.nox" as ns`) | `.nox` files | Interpreted |
 
## Tier 0: Built-in Functions

### Overview

Tier 0 functions are compiled directly into the Nox binary. They use Kotlin annotations for a clean developer experience and `MethodHandle`-based linking for near-direct-call performance.

### Quick Start

#### 1. Write Your Plugin

```kotlin
@NoxModule(namespace = "math_ext")
object MathExtension {

    @NoxFunction(name = "hypot")
    @JvmStatic
    fun hypot(a: Double, b: Double): Double =
        kotlin.math.sqrt(a * a + b * b)

    @NoxFunction(name = "clamp")
    @JvmStatic
    fun clamp(value: Double, min: Double, max: Double): Double =
        value.coerceIn(min, max)
}
```

#### 2. Use It in NSL

```c
@tool:name "geometry_tool"
@tool:description "Calculates distances using extended math."

main(double x, double y) {
    double distance = math_ext.hypot(x, y);
    double clamped = math_ext.clamp(distance, 0.0, 100.0);
    return `Distance: ${distance}, Clamped: ${clamped}`;
}
```
 
### The Annotation API

#### `@NoxModule`

Marks a class as a Nox plugin module.

```kotlin
@NoxModule(namespace = "my_namespace")
object MyPlugin { ... }
```

| Attribute | Type | Required | Description |
|---|---|---|---|
| `namespace` | `String` | Yes | The namespace prefix used in NSL (e.g., `my_namespace.func()`) |

**Rules:**
- All `@NoxFunction` methods must be annotated with `@JvmStatic`
- The namespace must be unique across all loaded modules

#### `@NoxFunction`

Marks a method as a callable NSL function.

```kotlin
@NoxFunction(name = "my_func")
@JvmStatic
fun myFunc(arg1: ParamType1, arg2: ParamType2): ReturnType { ... }
```

| Attribute | Type | Required | Description |
|---|---|---|---|
| `name` | `String` | Yes | The function name used in NSL (e.g., `namespace.name()`) |
 
### Generic Parameters & Return Types

You can use the `@NoxGeneric` annotation to define generic parameters (like `T`) for a function. This is especially useful for collections. You can then reference these placeholders in the `@NoxType` and `@NoxTypeMethod` annotations. 

At compile-time, the compiler resolves the generic types based on the arguments or target object, and produces a mangled SCALL name (e.g. `push!int`). At run-time, the VM parses this mangled name and automatically generates a strongly-typed `NoxNativeFunc` adapter specific to those types.

```kotlin
@NoxModule(namespace = "_ArrayMethods")
object ArrayMethods {

    @NoxGeneric(["T"])
    @NoxTypeMethod(targetType = "T[]", name = "push")
    @JvmStatic
    fun push(
        arr: Any?,
        @NoxType("T") item: Any?,
    ) {
        val list = arr as? MutableList<Any?> ?: throw NullPointerException("null array")
        list.add(item)
    }

    @NoxGeneric(["T"])
    @NoxTypeMethod(targetType = "T[]", name = "pop")
    @NoxType("T")
    @JvmStatic
    fun pop(arr: Any?): Any? {
        val list = arr as? MutableList<Any?> ?: throw NullPointerException("null array")
        return list.removeAt(list.size - 1)
    }
}
```

**Rules for Generics:**
- The `@NoxGeneric` annotation declares the placeholder names.
- Use `@NoxType("T")` on parameters and functions to specify that their type is tied to the generic `T`.
- In `@NoxTypeMethod`, the `targetType` can include placeholders like `"T[]"`.
- The backing Kotlin type for generic variables must be `Any?`, as it could represent either a primitive (`pMem` value like Long or Double) or a reference (`rMem` value like String). The VM Linker handles boxing and unboxing correctly when generating the call adapter.

### Supported Parameter Types

The linker automatically maps between NSL types and Kotlin/JVM types:

| NSL Type | Kotlin Type | VM Storage |
|---|---|---|
| `int` | `Long` or `Int` | `pMem` |
| `double` | `Double` | `pMem` (as raw long bits) |
| `boolean` | `Boolean` | `pMem` (as 0/1) |
| `string` | `String` | `rMem` |
| `json` | `NoxObject` | `rMem` |
| `json` (array) | `NoxArray` | `rMem` |
| Any array | `List<*>` | `rMem` |

The same mapping applies for return types. The linker generates code to place the return value in the correct register bank.

### Optional Parameters: `@NoxDefault`

Plugin parameters are required by default. To make a parameter optional, annotate it with `@NoxDefault` and provide a literal default value. This follows the same rule as user-defined functions: **optional parameters must come after all required parameters**.

```kotlin
@NoxModule(namespace = "Json")
object JsonModule {

    @NoxFunction(name = "stringify")
    @JvmStatic
    fun stringify(
        @NoxType("json") value: Any?,
        @NoxDefault("true") pretty: Boolean = true,
    ): String = NoxJsonWriter(prettyPrint = pretty).write(value)
}
```

**NSL usage:**

```c
json data = Json.parse("{\"name\": \"Alice\"}");
string pretty = Json.stringify(data);         // pretty-printed (default)
string compact = Json.stringify(data, false);  // compact
```

**How it works:** The VM has no concept of default parameters. The compiler handles defaults entirely, when a call omits an optional argument, the compiler injects the default literal into the bytecode at the call site. The linker and SCALL instruction remain unchanged.

**Supported literal forms:**

| Literal | Example | NSL Type |
|---|---|---|
| `"true"` / `"false"` | `@NoxDefault("true")` | `boolean` |
| Integer | `@NoxDefault("42")` | `int` |
| Decimal | `@NoxDefault("3.14")` | `double` |
| Quoted string | `@NoxDefault("\"hello\"")` | `string` |
| `"null"` | `@NoxDefault("null")` | any reference type |

**Rules:**
- Optional parameters must come after all required parameters (validated at registration time)
- The Kotlin parameter should also declare a matching default (`= true`, `= 42`, etc.) so the function works correctly when called directly from Kotlin tests

### Accessing the Runtime Context

Plugins that need to interact with the sandbox (check permissions, charge gas, access metadata) can accept a `RuntimeContext` as their **first parameter**.

```kotlin
@NoxModule(namespace = "secure_io")
object SecureIO {

    @NoxFunction(name = "log")
    @JvmStatic
    fun log(ctx: RuntimeContext, message: String) {
        val response = ctx.requestPermission(
            PermissionRequest.Plugin(
                category = "log",
                action = "write",
                details = mapOf("message" to message)
            )
        )
        when (response) {
            is PermissionResponse.Granted -> println("[Nox] $message")
            is PermissionResponse.Denied ->
                throw SecurityException("Permission denied: log.write. ${response.reason}")
        }
    }

    @NoxFunction(name = "quota_check")
    @JvmStatic
    fun quotaCheck(ctx: RuntimeContext): Int =
        ctx.remainingInstructions
}
```

**Automatic Injection:** The linker detects when the first parameter is `RuntimeContext` and injects the Sandbox's context object. **The NSL caller does not pass this argument:**

```c
// NSL code. NOTE: RuntimeContext is invisible to the script
secure_io.log("Hello from the sandbox!");
int remaining = secure_io.quota_check();
```
 
### Exception Handling in Plugins

All exceptions thrown by plugin code are **automatically contained** by the VM:

```kotlin
@NoxFunction(name = "divide")
@JvmStatic
fun divide(a: Double, b: Double): Double {
    if (b == 0.0) throw ArithmeticException("Division by zero")
    return a / b
}
```

The VM wraps every `SCALL` in a `try-catch(Throwable)`:

1. The JVM exception is caught
2. It's converted into a Nox-internal exception
3. The exception is routed through the [Exception Table](../vm/error-handling.md)
4. If uncaught, it propagates to the NSL `try-catch` or terminates the program

**Guarantee:** A plugin bug **cannot crash the Host**.
 
### type-bound Methods: `@NoxTypeMethod`

In addition to namespace-scoped functions, you can register methods that are **bound to a specific NSL type**. These appear as method calls on values of that type.

```kotlin
@NoxModule(namespace = "Integer")
object IntegerMethods {

    @NoxTypeMethod(targetType = "int", name = "toDouble")
    @JvmStatic
    fun toDouble(value: Long): Double = value.toDouble()

    @NoxTypeMethod(targetType = "int", name = "toString")
    @JvmStatic
    fun toString(value: Long): String = value.toString()

    @NoxTypeMethod(targetType = "int", name = "getNumOfDigits")
    @JvmStatic
    fun getNumOfDigits(value: Long): Long {
        if (value == 0L) return 1L
        return kotlin.math.floor(kotlin.math.log10(kotlin.math.abs(value.toDouble()))).toLong() + 1
    }
}
```

**NSL usage:**

```c
int x = 42;
double d = x.toDouble();          // 42.0
string s = x.toString();          // "42"
int digits = x.getNumOfDigits();  // 2
```
 
### Registration and Discovery

#### Automatic Discovery

On startup, the runtime scans for classes annotated with `@NoxModule`. Each discovered module is:

1. **Validated:** All `@NoxFunction` methods are checked for correct signatures
2. **Linked:** `MethodHandle` adapters are generated for each function
3. **Registered:** Functions are added to the `LibraryRegistry` under their namespace
4. **Available:** The compiler can validate calls to these functions statically

#### Manual Registration

```kotlin
val runtime = NoxRuntime()
runtime.registerModule(MathExtension::class)
runtime.registerModule(SecureIO::class)
```
 
### Example: A Complete Plugin

```kotlin
@NoxModule(namespace = "text")
object TextUtils {

    @NoxFunction(name = "reverse")
    @JvmStatic
    fun reverse(input: String): String =
        input.reversed()

    @NoxFunction(name = "repeat")
    @JvmStatic
    fun repeat(input: String, times: Long): String =
        input.repeat(times.toInt())

    @NoxFunction(name = "word_count")
    @JvmStatic
    fun wordCount(input: String?): Long {
        if (input.isNullOrBlank()) return 0L
        return input.trim().split("\\s+".toRegex()).size.toLong()
    }

    @NoxFunction(name = "truncate")
    @JvmStatic
    fun truncate(input: String, maxLength: Long): String {
        if (input.length <= maxLength) return input
        return input.substring(0, maxLength.toInt() - 3) + "..."
    }
}
```
 
## Tier 1: External Plugins (C ABI)

### Overview

When running as a **GraalVM Native Image binary**, Tier 0 annotation scanning is unavailable. Tier 1 (external) plugins are shared libraries (`.so`, `.dylib`, `.dll`) loaded at runtime via `dlopen` / `LoadLibrary` and called through a C ABI bridge.

### Plugin Contract

Every Tier 1 plugin exports a C-compatible interface:

```c
// plugin_contract.h is provided by the Nox SDK
typedef struct {
    const char* name;           // Function name visible to NSL
    int         param_count;    // Number of parameters
    int         param_types[];  // Type tags (INT=0, DOUBLE=1, BOOL=2, STRING=3, JSON=4)
    int         return_type;    // Type tag for return value
    void*       func_ptr;       // Pointer to the native implementation
} NoxPluginFunc;

typedef struct {
    const char*       namespace;       // NSL namespace
    int               func_count;
    NoxPluginFunc*    functions;
} NoxPluginManifest;

// Every plugin must export this symbol
NoxPluginManifest* nox_plugin_init();
```

### Writing a Tier 1 Plugin (C example)

```c
#include "nox_plugin.h"

// Note: By default, the first value passed to any C function is the opaque `void* noxruntime` context pointer.
static double hypot_impl(void* noxruntime, double a, double b) {
    return sqrt(a * a + b * b);
}

static NoxPluginFunc functions[] = {
    { "hypot", 2, {DOUBLE, DOUBLE}, DOUBLE, (void*)hypot_impl }
};

static NoxPluginManifest manifest = {
    .namespace = "math_ext",
    .func_count = 1,
    .functions = functions
};

NoxPluginManifest* nox_plugin_init() {
    return &manifest;
}
```

### Loading

```bash
# Place plugins in the runtime's plugin directory
$ nox run --plugin-dir ./plugins script.nox

# Or specify individual plugins
$ nox run --plugin ./plugins/libmath_ext.so script.nox
```

### JVM vs Native Mode

| Feature | JVM Mode (Tier 0) | External Plugin Mode (Tier 1) |
|---|---|---|
| Discovery | Classpath scanning | `dlopen` + symbol lookup |
| Call overhead | Inlined by JIT | C ABI call (~5ns) |
| Type safety | Compile-time | Manifest-validated at load |
| Platform | Any JVM | Platform-specific binary |
 
## Tier 2: Script Imports

### Overview

Tier 2 is the simplest extension mechanism: import another `.nox` file and use its functions under a user-chosen namespace.

```c
import "utils/math_helpers.nox" as mh;

main(double x, double y) {
    double dist = mh.calculateDistance(x, y, 0.0, 0.0);
    return `Distance: ${dist}`;
}
```

### Syntax

```c
import "path/to/file.nox" as namespace;
```

The namespace is **mandatory** and must be explicitly chosen by the developer. It must not clash with Tier 0 built-in namespaces (Math, File, Http, etc.) or Tier 1 external plugin namespaces.

### What Gets Imported

| Element | Imported? |
|---|---|
| Functions | Accessible as `namespace.funcName()` |
| Type definitions | Accessible as `namespace.TypeName` |
| Global variables | Private to the imported module (module-scoped) |
| `main()` | Not exported (allows standalone testing of library files) |
| `@tool` headers | Ignored when importing |

### Resolution

Import paths are resolved **relative to the importing file's directory**. Circular imports are a compile-time error.

### Compilation

Imported functions are compiled into the **same `CompiledProgram`**, they become regular `CALL` targets (not `SCALL`). Each module's globals get a reserved segment in the flat global memory array. See [Code Generation, ModuleMeta](../compiler/codegen.md) for details.
 
## Plugin Security Checklist

| Do | Don't |
|---|---|
| Use `RuntimeContext` for permission checks | Access the file system directly |
| Keep functions stateless | Store mutable state in globals |
| Throw exceptions for invalid input | Call `System.exit()` or `exitProcess()` |
| Return immutable results when possible | Return references to internal mutable state |
| Keep operations bounded in time | Start new threads |
| Document permission requirements | Assume permissions are granted |
 
## Next Steps

- [**FFI Internals**](ffi-internals.md)
- [**Standard Library**](../language/stdlib.md)
- [**Security Model**](../architecture/security.md)
