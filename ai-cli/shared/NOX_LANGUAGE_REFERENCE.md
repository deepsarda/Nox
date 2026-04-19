# Nox Scripting Language (NSL) Context for LLMs

NSL is a strict, statically-typed, sandbox-safe capability language executed by the Nox VM.

## Syntax & Structure
- C-family syntax (curly braces, semicolons).
- File structure: `@tool:name` headers -> `import` -> `type` (structs) -> `functions` -> `main()`.
- Every runnable script must have a `main(...)` entry point. Its parameters define the input schema, and it implicitly returns a `string` (converted automatically).

## Type System
- **Primitives**: `int` (64-bit), `double` (64-bit), `boolean`, `string` (immutable). Nulls are NOT allowed for primitives.
- **Structs**: Data only, e.g., `type Person { string name; int age; }`. Instantiated via object literals: `{ name: "A", age: 10 }`. Nested structs are nullable.
- **Arrays**: `int[]`, `string[]`, `Person[]`. Methods: `.length()`, `.push(item)`, `.pop()`.
- **`json` Type**: Universal bridge type. Auto-casts from structs to `json`. Explicit casts from `json` to structs (`jsonVar as StructType`) throw `CastError` if invalid.
- **String Interpolation**: Prefer `` `Value: ${var}` `` over `+` concatenation (strict type rules forbid `string + int`).

## Functions & UFCS
- Default arguments allowed at the end: `func(int a, int b = 0)`.
- **UFCS (Unified Function Call Syntax)**: `method(obj, arg)` is identically callable as `obj.method(arg)`. Useful for method chaining without classes.

## Control Flow & Streaming
- Standard `if`, `else`, `while`, `for (int i=0; i<N; i++)`, `foreach (Type x in array)`.
- `break` and `continue` supported.
- **Streaming**: `yield value;` sends intermediate data to host without pausing execution. `return value;` inside `main()` terminates the sandbox.
- **Errors**: `try { ... } catch (err) { ... }` where `err` is a `string` message. `throw "Error message";`.
  - **Note:** Resource limits (timeout, instruction limit) are not caught by a catch-all block. They have a kill instruction in their specific catch block that instantly terminates the VM to protect the host.

## Standard Library (Namespaced)
- **Requires Host Permissions**:
  - `File`: `.read(path)`, `.write(path, content)`, `.append()`, `.delete()`, `.exists()`, `.list(dir)`, `.metadata()`, `.createDir()`.
  - `Http`: `.get(url)`, `.getJson(url)`, `.post(url, body)`, `.put()`, `.delete()`.
  - `Env`: `.get(name)`, `.system(prop)`.
- **No Permissions Needed**:
  - `Math`: `.sqrt()`, `.abs()`, `.max()`, `.min()`, `.floor()`, `.ceil()`, `.round()`, `.random()`, `.pow()`.
  - `Date`: `.now()` (ms).
  - `Json`: `.parse(string)`, `.stringify(json, pretty=true)`.

## Safe JSON Methods
When dealing with `json` types, always use safe accessors with defaults:
- `.getString(key, def)`, `.getInt(key, def)`, `.getDouble(key, def)`, `.getBool(key, def)`
- `.getJSON(key, def)`, `.getArray(key, StructType, def)`

## CLI Tooling
Four binaries ship together and share one version (`--version` on each).

- **`nox <file.nox>`** runs a script in a deny-by-default sandbox. Every filesystem, network, env, sysinfo, or plugin call requires an explicit `--allow-*` flag (or interactive confirmation, unavailable in non-interactive contexts). Args to `main()` go through `-a name=value`. Resource caps default to 500 000 instructions, 60 s, depth 1024 (`0` = unlimited). Full flag reference: `docs/cli/nox.md`.
- **`noxc <file.nox>`** compiles to `.noxc` disassembly. `-o <out>`, `--stdout`, `--plugin <path>`. Full flag reference: `docs/cli/noxc.md`.
- **`noxfmt <files…>`** formats `.nox` files in place. `--check` (exit 1 on drift, no writes), `--stdout` (print formatted), `--stdin` (read source, write to stdout). Directories are walked for `*.nox`.
- **`nox-lsp`** language server over stdio (default) or `--socket <port>`. Used by editor integrations; not invoked directly by scripts.

When suggesting a `nox` invocation, always prefer `--no-prompt` plus the narrowest `--allow-*` flags the script actually needs. Only suggest `--allow-all` when the user explicitly asks.
