# Security Model & Internals: Behind the Curtain

NSL is built for execution in a **Zero-Trust** environment. This means the Host application executing the script assumes the script is actively trying to do harm.

## The Sandbox

NSL does not compile to JVM bytecode. It compiles to a custom instruction set executed by the **Nox VM**. This provides a hard boundary: scripts cannot use reflection, they cannot access host memory, and they cannot accidentally break out of the sandbox.

### The Memory Model

The VM separates memory into two distinct banks:
1. **`pMem` (Primitive Memory):** A flat `long[]` array. It stores all `int`, `double`, and `boolean` values. This is extremely fast and avoids garbage collection overhead.
2. **`rMem` (Reference Memory):** An `Object[]` array. It stores references to JVM objects like Strings, Lists (arrays), and HashMaps (structs).

Because the script only manipulates *indices* into `rMem`, it can never construct a raw pointer or manipulate JVM memory directly.

## The Permission Bridge

All external interactions (File, Http, Env) flow through the Permission Bridge. 

When a script calls `File.read("/etc/passwd")`:
1. The compiler generates a standard method call.
2. The VM hits the call and traps it.
3. The VM packages a `PermissionRequest.File.Read("/etc/passwd")` object.
4. The VM pauses and hands the request to the Host.
5. The Host evaluates the request against its active Policy (which might involve prompting the CLI user).
6. If the Host denies it, the VM instantly throws a `SecurityError`.
7. If the Host grants it (via a `FileGrant`), the VM proceeds.

Grants can be highly restrictive. A `FileGrant` might include a `maxBytes` limit, a `readOnly` flag, or it might silently rewrite the path so the script reads a temporary sandbox folder instead of the real `/etc/`.

## Resource Guards

Even without permissions, a script could try a Denial of Service (DoS) attack by looping forever or consuming all memory. The VM prevents this using Resource Guards.

- **Instruction Limit:** The VM counts every single bytecode instruction executed. If it hits the limit (e.g., 500,000), it halts.
- **Time Limit:** A watchdog thread kills the VM if execution exceeds the wall-clock timeout (e.g., 60 seconds).
- **Depth Limit:** The VM limits the call stack (e.g., 1024 deep) to prevent stack overflow from infinite recursion.

**CRITICAL:** These exceptions **cannot be caught** by a catch-all `try/catch` block in the script. They cannot be bypassed because they have a kill instruction in their specific catch block that instantly terminates the VM to protect the host.

## Super-instructions

To make struct field access fast, the compiler generates "Super-instructions":
- `HACC` (Hash Access): Looks up a field in a struct by its pre-hashed name string.
- `HMOD` (Hash Modify): Updates a field.

These bypass the overhead of standard method calls for common data manipulation.
