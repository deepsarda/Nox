/**
 * Compiler pipeline:
 *
 *  1. ASTBuilder: ANTLR ParseTree to AST (Expr / Stmt / Decl hierarchy)
 *  2. SemanticAnalyzer: type resolution, UFCS, null checks, control flow
 *  3. CodeGenerator: register allocation + bytecode emission
 */
package nox.compiler
