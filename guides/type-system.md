# Type System & JSON

NSL employs a **strong, static type system**. Every variable, parameter, and return value has a type known at compile time. Type mismatches are caught during validation, before any code runs.

## Primitive Types (Pass-by-Value)

Primitives are immutable and passed to functions by copying their value. They cannot be `null`.

- `int`: A 64-bit signed integer. (`int count = 42;`)
- `double`: A 64-bit floating-point number. (`double rate = 3.14;`)
- `boolean`: A logical `true` or `false`.

*(Note: `string` behaves like a primitive because it is immutable, but technically it is a pass-by-reference object under the hood.)*

## Complex Types (Pass-by-Reference)

Complex types are passed by reference. If you pass an array to a function and modify it inside the function, the original array is modified.

### Structs (User-Defined Types)

Structs define the expected shape of your data. They contain **no methods or logic**, only fields.

```c
type ApiConfig {
    string endpoint;
    int timeout_seconds;
}
```

You create a struct using object literal syntax:

```c
ApiConfig config = {
    endpoint: "https://api.example.com",
    timeout_seconds: 30
};
```

The compiler checks this at compile-time. If you miss a field or add an unknown field, it won't compile.

### Arrays

Arrays are homogeneous (they hold only one type of thing) and ordered.

```c
int[] numbers = [1, 2, 3, 4];
numbers.push(5);
int len = numbers.length();
```

## The `json` Type (Generic Object)

Because scripts often talk to external APIs, NSL has a special type called `json`. It represents any unstructured JSON object.

### Auto-Casting: Struct to `json`

You can implicitly cast any struct into a `json` variable. This is always safe.

```c
ApiConfig config = { endpoint: "url", timeout_seconds: 30 };
json generic = config; 
```

### Explicit Casting: `json` to Struct

Going from `json` to a strict `Struct` requires an explicit cast using the `as` keyword. The VM validates the shape at runtime.

```c
json rawConfig = Http.getJson("/config");

// This cast will throw a CastError if 'rawConfig' doesn't exactly match ApiConfig.
ApiConfig config = rawConfig as ApiConfig;
```

### Safe Access Methods

If you don't want to cast the whole object, the `json` type provides methods to safely extract data by providing a default value.

```c
json user = Http.getJson("/api/user/123");

string name = user.getString("name", "Unknown"); // Returns "Unknown" if missing
int age = user.getInt("age", 0);

// Extract an array safely
int[] ids = user.getIntArray("ids", []);
```

## Strict String Rules

NSL is very strict about strings to prevent silly bugs (like `"Count: " + 10 + 1` accidentally becoming `"Count: 101"` when you meant math, etc.).

The `+` operator is **only** defined for two strings (`string + string`).

To combine text and variables, you **must** use template literals (backticks and `${}`):

```c
int count = 42;
string bad = "Found " + count;       // Compile Error!
string good = `Found ${count}`;      // Correct: "Found 42"
```

## Nullability

- `int`, `double`, and `boolean` can **never** be `null`.
- `string`, `json`, `Structs`, and `Arrays` **can** be `null`.

If you try to access a field on a `null` struct, or call `.length()` on a `null` string, the VM throws a `NullAccessError`.
