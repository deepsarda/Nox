# Testing Strategy


### Arithmetic & Operators

| Behavior | Decision | Test Name |
|---|---|---|
| Integer overflow (`MAX + 1`) | Wraps silently (Kotlin `Long` semantics) | `intOverflowWraps` |
| Double division by zero (`1.0 / 0.0`) | Returns `Infinity` (IEEE 754) | `doubleDivByZeroInfinity` |
| Integer division truncation (`5 / 2`) | Returns `2` | `intDivisionTruncates` |
| Modulo with negatives (`-7 % 3`) | Returns `-1` (truncated, JVM behavior) | `moduloNegativeTruncated` |
| Shift ≥ 64 bits (`x << 100`) | Masks to 63 bits (`100 & 63 = 36`) | `shiftMasksSixtyThree` |
| `i++` in expressions | **Forbidden**, only allowed as statement | `postfixInExpressionFails` |
| Chained comparison (`a < b < c`) | Parse error (compares `boolean < int`) | `chainedComparisonTypeError` |

### Null Handling

| Behavior | Decision | Test Name |
|---|---|---|
| `null == null` | `true` | `nullEqualsNull` |
| `null == "hello"` | `false` (safe compare, no throw) | `nullEqualsStringFalse` |
| `null != null` | `false` | `nullNotEqualsNull` |
| `null as Config` | Result is `null` (no validation) | `nullCastIsNull` |
| `null.field` | Throws `NullAccessError` | `nullFieldAccessThrows` |
| `null.method()` | Throws `NullAccessError` | `nullMethodCallThrows` |
| `int x = null` | Compile error | `nullToPrimitiveFails` |
| `string s = null` | OK (reference type) | `nullToStringOk` |

### Conditions & Boolean

| Behavior | Decision | Test Name |
|---|---|---|
| `if (0)` | Compile error, only `boolean` | `nonBoolConditionFails` |
| `if ("")` | Compile error, only `boolean` | `stringConditionFails` |
| `if (null)` | Compile error, only `boolean` | `nullConditionFails` |

### Type Casting (`as`)

| Behavior | Decision | Test Name |
|---|---|---|
| Extra fields in json | Ignored (lenient) | `castIgnoresExtraFields` |
| Missing fields in json | Throws `CastError` | `castMissingFieldThrows` |
| Wrong field types | Throws `CastError` | `castWrongFieldTypeThrows` |
| Cast from non-json type | Compile error | `castFromIntFails` |

### JSON Extraction

| Behavior | Decision | Test Name |
|---|---|---|
| `json.getInt("key")` when value is string | Return default if provided, throw `TypeError` otherwise | `jsonGetIntWrongTypeUsesDefault` |
| `json.getString("key")` when value is int | Return default if provided, throw `TypeError` otherwise | `jsonGetStringWrongTypeUsesDefault` |
| `json.getInt("key")` no default, wrong type | Throws `TypeError` | `jsonGetIntWrongTypeThrows` |

### Resource Guards

| Behavior | Decision | Test Name |
|---|---|---|
| Resource errors catchable? | **Catchable but terminal**, catch handler runs, then sandbox terminates | `quotaCatchableButTerminal` |
| Timeout enforcement | Interrupts blocking `SCALL`s via `Thread.interrupt()` | `timeoutInterruptsBlocking` |
| Exactly at limit | Instruction n = limit completes, n+1 triggers error | `exactlyAtLimitCompletes` |

### Structs & Templates

| Behavior | Decision | Test Name |
|---|---|---|
| Empty struct `type Empty {}` | **Forbidden** throws compile error | `emptyStructForbidden` |
| Nested templates `` `${`inner`}` `` | **Forbidden** throws compile error | `nestedTemplateForbidden` |
| Template escape sequences | Yes, `\n`, `\t`, `\\` work in templates | `templateEscapeSequences` |
| Empty template `` ` ` `` | Returns `""` | `emptyTemplateReturnsEmpty` |

### Host API

| Behavior | Decision | Test Name |
|---|---|---|
| No `main()` function | Compile error | `noMainCompileError` |
| `main()` with no return | Returns `""` | `mainNoReturnEmptyString` |
| Multiple executions | Cache `CompiledProgram`, fresh sandbox each time | `compiledProgramReuse` |
| Concurrent executions | Safe as each sandbox has its own memory | `concurrentExecutionIsolation` |
| Permission handler throws | Translated to `SecurityError` | `permissionHandlerExceptionContained` |
| `SecurityError` catchable | Yes, catchable like any error | `securityErrorCatchable` |
| Permission caching | No caching, handler called every time | `permissionCalledEveryTime` |
 
## Test Organization

```
src/test/kotlin/nox/
    compiler/
        LexerTest.kt
        ParserTest.kt
        ASTBuilderTest.kt
        SemanticAnalyzerTest.kt
        CodeGeneratorTest.kt
    vm/
        ArithmeticTest.kt
        ControlFlowTest.kt
        MemoryTest.kt
        ResourceGuardTest.kt
        ExceptionTest.kt
    runtime/
        NoxRuntimeTest.kt
        PermissionTest.kt
        ConcurrencyTest.kt
    ffi/
        PluginTest.kt
        TypeMarshallingTest.kt
    types/
        NullTest.kt
        CastTest.kt
        StructTest.kt
        JsonTest.kt
        ArrayTest.kt
        StringTest.kt
    e2e/
        GoldenTest.kt

src/test/resources/nox/
    programs/           E2E golden test .nox files
    expected/           Expected .noxc disassembly outputs
    errors/             .nox files that SHOULD produce errors
```
 
## Helper Infrastructure

### `NoxTestHarness`

All test classes extend a shared harness that provides convenience methods:

```kotlin
abstract class NoxTestHarness {
    // Compile + Run
    fun run(source: String): String                          // Compile, execute, return final result
    fun run(source: String, args: Map<String, Any?>): String
    fun runFull(source: String): NoxResult                   // Returns yields + result + errors
    fun runExpectingError(source: String): String             // Returns error type string

    // Compile Only
    fun compile(source: String): CompiledProgram
    fun compileToNoxc(source: String): String                // Disassembly output
    fun analyzeErrors(source: String): List<SemanticError>
    fun analyzeWarnings(source: String): List<SemanticWarning>

    // Assertions
    fun assertNoxcContains(noxc: String, substring: String)
    fun assertContainsError(errors: List<SemanticError>, message: String)
    fun assertResultEquals(expected: String, source: String)
    fun assertYieldsEqual(expected: List<String>, source: String)
    fun assertThrows(errorType: String, source: String)
}
```
 
## 1. Compiler Tests

### 1.1 Lexer (`LexerTest.kt`)

```kotlin
// Token recognition
@Test fun integerLiterals()               // 0, 42, 9999999
@Test fun doubleLiterals()                // 0.0, 3.14, 0.5
@Test fun stringLiterals()                // "hello", "", "with \"escape\""
@Test fun stringEscapeSequences()         // \n, \t, \\, \", \uXXXX
@Test fun booleanLiterals()               // true, false
@Test fun nullLiteral()                   // null
@Test fun allKeywords()                   // if, else, while, for, foreach, in, ...
@Test fun asKeyword()                     // as
@Test fun allOperators()                  // +, -, *, /, %, ==, !=, <, <=, >, >=, ...
@Test fun allCompoundOperators()          // +=, -=, *=, /=, %=
@Test fun incrementDecrement()            // ++, --
@Test fun bitwiseOperators()              // &, |, ^, ~, <<, >>, >>>
@Test fun headerKeys()                    // @tool:name, @tool:description

// Template literals
@Test fun templateSimple()                // `hello`
@Test fun templateWithInterpolation()     // `hello ${name}`
@Test fun templateMultipleInterpolations()// `${a} and ${b}`
@Test fun templateNestedBraces()          // `${func({a: 1})}`, inner {} doesn't close ${}
@Test fun templateEmpty()                 // ` ` (backtick, backtick)
@Test fun templateOnlyExpr()              // `${42}`
@Test fun templateEscapes()               // `line1\nline2`

// Comments
@Test fun lineCommentSkipped()            // // comment
@Test fun blockCommentSkipped()           // /* multi-line */
@Test fun blockCommentNested()            // /* outer /* inner */ still comment */

// Edge cases
@Test fun identifiersWithUnderscores()    // my_var, _private, __dunder
@Test fun identifiersWithNumbers()        // var1, x2y
@Test fun maxIntLiteral()                 // 9223372036854775807
@Test fun ellipsis()                      // ...
```

### 1.2 Parser (`ParserTest.kt`)

```kotlin
// Declaration
@Test fun typeDefinition()                // type Point { int x; int y; }
@Test fun typeDefinitionSingleField()     // type Wrapper { string value; }
@Test fun emptyStructForbidden()          // type Empty {} gives parse error
@Test fun functionDefinition()            // int add(int a, int b) { return a + b; }
@Test fun mainDefinition()                // main(string url) { ... }
@Test fun mainNoParams()                  // main() { ... }
@Test fun globalVariable()                // int counter = 0;
@Test fun defaultParameters()             // main(int x = 5, string s = "hi")
@Test fun varargsParameter()              // main(int ...values[])

// Imports
@Test fun importDeclaration()             // import "path.nox" as ns;
@Test fun importMultiple()                // import "a.nox" as a; import "b.nox" as b;
@Test fun importMissingAs()               // import "path.nox"; gives parse error
@Test fun importMissingPath()             // import as ns; parse error
@Test fun importMissingSemicolon()        // import "path.nox" as ns parse error
@Test fun importAfterDeclaration()        // function before import gives parse error
@Test fun importSameNameDifferentFiles()  // import "a.nox" as a; import "b.nox" as b; gives parse error
@Test fun importSameNameSameFiles()       // import "a.nox" as a; import "a.nox" as a; gives parse error
@Test fun importSameFileDifferentNames()  // import "a.nox" as a; import "a.nox" as b; gives parse error
@Test fun importNoAs()                    // import "path.nox"; gives parse error

// Statements
@Test fun variableDeclaration()           // int x = 42;
@Test fun assignment()                    // x = 10;
@Test fun compoundAssignment()            // x += 5; x -= 1; x *= 2; x /= 3; x %= 4;
@Test fun incrementStatement()            // i++; i--;
@Test fun ifStatement()                   // if (cond) { ... }
@Test fun ifElse()                        // if (cond) { ... } else { ... }
@Test fun ifElseIfElse()                  // if ... else if ... else if ... else
@Test fun whileLoop()                     // while (cond) { ... }
@Test fun forLoop()                       // for (int i = 0; i < 10; i++) { ... }
@Test fun forLoopEmptyParts()             // for (;;) { ... }
@Test fun foreachLoop()                   // foreach (T x in arr) { ... }
@Test fun returnWithValue()               // return expr;
@Test fun returnVoid()                    // return;
@Test fun yieldStatement()                // yield expr;
@Test fun breakStatement()                // break;
@Test fun continueStatement()             // continue;
@Test fun throwStatement()                // throw "message";
@Test fun tryCatchSingle()                // try { } catch (E e) { }
@Test fun tryCatchMultiple()              // try { } catch (E1 e) { } catch (E2 e) { }
@Test fun tryCatchAll()                   // try { } catch (err) { }

// Expressions
@Test fun operatorPrecedenceMulOverAdd()  // a + b * c → +(a, *(b, c))
@Test fun operatorPrecedenceAndOverOr()   // a || b && c → ||(a, &&(b, c))
@Test fun operatorPrecedenceBitwise()     // a & b | c → |(&&(a, b), c)
@Test fun operatorPrecedenceShift()       // a + b << c → <<(+(a, b), c)
@Test fun operatorPrecedenceComparison()  // a << b < c → <(<<(a, b), c)
@Test fun castExpression()                // data as Config
@Test fun methodChaining()                // a.b().c().d()
@Test fun indexAccess()                   // arr[0], arr[i + 1]
@Test fun fieldAccess()                   // obj.field
@Test fun funcCall()                      // func(a, b)
@Test fun methodCall()                    // obj.method(a, b)
@Test fun arrayLiteral()                  // [1, 2, 3]
@Test fun arrayLiteralEmpty()             // []
@Test fun structLiteral()                 // { name: "x", age: 1 }
@Test fun nestedParens()                  // ((((1 + 2))))
@Test fun unaryMinus()                    // -42, -x
@Test fun unaryNot()                      // !flag
@Test fun bitwiseNot()                    // ~mask
```

### 1.3 AST Builder (`ASTBuilderTest.kt`)

```kotlin
@Test fun parenExprUnwrapped()            // (x) is IdentifierExpr, not ParenExpr
@Test fun headerPrefixStripped()          // @tool:name is Header("name", ...)
@Test fun stringEscapesResolved()         // "hello\n" is String with actual newline
@Test fun templatePartsCreated()          // `a${b}c` is [Text, Interpolation, Text]
@Test fun sourceLocationsAttached()       // Every node has line/column
@Test fun operatorMapping()               // + is BinaryOp.ADD, etc.
@Test fun castCreated()                   // data as Config is CastExpr
@Test fun structFieldsCollected()         // type P is TypeDef with field list
@Test fun defaultParamsPreserved()        // Param.defaultValue is set
@Test fun programConvenienceMaps()        // typesByName, functionsByName populated
```

### 1.4 Semantic Analyzer (`SemanticAnalyzerTest.kt`)

```kotlin
// Literal types
@Test fun intLiteralResolvesToInt()
@Test fun doubleLiteralResolvesToDouble()
@Test fun boolLiteralResolvesToBoolean()
@Test fun stringLiteralResolvesToString()
@Test fun nullLiteralInferredFromContext()
@Test fun templateResolvesToString()

// Binary operator types
@Test fun intPlusInt()                    // gives int
@Test fun intPlusDouble()                 // gives double (widening)
@Test fun doublePlusDouble()              // gives double
@Test fun stringPlusStringFails()         // gives error (use interpolation)
@Test fun intCompareInt()                 // gives boolean
@Test fun intCompareDouble()              // gives boolean
@Test fun boolAndBool()                   // gives boolean
@Test fun intAndIntFails()                // gives error (AND requires boolean)
@Test fun bitwiseIntInt()                 // gives int
@Test fun bitwiseDoubleFails()            // gives error
@Test fun shiftIntInt()                   // gives int
@Test fun shiftDoubleFails()              // gives error
@Test fun equalitySameType()              // gives boolean
@Test fun equalityNullRef()               // gives boolean (null == string)
@Test fun equalityIntStringFails()        // gives error

// Unary
@Test fun negateInt()                     // -x gives int
@Test fun negateDouble()                  // -x gives double
@Test fun negateBoolFails()               // -true gives error
@Test fun notBool()                       // !flag gives boolean
@Test fun notIntFails()                   // !0 gives error
@Test fun bitwiseNotInt()                 // ~mask gives int
@Test fun bitwiseNotDoubleFails()         // ~1.0 gives error

// Null safety
@Test fun nullToPrimitiveFails()          // int x = null gives error
@Test fun nullToStringOk()                // string x = null is ok
@Test fun nullToJsonOk()                  // json x = null is ok
@Test fun nullToStructOk()                // Config x = null is ok
@Test fun nullToArrayOk()                 // int[] x = null is ok

// Struct validation
@Test fun structComplete()                // All fields provided is ok
@Test fun structMissingFieldFails()       // Missing required field gives error
@Test fun structExtraFieldFails()         // Unknown field gives error
@Test fun structFieldTypeMismatch()       // int field = "hello" gives error
@Test fun structForwardReference()        // Type A uses type B defined later is ok
@Test fun structRecursive()               // TreeNode with TreeNode fields is ok
@Test fun emptyStructForbidden()          // type Empty {} gives error

// Cast
@Test fun castJsonToStructOk()
@Test fun castIntToStructFails()          // Only json castable
@Test fun castToUnknownTypeFails()        // Unknown type name gives error

// UFCS resolution
@Test fun namespaceCallResolved()         // Math.sqrt is through NAMESPACE
@Test fun builtinMethodResolved()         // str.upper is through TYPE_BOUND
@Test fun pluginTypeMethodResolved()      // int.toDouble is through TYPE_BOUND
@Test fun ufcsGlobalFuncResolved()        // point.distance(o) is through UFCS
@Test fun unknownMethodFails()            // x.nonexistent() gives error

// Variable scoping
@Test fun undeclaredVariableFails()
@Test fun variableShadowingAllowed()      // Inner scope shadows outer is ok
@Test fun duplicateInSameScope()          // Two `int x` in same block throws error
@Test fun outOfScopeAccess()              // Use var from exited block throws error

// Control flow
@Test fun missingReturnDetected()         // Non-void func without return on all paths
@Test fun allPathsReturnOk()              // if/else both return ok
@Test fun breakOutsideLoopFails()
@Test fun continueOutsideLoopFails()
@Test fun yieldOutsideMainFails()
@Test fun deadCodeDetected()              // Code after return warning
@Test fun breakInNestedLoopOk()           // break only exits inner loop

// Conditions
@Test fun nonBoolConditionFails()         // if (0) then error
@Test fun stringConditionFails()          // if ("") then error
@Test fun nullConditionFails()            // if (null) then error

// Postfix in expression
@Test fun postfixInExpressionFails()      // arr[i++] gives error
@Test fun postfixAsStatementOk()          // i++; is ok

// Assignability
@Test fun intToDoubleOk()                 // Implicit widening
@Test fun doubleToIntFails()              // Requires .toInt()
@Test fun structToJsonOk()                // Implicit upcast
@Test fun jsonToStructRequiresCast()      // Requires `as`
@Test fun arrayElementTypeMustMatch()     // int[] = string[] throws error

// Import resolution
@Test fun importResolvesPath()            // Resolves relative to importing file
@Test fun importCircularDetected()        // A imports B imports A throws error
@Test fun importNamespaceClashBuiltin()   // import "x.nox" as Math; throws error
@Test fun importNamespaceClashPlugin()    // import "x.nox" as <native_plugin>; throws error
@Test fun importNamespaceDuplicate()      // import "a.nox" as ns; import "b.nox" as ns; throws error
@Test fun importFuncVisible()             // ns.func() resolves correctly
@Test fun importTypeVisible()             // ns.Type is usable in declarations
@Test fun importMainNotExported()         // ns.main() throws error
@Test fun importGlobalsPrivate()          // ns.globalVar throws error
@Test fun importFileNotFound()            // import "nonexistent.nox" as x; throws error
```

### 1.5 Code Generator (`CodeGeneratorTest.kt`)

```kotlin
// Opcode selection
@Test fun intAddEmitsIADD()
@Test fun doubleAddEmitsDADD()
@Test fun intDoubleAddEmitsWidening()     // I2D + DADD
@Test fun incrementEmitsIINC()
@Test fun decrementEmitsIDEC()
@Test fun compoundAddEmitsIINCN()
@Test fun bitwiseAndEmitsBAND()
@Test fun stringEqualsEmitsSEQ()
@Test fun stringNotEqualsEmitsSNE()

// Super-instructions
@Test fun fieldAccessEmitsHACC()
@Test fun fieldMutationEmitsHMOD()
@Test fun methodCallEmitsSCALL()
@Test fun deepPathEmitsAGET_PATH()        // config.server.db is a single instruction

// Import codegen
@Test fun importFuncEmitsCALL()           // ns.func() is CALL (not SCALL)
@Test fun builtinNamespaceEmitsSCALL()    // Math.sqrt() is SCALL
@Test fun importGlobalsSeparateSlots()    // Module globals get offset slots
@Test fun importFuncGetsFuncMeta()        // Imported functions appear in functions array

// Control flow
@Test fun ifEmitsJIF()
@Test fun ifElseEmitsJIFAndJMP()
@Test fun whileEmitsBackwardJMP()
@Test fun forLoopEmitsCorrectStructure()
@Test fun foreachDesugarsToIndexLoop()     // SCALL(__arr_length) + AGET_IDX + IINC

// Memory management
@Test fun killRefEmittedAtScopeExit()
@Test fun registerReusedAfterDeath()      // Liveness analysis

// Constants 
@Test fun smallIntUsesLDI()               // No pool entry
@Test fun largeIntUsesLDC()               // Pool entry
@Test fun stringUsesPool()
@Test fun constantPoolDeduplication()      // Same string is one entry

// Exception table
@Test fun tryCatchGeneratesEntry()
@Test fun nestedTryCatchOrdering()        // Inner entry appears first
@Test fun catchAllUsesANYType()

// Disassembly
@Test fun noxcHasSourceAnnotations()
@Test fun noxcHasLabels()
@Test fun noxcHasRegisterPrefixes()       // p0, r0 naming
@Test fun noxcHasConstantPool()
@Test fun noxcHasExceptionTable()
@Test fun noxcHasSummary()
```
 
## 2. VM Execution Tests

### 2.1 Arithmetic (`ArithmeticTest.kt`)

```kotlin
// Integer
@Test fun intAdd()                        // 3 + 4 = 7
@Test fun intSub()                        // 10 - 3 = 7
@Test fun intMul()                        // 6 * 7 = 42
@Test fun intDiv()                        // 10 / 3 = 3 (truncated)
@Test fun intMod()                        // 10 % 3 = 1
@Test fun intDivByZeroThrows()            // 10 / 0 throws DivisionByZeroError
@Test fun intModByZeroThrows()            // 10 % 0 throws DivisionByZeroError
@Test fun intOverflowWraps()              // MAX + 1 wraps to MIN
@Test fun intUnderflowWraps()             // MIN - 1 wraps to MAX
@Test fun moduloNegativeTruncated()       // -7 % 3 = -1

// Double
@Test fun doubleAdd()                     // 1.5 + 2.5 = 4.0
@Test fun doubleSub()                     // 5.0 - 1.5 = 3.5
@Test fun doubleMul()                     // 3.14 * 2.0 = 6.28
@Test fun doubleDiv()                     // 10.0 / 3.0 ≈ 3.333...
@Test fun doubleMod()                     // 10.0 % 3.0 = 1.0
@Test fun doubleDivByZeroInfinity()       // 1.0 / 0.0 = Infinity
@Test fun doubleNegDivByZero()            // -1.0 / 0.0 = -Infinity
@Test fun doubleZeroDivByZero()           // 0.0 / 0.0 = NaN
@Test fun doubleNaNPropagation()          // NaN + 1.0 = NaN

// Widening
@Test fun intPlusDoubleWidens()           // 1 + 2.5 = 3.5
@Test fun doublePlusIntWidens()           // 2.5 + 1 = 3.5

// Bitwise
@Test fun bitwiseAnd()                    // 0xFF & 0x0F = 0x0F
@Test fun bitwiseOr()                     // 0xF0 | 0x0F = 0xFF
@Test fun bitwiseXor()                    // 0xFF ^ 0x0F = 0xF0
@Test fun bitwiseNot()                    // ~0 = -1
@Test fun shiftLeft()                     // 1 << 4 = 16
@Test fun shiftRight()                    // -16 >> 2 = -4 (sign-preserving)
@Test fun unsignedShiftRight()            // -1 >>> 32 = 4294967295
@Test fun shiftMasksSixtyThree()          // 1 << 100 = 1 << 36

// Increment / Decrement
@Test fun intIncrement()                  // i++ adds 1
@Test fun intDecrement()                  // i-- subtracts 1
@Test fun intIncrementByN()               // i += 5
@Test fun doubleIncrement()               // d++ adds 1.0
```

### 2.2 Control Flow (`ControlFlowTest.kt`)

```kotlin
// If/Else
@Test fun ifTrue()                        // Takes the then branch
@Test fun ifFalse()                       // Skips the then branch
@Test fun ifElseTrueTakesThen()
@Test fun ifElseFalseTakesElse()
@Test fun ifElseIfChain()                 // Multiple conditions
@Test fun ifElseIfNoMatch()               // Falls through to else

// While
@Test fun whileBasic()                    // Counts to 10
@Test fun whileFalseNeverExecutes()       // while (false) the body never runs
@Test fun whileBreak()                    // break exits the loop
@Test fun whileContinue()                 // continue skips to next iteration
@Test fun whileNestedBreak()              // break in inner loop doesn't exit outer

// For
@Test fun forBasic()                      // for (int i = 0; i < 5; i++)
@Test fun forEmptyInit()                  // for (; i < 5; i++)
@Test fun forEmptyCondition()             // for (int i = 0;; i++) with break
@Test fun forEmptyUpdate()                // for (int i = 0; i < 5;) with manual i++
@Test fun forAllEmpty()                   // for (;;) with break is an infinite loop
@Test fun forBreak()
@Test fun forContinue()                   // continue goes to UPDATE, not condition
@Test fun forNestedLoops()

// ForEach
@Test fun foreachOverArray()              // Iterates all elements
@Test fun foreachEmptyArray()             // Body never executes
@Test fun foreachBreak()                  // Exits early
@Test fun foreachContinue()               // Skips current iteration

// Functions
@Test fun functionCallAndReturn()         // Simple int add(int a, int b)
@Test fun functionDefaultParams()         // Missing args use defaults
@Test fun recursion()                     // factorial(5) = 120
@Test fun mutualRecursion()               // isEven/isOdd
@Test fun functionReturnVoid()            // void function with no return
@Test fun mainNoReturnEmptyString()       // Falling off main gives ""
```

### 2.3 Memory & Types (`MemoryTest.kt`)

```kotlin
// Null
@Test fun nullEqualsNull()                // null == null is true
@Test fun nullNotEqualsNull()             // null != null is false
@Test fun nullEqualsStringFalse()         // null == "hello" is false
@Test fun nullFieldAccessThrows()         // null.field throws NullAccessError
@Test fun nullMethodCallThrows()          // null.upper() throws NullAccessError
@Test fun nullCastIsNull()                // null as Config throws null

// Arrays
@Test fun arrayCreate()                   // [1, 2, 3]
@Test fun arrayIndexAccess()              // arr[0], arr[2]
@Test fun arrayIndexOutOfBounds()         // arr[5] on 3-element array gives IndexOutOfBoundsError
@Test fun negativeArrayIndex()            // arr[-1] gives IndexOutOfBoundsError (no wrap)
@Test fun arrayLength()                   // [1,2,3].length() = 3
@Test fun emptyArrayLength()              // [].length() = 0
@Test fun arrayMutation()                 // arr[0] = 99

// Structs
@Test fun structCreate()                  // { field: value }
@Test fun structFieldAccess()             // s.field
@Test fun structFieldMutation()           // s.field = newValue
@Test fun structNestedAccess()            // s.inner.field
@Test fun structRecursiveNull()           // TreeNode with null children
@Test fun structDeepRecursion()           // Tree 100 levels deep
@Test fun structInArray()                 // Array of structs, mutate one

// JSON
@Test fun jsonObjectCreate()              // { key: value } via json
@Test fun jsonFieldAccess()               // data.name is dynamic
@Test fun jsonSize()                      // json object/array .size()
@Test fun jsonGetStringOk()               // getString("key") when value is string
@Test fun jsonGetIntOk()                  // getInt("key") when value is int
@Test fun jsonGetIntWrongTypeUsesDefault()// getInt("key") when string uses default
@Test fun jsonGetIntWrongTypeThrows()     // getInt("key") when string, no default gives TypeError
@Test fun jsonGetStringWrongTypeUsesDefault()
@Test fun jsonGetStringWrongTypeThrows()
@Test fun jsonHas()                       // has("key") -> true/false
@Test fun jsonKeys()                      // keys() gives string[]
@Test fun jsonCastOk()                    // json as Struct should be ok
@Test fun jsonCastExtraFieldsIgnored()    // Extra fields should be ok (lenient)
@Test fun jsonCastMissingFieldThrows()    // Missing field gibes CastError
@Test fun jsonCastWrongFieldTypeThrows()  // Wrong type gives CastError

// Strings
@Test fun stringLength()                  // "hello".length() = 5
@Test fun stringUpper()                   // "hello".upper() = "HELLO"
@Test fun stringLower()                   // "HELLO".lower() = "hello"
@Test fun stringContains()                // "hello".contains("ell") = true
@Test fun stringSplit()                   // "a,b,c".split(",") = ["a","b","c"]
@Test fun stringEquals()                  // Value equality, not reference
@Test fun stringNotEquals()               // "a" != "b" then true
@Test fun emptyString()                   // "" should length 0
@Test fun templateInterpolation()         // `Hello ${name}` concatenation
@Test fun templateAllText()               // `just text` should be "just text"
@Test fun templateAllExpr()               // `${42}` should be "42"
@Test fun templateEscapeSequences()       // `line1\nline2` should be two lines
@Test fun templateEmpty()                 // ` ` should be ""
@Test fun templateIntConversion()         // `${42}` should be "42" (auto toString)
@Test fun templateDoubleConversion()      // `${3.14}` should be "3.14"
@Test fun templateBoolConversion()        // `${true}` should give "true"
@Test fun templateNullConversion()        // `${null}` should give "null"

// Type conversions
@Test fun intToDouble()                   // x.toDouble() should give double
@Test fun intToString()                   // x.toString() gives "42"
@Test fun doubleToInt()                   // x.toInt() gives truncated
@Test fun doubleToString()                // x.toString() gives "3.14"
@Test fun stringToInt()                   // "42".toInt(0) gives 42
@Test fun stringToIntInvalid()            // "abc".toInt(0) gives 0 (default)
@Test fun stringToDouble()                // "3.14".toDouble(0.0) gives 3.14
@Test fun stringToDoubleInvalid()         // "abc".toDouble(0.0) gives 0.0
```
 
## 3. Exception Tests (`ExceptionTest.kt`)

```kotlin
// Table-driven matching
@Test fun typedCatchMatches()             // catch (TypeError e) catches TypeError
@Test fun typedCatchDoesNotMatchOther()   // catch (TypeError) does NOT catch NetworkError
@Test fun catchAllMatchesEverything()     // catch (err) catches any exception
@Test fun firstMatchWins()                // Multiple catches then first match
@Test fun nestedTryCatchInnerFirst()      // Inner try-catch handles first

// Propagation
@Test fun uncaughtPropagatesToCaller()    // Exception in helper should be caught in caller
@Test fun uncaughtBubblesAllTheWay()      // No catch anywhere, then error reported to Host
@Test fun exceptionInCatchHandler()       // Throw inside catch should re-propagate

// User exceptions
@Test fun throwString()                   // throw "message", Error type
@Test fun throwTemplate()                 // throw `User ${name} not found`, Error type
@Test fun thrownValueIsCatchVariable()    // catch (err) { err == "message" }

// JVM exception mapping
@Test fun jvmNullPointer()               // Plugin throws NPE then NullAccessError
@Test fun jvmArithmetic()                // Plugin throws ArithmeticException then ArithmeticError
@Test fun jvmIndexOutOfBounds()          // Plugin throws IndexOutOfBoundsException then IndexOutOfBoundsError
@Test fun jvmClassCast()                 // Plugin throws ClassCastException then CastError
@Test fun jvmIOException()               // Plugin throws IOException then FileError
@Test fun jvmNetException()              // Plugin throws NetException then NetworkError
@Test fun jvmSecurityException()         // Plugin throws SecurityException then SecurityError
@Test fun jvmIllegalArgument()           // Plugin throws IllegalArgumentException then TypeError
@Test fun jvmUnknownThrowable()          // Plugin throws unknown then Error (catch-all)

// Resource guard exceptions
@Test fun quotaCatchableButTerminal()
// try { infiniteLoop(); } catch (QuotaExceededError e) { cleanup(); }
// catch handler runs, then sandbox terminates (cannot resume execution)

@Test fun timeoutCatchable()
@Test fun memoryLimitCatchable()
@Test fun stackOverflowCatchable()
```
 
## 4. Resource Guard Tests (`ResourceGuardTest.kt`)

```kotlin
// Instruction counter
@Test fun normalExecutionWithinQuota()    // Short program completes
@Test fun exactlyAtLimitCompletes()       // Instruction N = limit then completes
@Test fun onePastLimitThrows()            // Instruction N+1 throws QuotaExceededError
@Test fun quotaConfigurable()             // Custom maxInstructions works

// Time limit
@Test fun normalExecutionWithinTime()     // Fast program completes
@Test fun timeoutOnLongLoop()             // CPU-bound loop should throw TimeoutError
@Test fun timeoutInterruptsBlocking()     // Blocking SCALL is interrupted via Thread.interrupt()
@Test fun timeoutConfigurable()           // Custom maxExecutionTime works

// Memory limit
@Test fun normalMemoryUsage()
@Test fun largeArrayTriggersLimit()       // Creating huge array leads to MemoryLimitError
@Test fun stringConcatTriggersLimit()     // Building huge string leads to MemoryLimitError
@Test fun memoryConfigurable()

// Stack depth
@Test fun normalRecursionOk()             // 50 levels then ok
@Test fun deepRecursionThrows()           // 300 levels then StackOverflowError
@Test fun stackDepthConfigurable()

// Terminal behavior
@Test fun catchResourceErrorRunsHandler() // catch block executes
@Test fun sandboxTerminatesAfterCatch()   // Execution doesn't continue after catch
@Test fun cannotLoopCatchingQuota()       // try { loop; } catch { } doesn't repeat
```
 
## 5. Host API Tests (`NoxRuntimeTest.kt`)

```kotlin
// Basic execution
@Test fun executeSimpleProgram()
@Test fun executeReturnsNoxResult()
@Test fun resultContainsFinalResult()
@Test fun resultContainsYields()
@Test fun resultContainsError()

// Arguments
@Test fun passArguments()                 // Map<String, Object> to main params
@Test fun defaultsUsedWhenArgsMissing()
@Test fun extraArgsIgnored()
@Test fun missingRequiredArgThrows()      // No default + no arg throws error

// Compiled program reuse
@Test fun compiledProgramReuse()          // Compile once, execute many times
@Test fun freshSandboxEachExecution()     // State doesn't leak between runs
@Test fun compiledProgramImmutable()      // Bytecode not modified by execution

// Concurrency
@Test fun concurrentExecutionIsolation()  // Two parallel executions don't interfere
@Test fun concurrentYieldsCorrect()       // Each execution gets its own yields

// Configuration
@Test fun customInstructionLimit()
@Test fun customTimeLimit()
@Test fun customMemoryLimit()
@Test fun customStackDepth()
@Test fun builderChaining()               // NoxRuntime.builder().x().y().build()

// Plugin registration
@Test fun registerPlugin()                // runtime.registerModule(MyPlugin::class)
@Test fun pluginFunctionsAccessible()     // NSL can call plugin functions
@Test fun pluginTypeMethodsAccessible()   // NSL can call type-bound methods

// Yield/Return lifecycle
@Test fun yieldWithNoReturn()             // Yields collected, result = ""
@Test fun returnWithNoYield()             // No yields, result = "value"
@Test fun multipleYieldsThenReturn()      // Both yields and result
```
 
## 6. Permission Tests (`PermissionTest.kt`)

```kotlin
@Test fun defaultPermissionDenied()       // No handler set then all denied
@Test fun grantSpecificPermission()       // file.read is GRANTED
@Test fun denySpecificPermission()        // file.write is DENIED
@Test fun deniedThrowsSecurityError()     // SecurityError thrown in NSL
@Test fun securityErrorCatchable()        // Can catch (SecurityError e)
@Test fun permissionCalledEveryTime()     // No caching between invocations
@Test fun permissionHandlerContext()      // Handler receives action + context
@Test fun permissionHandlerExceptionContained() // Handler throws SecurityError
@Test fun filePermissionGranularity()     // file.read vs file.write are separate
```
 
## 7. FFI Tests (`PluginTest.kt`)

```kotlin
// Type marshalling
@Test fun intParamMarshalling()           // NSL int to Kotlin Long
@Test fun doubleParamMarshalling()        // NSL double to Kotlin Double
@Test fun boolParamMarshalling()          // NSL boolean to Kotlin Boolean
@Test fun stringParamMarshalling()        // NSL string to Kotlin String
@Test fun jsonParamMarshalling()          // NSL json to NoxObject
@Test fun returnIntMarshalling()          // Kotlin Long to NSL int
@Test fun returnStringMarshalling()       // Kotlin String to NSL string
@Test fun returnNoxObjectMarshalling()    // NoxObject to NSL json

// Error containment
@Test fun pluginExceptionContained()      // Plugin throws converted into Nox exception, host safe
@Test fun pluginNullReturnHandled()       // Plugin returns null then null in NSL
@Test fun pluginExceptionMapped()         // JVM IOException then FileError

// Registration
@Test fun duplicateMethodNameErrors()     // Two plugins register same name then error
@Test fun typeMethodCollisionErrors()     // Two @NoxTypeMethod for same type.method then error
```
 
## 8. E2E Golden Tests (`GoldenTest.kt`)

### Test File Convention

```c
// @test:result "expected final result"
// @test:yields ["yield1", "yield2"]
// @test:error "ErrorType"  (alternative to @test:result for error cases)
// @test:errorContains "partial message"

main() { ... }
```

### Test Runner

```kotlin
@ParameterizedTest
@MethodSource("discoverTestFiles")
fun goldenTest(noxFile: Path) {
    val expected = parseExpectations(noxFile)
    val actual = compileAndRun(noxFile)

    expected.result?.let { assertEquals(it, actual.finalResult) }
    expected.yields?.let { assertEquals(it, actual.yieldedOutputs) }
    if (expected.error != null) {
        assertTrue(actual.isError)
        assertContains(actual.errorType, expected.error!!)
    }
    expected.errorContains?.let { assertContains(actual.errorMessage, it) }
}
```

### Golden Test Programs

| File | Tests | Expected Result |
|---|---|---|
| `arithmetic.nox` | Basic math | `"42"` |
| `fibonacci.nox` | Recursion | `"55"` (fib(10)) |
| `factorial.nox` | Recursion | `"120"` (fact(5)) |
| `string_methods.nox` | upper, lower, split, contains | `"HELLO"` |
| `struct_create.nox` | Struct creation + field access | `"Alice"` |
| `struct_cast.nox` | json to struct via `as` | `"Alice"` |
| `struct_nested.nox` | Nested struct access | `"inner"` |
| `array_ops.nox` | Create, index, length, mutate | `"3"` |
| `json_extract.nox` | getString, getInt with defaults | `"42"` |
| `template_basic.nox` | Template interpolation | `"Hello Alice"` |
| `template_types.nox` | Int, double, bool in templates | `"42 3.14 true"` |
| `for_loop.nox` | For loop counting | `"10"` |
| `foreach_loop.nox` | Foreach over array | `"6"` (sum) |
| `while_break.nox` | While with break | `"5"` |
| `yield_streaming.nox` | Multiple yields + return | result + yields |
| `try_catch.nox` | Exception recovery | `"recovered"` |
| `try_catch_typed.nox` | Typed catch matching | `"network"` |
| `nested_try.nox` | Nested try-catch | `"inner"` |
| `throw_custom.nox` | User throw + catch | `"custom error"` |
| `ufcs_call.nox` | UFCS resolution | `"42"` |
| `type_convert.nox` | toInt, toDouble, toString | `"42"` |
| `null_handling.nox` | null comparisons | `"true"` |
| `bitwise_ops.nox` | AND, OR, XOR, NOT, shifts | `"255"` |
| `default_params.nox` | Missing args use defaults | `"hello world"` |
| `global_vars.nox` | Global variable access | `"42"` |
| `mutual_recursion.nox` | isEven/isOdd | `"true"` |
| `scope_shadowing.nox` | Inner shadows outer | `"1"` (outer unchanged) |

### Error Golden Tests

| File | Expected Error |
|---|---|
| `err_null_primitive.nox` | `SemanticError: Cannot assign null to non-nullable type 'int'` |
| `err_type_mismatch.nox` | `SemanticError: expected 'int', found 'string'` |
| `err_div_zero.nox` | `DivisionByZeroError` |
| `err_null_access.nox` | `NullAccessError` |
| `err_index_oob.nox` | `IndexOutOfBoundsError` |
| `err_cast_missing.nox` | `CastError` |
| `err_stack_overflow.nox` | `StackOverflowError` |
| `err_quota.nox` | `QuotaExceededError` |
| `err_no_method.nox` | `SemanticError: No method 'x' found on type 'int'` |
| `err_break_outside.nox` | `SemanticError: 'break' can only appear inside a loop` |
| `err_no_main.nox` | `CompileError: Program must have a main() function` |
| `err_empty_struct.nox` | `CompileError: Struct must have at least one field` |
 
## 9. Compiler Snapshot Tests (Disassembly Diffing)

To prevent regressions in the most complex parts of the compiler (register allocation, jump target calculation, and super-instruction selection), we use the `.noxc` disassembly format as defined in [**Bytecode Disassembly**](compiler/disassembly.md) for full-program snapshot testing.

### Why Snapshot Testing?
While E2E tests verify *behavior*, they often miss subtle regressions in *efficiency* or *instruction choice*. If a compiler change causes a loop to use 3 registers instead of 2, or misses a super-instruction opportunity, the E2E result remains correct, but the compiler has regressed.

### The Workflow
1. **Source File**: A `.nox` file is compiled.
2. **Generation**: The compiler generates a `.noxc` disassembly.
3. **Comparison**: The test runner compares the generated `.noxc` string against a **golden file** in `src/test/resources/nox/expected/`.
4. **Diff Failure**: If any instruction, register prefix, or label placement differs, the test fails and shows a colorized diff.

```kotlin
@Test fun compilerRegression_Arithmetic() {
    val source = loadSource("arithmetic.nox")
    val actualNoxc = compiler.compileToNoxc(source)
    val expectedNoxc = loadExpected("arithmetic.noxc")

    assertEquals(expectedNoxc, actualNoxc, "Compiler output changed! Check register allocation.")
}
```

### What to Look For in Diffs
*   **Registers**: Changes in `p0` vs `p1` might indicate a bug in the liveness analyzer.
*   **Labels**: Shifted `.loop_start` or `.loop_exit` labels indicate jump target errors.
*   **KILL_REF**: Incorrectly placed or missing cleanup opcodes.
 
## Next Steps

- [**Disassembly Format**](compiler/disassembly.md)
- [**Error Handling**](vm/error-handling.md)
- [**Compiler Overview**](compiler/overview.md)
