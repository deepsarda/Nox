# Compiler Overview

## Pipeline

```
   .nox source file
        │
        │  Phase 1: Parsing (ANTLR 4)
        │  ├─ NoxLexer to Token stream
        │  └─ NoxParser to Parse tree (CST)
        │
        │  Phase 2: AST Construction
        │  ├─ ASTBuilder (ANTLR Visitor)
        │  ├─ Desugaring (ParenExpr to inner, escape sequences)
        │  └─ SourceLocation attachment
        ▼
   AST (Expr / Stmt / Decl hierarchy)
        │
        │  Phase 2.5: Import Resolution
        │  ├─ Resolve import paths (relative to file)
        │  ├─ Validate namespaces (no Tier 0/1 collisions)
        │  ├─ Recursively parse + analyze imported files
        │  └─ Register import namespaces + assign global offsets
        │
        │  Phase 3: Semantic Analysis
        │  ├─ Pass 1: Collect types and function signatures
        │  ├─ Pass 2: Resolve types in function bodies  
        │  ├─ Pass 3: Validate expressions and control flow
        │  └─ Result: every Expr has .resolvedType set
        ▼
   Annotated AST
        │
        │  Phase 4: Code Generation
        │  ├─ Register allocation (liveness analysis)
        │  ├─ Bytecode emission (opcode selection)
        │  ├─ Constant pool construction
        │  ├─ Exception table generation
        │  ├─ Module metadata (per-module global offsets)
        │  └─ KILL_REF emission at scope exits
        ▼
   CompiledProgram {
       bytecode:       LongArray
       constantPool:   Array<Any?>
       exceptionTable: Array<ExEntry>
       functions:      Array<FuncMeta>
       modules:        Array<ModuleMeta>
   }
```
 

## Phase Details

### Phase 1, 2: Parsing to AST

The `ASTBuilder` class implements ANTLR's generated `NoxParserVisitor<T>` interface. Each `visit*` method converts a parse tree context into an AST node:

```kotlin
class ASTBuilder(private val fileName: String) : NoxParserBaseVisitor<Any>() {

    override fun visitBinaryExpr(ctx: NoxParser.MulDivModExprContext): Expr {
        val left = visit(ctx.expression(0)) as Expr
        val right = visit(ctx.expression(1)) as Expr
        val op = mapBinaryOp(ctx)
        return BinaryExpr(left, op, right, locOf(ctx))
    }

    override fun visitIdentifierExpr(ctx: NoxParser.IdentifierExprContext): Expr =
        IdentifierExpr(ctx.Identifier().text, locOf(ctx))

    // ParenExpr is NOT created, we just return the inner expression
    override fun visitParenExpr(ctx: NoxParser.ParenExprContext): Expr =
        visit(ctx.expression()) as Expr  // Unwrap

    private fun locOf(ctx: ParserRuleContext) = SourceLocation(
        file = fileName,
        line = ctx.start.line,
        column = ctx.start.charPositionInLine
    )
}
```

### Phase 3: Semantic Analysis

Multiple passes over the AST, each using exhaustive `when` on sealed types:

**Pass 1: Declaration Collection:**
- Scan all `TypeDef`s and build type registry (supports forward references for recursive structs)
- Scan all `FuncDef`s and build function registry
- Scan all `GlobalVarDecl`s and register global variables

**Pass 2: Type Resolution:**
- Walk every expression in every function body
- Set `expr.resolvedType` on every `Expr` node
- Resolve `IdentifierExpr.resolvedSymbol` to variables/globals
- Resolve `FuncCallExpr.resolvedFunction` to function definitions
- Resolve `MethodCallExpr.resolution` (UFCS vs type-bound vs namespace)
- Validate struct literal fields match type definition
- Check null safety (null assigned only to nullable types)
- Validate `as` casts (target must be a struct type)

**Pass 3: Control Flow Validation:**
- Every code path in non-void functions returns a value
- `break`/`continue` only appear inside loops
- `yield` only appears in `main`
- Dead code detection (statements after `return`/`throw`)

### Phase 4: Code Generation

Single pass over the annotated AST:

**Register Allocation:**
1. Compute liveness intervals for all locals
2. Assign registers (dual-bank: `pMem` for primitives, `rMem` for references)
3. Reuse registers when lifetimes don't overlap
4. Record max register count per function frame size

**Bytecode Emission:**
- Exhaustive `when` on `Expr` and `Stmt` nodes
- Opcode selection based on `resolvedType` (e.g., `IADD` vs `DADD`)
- Super-instruction selection for json/struct property access
- Constant pool deduplication
- Forward-reference backpatching for jumps
 
## Error Strategy

**Principle: Collect errors, don't fail fast.**

Each phase collects as many errors as possible before stopping:

```kotlin
class CompilerErrors {
    private val errors = mutableListOf<CompilerError>()

    fun report(loc: SourceLocation, message: String, suggestion: String? = null) {
        errors.add(CompilerError(loc, message, suggestion))
    }

    fun hasErrors(): Boolean = errors.isNotEmpty()
}
```

If Phase 3 finds errors, Phase 4 is never run. This gives the developer a full list of issues to fix in one pass.
 
## Next Steps

- [**AST Design**](ast.md)
- [**Semantic Analysis**](semantic-analysis.md)
- [**Code Generation**](codegen.md)
