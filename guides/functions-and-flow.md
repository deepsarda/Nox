# Functions, Flow & Streaming

## Function Definitions

Functions are the main way to organize your NSL code. They are defined at the top level of your file.

```c
// ReturnType functionName(Type param1, Type param2) { body }
int add(int a, int b) {
    return a + b;
}
```

### Default Arguments

You can provide default values for parameters. They must come at the end of the list.

```c
void log(string message, string level = "INFO") {
    // ...
}

// You can call it with or without the level
log("Server started");          // level becomes "INFO"
log("Error", "ERROR");          // level becomes "ERROR"
```

### Varargs

A function can accept a variable number of arguments by adding `...` before the type and `[]` after the name.

```c
int sum(int ...values[]) {
    int total = 0;
    for (int i = 0; i < values.length(); i++) {
        total = total + values[i];
    }
    return total;
}
// Call: sum(1, 2, 3, 4)
```

## Unified Function Call Syntax (UFCS)

NSL allows you to call any function as if it were a method of its first argument.

```c
variable.method(args...)  <--->  method(variable, args...)
```

These are identical:
```c
int len1 = calculateLength(myString);
int len2 = myString.calculateLength(); // UFCS
```

This lets you chain operations beautifully: `myString.trim().upper().split(",")` instead of `split(upper(trim(myString)), ",")`.

## The `main` Function

Every script **must** have a `main` function. This is where execution starts. 

The arguments to `main` define your script's input schema. If your script is run via an API, those arguments represent the required JSON payload.

## Control Flow

### `if / else`
```c
if (score > 90) {
    // ...
} else if (score > 80) {
    // ...
} else {
    // ...
}
```

### Loops
NSL supports `while`, `for`, and `foreach`.

```c
// foreach is great for arrays
string[] names = ["Alice", "Bob"];
foreach (string n in names) {
    yield `Hello ${n}`;
}
```

## Error Handling: `try / catch`

You can gracefully catch errors using `try / catch`.

```c
try {
    json data = Http.getJson(url);
} catch (err) {
    // err is a string containing the error message
    yield `Failed to fetch: ${err}`;
}
```

**CRITICAL NOTE:** A catch-all block (`catch (err)`) does **not** catch Resource Exceptions (like out of memory, timeout, instruction limit exceeded, or stack overflow). These exceptions cannot be bypassed because they have a kill instruction in their specific catch block that instantly terminates the VM to protect the host.

## Yield vs. Return

- **`yield value;`**: Sends an intermediate result to the Host. The script **continues running**. Use this for progress bars, streaming logs, or returning chunks of data.
- **`return value;`**: Inside `main()`, this sends the final result and **terminates** the Sandbox. Inside a normal function, it just returns a value to the caller.
