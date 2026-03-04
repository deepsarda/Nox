parser grammar NoxParser;

options { tokenVocab = NoxLexer; }


// Nox Scripting Language Parser Grammar
//
// Structure mirrors the .nox file format:
//   1. Metadata headers      (@tool:name, @tool:description, ...)
//   2. Import declarations   (import "path.nox" as namespace;)
//   3. Type definitions      (type Point { int x; int y; })
//   4. Global variables      (int counter = 0;)
//   5. Helper functions      (int add(int a, int b) { ... })
//   6. main() entry point    (main(string url) { ... })
//
// Ordering is NOT enforced by the grammar. The semantic analyzer
// validates structure if needed.

 
 // Program Structure
 program
    : header* importDeclaration* topLevelDeclaration* EOF
    ;

header
    : HEADER_KEY StringLiteral
    ;

importDeclaration
    : IMPORT StringLiteral AS Identifier SEMI        // import "path.nox" as namespace;
    ;

topLevelDeclaration
    : typeDefinition
    | functionDefinition
    | mainDefinition
    | variableDeclaration SEMI          // Global variable
    ;


 
 // Type Definitions
 typeDefinition
    : TYPE Identifier LBRACE fieldDeclaration+ RBRACE
    ;

fieldDeclaration
    : typeRef Identifier SEMI
    ;


 
 // Functions
 functionDefinition
    : typeRef Identifier LPAREN parameterList? RPAREN block
    ;

mainDefinition
    : MAIN LPAREN parameterList? RPAREN block
    ;

parameterList
    : parameter (COMMA parameter)*
    ;

parameter
    : typeRef ELLIPSIS Identifier LBRACK RBRACK     // Varargs: int ...values[]
    | typeRef Identifier (ASSIGN expression)?        // Regular or default
    ;


 
 // Types
 typeRef
    : primitiveType (LBRACK RBRACK)?                // int, string[], json, etc.
    | Identifier    (LBRACK RBRACK)?                // ApiConfig, Point[], etc.
    ;

primitiveType
    : INT | DOUBLE | BOOLEAN | STRING | JSON | VOID
    ;


 
 // Blocks & Statements
 block
    : LBRACE statement* RBRACE
    ;

statement
    : variableDeclaration SEMI                                  # VarDeclStmt
    | expression assignOp expression SEMI                       # AssignStmt
    | expression (PLUS_PLUS | MINUS_MINUS) SEMI                 # IncrementStmt
    | ifStatement                                               # IfStmt
    | whileStatement                                            # WhileStmt
    | forStatement                                              # ForStmt
    | foreachStatement                                          # ForeachStmt
    | RETURN expression? SEMI                                   # ReturnStmt
    | YIELD expression SEMI                                     # YieldStmt
    | BREAK SEMI                                                # BreakStmt
    | CONTINUE SEMI                                             # ContinueStmt
    | THROW expression SEMI                                     # ThrowStmt
    | tryCatchStatement                                         # TryCatchStmt
    | expression SEMI                                           # ExpressionStmt
    ;

variableDeclaration
    : typeRef Identifier ASSIGN expression
    ;

assignOp
    : ASSIGN
    | PLUS_ASSIGN
    | MINUS_ASSIGN
    | STAR_ASSIGN
    | SLASH_ASSIGN
    | PERCENT_ASSIGN
    ;


 
 // Control Flow
 ifStatement
    : IF LPAREN expression RPAREN block
      (ELSE IF LPAREN expression RPAREN block)*
      (ELSE block)?
    ;

whileStatement
    : WHILE LPAREN expression RPAREN block
    ;

forStatement
    : FOR LPAREN forInit? SEMI expression? SEMI forUpdate? RPAREN block
    ;

forInit
    : variableDeclaration                               // int i = 0
    | expression assignOp expression                    // i = 0
    ;

forUpdate
    : expression assignOp expression                    // i = i + 1, i += 1
    | expression (PLUS_PLUS | MINUS_MINUS)              // i++, i--
    ;

foreachStatement
    : FOREACH LPAREN typeRef Identifier IN expression RPAREN block
    ;


 
 // Error Handling
 tryCatchStatement
    : TRY block catchClause+
    ;

// catch (NetworkError e) { ... }    typed catch
// catch (err)            { ... }    catch-all
//
// ANTLR resolves via ordered alternatives (two-ident tried first).
catchClause
    : CATCH LPAREN Identifier Identifier RPAREN block   // Typed: catch (Type name)
    | CATCH LPAREN Identifier RPAREN block              // Catch-all: catch (name)
    ;


 
 // Expressions
//
 // Ordered by precedence (highest at top for suffixes,
 // lowest at bottom for binary operators).
//
 // ANTLR 4 handles left recursion natively. Non-left-recursive
 // alternatives are primaries; left-recursive ones are
 // binary/suffix operators with implicit precedence from
 // their order in this rule.
 expression
    // Primaries (non-left-recursive, tried in order)
    : LPAREN expression RPAREN                                      # ParenExpr
    | Identifier LPAREN argumentList? RPAREN                        # FuncCallExpr
    | IntegerLiteral                                                # IntLiteralExpr
    | DoubleLiteral                                                 # DoubleLiteralExpr
    | (TRUE | FALSE)                                                # BoolLiteralExpr
    | StringLiteral                                                 # StringLiteralExpr
    | templateLiteral                                               # TemplateLiteralExpr
    | NULL                                                          # NullLiteralExpr
    | arrayLiteral                                                  # ArrayLiteralExpr
    | structLiteral                                                 # StructLiteralExpr
    | Identifier                                                    # IdentifierExpr

    // Suffix operators (highest precedence)
    | expression DOT Identifier LPAREN argumentList? RPAREN         # MethodCallExpr
    | expression DOT Identifier                                     # FieldAccessExpr
    | expression LBRACK expression RBRACK                           # IndexAccessExpr
    | expression (PLUS_PLUS | MINUS_MINUS)                          # PostfixExpr

    // Unary prefix
    | (MINUS | BANG | TILDE) expression                             # UnaryExpr

    // Cast (binary, very high precedence)
    | expression AS typeRef                                         # CastExpr

    // Binary operators (ordered by precedence, low = first tried = highest prec)
    | expression (STAR | SLASH | PERCENT) expression                # MulDivModExpr
    | expression (PLUS | MINUS) expression                          # AddSubExpr
    | expression (SHL | SHR | USHR) expression                     # ShiftExpr
    | expression (LT | LE | GT | GE) expression                    # CompareExpr
    | expression (EQ | NE) expression                               # EqualityExpr
    | expression AMPERSAND expression                               # BitAndExpr
    | expression CARET expression                                   # BitXorExpr
    | expression PIPE expression                                    # BitOrExpr
    | expression AND expression                                     # LogicAndExpr
    | expression OR expression                                      # LogicOrExpr
    ;

argumentList
    : expression (COMMA expression)*
    ;


 
 // Template Literals (String Interpolation)
//
 // `Hello, ${name}! You have ${count} items.`
//
 // Lexer modes handle the tokenization:
  //  BACKTICK  ->  TEMPLATE mode
  //  ${        ->  back to DEFAULT_MODE (expression tokens)
  //  }         ->  back to TEMPLATE mode (via brace stack)
  //  `         ->  back to DEFAULT_MODE
 templateLiteral
    : BACKTICK templatePart* TEMPLATE_CLOSE
    ;

templatePart
    : TEMPLATE_TEXT                                     # TemplateTextPart
    | TEMPLATE_EXPR_OPEN expression RBRACE              # TemplateExprPart
    ;


 
 // Composite Literals
 arrayLiteral
    : LBRACK (expression (COMMA expression)*)? RBRACK
    ;

// Struct literal: { field: value, field: value }
// Distinguished from blocks by the `identifier : expression` pattern.
structLiteral
    : LBRACE fieldInit (COMMA fieldInit)* RBRACE
    ;

fieldInit
    : Identifier COLON expression
    ;
