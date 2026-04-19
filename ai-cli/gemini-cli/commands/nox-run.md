---
description: Run a .nox script via the `nox` CLI with explicit sandbox permissions.
---

Invoke `nox {{args}}`.

Nox executes scripts in a deny-by-default sandbox: every filesystem, network,
env, sysinfo, or plugin access requires an explicit `--allow-*` flag (interactive
confirmation is not available here). When suggesting a command line, pass only
the narrowest permissions the script actually needs, and prefer `--no-prompt`
so the run fails closed instead of hanging on a prompt.

The full flag reference (file, HTTP, env, sysinfo, plugin permissions plus
resource caps) lives in `docs/cli/nox.md` in the repo. Read it before
proposing flags you have not used recently.

Quick reminders:

- Args to `main()`: `-a name=value` (repeatable).
- Inspect bytecode without running: `-d` / `--disassemble`.
- Resource caps default to 500 000 instructions, 60 s wall-clock, depth 1024;
  `0` means unlimited; `--auto-extend` raises any limit when hit.
- Catch-alls: `--allow-all` (everything; only when the user explicitly asks),
  `--no-prompt` (deny anything not pre-allowed).

Report yields and the final return value to the user as they appear.
