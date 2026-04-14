# NSL for Experienced Developers

If you already know Java, Python, TypeScript, or C, this guide will fast-track your understanding of NSL. 

**TL;DR:** NSL has C-family syntax, strict static typing, no classes, no generics, and no inheritance. It executes inside a custom bytecode VM. 

Here are the 4 major differences.

## 1. Unified Function Call Syntax (UFCS)

NSL does not have classes or methods. However, it provides the ergonomic feel of object-oriented programming through UFCS.

**Rule:** Any function `func(arg1, arg2)` can be called as `arg1.func(arg2)`.

```c
type Point { int x; int y; }

// This is a global function
double calculateDistance(Point p) {
    return Math.sqrt(p.x * p.x + p.y * p.y);
}

main() {
    Point origin = { x: 3, y: 4 };
    
    // These two are semantically identical. They compile to the exact same bytecode.
    double d1 = calculateDistance(origin);
    double d2 = origin.calculateDistance(); 
}
```

This allows fluent method chaining (`string.upper().split(",")`) without requiring a complex object model.

## 2. Streaming with `yield`

In Python or JS, `yield` pauses a generator function. **In NSL, `yield` does not pause execution.**

Instead, `yield` acts as a message-passing mechanism to the Host application. It streams intermediate results (like progress bars or chunks of data) out of the sandbox asynchronously.

```c
main() {
    yield "Starting download..."; // Sent to host immediately
    // ... some long process ...
    yield "Processing data...";   // Sent to host immediately
    
    return "All done";            // Terminates the VM and returns final result
}
```

`yield` can be called from *any* function, at *any* depth. `return` inside `main()` permanently halts the Sandbox.

## 3. The `json` Type Bridge

Handling untyped data (like HTTP responses) in a statically typed language can be painful. NSL solves this with a dedicated, dynamic `json` type.

**Structs to JSON (Implicit):**
Any user-defined struct can be implicitly upcast to `json`.
```c
User u = { name: "Alice", age: 30 };
json payload = u; // Always safe
```

**JSON to Structs (Explicit with Validation):**
To go backward, you use the `as` keyword. This performs a deep, structural type-check at runtime.
```c
json response = Http.getJson("/api/user");

// If the JSON is missing 'name' or 'age', or if they are the wrong types,
// a CastError is thrown.
User safeUser = response as User; 
```

You can also use safe accessors if you don't want to cast the whole object:
```c
string name = response.getString("name", "Unknown"); // fallback to "Unknown"
```

## 4. Zero-Trust I/O & The Permission Bridge

In Node.js or Python, `fs.readFileSync()` just executes. In NSL, **every** side-effect (File, Network, Environment) triggers a hardware-like interrupt called the Permission Bridge.

```c
// This pauses the VM and asks the Host application for permission
string text = File.read("/etc/passwd"); 
```

If the Host (or CLI user) denies the request, the VM instantly throws a `SecurityError`. The script cannot bypass this. 

## Other Quick Notes
- **No Null Primitives:** `int`, `double`, and `boolean` can never be `null`. Only references (`string`, `json`, arrays, structs) can be `null`.
- **Strict Strings:** `+` is only for `string + string`. If you do `"Count: " + 10`, it throws a compile error. You **must** use interpolation: `` `Count: ${10}` ``.
- **Default Arguments:** Handled entirely at compile-time by the bytecode emitter. Zero runtime cost.
- **Resource Guard Exceptions:** A catch-all block (`catch (err)`) does **not** catch Resource Guard Exceptions (like timeout, instruction limit exceeded, or stack overflow). These exceptions cannot be bypassed because they have a kill instruction in their specific catch block that instantly terminates the VM to protect the host.
