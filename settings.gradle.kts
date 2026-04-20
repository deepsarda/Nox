rootProject.name = "nox"
include("core")
include("tools:cli")
include("tools:ksp")
include("tools:format")
include("tools:lsp")
include("editors:intellij")
project(":editors:intellij").name = "intellij"
