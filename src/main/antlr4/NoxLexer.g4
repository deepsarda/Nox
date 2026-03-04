lexer grammar NoxLexer;

//  Nox Scripting Language Lexer Grammar
//
//  Split from the parser to support lexer modes, which are
//  required for template literal (string interpolation) handling.
//
//  Template literals use a mode stack:
//    DEFAULT_MODE  ->  TEMPLATE  ->  DEFAULT_MODE (nested)
//    `text ${expr} text`

@lexer::header {
    import java.util.ArrayDeque;
    import java.util.Deque;
}

@lexer::members {
    // Tracks brace depth inside template interpolation expressions.
    // When we enter ${...}, we push 0. Every { increments the top,
    // every } decrements it. When the top hits 0 on }, we pop the
    // mode back to TEMPLATE.
    private Deque<Integer> braceStack = new ArrayDeque<>();

    private void pushBrace()  { braceStack.push(0); }
    private boolean inInterpolation() { return !braceStack.isEmpty(); }

    private void openBrace() {
        if (inInterpolation()) {
            braceStack.push(braceStack.pop() + 1);
        }
    }

    private boolean closeBrace() {
        if (inInterpolation()) {
            int depth = braceStack.pop();
            if (depth == 0) {
                popMode();  // Back to TEMPLATE mode
                return true; // Signal: this } closes an interpolation
            }
            braceStack.push(depth - 1);
        }
        return false;
    }
}
 
// Keywords
TYPE        : 'type';
MAIN        : 'main';
IF          : 'if';
ELSE        : 'else';
WHILE       : 'while';
FOR         : 'for';
FOREACH     : 'foreach';
IN          : 'in';
RETURN      : 'return';
YIELD       : 'yield';
BREAK       : 'break';
CONTINUE    : 'continue';
TRY         : 'try';
CATCH       : 'catch';
THROW       : 'throw';
IMPORT      : 'import';
NULL        : 'null';
TRUE        : 'true';
FALSE       : 'false';

// Type keywords
INT         : 'int';
DOUBLE      : 'double';
BOOLEAN     : 'boolean';
STRING      : 'string';
JSON        : 'json';
VOID        : 'void';
AS          : 'as';
 
// Operators (longest-match first)
// Shifts (must precede < and >)
USHR            : '>>>';
SHL             : '<<';
SHR             : '>>';

// Comparison
LE              : '<=';
GE              : '>=';
EQ              : '==';
NE              : '!=';
LT              : '<';
GT              : '>';

// Logical
AND             : '&&';
OR              : '||';

// Increment / Decrement (must precede + and -)
PLUS_PLUS       : '++';
MINUS_MINUS     : '--';

// Compound Assignment (must precede single-char operators)
PLUS_ASSIGN     : '+=';
MINUS_ASSIGN    : '-=';
STAR_ASSIGN     : '*=';
SLASH_ASSIGN    : '/=';
PERCENT_ASSIGN  : '%=';

// Arithmetic
PLUS            : '+';
MINUS           : '-';
STAR            : '*';
SLASH           : '/';
PERCENT         : '%';

// Bitwise
TILDE           : '~';
AMPERSAND       : '&';
PIPE            : '|';
CARET           : '^';

// Other
BANG            : '!';
ASSIGN          : '=';
DOT             : '.';
ELLIPSIS        : '...';
 
// Delimiters
LPAREN          : '(';
RPAREN          : ')';
LBRACK          : '[';
RBRACK          : ']';
LBRACE          : '{'  { openBrace(); };
RBRACE          : '}'  { closeBrace(); };
SEMI            : ';';
COMMA           : ',';
COLON           : ':';
 
// Metadata Headers
HEADER_KEY      : '@tool:' [a-z_]+;
 
// Literals
DoubleLiteral   : Digits '.' Digits;
IntegerLiteral  : Digits;

fragment Digits : [0-9]+;

StringLiteral   : '"' (~["\\\r\n] | EscapeSeq)* '"';

fragment EscapeSeq
    : '\\' [btnfr"'\\]
    | '\\u' HexDigit HexDigit HexDigit HexDigit
    ;

fragment HexDigit : [0-9a-fA-F];
 
// Template Literal Entry
BACKTICK        : '`' -> pushMode(TEMPLATE);
 
// Identifiers
Identifier      : [a-zA-Z_] [a-zA-Z0-9_]*;
 
// Whitespace & Comments (skipped)
WS              : [ \t\r\n]+ -> skip;
LINE_COMMENT    : '//' ~[\r\n]* -> skip;
BLOCK_COMMENT   : '/*' .*? '*/' -> skip;


// TEMPLATE mode inside backtick-delimited template literals

mode TEMPLATE;

// Literal text between interpolation expressions.
// Matches any character except `, $, and \, plus escaped chars
// and lone $ not followed by {.
TEMPLATE_TEXT
    : ( ~[`$\\]
      | '\\' .
      | '$' ~'{'
      )+
    ;

// Start of an interpolation expression: ${
// Pushes back to DEFAULT_MODE so regular expression tokens are lexed.
// The matching } will pop back to TEMPLATE via closeBrace().
TEMPLATE_EXPR_OPEN
    : '${' { pushBrace(); } -> pushMode(DEFAULT_MODE)
    ;

// End of the template literal
TEMPLATE_CLOSE
    : '`' -> popMode
    ;
