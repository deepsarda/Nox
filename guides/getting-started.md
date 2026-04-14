# Getting Started: Compiling & Running

Now that you know what NSL looks like, it is time to run it. NSL comes with two command-line (CLI) tools: `nox` (the runner) and `noxc` (the compiler).

## Installing the Tools

> **TODO:** Insert specific instructions on how to download pre-built binaries or install the CLI via a package manager.

If you have the Nox repository, you can build the native binaries using Gradle:
```bash
./gradlew nativeCompile
# This produces 'nox' and 'noxc' inside build/native/nativeCompile/
```

## Writing Your First Script

Create a new file named `hello.nox` and add the following code:

```c
main(string name) {
    yield `Hello, ${name}! Welcome to Nox.`;
    return "Success";
}
```

## Running with `nox`

The `nox` tool runs your script. Let's try it:

```bash
nox hello.nox
```

### Permission Prompts (The Zero-Trust Model)
NSL is "Zero-Trust", meaning scripts are not allowed to access your files or the internet without your permission. If your script tries to read a file (`File.read()`), the `nox` runner will pause and ask you:

```
Script is requesting to read file: /secret/passwords.txt
Allow? [y/N/always/never]
```

If you trust the script and want to skip these interactive prompts, you can pass flags to pre-approve actions:
```bash
# Allow the script to do anything it wants. This is highly dangerous and is recommended to be used with the utmost caution.
nox script.nox --allow-all

# Only allow reading files in the /data directory, and HTTP GET to an API
nox script.nox --allow-file-read=/data --allow-http-get=api.example.com
```

### Streaming Output
Did you notice `yield` in the script? `yield` immediately prints to your screen. It is used to stream progress updates while the script is running. 

## Resource Limits

NSL protects your computer from bad code. If a script accidentally gets stuck in an infinite loop, it will not freeze your computer. `nox` has built-in limits:

- **Time:** By default, scripts time out after 60 seconds. (`--max-time=60`)
- **Instructions:** Limits the total number of operations. (`--max-instructions=500000`)
- **Memory/Depth:** Prevents stack overflows. (`--max-depth=1024`)

If a script hits one of these limits, the runtime safely kills it and throws an error.

## Compiling with `noxc` (Optional)

The `nox` runner actually compiles your script automatically before running it. However, if you are curious about what happens under the hood, or you want to distribute a compiled file (however to prevent manipulation inorder to access the VM, there is no exposed way to run an compiled binary), you can use the `noxc` compiler.

```bash
# Compiles script.nox into a bytecode file named script.noxc
noxc script.nox
```

You can view the raw bytecode (disassembly) in your terminal using:
```bash
noxc script.nox --stdout
```
