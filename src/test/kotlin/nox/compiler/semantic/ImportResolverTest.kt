package nox.compiler.semantic

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import nox.compiler.CompilerErrors
import nox.compiler.parsing.NoxParsing
import java.nio.file.Path

/**
 * Tests for [ImportResolver]: path resolution, cycle detection,
 * namespace validation, and global slot assignment.
 *
 * Uses a stubbed [fileReader] to avoid real file I/O.
 */
class ImportResolverTest :
    FunSpec({

        // Helper: creates a minimal valid Nox source with no imports
        fun minNoxSource(name: String = "helper") =
            """
            int ${name}Counter = 0;
            int ${name}Value(int x) { return x; }
            main() { return "ok"; }
            """.trimIndent()

        // Helper: creates Nox source with N global variables
        fun noxSourceWithGlobals(vararg names: String): String =
            names.joinToString("\n") { "int $it = 0;" } + "\nmain() { return \"ok\"; }"

        // Helper: builds a file system map for the fileReader stub
        fun fileSystem(vararg entries: Pair<String, String>): (Path) -> String {
            val map =
                entries.associate { (path, content) ->
                    Path.of(path).normalize().toString() to content
                }
            return { path ->
                map[path.normalize().toString()]
                    ?: throw java.io.FileNotFoundException("Not found: $path")
            }
        }

        test("resolves relative path and sets ImportDecl.resolvedPath") {
            val errors = CompilerErrors()
            val fs = fileSystem("/project/utils/helpers.nox" to minNoxSource())

            val program =
                NoxParsing.parse(
                    source = """import "utils/helpers.nox" as helpers;""",
                    fileName = "/project/main.nox",
                    errors = errors,
                )

            val resolver =
                ImportResolver(
                    basePath = Path.of("/project/main.nox"),
                    errors = errors,
                    fileReader = fs,
                )
            resolver.resolveImports(program)

            errors.hasErrors() shouldBe false
            program.imports[0].resolvedPath shouldBe
                Path.of("/project/utils/helpers.nox").normalize().toString()
        }

        test("detects circular imports") {
            val errors = CompilerErrors()

            // A imports B, B imports A
            val fs =
                fileSystem(
                    "/project/a.nox" to """import "b.nox" as b;""",
                    "/project/b.nox" to """import "a.nox" as a;""",
                )

            val programA =
                NoxParsing.parse(
                    source = """import "b.nox" as b;""",
                    fileName = "/project/a.nox",
                    errors = errors,
                )

            val resolver =
                ImportResolver(
                    basePath = Path.of("/project/a.nox"),
                    errors = errors,
                    fileReader = fs,
                )
            resolver.resolveImports(programA)

            errors.hasErrors() shouldBe true
            errors.all().any { it.message.contains("Circular import") } shouldBe true
        }

        test("rejects built-in namespace collision") {
            val errors = CompilerErrors()
            val fs = fileSystem("/project/math.nox" to minNoxSource())

            val program =
                NoxParsing.parse(
                    source = """import "math.nox" as Math;""",
                    fileName = "/project/main.nox",
                    errors = errors,
                )

            val resolver =
                ImportResolver(
                    basePath = Path.of("/project/main.nox"),
                    errors = errors,
                    fileReader = fs,
                )
            resolver.resolveImports(program)

            errors.hasErrors() shouldBe true
            errors.all()[0].message shouldContain "conflicts with built-in namespace"
        }

        test("rejects external plugin namespace collision") {
            val errors = CompilerErrors()
            val fs = fileSystem("/project/game.nox" to minNoxSource())

            val program =
                NoxParsing.parse(
                    source = """import "game.nox" as GameAPI;""",
                    fileName = "/project/main.nox",
                    errors = errors,
                )

            val resolver =
                ImportResolver(
                    basePath = Path.of("/project/main.nox"),
                    errors = errors,
                    fileReader = fs,
                    externalPluginNamespaces = setOf("GameAPI"),
                )
            resolver.resolveImports(program)

            errors.hasErrors() shouldBe true
            errors.all()[0].message shouldContain "conflicts with a loaded plugin namespace"
        }

        test("rejects duplicate namespace names") {
            val errors = CompilerErrors()
            val fs =
                fileSystem(
                    "/project/a.nox" to minNoxSource("a"),
                    "/project/b.nox" to minNoxSource("b"),
                )

            val program =
                NoxParsing.parse(
                    source =
                        """
                        import "a.nox" as helpers;
                        import "b.nox" as helpers;
                        """.trimIndent(),
                    fileName = "/project/main.nox",
                    errors = errors,
                )

            val resolver =
                ImportResolver(
                    basePath = Path.of("/project/main.nox"),
                    errors = errors,
                    fileReader = fs,
                )
            resolver.resolveImports(program)

            errors.hasErrors() shouldBe true
            errors.all().any { it.message.contains("already imported") } shouldBe true
        }

        test("assigns sequential global slot offsets across modules") {
            val errors = CompilerErrors()
            val fs =
                fileSystem(
                    "/project/a.nox" to noxSourceWithGlobals("g1", "g2"), // 2 globals
                    "/project/b.nox" to noxSourceWithGlobals("g3", "g4", "g5"), // 3 globals
                )

            val program =
                NoxParsing.parse(
                    source =
                        """
                        import "a.nox" as modA;
                        import "b.nox" as modB;
                        """.trimIndent(),
                    fileName = "/project/main.nox",
                    errors = errors,
                )

            val resolver =
                ImportResolver(
                    basePath = Path.of("/project/main.nox"),
                    errors = errors,
                    fileReader = fs,
                )
            resolver.resolveImports(program)

            errors.hasErrors() shouldBe false
            resolver.modules shouldHaveSize 2

            val modA = resolver.modules[0]
            modA.namespace shouldBe "modA"
            modA.globalCount shouldBe 2
            modA.globalBaseOffset shouldBe 0

            val modB = resolver.modules[1]
            modB.namespace shouldBe "modB"
            modB.globalCount shouldBe 3
            modB.globalBaseOffset shouldBe 2
        }

        test("reports error for file not found") {
            val errors = CompilerErrors()
            val fs = emptyFileSystem() // empty file system

            val program =
                NoxParsing.parse(
                    source = """import "nonexistent.nox" as missing;""",
                    fileName = "/project/main.nox",
                    errors = errors,
                )

            val resolver =
                ImportResolver(
                    basePath = Path.of("/project/main.nox"),
                    errors = errors,
                    fileReader = fs,
                )
            resolver.resolveImports(program)

            errors.hasErrors() shouldBe true
            errors.all()[0].message shouldContain "Cannot read imported file"
        }

        test("handles file with no imports gracefully") {
            val errors = CompilerErrors()
            val program =
                NoxParsing.parse(
                    source = minNoxSource(),
                    fileName = "/project/main.nox",
                    errors = errors,
                )

            val resolver =
                ImportResolver(
                    basePath = Path.of("/project/main.nox"),
                    errors = errors,
                )
            resolver.resolveImports(program)

            errors.hasErrors() shouldBe false
            resolver.modules shouldHaveSize 0
        }

        test("depth-first recursive resolution order") {
            val errors = CompilerErrors()

            // main imports A, A imports B, B resolved first, then A
            val fs =
                fileSystem(
                    "/project/a.nox" to
                        """
                        import "b.nox" as b;
                        int aGlobal = 0;
                        main() { return "a"; }
                        """.trimIndent(),
                    "/project/b.nox" to
                        """
                        int bGlobal = 0;
                        main() { return "b"; }
                        """.trimIndent(),
                )

            val program =
                NoxParsing.parse(
                    source = """import "a.nox" as a;""",
                    fileName = "/project/main.nox",
                    errors = errors,
                )

            val resolver =
                ImportResolver(
                    basePath = Path.of("/project/main.nox"),
                    errors = errors,
                    fileReader = fs,
                )
            resolver.resolveImports(program)

            errors.hasErrors() shouldBe false
            resolver.modules shouldHaveSize 2

            // B should be resolved first (depth-first), then A
            resolver.modules[0].namespace shouldBe "b"
            resolver.modules[1].namespace shouldBe "a"
        }

        test("deduplicates same file imported from multiple locations") {
            val errors = CompilerErrors()

            // Both a.nox and b.nox import utils.nox, it should only be resolved once
            val fs =
                fileSystem(
                    "/project/a.nox" to
                        """
                        import "utils.nox" as u;
                        int aGlobal = 0;
                        main() { return "a"; }
                        """.trimIndent(),
                    "/project/b.nox" to
                        """
                        import "utils.nox" as v;
                        int bGlobal = 0;
                        main() { return "b"; }
                        """.trimIndent(),
                    "/project/utils.nox" to noxSourceWithGlobals("utilG1", "utilG2"),
                )

            val program =
                NoxParsing.parse(
                    source =
                        """
                        import "a.nox" as a;
                        import "b.nox" as b;
                        """.trimIndent(),
                    fileName = "/project/main.nox",
                    errors = errors,
                )

            val resolver =
                ImportResolver(
                    basePath = Path.of("/project/main.nox"),
                    errors = errors,
                    fileReader = fs,
                )
            resolver.resolveImports(program)

            errors.hasErrors() shouldBe false

            // utils.nox appears twice (under namespaces "u" and "v") but
            // must share the same Program instance and globalBaseOffset
            val utilsModules =
                resolver.modules.filter {
                    it.sourcePath == Path.of("/project/utils.nox").normalize().toString()
                }
            utilsModules shouldHaveSize 2

            // Same program object (not re-parsed)
            (utilsModules[0].program === utilsModules[1].program) shouldBe true

            // Same global offsets (shared, not duplicated)
            utilsModules[0].globalBaseOffset shouldBe utilsModules[1].globalBaseOffset
            utilsModules[0].globalCount shouldBe utilsModules[1].globalCount
        }
    })

// Utility to create an empty file system that always throws
private fun emptyFileSystem(): (Path) -> String =
    { path ->
        throw java.io.FileNotFoundException("Not found: $path")
    }
