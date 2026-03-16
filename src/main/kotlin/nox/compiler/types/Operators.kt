package nox.compiler.types

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


/**
 * Readable symbol for binary operators in error messages.
 */
val BinaryOp.symbol: String
    get() = when (this) {
        BinaryOp.ADD -> "+"
        BinaryOp.SUB -> "-"
        BinaryOp.MUL -> "*"
        BinaryOp.DIV -> "/"
        BinaryOp.MOD -> "%"
        BinaryOp.EQ -> "=="
        BinaryOp.NE -> "!="
        BinaryOp.LT -> "<"
        BinaryOp.LE -> "<="
        BinaryOp.GT -> ">"
        BinaryOp.GE -> ">="
        BinaryOp.AND -> "&&"
        BinaryOp.OR -> "||"
        BinaryOp.BIT_AND -> "&"
        BinaryOp.BIT_OR -> "|"
        BinaryOp.BIT_XOR -> "^"
        BinaryOp.SHL -> "<<"
        BinaryOp.SHR -> ">>"
        BinaryOp.USHR -> ">>>"
    }

/**
 * Readable symbol for unary operators in error messages.
 */
val UnaryOp.symbol: String
    get() = when (this) {
        UnaryOp.NEG -> "-"
        UnaryOp.NOT -> "!"
        UnaryOp.BIT_NOT -> "~"
    }

/**
 * Readable symbol for postfix operators in error messages.
 */
val PostfixOp.symbol: String
    get() = when (this) {
        PostfixOp.INCREMENT -> "++"
        PostfixOp.DECREMENT -> "--"
    }

/**
 * Readable symbol for assignment operators in error messages.
 */
val AssignOp.symbol: String
    get() = when (this) {
        AssignOp.ASSIGN -> "="
        AssignOp.ADD_ASSIGN -> "+="
        AssignOp.SUB_ASSIGN -> "-="
        AssignOp.MUL_ASSIGN -> "*="
        AssignOp.DIV_ASSIGN -> "/="
        AssignOp.MOD_ASSIGN -> "%="
    }

