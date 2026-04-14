# Language Reference

## Syntax Cheatsheet

**Variables & Math**
```c
int count = 10;
double rate = 5.5;
boolean active = true;
string msg = `Items: ${count}`;
```

**Structs & JSON**
```c
type User { string name; int age; }
User u = { name: "Alice", age: 30 };

json generic = u;             // Implicit upcast
User specific = generic as User; // Explicit downcast (validates at runtime)
```

**Functions & UFCS**
```c
int calculate(int a, int b = 0) { return a + b; }
// UFCS usage:
int result = 5.calculate(10);
```

**Control Flow**
```c
if (count > 0) { ... } else { ... }

for (int i = 0; i < 10; i++) { ... }

int[] items = [1, 2, 3];
foreach (int x in items) { ... }
```

**Streaming & Errors**
```c
yield "Progress...";

try {
    throw "Oops";
} catch (err) {
    yield err;
}
```

## Reserved Words
`int`, `double`, `boolean`, `string`, `json`, `void`, `true`, `false`, `null`, `if`, `else`, `while`, `for`, `foreach`, `in`, `return`, `yield`, `break`, `continue`, `try`, `catch`, `throw`, `type`, `main`, `as`, `import`

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

## Type Compatibility Matrix

| From \ To | `int` | `double` | `string` | `json` | Struct |
|---|---|---|---|---|---|
| **`int`** | - | Implicit | `.toString()` | NO | NO |
| **`double`**| `.toInt()` | - | `.toString()` | NO | NO |
| **`string`**| `.toInt()` | `.toDouble()` | - | NO | NO |
| **Struct**| NO | NO | NO | Implicit | Explicit `as` |
| **`json`**| NO | NO | NO | - | Explicit `as` |
