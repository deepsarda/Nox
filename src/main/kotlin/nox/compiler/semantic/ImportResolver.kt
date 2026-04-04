package nox.compiler.semantic

import nox.compiler.CompilerErrors
import nox.compiler.ast.RawImportDecl
import nox.compiler.ast.RawProgram
import nox.compiler.parsing.NoxParsing
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Resolves `import "path.nox" as namespace;` declarations.
 *
 * Resolution is depth-first: if A imports B and B imports C, then C
 * is fully resolved before B, and B before A. This ensures that by the
 * time we process a module, all its transitive dependencies are ready.
 *
 * See docs/compiler/semantic-analysis.md.
 *
 * @property basePath                   absolute path of the file being compiled
 * @property errors                     shared error collector
 * @property fileReader                 abstraction over file I/O for testability
 * @property builtinNamespaces          reserved Tier 0 (built-in) namespace names (e.g. `Math`, `File`)
 * @property externalPluginNamespaces   reserved Tier 1 (external plugin) namespace names (from loaded plugins)
 * @property processingSet              paths currently being resolved (cycle detection)
 * @property resolvedFiles              cache of already-resolved files (deduplication across the import tree)
 */
internal class ImportResolver(
    private val basePath: Path,
    private val errors: CompilerErrors,
    private val fileReader: (Path) -> String = { it.toFile().readText() },
    private val builtinNamespaces: Set<String> = emptySet(),
    private val externalPluginNamespaces: Set<String> = emptySet(),
    private val processingSet: MutableSet<Path> = mutableSetOf(),
    private val resolvedFiles: MutableMap<Path, ResolvedFile> = mutableMapOf(),
) {
    /** All successfully resolved modules, in depth-first order. */
    val modules = mutableListOf<ResolvedModule>()

    /** Namespace names already imported by the current file. */
    private val importedNamespaces = mutableSetOf<String>()

    /** Running counter for global memory slot assignment. */
    private var nextGlobalOffset = 0

    /**
     * Resolve all imports in the given [program].
     *
     * For each `import` declaration:
     * 1. Resolve the path relative to the importing file's directory
     * 2. Validate the namespace name (no Tier 0/1 collisions, no duplicates)
     * 3. Detect circular imports
     * 4. Check the deduplication cache
     * 5. Otherwise, parse and recursively resolve the imported file
     * 6. Register the module with its global slot offset
     */
    fun resolveImports(program: RawProgram) {
        for (imp in program.imports) {
            resolveImport(imp)
        }
    }

    private fun resolveImport(imp: RawImportDecl) {
        // 1. Resolve path relative to importing file's directory
        val resolved = basePath.parent.resolve(imp.path).normalize()

        // 2. Validate namespace name
        if (!validateNamespace(imp)) return

        // 3. Cycle detection
        if (resolved in processingSet) {
            errors.report(
                imp.loc,
                "Circular import detected: '${imp.path}' is already being resolved (import cycle)",
                suggestion = "Break the cycle by extracting shared code into a third file that neither imports",
            )
            return
        }

        // 4. Check deduplication cache
        val cached = resolvedFiles[resolved]
        if (cached != null) {
            // File was already resolved, so reuse the program, register under new namespace
            modules.add(
                ResolvedModule(
                    namespace = imp.namespace,
                    sourcePath = resolved.pathString,
                    program = cached.program,
                    globalBaseOffset = cached.globalBaseOffset,
                    globalCount = cached.globalCount,
                ),
            )
            return
        }

        // 5. Parse the imported file
        val source =
            try {
                fileReader(resolved)
            } catch (_: Exception) {
                errors.report(
                    imp.loc,
                    "Cannot read imported file '${imp.path}'",
                    suggestion =
                        "Check that the file exists and the path is relative to the importing file's directory",
                )
                return
            }

        processingSet.add(resolved)

        try {
            val importedProgram = NoxParsing.parse(source, resolved.pathString, errors)

            // 5b. Recursively resolve imports in the imported file
            val childResolver =
                ImportResolver(
                    basePath = resolved,
                    errors = errors,
                    fileReader = fileReader,
                    builtinNamespaces = builtinNamespaces,
                    externalPluginNamespaces = externalPluginNamespaces,
                    processingSet = processingSet,
                    resolvedFiles = resolvedFiles,
                )
            childResolver.nextGlobalOffset = nextGlobalOffset
            childResolver.resolveImports(importedProgram)

            // Absorb child's modules and carry forward the global offset
            modules.addAll(childResolver.modules)
            nextGlobalOffset = childResolver.nextGlobalOffset

            // 6. Register this module
            val moduleGlobals = importedProgram.globals.size
            val globalBaseOffset = nextGlobalOffset
            modules.add(
                ResolvedModule(
                    namespace = imp.namespace,
                    sourcePath = resolved.pathString,
                    program = importedProgram,
                    globalBaseOffset = globalBaseOffset,
                    globalCount = moduleGlobals,
                ),
            )
            nextGlobalOffset += moduleGlobals

            // Cache for deduplication
            resolvedFiles[resolved] =
                ResolvedFile(
                    program = importedProgram,
                    globalBaseOffset = globalBaseOffset,
                    globalCount = moduleGlobals,
                )
        } finally {
            processingSet.remove(resolved)
        }
    }

    /**
     * Validate that the chosen namespace name does not collide with
     * built-in, external plugin, or already-imported namespaces.
     *
     * @return `true` if valid, `false` if an error was reported
     */
    private fun validateNamespace(imp: RawImportDecl): Boolean =
        when (val name = imp.namespace) {
            in builtinNamespaces -> {
                errors.report(
                    imp.loc,
                    "Import namespace '$name' conflicts with built-in namespace '$name'",
                    suggestion = "Choose a different alias: 'import \"${imp.path}\" as My$name;'",
                )
                false
            }
            in externalPluginNamespaces -> {
                errors.report(
                    imp.loc,
                    "Import namespace '$name' conflicts with a loaded plugin namespace '$name'",
                    suggestion = "Choose a different alias: 'import \"${imp.path}\" as My$name;'",
                )
                false
            }
            in importedNamespaces -> {
                errors.report(
                    imp.loc,
                    "Namespace '$name' is already imported",
                    suggestion = "Use a different alias for this import: 'import \"${imp.path}\" as OtherName;'",
                )
                false
            }
            else -> {
                importedNamespaces.add(name)
                true
            }
        }
}

/**
 * Cache entry for an already-resolved file, private to [ImportResolver].
 *
 * When the same `.nox` file is imported from multiple locations, we reuse
 * the parsed [Program] and its global memory allocation rather than
 * re-parsing and re-resolving the file.
 */
internal data class ResolvedFile(
    val program: nox.compiler.ast.RawProgram,
    val globalBaseOffset: Int,
    val globalCount: Int,
)
