# Nox IntelliJ Plugin

IntelliJ IDEA plugin for the Nox language. Delegates everything (diagnostics, completion,
hover, semantic highlighting, rename, formatting) to the `nox-lsp` server.

## Requirements

- **IntelliJ IDEA Ultimate 2024.2:+** uses the platform's built-in LSP API.
- **Community Edition**: install the [`lsp4ij`](https://plugins.jetbrains.com/plugin/23257-lsp4ij)
  plugin and register `nox-lsp` manually; the platform LSP API is Ultimate-only.
- `nox-lsp` binary on `PATH`, pointed to by `NOX_LSP` env var, or configured via the
  `nox.lsp.path` system property.

## Build

```sh
./gradlew :editors:intellij:buildPlugin
```

Resulting `.zip` is in `build/distributions/`.

## Verify compatibility

```sh
./gradlew :editors:intellij:verifyPlugin
```

## Publish

Tags `intellij-v*.*.*` drive the `release-intellij.yml` workflow. Token comes from
`JETBRAINS_MARKETPLACE_TOKEN`.
