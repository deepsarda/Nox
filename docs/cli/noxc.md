# `noxc` CLI Compiler

The `noxc` command compiles a Nox source file and outputs its `.noxc` disassembly.

## Usage

```bash
noxc <file.nox> [options]
```

## Options

| Flag | Description |
|---|---|
| `-o`, `--output <path>` | Output path for `.noxc` file (default: beside input) |
| `--plugin <path>` | Load a C plugin for type resolution (repeatable) |
| `--stdout` | Print disassembly to stdout instead of writing a file |

## Examples

```bash
# Compile to .noxc file (written beside the source)
noxc script.nox

# Output to specific path
noxc script.nox -o /tmp/output.noxc

# Print to stdout
noxc script.nox --stdout

# With plugin for type resolution
noxc script.nox --plugin=./libdb.dylib
```

## Building Native Binaries

```bash
./gradlew nativeCompile
# Produces: build/native/nativeCompile/noxc
```
