package nox.compiler.ast

/**
 * Binary operators, grouped by category.
 *
 * Used in [BinaryExpr] for two-operand operations.
 */
enum class BinaryOp {
    // Arithmetic
    ADD,
    SUB,
    MUL,
    DIV,
    MOD,

    // Comparison
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE,

    // Logical
    AND,
    OR,

    // Bitwise
    BIT_AND,
    BIT_OR,
    BIT_XOR,

    // Shift
    SHL,
    SHR,
    USHR,
}

/**
 * Unary prefix operators.
 *
 * Used in [UnaryExpr] for single-operand prefix operations.
 */
enum class UnaryOp {
    /** Arithmetic negation (`-x`) */
    NEG,

    /** Logical negation (`!x`) */
    NOT,

    /** Bitwise complement (`~x`) */
    BIT_NOT,
}

/**
 * Postfix increment / decrement operators.
 *
 * Used in both [PostfixExpr] (expression context: `x++`)
 * and [IncrementStmt] (statement context: `x++;`).
 */
enum class PostfixOp {
    INCREMENT,
    DECREMENT,
}

/**
 * Assignment operators, including simple assignment and compound forms.
 *
 * Used in [AssignStmt].
 */
enum class AssignOp {
    ASSIGN,
    ADD_ASSIGN,
    SUB_ASSIGN,
    MUL_ASSIGN,
    DIV_ASSIGN,
    MOD_ASSIGN,
}
