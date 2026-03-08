# Semantic Analysis

Semantic analysis transforms a raw AST (syntactically valid) into an **annotated AST** (semantically valid). After this phase completes, every `Expr` node has its `.resolvedType` set, every `IdentifierExpr` has its `.resolvedSymbol` set, and every `MethodCallExpr` knows exactly what it calls.

```
Raw AST (from ASTBuilder)
    │
    │  Phase 0: Import Resolution
    │  • Resolve import paths relative to importing file
    │  • Validate namespace names (no Tier 0/1 collisions)
    │  • Recursively parse + analyze imported files (cycle detection)
    │  • Register import namespaces (functions + types)
    │  • Assign per-module globalSlot offsets
    │
    │  Pass 1: Declaration Collection
    │  • Register all type names (enables forward references)
    │  • Register all function signatures
    │  • Register global variables
    │
    │  Pass 2: Type Resolution
    │  • Resolve struct field types (all names now known)
    │  • Resolve every expression's type in every function body
    │  • Resolve identifiers into symbols
    │  • Resolve method calls into resolution kind (including import namespaces)
    │  • Validate assignments, null safety, lvalues
    │
    │  Pass 3: Control Flow Validation
    │  • All non-void paths return a value
    │  • break/continue only inside loops
    │  • yield only inside main
    │  • Dead code detection
    ▼
Annotated AST (ready for code generation)
```

**Error strategy:** Errors are **collected, not thrown**. Each pass runs to completion, reporting as many errors as possible. If Pass 2 has errors, Pass 3 still runs (where feasible) to catch additional issues. Codegen only runs if zero errors were found.
 
## Phase 0: Import Resolution

Before declarations are collected, all `import` statements are resolved. This phase runs **depth-first**, if A imports B and B imports C, C is fully resolved before B, and B before A.

### Resolution Process

```kotlin
class ImportResolver(
    private val basePath: Path,
    private val processingSet: MutableSet<Path> = mutableSetOf()  // Cycle detection
) {
    val modules = mutableListOf<ResolvedModule>()
    private var nextGlobalOffset = 0

    fun resolveImports(program: Program) {
        for (imp in program.imports) {
            // 1. Resolve path relative to importing file's directory
            val resolved = basePath.parent.resolve(imp.path).normalize()
            imp.resolvedPath = resolved.toString()

            // 2. Validate namespace
            validateNamespace(imp.namespace, imp.loc)

            // 3. Cycle detection
            if (resolved in processingSet) {
                error(imp.loc, "Circular import detected: ${imp.path}")
            }
            processingSet.add(resolved)

            // 4. Parse + recursively resolve imported file
            val importedProgram = parseFile(resolved)
            resolveImports(importedProgram)  // Recursive: depth-first

            // 5. Run semantic analysis on imported program
            val analyzer = SemanticAnalyzer()
            analyzer.analyze(importedProgram)

            // 6. Register module with global offset
            val moduleGlobals = importedProgram.globals.size
            modules.add(ResolvedModule(
                namespace = imp.namespace,
                sourcePath = resolved.toString(),
                program = importedProgram,
                globalBaseOffset = nextGlobalOffset,
                globalCount = moduleGlobals
            ))
            nextGlobalOffset += moduleGlobals

            processingSet.remove(resolved)
        }
    }
}
```

### Namespace Collision Validation

```kotlin
// Built-in namespaces (Tier 0), always reserved
val builtinNamespaces = setOf("Math", "File", "Http", "Date", "Json") // Auto populated from built-in plugins

// Tier 1 external plugin namespaces, registered at runtime startup
val externalPluginNamespaces: Set<String>   // From loaded plugins (.so/.dylib or manual registration)

fun validateNamespace(name: String, loc: SourceLocation) {
    when {
        name in builtinNamespaces ->
            error(loc, "Import namespace '$name' clashes with built-in namespace")
        name in externalPluginNamespaces ->
            error(loc, "Import namespace '$name' clashes with external plugin namespace")
        name in importedNamespaces ->
            error(loc, "Duplicate import namespace '$name'")
    }
}
```

### What Gets Exported

| Element | Exported? | Rationale |
|---|---|---|
| Functions | Yes | Primary purpose of imports |
| Type definitions | Yes | Shared structs across files |
| Global variables | Private | Module-scoped, not accessible by name |
| `main()` | Hidden | Allows standalone testing, not exported |
| `@tool` headers | Ignored | Only relevant when file is run directly |
 
## Symbols & Scopes

### Symbol Types

```kotlin
sealed interface Symbol {
    val name: String
    val type: TypeRef
}

// Local variable declared in a function body
data class VarSymbol(override val name: String, override val type: TypeRef, val scopeDepth: Int) : Symbol

// Function parameter
data class ParamSymbol(override val name: String, override val type: TypeRef, val defaultValue: Expr?) : Symbol

// Global variable (declared outside functions)
data class GlobalSymbol(override val name: String, override val type: TypeRef, val globalSlot: Int) : Symbol

// Function (user-defined or main)
data class FuncSymbol(
    override val name: String,
    val returnType: TypeRef,
    val params: List<ParamSymbol>,
    val astNode: FuncDef           // Back-reference for codegen
) : Symbol {
    override val type: TypeRef get() = returnType
}

// Struct type definition
data class TypeSymbol(
    override val name: String,
    val fields: LinkedHashMap<String, TypeRef>,  // Ordered
    val astNode: TypeDef
) : Symbol {
    override val type: TypeRef get() = TypeRef(name)
}
```

### Symbol Table (Scope Chain)

```kotlin
class SymbolTable(
    private val parent: SymbolTable? = null,  // null for global scope
    val depth: Int = 0
) {
    private val symbols = mutableMapOf<String, Symbol>()

    // Look up a name, walking up the scope chain
    fun lookup(name: String): Symbol? =
        symbols[name] ?: parent?.lookup(name)

    // Define a symbol in the current scope only
    fun define(name: String, symbol: Symbol) {
        if (name in symbols) {
            // Error: duplicate definition in same scope
        }
        symbols[name] = symbol
    }

    // Create a child scope (entering a block)
    fun child(): SymbolTable = SymbolTable(parent = this, depth = depth + 1)
}
```

### Scope Lifecycle

```
Global Scope
├── TypeSymbol("ApiConfig")
├── TypeSymbol("Point")
├── FuncSymbol("calculateDistance")
├── GlobalSymbol("counter")
│
└── FuncDef "processData"
    └── Function Scope
        ├── ParamSymbol("url")
        ├── ParamSymbol("timeout")
        │
        ├── Block (if-body)
        │   └── Block Scope
        │       ├── VarSymbol("data")
        │       └── VarSymbol("result")
        │
        └── Block (for-body)
            └── Block Scope
                └── VarSymbol("i")
```

Each `{...}` block creates a new scope. Variables declared in inner scopes **shadow** outer ones. The `lookup` method walks the chain from innermost to outermost.
 
## Pass 1: Declaration Collection

**Goal:** Register all top-level names so that forward references work.

```kotlin
fun collectDeclarations(program: Program) {
    for (decl in program.declarations) {
        when (decl) {
            is TypeDef -> {
                // Register name only (fields resolved in Pass 2)
                globalScope.define(decl.name, TypeSymbol(decl.name, linkedMapOf(), decl))
            }
            is FuncDef -> {
                // Register signature (param types validated in Pass 2)
                val params = decl.params.map { p -> ParamSymbol(p.name, p.type, p.defaultValue) }
                globalScope.define(decl.name, FuncSymbol(decl.name, decl.returnType, params, decl))
            }
            is MainDef -> {
                // Special: main always returns string
                program.main = decl
            }
            is GlobalVarDecl -> {
                globalScope.define(decl.name, GlobalSymbol(decl.name, decl.type, globalSlotCounter++))
            }
        }
    }
}
```

**Why forward references matter:**

```c
// TreeNode references itself
type TreeNode {
    string value;
    TreeNode left;      // Forward reference to own type
    TreeNode right;
}

// Mutual function calls
boolean isEven(int n) { return n == 0 || isOdd(n - 1); }
boolean isOdd(int n)  { return n != 0 && isEven(n - 1); }
```

By registering all names in Pass 1, Pass 2 can resolve any type or function reference regardless of declaration order.
 
## Pass 2: Type Resolution

The core pass. Walks every function body and resolves types for all expressions.

### Entry Point

```kotlin
fun resolveTypes(program: Program) {
    // 2a: Resolve struct field types (all type names now known)
    for (typeDef in program.typesByName.values) {
        resolveStructFields(typeDef)
    }

    // 2b: Resolve each function body
    for (func in program.functionsByName.values) {
        val funcScope = globalScope.child()
        registerParams(funcScope, func.params)
        resolveBlock(funcScope, func.body, func.returnType)
    }

    // 2c: Resolve main body
    program.main?.let { main ->
        val mainScope = globalScope.child()
        registerParams(mainScope, main.params)
        resolveBlock(mainScope, main.body, TypeRef.STRING)
    }
}
```

### Expression Type Resolution

```kotlin
fun resolveExpr(scope: SymbolTable, expr: Expr): TypeRef? {
    val type = when (expr) {
        // Literals
        is IntLiteralExpr        -> TypeRef.INT
        is DoubleLiteralExpr     -> TypeRef.DOUBLE
        is BoolLiteralExpr       -> TypeRef.BOOLEAN
        is StringLiteralExpr     -> TypeRef.STRING
        is NullLiteralExpr       -> null  // Type inferred from context
        is TemplateLiteralExpr   -> resolveTemplate(scope, expr)

        // Composites
        is ArrayLiteralExpr      -> resolveArrayLiteral(scope, expr)
        is StructLiteralExpr     -> resolveStructLiteral(scope, expr)

        // References
        is IdentifierExpr        -> resolveIdentifier(scope, expr)
        is FieldAccessExpr       -> resolveFieldAccess(scope, expr)
        is IndexAccessExpr       -> resolveIndexAccess(scope, expr)

        // Calls
        is FuncCallExpr          -> resolveFuncCall(scope, expr)
        is MethodCallExpr        -> resolveMethodCall(scope, expr)

        // Operators
        is BinaryExpr            -> resolveBinary(scope, expr)
        is UnaryExpr             -> resolveUnary(scope, expr)
        is PostfixExpr           -> resolvePostfix(scope, expr)
        is CastExpr              -> resolveCast(scope, expr)
    }

    expr.resolvedType = type
    return type
}
```
 
### Binary Expression Rules

```kotlin
fun resolveBinary(scope: SymbolTable, expr: BinaryExpr): TypeRef {
    val left = resolveExpr(scope, expr.left)!!
    val right = resolveExpr(scope, expr.right)!!

    return when (expr.op) {
        // Arithmetic: int×int->int, double×double->double, int×double->double
        BinaryOp.ADD, BinaryOp.SUB, BinaryOp.MUL, BinaryOp.DIV, BinaryOp.MOD -> when {
            left == TypeRef.INT && right == TypeRef.INT -> TypeRef.INT
            isNumeric(left) && isNumeric(right)         -> TypeRef.DOUBLE  // Implicit widening
            // String concatenation (ADD only)
            expr.op == BinaryOp.ADD && left == TypeRef.STRING && right == TypeRef.STRING -> TypeRef.STRING
            else -> error(expr, "Operator '%s' requires numeric operands, got %s and %s",
                          expr.op, left, right)
        }

        // Comparison: numeric×numeric->boolean
        BinaryOp.LT, BinaryOp.LE, BinaryOp.GT, BinaryOp.GE -> {
            if (!isNumeric(left) || !isNumeric(right))
                error(expr, "Comparison requires numeric operands")
            TypeRef.BOOLEAN
        }

        // Equality: same type or null comparison->boolean
        BinaryOp.EQ, BinaryOp.NE -> {
            if (!isComparable(left, right))
                error(expr, "Cannot compare %s with %s", left, right)
            TypeRef.BOOLEAN
        }

        // Logical: boolean×boolean->boolean
        BinaryOp.AND, BinaryOp.OR -> {
            requireType(expr.left, TypeRef.BOOLEAN)
            requireType(expr.right, TypeRef.BOOLEAN)
            TypeRef.BOOLEAN
        }

        // Bitwise: int×int->int
        BinaryOp.BIT_AND, BinaryOp.BIT_OR, BinaryOp.BIT_XOR -> {
            requireType(expr.left, TypeRef.INT)
            requireType(expr.right, TypeRef.INT)
            TypeRef.INT
        }

        // Shift: int×int->int
        BinaryOp.SHL, BinaryOp.SHR, BinaryOp.USHR -> {
            requireType(expr.left, TypeRef.INT)
            requireType(expr.right, TypeRef.INT)
            TypeRef.INT
        }
    }
}
```

### Numeric Widening Rule

The only implicit conversion: `int` -> `double` (widening, lossless).

```
int + int       -> int       (IADD)
int + double    -> double    (DADD, with implicit int->double on left)
double + int    -> double    (DADD, with implicit int->double on right)
double + double -> double    (DADD)
```

The compiler inserts a widening conversion node where needed. All other conversions require explicit `.toInt()`, `.toDouble()`, `.toString()` calls.
 
### Method Call Resolution (The UFCS Chain)

When the semantic analyzer sees `target.method(args)`, it must determine **what kind of call** this actually is. This is the most complex resolution in the compiler.

```kotlin
fun resolveMethodCall(scope: SymbolTable, call: MethodCallExpr): TypeRef {
    val targetType = resolveExpr(scope, call.target)!!

    // Step 1: Namespace function
    // If target is an unresolved identifier matching a namespace:
    // - Built-in: Math, File, Http, Date (Tier 0)
    // - Import: user-defined via `import ... as name` (Tier 2)
    val target = call.target
    if (target is IdentifierExpr && isNamespace(target.name)) {
        val func = lookupNamespaceFunc(target.name, call.methodName)
        if (func != null) {
            call.resolution = MethodCallExpr.Resolution.NAMESPACE
            call.resolvedTarget = func
            validateArgs(call, func.params)
            return func.returnType
        }
    }

    // Step 2: Built-in type property
    // .length on string and arrays (property, not method)
    // Handled by FieldAccessExpr, not here but .size() on json is a method

    // Step 3: Built-in type method
    // string.upper(), string.lower(), string.contains(), string.split()
    // array.push(), array.pop()
    // json.getString(), json.getInt(), json.has(), json.keys(), json.size()
    val builtin = lookupBuiltinMethod(targetType, call.methodName)
    if (builtin != null) {
        call.resolution = MethodCallExpr.Resolution.TYPE_BOUND
        call.resolvedTarget = builtin
        validateArgs(call, builtin.params)
        return builtin.returnType
    }

    // Step 4: @NoxTypeMethod (plugin-registered type-bound methods)
    // int.toDouble(), int.toString(), string.toInt(default), etc.
    val typeMethod = lookupTypeMethod(targetType, call.methodName)
    if (typeMethod != null) {
        call.resolution = MethodCallExpr.Resolution.TYPE_BOUND
        call.resolvedTarget = typeMethod
        validateArgs(call, typeMethod.params)
        return typeMethod.returnType
    }

    // Step 5: UFCS
    // Look for a function whose first parameter matches targetType
    // target.foo(a, b) to foo(target, a, b)
    val ufcsFunc = lookupUFCS(scope, call.methodName, targetType)
    if (ufcsFunc != null) {
        call.resolution = MethodCallExpr.Resolution.UFCS
        call.resolvedTarget = ufcsFunc
        // Validate remaining args (first arg is the target)
        validateUFCSArgs(call, ufcsFunc)
        return ufcsFunc.returnType
    }

    // Step 6: Error
    return error(call, "No method '%s' found on type '%s'", call.methodName, targetType)
}
```

#### Resolution Priority Summary

| Priority | Kind | Example | How It Resolves |
|---|---|---|---|
| 1 | **Namespace function** | `Math.sqrt(x)`, `helpers.format(d)` | Built-in or import namespace, function is registered |
| 2 | **Built-in type method** | `text.upper()` | `string` has built-in `upper()` |
| 3 | **Plugin type method** | `x.toDouble()` | `int` has `@NoxTypeMethod` `toDouble` registered |
| 4 | **UFCS** | `point.distance(other)` | Global function `distance(Point p, Point other)` exists |
| 5 | **Error** | `x.nonexistent()` | No match in any category |

#### UFCS Example

```c
// Global function
double calculateDistance(Point p, Point other) {
    int dx = p.x - other.x;
    int dy = p.y - other.y;
    return Math.sqrt(dx * dx + dy * dy);
}

// Both of these call the same function:
double d1 = calculateDistance(origin, target);   // FuncCallExpr
double d2 = origin.calculateDistance(target);     // MethodCallExpr UFCS
```

The semantic analyzer sees `origin.calculateDistance(target)`:
1. `origin` is type `Point` -> not a namespace
2. `Point` has no built-in `calculateDistance`
3. No `@NoxTypeMethod` for `Point.calculateDistance`
4. **UFCS match:** global function `calculateDistance(Point, Point)` exists ✓
5. Rewrite: `target.foo(args)` to `foo(target, args)`
 
### Field Access Resolution

```kotlin
fun resolveFieldAccess(scope: SymbolTable, expr: FieldAccessExpr): TypeRef {
    val targetType = resolveExpr(scope, expr.target)!!

    // json.someField -> dynamic property access (type is json)
    if (targetType == TypeRef.JSON) return TypeRef.JSON

    // struct.field -> look up field in struct definition
    val structType = lookupType(targetType.name)
    if (structType != null) {
        val fieldType = structType.fields[expr.fieldName]
        if (fieldType != null) return fieldType
        return error(expr, "Struct '%s' has no field '%s'", targetType.name, expr.fieldName)
    }

    // string.length, array.length -> built-in properties
    if (expr.fieldName == "length") {
        if (targetType == TypeRef.STRING || targetType.isArray)
            return TypeRef.INT
    }

    return error(expr, "Cannot access field '%s' on type '%s'", expr.fieldName, targetType)
}
```
 
### Null Safety Checks

Static null checking is **limited and conservative**, it prevents definite errors at compile time but does not track null flow.

```kotlin
// Assignment null check
fun checkAssignment(targetType: TypeRef, value: Expr, loc: SourceLocation) {
    if (value is NullLiteralExpr) {
        if (!targetType.isNullable()) {
            error(loc, "Cannot assign null to non-nullable type '%s'", targetType)
            // Suggest: use a default value instead
        }
    }
}

// Types that are nullable
//   string    nullable
//   json      nullable
//   MyStruct  nullable (reference type)
//   int[]     nullable (reference type)
//   int       not nullable
//   double    not nullable
//   boolean   not nullable
```

**Runtime enforcement:** Accessing `.field` or `.method()` on a `null` reference throws `NullAccessError` at runtime. The compiler does **not** perform null flow analysis (no warning on "might be null"). This is a deliberate simplicity choice, null-safe accessors (`?.`) can be added in future if the need arises.
 
### Cast Validation

```kotlin
fun resolveCast(scope: SymbolTable, expr: CastExpr): TypeRef {
    val sourceType = resolveExpr(scope, expr.operand)

    // Only json -> struct casts are allowed
    if (sourceType != TypeRef.JSON) {
        error(expr, "Cannot cast from '%s'. Only 'json' can be cast", sourceType)
    }

    // Target must be a known struct type
    if (lookupType(expr.targetType.name) == null) {
        error(expr, "Unknown cast target type '%s'", expr.targetType.name)
    }

    // Actual validation happens at runtime (CastError if fields mismatch)
    return expr.targetType
}
```
 
### Struct Literal Validation

```kotlin
fun resolveStructLiteral(scope: SymbolTable, expr: StructLiteralExpr): TypeRef {
    // Struct type is inferred from assignment context
    // The caller (resolveVarDecl or resolveAssign) sets expr.structType
    if (expr.structType == null) {
        return error(expr, "Cannot infer struct type from context. Use an explicit type.")
    }

    // json literal: {key: value, ...} with no struct validation
    // json is dynamic so any field names and value types are valid
    if (expr.structType == TypeRef.JSON) {
        for (init in expr.fields) {
            resolveExpr(scope, init.value)
        }
        return TypeRef.JSON
    }

    val typeSym = lookupType(expr.structType!!.name)!!

    // Check for unknown/mistyped fields
    val provided = mutableSetOf<String>()
    for (init in expr.fields) {
        provided.add(init.name)
        val expectedFieldType = typeSym.fields[init.name]
        if (expectedFieldType == null) {
            error(init.loc, "Unknown field '%s' in struct '%s'", init.name, typeSym.name)
            continue
        }
        val actualType = resolveExpr(scope, init.value)
        checkAssignable(expectedFieldType, actualType, init.loc)
    }

    // Check for missing required fields
    for (fieldName in typeSym.fields.keys) {
        if (fieldName !in provided) {
            error(expr, "Missing field '%s' in struct '%s'", fieldName, typeSym.name)
        }
    }

    return expr.structType!!
}
```
 
### Statement Validation

```kotlin
fun resolveStmt(scope: SymbolTable, stmt: Stmt, expectedReturn: TypeRef) {
    when (stmt) {
        is VarDeclStmt -> {
            val initType = resolveExpr(scope, stmt.initializer)
            checkAssignable(stmt.type, initType, stmt.loc)
            checkAssignment(stmt.type, stmt.initializer, stmt.loc)  // Null check
            scope.define(stmt.name, VarSymbol(stmt.name, stmt.type, scope.depth))
        }

        is AssignStmt -> {
            validateLValue(stmt.target)                              // Must be assignable
            val targetType = resolveExpr(scope, stmt.target)!!
            val valueType = resolveExpr(scope, stmt.value)!!
            if (stmt.op == AssignOp.ASSIGN) {
                checkAssignable(targetType, valueType, stmt.loc)
            } else {
                // Compound: +=, -=, *=, /=, %=
                checkCompoundAssign(targetType, valueType, stmt.op, stmt.loc)
            }
        }

        is IncrementStmt -> {
            validateLValue(stmt.target)
            val type = resolveExpr(scope, stmt.target)!!
            if (!isNumeric(type))
                error(stmt, "Cannot increment/decrement non-numeric type '%s'", type)
        }

        is IfStmt -> {
            requireType(stmt.condition, TypeRef.BOOLEAN)
            resolveBlock(scope.child(), stmt.thenBlock, expectedReturn)
            for (elseIf in stmt.elseIfs) {
                requireType(elseIf.condition, TypeRef.BOOLEAN)
                resolveBlock(scope.child(), elseIf.body, expectedReturn)
            }
            stmt.elseBlock?.let { resolveBlock(scope.child(), it, expectedReturn) }
        }

        is WhileStmt -> {
            requireType(stmt.condition, TypeRef.BOOLEAN)
            resolveBlock(scope.child(), stmt.body, expectedReturn)
        }

        is ForStmt -> {
            val forScope = scope.child()
            stmt.init?.let { resolveStmt(forScope, it, expectedReturn) }
            stmt.condition?.let { requireType(it, TypeRef.BOOLEAN) }
            stmt.update?.let { resolveStmt(forScope, it, expectedReturn) }
            resolveBlock(forScope, stmt.body, expectedReturn)
        }

        is ForEachStmt -> {
            val iterType = resolveExpr(scope, stmt.iterable)!!
            if (!iterType.isArray) error(stmt, "foreach requires an array, got '%s'", iterType)
            val elemType = TypeRef(iterType.name)
            checkAssignable(stmt.elementType, elemType, stmt.loc)
            val feScope = scope.child()
            feScope.define(stmt.elementName, VarSymbol(stmt.elementName, stmt.elementType, feScope.depth))
            resolveBlock(feScope, stmt.body, expectedReturn)
        }

        is ReturnStmt -> {
            if (stmt.value != null) {
                val returnType = resolveExpr(scope, stmt.value)!!
                checkAssignable(expectedReturn, returnType, stmt.loc)
            } else if (expectedReturn != TypeRef.VOID) {
                error(stmt, "Missing return value. Expected '%s'", expectedReturn)
            }
        }

        is YieldStmt -> {
            resolveExpr(scope, stmt.value)    // Any type can be yielded, converted to string by the runtime
        }

        is ThrowStmt -> {
            val type = resolveExpr(scope, stmt.value)!!
            if (type != TypeRef.STRING)
                error(stmt, "throw requires a string message, got '%s'", type)
        }

        is TryCatchStmt -> {
            resolveBlock(scope.child(), stmt.tryBlock, expectedReturn)
            for (cc in stmt.catchClauses) {
                val catchScope = scope.child()
                catchScope.define(cc.variableName,
                    VarSymbol(cc.variableName, TypeRef.STRING, catchScope.depth))
                resolveBlock(catchScope, cc.body, expectedReturn)
            }
        }

        is ExprStmt     -> resolveExpr(scope, stmt.expression)
        is Block        -> resolveBlock(scope.child(), stmt, expectedReturn)
        is BreakStmt, is ContinueStmt -> { } // Validated in Pass 3
    }
}
```

### LValue Validation

Only certain expressions are valid assignment targets:

```kotlin
fun validateLValue(target: Expr) {
    when (target) {
        is IdentifierExpr  -> { }  // Variable
        is FieldAccessExpr -> { }  // Struct field or json property
        is IndexAccessExpr -> { }  // Array element or json index
        else -> error(target, "Invalid assignment target. Expected a variable, field, or index")
    }
}
```
 
## Pass 3: Control Flow Validation

### Return Path Analysis

Every non-void function must return a value on **all paths**:

```kotlin
fun allPathsReturn(block: Block): Boolean {
    for (stmt in block.statements) {
        if (definitelyReturns(stmt)) return true
    }
    return false
}

fun definitelyReturns(stmt: Stmt): Boolean = when (stmt) {
    is ReturnStmt -> true
    is ThrowStmt  -> true
    is IfStmt     -> {
        if (stmt.elseBlock == null) false  // No else = might not return
        else {
            val thenReturns = allPathsReturn(stmt.thenBlock)
            val elseReturns = allPathsReturn(stmt.elseBlock!!)
            val elseIfsReturn = stmt.elseIfs.all { allPathsReturn(it.body) }
            thenReturns && elseReturns && elseIfsReturn
        }
    }
    is TryCatchStmt -> {
        allPathsReturn(stmt.tryBlock) &&
            stmt.catchClauses.all { allPathsReturn(it.body) }
    }
    is Block -> allPathsReturn(stmt)
    else -> false
}
```

### Loop Context Tracking

```kotlin
class ControlFlowValidator {
    private var loopDepth = 0

    fun validate(stmt: Stmt) {
        when (stmt) {
            is BreakStmt -> {
                if (loopDepth == 0) error(stmt, "'break' can only appear inside a loop")
            }
            is ContinueStmt -> {
                if (loopDepth == 0) error(stmt, "'continue' can only appear inside a loop")
            }
            is WhileStmt   -> { loopDepth++; validate(stmt.body); loopDepth-- }
            is ForStmt     -> { loopDepth++; validate(stmt.body); loopDepth-- }
            is ForEachStmt -> { loopDepth++; validate(stmt.body); loopDepth-- }
            // Recurse into blocks, if-else, try-catch...
            else -> { /* recurse into child statements */ }
        }
    }
}
```

### Dead Code Detection

```kotlin
fun checkDeadCode(block: Block) {
    var terminated = false
    for (stmt in block.statements) {
        if (terminated) {
            warning(stmt, "Unreachable code after return/throw/break")
            break
        }
        if (stmt is ReturnStmt || stmt is ThrowStmt ||
            stmt is BreakStmt  || stmt is ContinueStmt) {
            terminated = true
        }
    }
}
```
 
## Type Assignability Rules

```kotlin
fun isAssignable(target: TypeRef, value: TypeRef?): Boolean {
    // Same type always ok
    if (target == value) return true

    // int -> double (implicit widening)
    if (target == TypeRef.DOUBLE && value == TypeRef.INT) return true

    // null -> any nullable type
    if (value == null) return target.isNullable()

    // struct -> json (implicit upcast), supports both scalar and array forms
    // e.g. Config -> json, Config[] -> json[]
    if (target.name == "json" && !isBuiltinType(value.name) && target.isArray == value.isArray) return true

    // array element type must match exactly
    if (target.isArray && value!!.isArray)
        return target.name == value.name

    return false
}
```

### Assignability Matrix

| Value ↓ \ Target -> | `int` | `double` | `boolean` | `string` | `json` | `Struct` | `T[]` |
|---|---|---|---|---|---|---|---|
| **`int`** | ✓ | ✓ implicit | ✗ | ✗ | ✗ | ✗ | ✗ |
| **`double`** | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ |
| **`boolean`** | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ |
| **`string`** | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ |
| **`json`** | ✗ | ✗ | ✗ | ✗ | ✓ | `as` cast | ✗ |
| **`Struct`** | ✗ | ✗ | ✗ | ✗ | ✓ implicit | same type | ✗ |
| **`Struct[]`** | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ json[] |
| **`T[]`** | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | same `T` |
| **`null`** | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ |
 
## Error Reporting

### Error Format

```kotlin
data class SemanticError(
    val location: SourceLocation,
    val message: String,
    val suggestion: String? = null   // Optional, "Did you mean ...?"
)
```

### Example Error Messages

```
error[E001]: Type mismatch in assignment
  --> data_processor.nox:15:12
   |
15 |     int count = "hello";
   |                 ^^^^^^^ expected 'int', found 'string'
   |
   = suggestion: Use `"hello".toInt(0)` to parse the string as an integer.

error[E002]: No method 'upper' found on type 'int'
  --> data_processor.nox:20:18
   |
20 |     string s = count.upper();
   |                      ^^^^^ 'int' has no method 'upper'
   |
   = note: 'upper()' is available on 'string'. Did you mean to call '.toString().upper()'?

error[E003]: Cannot assign null to non-nullable type 'int'
  --> data_processor.nox:25:16
   |
25 |     int x = null;
   |             ^^^^ 'int' is a primitive and cannot be null
   |
   = suggestion: Use a default value like `0` instead.

error[E004]: Missing field 'enable_retries' in struct 'ApiConfig'
  --> config.nox:8:24
   |
 8 |     ApiConfig c = { endpoint: url, timeout_seconds: 30 };
   |                    ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
   |
   = note: 'ApiConfig' requires fields: endpoint, timeout_seconds, enable_retries
```
 
## Complete Pass Summary

| Pass | Input | Output | Errors Detected |
|---|---|---|---|
| **Pass 1** | Raw AST | Global scope populated | Duplicate type/function names |
| **Pass 2** | AST + global scope | Annotated AST (`.resolvedType` set on every `Expr`) | Type mismatches, unknown identifiers, bad assignments, null safety violations, struct field errors, method resolution failures |
| **Pass 3** | Annotated AST | Validated AST | Missing returns, break/continue outside loops, unreachable code |
 
## Next Steps

- [**Code Generation**](codegen.md)
- [**AST Design**](ast.md)
- [**Compiler Overview**](overview.md)
