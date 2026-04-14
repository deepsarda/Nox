package nox.compiler

import nox.compiler.ast.RawProgram
import nox.compiler.ast.typed.TypedProgram
import nox.compiler.codegen.CodeGenerator
import nox.compiler.codegen.CompiledProgram
import nox.compiler.codegen.NoxcEmitter
import nox.compiler.parsing.NoxParsing
import nox.compiler.semantic.*
import nox.compiler.types.SymbolTable
import nox.plugin.LibraryRegistry
import java.nio.file.Path

/**
 * Single entry point for the Nox compilation pipeline.
 *
 * Orchestrates all phases in order:
 * 1. **Parse:** ANTLR lexer/parser to raw parse tree to AST ([NoxParsing])
 * 2. **Import Resolution:** resolve `import` declarations ([ImportResolver])
 * 3. **Declaration Collection:** register types, functions, globals ([DeclarationCollector])
 * 4. **Type Resolution:** resolve expression types, validate statements ([TypeResolver])
 * 5. **Control Flow Validation:** validate return paths, loop context, dead code ([ControlFlowValidator])
 * 6. **Code Generation:** emit bytecode from the annotated AST ([CodeGenerator])
 * 7. **Disassembly:** format `.noxc` output for debugging ([NoxcEmitter])
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
        val program: RawProgram,
        val typedProgram: TypedProgram?,
        val errors: CompilerErrors,
        val warnings: CompilerWarnings,
        val modules: List<ResolvedModule>,
        val compiledProgram: CompiledProgram? = null,
        val disassembly: String? = null,
    )

    /**
     * Compile a Nox source string through all implemented phases.
     *
     * @param source     the complete `.nox` source code
     * @param fileName   the source file name (for error messages)
     * @param basePath   absolute path of the source file (for import resolution, if not provided, it will be inferred from the file name)
     * @param fileReader abstraction over file I/O, for testability
     * @return a [CompilationResult] with the annotated AST, errors, warnings, and modules
     */
    fun compile(
        source: String,
        fileName: String,
        basePath: Path = Path.of(fileName).toAbsolutePath(),
        registry: LibraryRegistry = LibraryRegistry.createDefault(),
        fileReader: (Path) -> String = { it.toFile().readText() },
    ): CompilationResult {
        val errors = CompilerErrors()
        val warnings = CompilerWarnings()

        // Provide source lines to error/warning formatters for rich diagnostics
        val lines = source.lines()
        errors.sourceLines = lines
        warnings.sourceLines = lines

        // Phase 1: Parse
        val program = NoxParsing.parse(source, fileName, errors)

        // Phase 2: Import Resolution
        val importResolver =
            ImportResolver(
                basePath = basePath,
                errors = errors,
                fileReader = fileReader,
                builtinNamespaces = registry.builtinNamespaceNames,
                externalPluginNamespaces = registry.externalPluginNamespaces,
            )
        importResolver.resolveImports(program)
        val modules = importResolver.modules.toList()

        // Early exit if parsing or import resolution produced errors
        if (errors.hasErrors()) {
            return CompilationResult(program, null, errors, warnings, modules)
        }

        // Phase 3: Declaration Collection (Pass 1)
        val globalScope = SymbolTable()
        DeclarationCollector(globalScope, errors).collect(program)

        // Phase 4: Type Resolution (Pass 2)
        val (typedProgram, typedModules) = TypeResolver(globalScope, errors, modules, registry).resolve(program)

        // Phase 4b: Tree Validation (Defensive Check)
        TreeValidator(errors).validate(program, typedProgram)

        // Phase 5: Control Flow Validation (Pass 3)
        ControlFlowValidator(errors, warnings).validate(typedProgram)

        // Early exit if semantic analyses produced errors
        if (errors.hasErrors()) {
            return CompilationResult(program, typedProgram, errors, warnings, modules)
        }

        // Phase 5b: Constant Folding & Dead Branch Elimination
        val foldedProgram = ConstantFolder.fold(typedProgram)

        // Populate source lines for the disassembler
        program.sourceLines.addAll(source.lines())
        foldedProgram.sourceLines.addAll(source.lines())

        // Phase 6: Code Generation
        val compiledProgram = CodeGenerator(errors, typedModules, registry).generate(foldedProgram)

        // Phase 7: Disassembly
        val programName = program.headers.firstOrNull { it.key == "name" }?.value ?: "(unnamed)"
        val disassembly = NoxcEmitter().emit(compiledProgram, fileName, programName, program.sourceLines)

        return CompilationResult(program, typedProgram, errors, warnings, modules, compiledProgram, disassembly)
    }
}
