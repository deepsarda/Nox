rootProject.name = "nox"
include("nox-ksp")
include("nox-format")
include("nox-lsp")
include("editors:intellij")
project(":editors:intellij").name = "intellij"
