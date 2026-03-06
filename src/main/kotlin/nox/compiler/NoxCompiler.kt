package nox.compiler

import nox.compiler.ast.Program
import nox.compiler.semantic.DeclarationCollector
import nox.compiler.semantic.ImportResolver
import nox.compiler.semantic.ResolvedModule
import nox.compiler.semantic.SymbolTable
import nox.compiler.semantic.TypeResolver
import java.nio.file.Path

/**
 * Single entry point for the Nox compilation pipeline.
 *
 * Orchestrates all phases in order:
 * 1. **Parse:** ANTLR lexer/parser to raw parse tree to AST ([NoxParsing])
 * 2. **Import Resolution:** resolve `import` declarations ([ImportResolver])
 * 3. **Declaration Collection:** register types, functions, globals ([DeclarationCollector])
 * 4. **Type Resolution:** resolve expression types, validate statements ([TypeResolver])
 * 5. **Control Flow Validation:** (Not implemented)
 * 6. **Code Generation:** (Not implemented)
 * 7. **Disassembly:** (Not implemented)
 *
 * See docs/compiler/overview.md for the full pipeline design.
 */
object NoxCompiler {

    /**
     * The result of a full compilation.
     *
     * @property program   the parsed and annotated AST
     * @property errors    all errors collected across all phases
     * @property warnings  all warnings collected across all phases
     * @property modules   resolved import modules, in depth-first order
     */
    data class CompilationResult(
        val program: Program,
        val errors: CompilerErrors,
        val warnings: CompilerWarnings,
        val modules: List<ResolvedModule>,
    )

    /**
     * Compile a Nox source string through all implemented phases.
     *
     * @param source     the complete `.nox` source code
     * @param fileName   the source file name (for error messages)
     * @param basePath   absolute path of the source file (for import resolution)
     * @param fileReader abstraction over file I/O, for testability
     * @return a [CompilationResult] with the annotated AST, errors, warnings, and modules
     */
    fun compile(
        source: String,
        fileName: String,
        basePath: Path = Path.of(fileName).toAbsolutePath(),
        fileReader: (Path) -> String = { it.toFile().readText() },
    ): CompilationResult {
        val errors = CompilerErrors()
        val warnings = CompilerWarnings()

        // Phase 1: Parse
        val program = NoxParsing.parse(source, fileName, errors)

        // Phase 2: Import Resolution
        val importResolver = ImportResolver(basePath, errors, fileReader)
        importResolver.resolveImports(program)
        val modules = importResolver.modules.toList()

        // Early exit if parsing or import resolution produced errors
        if (errors.hasErrors()) {
            return CompilationResult(program, errors, warnings, modules)
        }

        // Phase 3: Declaration Collection (Pass 1)
        val globalScope = SymbolTable()
        DeclarationCollector(globalScope, errors).collect(program)

        // Phase 4: Type Resolution (Pass 2)
        TypeResolver(globalScope, errors, modules).resolve(program)

        //TODO: Phase 5: Control Flow Validation (Pass 3)
        //TODO: Phase 6: Code Generation

        return CompilationResult(program, errors, warnings, modules)
    }
}
