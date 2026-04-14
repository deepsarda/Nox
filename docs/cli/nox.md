# `nox` CLI Runner

The `nox` command compiles and executes a Nox program with interactive permission/resource prompts, C plugin loading, yield streaming, and disassembly output.

## Usage

```bash
nox <file.nox> [options]
```

## Execution Options

| Flag | Description |
|---|---|
| `-d`, `--disassemble` | Show disassembly instead of executing |
| `--plugin <path>` | Load a C plugin (`.dylib`/`.so`/`.dll`), repeatable |

Yields are always streamed to stdout in real time -- no flag needed.

## Permission Flags

### File Permissions

| Flag | Description |
|---|---|
| `--allow-file-read=<path>` | Allow reads under path (repeatable) |
| `--allow-file-write=<path>` | Allow writes under path (repeatable) |
| `--allow-file-delete=<path>` | Allow deletes under path (repeatable) |
| `--allow-file-list=<path>` | Allow directory listing under path (repeatable) |
| `--allow-file=<path>` | Allow all file ops under path (repeatable) |
| `--allow-file-ext=<ext>` | Restrict file ops to extensions (e.g. `.json,.csv`) |
| `--file-max-bytes=<n>` | Max bytes per file read/write operation |
| `--file-read-only` | Allow reads everywhere, deny all writes |

### HTTP Permissions

| Flag | Description |
|---|---|
| `--allow-http=<domain>` | Allow all HTTP methods to domain (repeatable) |
| `--allow-http-get=<domain>` | Allow only GET to domain (repeatable) |
| `--allow-http-post=<domain>` | Allow only POST to domain (repeatable) |
| `--allow-http-put=<domain>` | Allow only PUT to domain (repeatable) |
| `--allow-http-delete=<domain>` | Allow only DELETE to domain (repeatable) |
| `--allow-http-port=<port>` | Restrict HTTP to specific ports (repeatable) |
| `--https-only` | Deny plain HTTP, require HTTPS |
| `--http-timeout=<ms>` | Max HTTP response time in milliseconds |
| `--http-max-response=<n>` | Max HTTP response body size in bytes |

### Environment Permissions

| Flag | Description |
|---|---|
| `--allow-env=<name>` | Allow reading specific env var (repeatable) |
| `--allow-env-all` | Allow reading all env vars |
| `--allow-sysinfo=<prop>` | Allow reading specific system property (repeatable) |
| `--allow-sysinfo-all` | Allow reading all system properties |

### Plugin Permissions

| Flag | Description |
|---|---|
| `--allow-plugin=<cat:act>` | Allow plugin permission category:action (repeatable) |
| `--allow-plugin-cat=<cat>` | Allow all actions in plugin category (repeatable) |

### Blanket Flags

| Flag | Description |
|---|---|
| `--allow-all` | Allow everything (no prompts at all) |
| `--no-prompt` | Non-interactive: deny anything not pre-allowed |

## Resource Limits

| Flag | Default | Description |
|---|---|---|
| `--max-instructions=<n>` | 500,000 | Instruction limit (0=unlimited) |
| `--max-time=<sec>` | 60 | Execution time limit in seconds (0=unlimited) |
| `--max-depth=<n>` | 1,024 | Call stack depth limit (0=unlimited) |
| `--auto-extend` | off | Auto-extend all resource limits (never prompt, never stop) |

## Permission Architecture

When a Nox program requests a permission (file access, HTTP, env, plugin), the request flows through three layers:

1. **PermissionPolicy:** Static policy built from CLI flags. If the request matches a flag, it's granted/denied immediately.
2. **SessionPolicyCache:** Caches interactive decisions made earlier in the session. Prevents re-prompting for the same type of request.
3. **PermissionPrompt:** Interactive TUI prompt shown to the user with granular options (allow this file, allow this directory, allow all reads, deny, etc.).

## Examples

```bash
# Run with all permissions granted (yields stream by default)
nox script.nox --allow-all

# Show disassembly
nox script.nox -d

# Granular permissions
nox script.nox --allow-file-read=/data --allow-http=api.example.com --https-only

# Non-interactive (CI/CD)
nox script.nox --no-prompt --allow-file-read=/data --allow-env=HOME

# Load C plugins
nox script.nox --plugin=./libmyplugin.dylib --allow-plugin-cat=db

# Unlimited resources
nox script.nox --max-instructions=0 --max-time=0 --allow-all
```

## Building Native Binaries

```bash
./gradlew nativeCompile
# Produces: build/native/nativeCompile/nox
```
