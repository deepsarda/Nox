---
name: nox
description: Write, edit, compile, run, and format Nox (.nox) programs. Use whenever working with the Nox language, NoxCompiler, noxc, noxfmt, or the Nox runtime.
triggers:
  - "*.nox"
  - NoxCompiler
  - noxc
  - noxfmt
  - "nox run"
---

# Nox language skill

You are working with a `.nox` program or library. **Before writing any Nox code**,
read the bundled reference:

```
./NOX_LANGUAGE_REFERENCE.md
```

It is shipped alongside this skill and contains the authoritative grammar,
type system, and standard-library surface. Do not guess syntax from memory —
Nox looks like C/Java but differs in important ways (no classes, no generics,
no closures, UFCS, capability-based I/O).

## Workflow

1. **Read context:** Load `NOX_LANGUAGE_REFERENCE.md`. Also read any existing
   `.nox` files in the project to pick up conventions.
2. **Edit:** Make the requested change.
3. **Self-check:** Run `noxc --json <file.nox>`. Non-zero exit means errors on
   stderr as a JSON array of `{file, line, column, severity, message, suggestion?}`.
   Fix them and re-run until clean.
4. **Format:** Run `noxfmt <file.nox>` before finalizing. If `noxfmt --check`
   reports nothing, you're done.
5. **Optionally run:** `nox <file.nox> [args]` executes in a sandboxed VM.
   Use this only when the user asks. Nox programs may prompt for capabilities
   (file, network) at runtime.

## Tools

| Command              | Purpose                                    | Exit code      |
|----------------------|--------------------------------------------|----------------|
| `noxc --json file.nox` | Typecheck only; emit structured errors   | 0 ok, 1 errors |
| `noxfmt file.nox`     | Format in place                           | 0 always       |
| `noxfmt --check path/`| Verify formatted                         | 0 clean, 1 dirty |
| `nox file.nox`    | Execute in sandbox                       | program exit   |

## Output contract

Do **not** post Nox code to the user without first running `noxc --json` and
`noxfmt` on it. If the user pastes code that fails `noxc --json`, report the
errors verbatim, the `suggestion` field is usually the correct fix.
