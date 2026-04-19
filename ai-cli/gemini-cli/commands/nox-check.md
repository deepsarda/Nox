---
description: Typecheck a .nox file using `noxc --json` and surface any errors.
---

Run `noxc --json {{args}}`. On non-zero exit, parse stderr as a JSON array of
`{file, line, column, severity, message, suggestion?}` entries and present each
error with file:line:column, the message, and the suggested fix if present.

Do not attempt to fix the code unless the user explicitly asks, just report.
