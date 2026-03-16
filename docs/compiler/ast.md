# AST Design

## Architecture

```
  ANTLR Parse Tree (NoxParser.ExpressionContext, etc.)
         │
         │  ASTBuilder (ANTLR Visitor)
         │  • Converts parse tree -> AST
         │  • Desugars ParenExpr, template literals
         │  • Attaches SourceLocation to every node
         ▼
  AST (Expr, Stmt, Decl hierarchy)
         │  • Includes ErrorNode placeholders for invalid syntax
         │
         │  SemanticAnalyzer (when-based passes)
         │  • Fills in resolvedType on every Expr
         │  • Resolves identifiers -> Symbol
         │  • Validates types, struct fields, null safety
         ▼
  Annotated AST (same tree, mutable fields populated)
         │
         │  CodeGenerator (when-based)
         │  • Fills in register on every Expr
         │  • Emits bytecode
         ▼
  CompiledProgram (bytecode, constantPool, exceptionTable)
```
 
## Base Types

### SourceLocation

```kotlin
data class SourceLocation(val file: String, val line: Int, val column: Int) {
    override fun toString(): String = "$file:$line:$column"
}
```

A data class because it's purely structural, it is never mutated once created. Attached to every AST node.

### TypeRef

```kotlin
data class TypeRef(val name: String, val isArray: Boolean = false) {

    companion object {
        val INT     = TypeRef("int")
        val DOUBLE  = TypeRef("double")
        val BOOLEAN = TypeRef("boolean")
        val STRING  = TypeRef("string")
        val JSON    = TypeRef("json")
        val VOID    = TypeRef("void")
    }

    fun isPrimitive(): Boolean =
        !isArray && name in setOf("int", "double", "boolean")

    fun isNullable(): Boolean =
        isArray || name in setOf("string", "json") || !isPrimitive()
}
```

Also a data class, represents a type reference in source code. Not a resolved type, resolution happens during semantic analysis.
 
## Node Hierarchy

### Why Sealed Classes

Kotlin sealed classes provide exactly the properties we need:

```kotlin
expr.resolvedType = TypeRef.INT     // Set by semantic analyzer
expr.register = 5                    // Set by register allocator
```

Sealed classes give us:
- **Exhaustive `when`** compiler warns on missing cases
- **Mutable fields** `var` properties for annotations
- **Smart casts** `is BinaryExpr` automatically casts in branch body
- **Clean inheritance** shared fields in base class
- **Concise syntax** no boilerplate constructors or getters
 
## Expressions

```kotlin
sealed class Expr(val loc: SourceLocation) {
    // Mutable Annotations (set by later passes) 
    var resolvedType: TypeRef? = null    // Set by SemanticAnalyzer
    var register: Int = -1               // Set by RegisterAllocator (-1 = unassigned)
}
```

### Operators

```kotlin
enum class BinaryOp {
    // Arithmetic
    ADD, SUB, MUL, DIV, MOD,
    // Comparison
    EQ, NE, LT, LE, GT, GE,
    // Logical
    AND, OR,
    // Bitwise
    BIT_AND, BIT_OR, BIT_XOR,
    // Shift
    SHL, SHR, USHR
}

enum class UnaryOp  { NEG, NOT, BIT_NOT }
enum class PostfixOp { INCREMENT, DECREMENT }
enum class AssignOp  { ASSIGN, ADD_ASSIGN, SUB_ASSIGN, MUL_ASSIGN, DIV_ASSIGN, MOD_ASSIGN }
```

### Concrete Expression Nodes

```kotlin
//  Binary: left op right 
class BinaryExpr(
    val left: Expr, val op: BinaryOp, val right: Expr, loc: SourceLocation
) : Expr(loc)

//  Unary: op operand 
class UnaryExpr(
    val op: UnaryOp, val operand: Expr, loc: SourceLocation
) : Expr(loc)

//  Postfix: operand++ or operand-- 
class PostfixExpr(
    val operand: Expr, val op: PostfixOp, loc: SourceLocation
) : Expr(loc)

//  Cast: expr as Type 
class CastExpr(
    val operand: Expr, val targetType: TypeRef, loc: SourceLocation
) : Expr(loc)

//  Literals 
class IntLiteralExpr(val value: Long, loc: SourceLocation) : Expr(loc)
class DoubleLiteralExpr(val value: Double, loc: SourceLocation) : Expr(loc)
class BoolLiteralExpr(val value: Boolean, loc: SourceLocation) : Expr(loc)
class StringLiteralExpr(val value: String, loc: SourceLocation) : Expr(loc)  // Escapes resolved
class NullLiteralExpr(loc: SourceLocation) : Expr(loc)

//  Template Literal: `text ${expr} text` 
class TemplateLiteralExpr(
    val parts: List<TemplatePart>, loc: SourceLocation
) : Expr(loc)

// Parts of a template literal
sealed interface TemplatePart {
    data class Text(val value: String) : TemplatePart
    data class Interpolation(val expression: Expr) : TemplatePart
}

//  Array Literal: [1, 2, 3] 
class ArrayLiteralExpr(
    val elements: List<Expr>, loc: SourceLocation
) : Expr(loc) {
    var elementType: TypeRef? = null     // Inferred by semantic analyzer
}

//  Struct Literal: { field: value, field: value } 
class StructLiteralExpr(
    val fields: List<FieldInit>, loc: SourceLocation
) : Expr(loc) {
    var structType: TypeRef? = null      // Inferred from assignment context
}

data class FieldInit(val name: String, val value: Expr, val loc: SourceLocation)

//  Identifier 
class IdentifierExpr(
    val name: String, loc: SourceLocation
) : Expr(loc) {
    var resolvedSymbol: Symbol? = null   // Set by semantic analyzer
}

//  Function Call: func(args) 
class FuncCallExpr(
    val name: String, val args: List<Expr>, loc: SourceLocation
) : Expr(loc) {
    var resolvedFunction: FuncDef? = null  // Set by semantic analyzer
}

//  Method Call: target.method(args) 
//    Could resolve to: UFCS, type-bound method, or namespace function
class MethodCallExpr(
    val target: Expr, val methodName: String, val args: List<Expr>, loc: SourceLocation
) : Expr(loc) {
    enum class Resolution { UFCS, TYPE_BOUND, NAMESPACE, STRUCT_METHOD }
    var resolution: Resolution? = null
    var resolvedTarget: Any? = null      // FuncDef, TypeMethod, or NamespaceFunc
}

//  Field Access: target.field 
class FieldAccessExpr(
    val target: Expr, val fieldName: String, loc: SourceLocation
) : Expr(loc)

//  Index Access: target[index] 
class IndexAccessExpr(
    val target: Expr, val index: Expr, loc: SourceLocation
) : Expr(loc)

//  Syntax Error Placeholder 
class ErrorExpr(loc: SourceLocation) : Expr(loc)
```

### Expression Pattern Matching Example

```kotlin
// In the semantic analyzer:
fun resolveType(expr: Expr): TypeRef? = when (expr) {
    is IntLiteralExpr        -> TypeRef.INT
    is DoubleLiteralExpr     -> TypeRef.DOUBLE
    is BoolLiteralExpr       -> TypeRef.BOOLEAN
    is StringLiteralExpr     -> TypeRef.STRING
    is NullLiteralExpr       -> null  // Resolved from context
    is TemplateLiteralExpr   -> TypeRef.STRING

    is BinaryExpr            -> resolveBinaryType(expr)    // smart-cast: expr is BinaryExpr
    is UnaryExpr             -> resolveUnaryType(expr)
    is PostfixExpr           -> resolvePostfixType(expr)
    is CastExpr              -> expr.targetType

    is IdentifierExpr        -> lookupVariable(expr)
    is FuncCallExpr          -> resolveFuncReturn(expr)
    is MethodCallExpr        -> resolveMethodReturn(expr)
    is FieldAccessExpr       -> resolveFieldType(expr)
    is IndexAccessExpr       -> resolveIndexType(expr)

    is ArrayLiteralExpr      -> resolveArrayType(expr)
    is StructLiteralExpr     -> resolveStructType(expr)
}
// ↑ Compiler enforces all 17 cases are covered (sealed)
```
 
## Statements

```kotlin
sealed class Stmt(val loc: SourceLocation)
```

### Concrete Statement Nodes

```kotlin
//  Variable Declaration: int x = 42; 
class VarDeclStmt(
    val type: TypeRef, val name: String, val initializer: Expr, loc: SourceLocation
) : Stmt(loc) {
    var register: Int = -1           // Set by register allocator
}

//  Assignment: target = value; or target += value; 
class AssignStmt(
    val target: Expr,                // IdentifierExpr, FieldAccessExpr, or IndexAccessExpr
    val op: AssignOp, val value: Expr, loc: SourceLocation
) : Stmt(loc)

//  Increment/Decrement: i++; i--; 
class IncrementStmt(
    val target: Expr, val op: PostfixOp, loc: SourceLocation
) : Stmt(loc)

//  If/Else 
class IfStmt(
    val condition: Expr, val thenBlock: Block,
    val elseIfs: List<ElseIf>, val elseBlock: Block?,  // null if no else
    loc: SourceLocation
) : Stmt(loc) {
    data class ElseIf(val condition: Expr, val body: Block, val loc: SourceLocation)
}

//  While Loop 
class WhileStmt(
    val condition: Expr, val body: Block, loc: SourceLocation
) : Stmt(loc)

//  For Loop: for (init; condition; update) { body } 
class ForStmt(
    val init: Stmt?,                 // VarDeclStmt or AssignStmt, or null
    val condition: Expr?,            // null = infinite loop
    val update: Stmt?,               // AssignStmt or IncrementStmt, or null
    val body: Block, loc: SourceLocation
) : Stmt(loc)

//  ForEach: foreach (Type name in iterable) { body } 
class ForEachStmt(
    val elementType: TypeRef, val elementName: String,
    val iterable: Expr, val body: Block, loc: SourceLocation
) : Stmt(loc) {
    var elementRegister: Int = -1    // Register for the loop variable
}

//  Return 
class ReturnStmt(val value: Expr?, loc: SourceLocation) : Stmt(loc)  // null for bare `return;`

//  Yield 
class YieldStmt(val value: Expr, loc: SourceLocation) : Stmt(loc)

//  Break / Continue (no extra fields) 
class BreakStmt(loc: SourceLocation) : Stmt(loc)
class ContinueStmt(loc: SourceLocation) : Stmt(loc)

//  Throw 
class ThrowStmt(val value: Expr, loc: SourceLocation) : Stmt(loc)

//  Try/Catch 
class TryCatchStmt(
    val tryBlock: Block, val catchClauses: List<CatchClause>, loc: SourceLocation
) : Stmt(loc)

data class CatchClause(
    val exceptionType: String?,      // null for catch-all
    val variableName: String,
    val body: Block,
    val loc: SourceLocation
)

//  Expression Statement: func(); 
class ExprStmt(val expression: Expr, loc: SourceLocation) : Stmt(loc)

//  Block: { stmt; stmt; } 
class Block(
    val statements: List<Stmt>, loc: SourceLocation
) : Stmt(loc) {
    var scopeDepth: Int = -1         // Set by semantic analyzer
}

//  Syntax Error Placeholder 
class ErrorStmt(loc: SourceLocation) : Stmt(loc)
```
 
## Declarations

```kotlin
sealed class Decl(val loc: SourceLocation)
```

### Concrete Declaration Nodes

```kotlin
//  Type Definition: type Point { int x; int y; } 
class TypeDef(
    val name: String, val fields: List<FieldDecl>, loc: SourceLocation
) : Decl(loc)

data class FieldDecl(val type: TypeRef, val name: String, val loc: SourceLocation)

//  Function Definition 
class FuncDef(
    val returnType: TypeRef, val name: String,
    val params: List<Param>, val body: Block, loc: SourceLocation
) : Decl(loc) {
    // Mutable codegen annotations
    var maxPrimitiveRegisters: Int = 0   // Frame size for pMem
    var maxReferenceRegisters: Int = 0   // Frame size for rMem
}

//  Main Entry Point 
class MainDef(
    val params: List<Param>, val body: Block, loc: SourceLocation
) : Decl(loc) {
    // Same codegen annotations as FuncDef
    var maxPrimitiveRegisters: Int = 0
    var maxReferenceRegisters: Int = 0
}

//  Global Variable 
class GlobalVarDecl(
    val type: TypeRef, val name: String,
    val initializer: Expr?,          // null if uninitialized (uses type default)
    loc: SourceLocation
) : Decl(loc) {
    var globalSlot: Int = -1         // Index in global memory (offset by module's globalBaseOffset)
}

//  Import Declaration: import "path.nox" as namespace; 
class ImportDecl(
    val path: String,                // Raw path from source: "utils/helpers.nox"
    val namespace: String,           // User-chosen namespace: "helpers"
    loc: SourceLocation
) : Decl(loc) {
    var resolvedPath: String? = null  // Set by import resolver: absolute path
}

//  Function Parameter 
data class Param(
    val type: TypeRef,
    val name: String,
    val defaultValue: Expr?,         // null if required
    val isVarargs: Boolean,
    val loc: SourceLocation
)

//  Syntax Error Placeholder 
class ErrorDecl(loc: SourceLocation) : Decl(loc)
```
 
## Program (Root Node)

```kotlin
class Program(
    val fileName: String,
    val headers: List<Header>,
    val imports: List<ImportDecl>,        // NEW: import declarations
    val declarations: List<Decl>
) {
    // Convenience accessors (populated during AST construction)
    val typesByName = mutableMapOf<String, TypeDef>()
    val functionsByName = mutableMapOf<String, FuncDef>()
    var main: MainDef? = null
    val globals = mutableListOf<GlobalVarDecl>()
}

data class Header(val key: String, val value: String, val loc: SourceLocation)
// key = "name", "description", "author", "permission"
// (the "@tool:" prefix is stripped during AST construction)
```
 
## Desugaring During AST Construction

The `ASTBuilder` (ANTLR visitor that converts parse tree -> AST) performs these simplifications:

| Parse Tree | AST | Reason |
|---|---|---|
| `ParenExpr(expr)` | just `expr` | Grouping is syntactic, not semantic |
| `HEADER_KEY` `"@tool:name"` | `Header("name", ...)` | Strip prefix during construction |
| Escape sequences in `StringLiteral` | Resolved `String` value | `"hello\n"` -> `"hello"` + newline |
| `TEMPLATE_TEXT` + `TEMPLATE_EXPR_OPEN` | `TemplateLiteralExpr` with parts | Lexer tokens -> structured parts list |
| Invalid / Unexpected Syntax | `ErrorExpr` / `ErrorStmt` / `ErrorDecl` | Prevent compiler crashes on partial parse trees |

**Not** desugared (kept as first-class nodes):
- `foreach` is kept for clear error messages and potential future iterator optimization
- `i++`/`i--` is kept as `IncrementStmt` / `PostfixExpr` for direct `IINC`/`IDEC` emission
- `i += N` is kept as `AssignStmt` with `AssignOp.ADD_ASSIGN` for direct `IINCN` emission
- Template literals is kept as `TemplateLiteralExpr` for potential `StringBuilder` optimization
 
## Next Steps

- [**Semantic Analysis**](semantic-analysis.md)
- [**Code Generation**](codegen.md)
- [**Grammar**](../../src/main/antlr4/NoxParser.g4)
