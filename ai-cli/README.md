# AI CLI integrations for Nox

Config files that teach Claude Code, Gemini CLI, and Codex CLI to work with `.nox`
files fluently. These tools do **not** speak LSP — they read files and shell out to
binaries. So instead of handing them a language server, we give them:

1. A consolidated language reference (`shared/NOX_LANGUAGE_REFERENCE.md`) the agent
   loads before writing any Nox code.
2. Instructions to self-check edits via `noxc --json` and format with `noxfmt`.

The reference is a synced copy of the repo-root `llms.txt` (already purpose-built
for LLMs). Run `./gradlew generateAiReference` to refresh the copy; CI's
`check-ai-reference` job fails on drift.

## Claude Code

```sh
mkdir -p ~/.claude/skills/nox
cp ai-cli/claude-code/skill-nox.md ~/.claude/skills/nox/skill.md
cp ai-cli/shared/NOX_LANGUAGE_REFERENCE.md ~/.claude/skills/nox/
```

The skill triggers on `.nox` files and on keywords like `NoxCompiler`, `noxc`, `noxfmt`.

## Gemini CLI

```sh
mkdir -p ~/.gemini/extensions/nox
cp -r ai-cli/gemini-cli/. ~/.gemini/extensions/nox/
cp ai-cli/shared/NOX_LANGUAGE_REFERENCE.md ~/.gemini/extensions/nox/
```

Adds `/nox-check`, `/nox-format`, `/nox-ref` commands.

## Codex CLI

Copy `ai-cli/codex-cli/AGENTS.md.template` to your project root as `AGENTS.md`,
alongside `NOX_LANGUAGE_REFERENCE.md`. Codex picks these up automatically.

## Editor use

CLI agents do not need `nox-lsp`. For editor use, install the VSCode extension or
IntelliJ plugin — each bundles its own LSP wiring.
