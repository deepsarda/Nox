package nox.compiler

import nox.compiler.ast.*
import nox.parser.NoxParser
import nox.parser.NoxParserBaseVisitor
import org.antlr.v4.runtime.ParserRuleContext

/**
 * Converts the ANTLR parse tree into the Nox AST.
 *
 * This visitor walks the concrete syntax tree (CST) produced by
 * [NoxParser] and builds an abstract syntax tree using the sealed
 * class hierarchy defined in [nox.compiler.ast].
 *
 * Desugaring performed during construction:
 * - `ParenExpr` is unwrapped (grouping is syntactic, not semantic)
 * - `@tool:` prefix is stripped from header keys
 * - Escape sequences in string literals are resolved
 * - Template literal tokens are combined into [TemplateLiteralExpr] parts
 *
 * @property fileName the source file name, attached to every [SourceLocation]
 * @see <a href="file:///docs/compiler/ast.md">AST Design</a>
 */
class ASTBuilder(
    private val fileName: String,
) : NoxParserBaseVisitor<Any>() {
    // Program
    override fun visitProgram(ctx: NoxParser.ProgramContext): Program {
        val headers = ctx.header().map { visitHeader(it) }
        val imports = ctx.importDeclaration().map { visitImportDeclaration(it) }
        val declarations = ctx.topLevelDeclaration().map { visitTopLevelDeclaration(it) }

        val program = Program(fileName, headers, imports, declarations)

        // Populate convenience maps
        for (decl in declarations) {
            when (decl) {
                is TypeDef -> program.typesByName[decl.name] = decl
                is FuncDef -> program.functionsByName[decl.name] = decl
                is MainDef -> program.main = decl
                is GlobalVarDecl -> program.globals.add(decl)
                is ImportDecl -> Unit
                is ErrorDecl -> Unit
            }
        }

        return program
    }

    // Headers
    override fun visitHeader(ctx: NoxParser.HeaderContext): Header {
        val rawKey = ctx.HEADER_KEY().text // e.g. "@tool:name"
        val key = rawKey.removePrefix("@tool:")
        val value = unquote(ctx.StringLiteral().text)
        return Header(key, value, locOf(ctx))
    }

    // Imports
    override fun visitImportDeclaration(ctx: NoxParser.ImportDeclarationContext): ImportDecl {
        val path = unquote(ctx.StringLiteral().text)
        val namespace = ctx.Identifier().text
        return ImportDecl(path, namespace, locOf(ctx))
    }

    // Top-level declarations
    override fun visitTopLevelDeclaration(ctx: NoxParser.TopLevelDeclarationContext): Decl =
        when {
            ctx.typeDefinition() != null -> visitTypeDefinition(ctx.typeDefinition())
            ctx.functionDefinition() != null -> visitFunctionDefinition(ctx.functionDefinition())
            ctx.mainDefinition() != null -> visitMainDefinition(ctx.mainDefinition())
            ctx.variableDeclaration() != null -> {
                val varDecl = ctx.variableDeclaration()
                val type = visitTypeRef(varDecl.typeRef())
                val name = varDecl.Identifier().text
                val init = visitExpression(varDecl.expression())
                GlobalVarDecl(type, name, init, locOf(varDecl))
            }
            else -> ErrorDecl(locOf(ctx))
        }

    // Type definitions
    override fun visitTypeDefinition(ctx: NoxParser.TypeDefinitionContext): TypeDef {
        val name = ctx.Identifier().text
        val fields =
            ctx.fieldDeclaration().map { fd ->
                FieldDecl(
                    type = visitTypeRef(fd.typeRef()),
                    name = fd.Identifier().text,
                    loc = locOf(fd),
                )
            }
        return TypeDef(name, fields, locOf(ctx))
    }

    // Function definitions
    override fun visitFunctionDefinition(ctx: NoxParser.FunctionDefinitionContext): FuncDef {
        val returnType = visitTypeRef(ctx.typeRef())
        val name = ctx.Identifier().text
        val params = buildParamList(ctx.parameterList())
        val body = visitBlock(ctx.block())
        return FuncDef(returnType, name, params, body, locOf(ctx))
    }

    // Main definition
    override fun visitMainDefinition(ctx: NoxParser.MainDefinitionContext): MainDef {
        val params = buildParamList(ctx.parameterList())
        val body = visitBlock(ctx.block())
        return MainDef(params, body, locOf(ctx))
    }

    // Parameters
    private fun buildParamList(ctx: NoxParser.ParameterListContext?): List<Param> =
        ctx?.parameter()?.map { visitParameter(it) } ?: emptyList()

    override fun visitParameter(ctx: NoxParser.ParameterContext): Param {
        val type = visitTypeRef(ctx.typeRef())
        val isVarargs = ctx.ELLIPSIS() != null
        val name = ctx.Identifier().text
        val defaultValue = ctx.expression()?.let { visitExpression(it) }

        val actualType = if (isVarargs) TypeRef(type.name, isArray = true) else type
        return Param(actualType, name, defaultValue, isVarargs, locOf(ctx))
    }

    // Type references
    override fun visitTypeRef(ctx: NoxParser.TypeRefContext): TypeRef {
        val baseName = ctx.primitiveType()?.text ?: ctx.Identifier().text
        val isArray = ctx.LBRACK() != null
        return TypeRef(baseName, isArray)
    }

    // Block
    override fun visitBlock(ctx: NoxParser.BlockContext): Block {
        val stmts = ctx.statement().map { visitStatement(it) }
        return Block(stmts, locOf(ctx))
    }

    // Statements are dispatched via labeled alternatives
    private fun visitStatement(ctx: NoxParser.StatementContext): Stmt =
        when (ctx) {
            is NoxParser.VarDeclStmtContext -> {
                val vd = ctx.variableDeclaration()
                VarDeclStmt(
                    type = visitTypeRef(vd.typeRef()),
                    name = vd.Identifier().text,
                    initializer = visitExpression(vd.expression()),
                    loc = locOf(vd),
                )
            }
            is NoxParser.AssignStmtContext -> {
                val target = visitExpression(ctx.expression(0))
                val op = mapAssignOp(ctx.assignOp()) ?: return ErrorStmt(locOf(ctx))
                val value = visitExpression(ctx.expression(1))
                AssignStmt(target, op, value, locOf(ctx))
            }
            is NoxParser.IncrementStmtContext -> {
                val target = visitExpression(ctx.expression())
                val op = if (ctx.PLUS_PLUS() != null) PostfixOp.INCREMENT else PostfixOp.DECREMENT
                IncrementStmt(target, op, locOf(ctx))
            }
            is NoxParser.IfStmtContext -> visitIfStatement(ctx.ifStatement())
            is NoxParser.WhileStmtContext -> visitWhileStatement(ctx.whileStatement())
            is NoxParser.ForStmtContext -> visitForStatement(ctx.forStatement())
            is NoxParser.ForeachStmtContext -> visitForeachStatement(ctx.foreachStatement())
            is NoxParser.ReturnStmtContext -> {
                val value = ctx.expression()?.let { visitExpression(it) }
                ReturnStmt(value, locOf(ctx))
            }
            is NoxParser.YieldStmtContext -> YieldStmt(visitExpression(ctx.expression()), locOf(ctx))
            is NoxParser.BreakStmtContext -> BreakStmt(locOf(ctx))
            is NoxParser.ContinueStmtContext -> ContinueStmt(locOf(ctx))
            is NoxParser.ThrowStmtContext -> ThrowStmt(visitExpression(ctx.expression()), locOf(ctx))
            is NoxParser.TryCatchStmtContext -> visitTryCatchStatement(ctx.tryCatchStatement())
            is NoxParser.ExpressionStmtContext -> ExprStmt(visitExpression(ctx.expression()), locOf(ctx))
            else -> ErrorStmt(locOf(ctx))
        }

    // If/else-if/else
    override fun visitIfStatement(ctx: NoxParser.IfStatementContext): IfStmt {
        val expressions = ctx.expression()
        val blocks = ctx.block()

        val condition = visitExpression(expressions[0])
        val thenBlock = visitBlock(blocks[0])

        // else-if branches: expressions[1..n-1] and blocks[1..n-1]
        // The last block might be the else clause (if total blocks > total expressions)
        val hasElse = blocks.size > expressions.size
        val elseIfCount = expressions.size - 1

        val elseIfs =
            (0 until elseIfCount).map { i ->
                IfStmt.ElseIf(
                    condition = visitExpression(expressions[i + 1]),
                    body = visitBlock(blocks[i + 1]),
                    loc = locOf(expressions[i + 1]),
                )
            }

        val elseBlock = if (hasElse) visitBlock(blocks.last()) else null

        return IfStmt(condition, thenBlock, elseIfs, elseBlock, locOf(ctx))
    }

    // While
    override fun visitWhileStatement(ctx: NoxParser.WhileStatementContext): WhileStmt =
        WhileStmt(visitExpression(ctx.expression()), visitBlock(ctx.block()), locOf(ctx))

    // For
    override fun visitForStatement(ctx: NoxParser.ForStatementContext): ForStmt {
        val init = ctx.forInit()?.let { buildForInit(it) }
        val condition = ctx.expression()?.let { visitExpression(it) }
        val update = ctx.forUpdate()?.let { buildForUpdate(it) }
        val body = visitBlock(ctx.block())
        return ForStmt(init, condition, update, body, locOf(ctx))
    }

    private fun buildForInit(ctx: NoxParser.ForInitContext): Stmt =
        when {
            ctx.variableDeclaration() != null -> {
                val vd = ctx.variableDeclaration()
                VarDeclStmt(
                    type = visitTypeRef(vd.typeRef()),
                    name = vd.Identifier().text,
                    initializer = visitExpression(vd.expression()),
                    loc = locOf(vd),
                )
            }
            else -> {
                val target = visitExpression(ctx.expression(0))
                val op = mapAssignOp(ctx.assignOp()) ?: return ErrorStmt(locOf(ctx))
                val value = visitExpression(ctx.expression(1))
                AssignStmt(target, op, value, locOf(ctx))
            }
        }

    private fun buildForUpdate(ctx: NoxParser.ForUpdateContext): Stmt =
        when {
            ctx.assignOp() != null -> {
                val target = visitExpression(ctx.expression(0))
                val op = mapAssignOp(ctx.assignOp()) ?: return ErrorStmt(locOf(ctx))
                val value = visitExpression(ctx.expression(1))
                AssignStmt(target, op, value, locOf(ctx))
            }
            else -> {
                val target = visitExpression(ctx.expression(0))
                val op = if (ctx.PLUS_PLUS() != null) PostfixOp.INCREMENT else PostfixOp.DECREMENT
                IncrementStmt(target, op, locOf(ctx))
            }
        }

    // Foreach
    override fun visitForeachStatement(ctx: NoxParser.ForeachStatementContext): ForEachStmt =
        ForEachStmt(
            elementType = visitTypeRef(ctx.typeRef()),
            elementName = ctx.Identifier().text,
            iterable = visitExpression(ctx.expression()),
            body = visitBlock(ctx.block()),
            loc = locOf(ctx),
        )

    // Try-catch
    override fun visitTryCatchStatement(ctx: NoxParser.TryCatchStatementContext): TryCatchStmt {
        val tryBlock = visitBlock(ctx.block())
        val catchClauses = ctx.catchClause().map { visitCatchClause(it) }
        return TryCatchStmt(tryBlock, catchClauses, locOf(ctx))
    }

    override fun visitCatchClause(ctx: NoxParser.CatchClauseContext): CatchClause {
        val identifiers = ctx.Identifier()
        return if (identifiers.size == 2) {
            // Typed catch: catch (ExceptionType varName) { ... }
            CatchClause(
                exceptionType = identifiers[0].text,
                variableName = identifiers[1].text,
                body = visitBlock(ctx.block()),
                loc = locOf(ctx),
            )
        } else {
            // Catch-all: catch (varName) { ... }
            CatchClause(
                exceptionType = null,
                variableName = identifiers[0].text,
                body = visitBlock(ctx.block()),
                loc = locOf(ctx),
            )
        }
    }

    // Expressions are dispatched via labeled alternatives
    private fun visitExpression(ctx: NoxParser.ExpressionContext): Expr =
        when (ctx) {
            // Primaries
            is NoxParser.ParenExprContext -> visitExpression(ctx.expression()) // Desugar: unwrap
            is NoxParser.FuncCallExprContext -> buildFuncCallExpr(ctx)
            is NoxParser.IntLiteralExprContext -> IntLiteralExpr(ctx.IntegerLiteral().text.toLong(), locOf(ctx))
            is NoxParser.DoubleLiteralExprContext -> DoubleLiteralExpr(ctx.DoubleLiteral().text.toDouble(), locOf(ctx))
            is NoxParser.BoolLiteralExprContext -> BoolLiteralExpr(ctx.TRUE() != null, locOf(ctx))
            is NoxParser.StringLiteralExprContext ->
                StringLiteralExpr(resolveEscapes(unquote(ctx.StringLiteral().text)), locOf(ctx))
            is NoxParser.TemplateLiteralExprContext -> buildTemplateLiteral(ctx.templateLiteral())
            is NoxParser.NullLiteralExprContext -> NullLiteralExpr(locOf(ctx))
            is NoxParser.ArrayLiteralExprContext -> buildArrayLiteral(ctx.arrayLiteral())
            is NoxParser.StructLiteralExprContext -> buildStructLiteral(ctx.structLiteral())
            is NoxParser.IdentifierExprContext -> IdentifierExpr(ctx.Identifier().text, locOf(ctx))

            // Suffix operators
            is NoxParser.MethodCallExprContext -> buildMethodCallExpr(ctx)
            is NoxParser.FieldAccessExprContext ->
                FieldAccessExpr(visitExpression(ctx.expression()), ctx.Identifier().text, locOf(ctx))
            is NoxParser.IndexAccessExprContext ->
                IndexAccessExpr(visitExpression(ctx.expression(0)), visitExpression(ctx.expression(1)), locOf(ctx))
            is NoxParser.PostfixExprContext -> {
                val op = if (ctx.PLUS_PLUS() != null) PostfixOp.INCREMENT else PostfixOp.DECREMENT
                PostfixExpr(visitExpression(ctx.expression()), op, locOf(ctx))
            }

            // Unary
            is NoxParser.UnaryExprContext -> {
                val op =
                    when {
                        ctx.MINUS() != null -> UnaryOp.NEG
                        ctx.BANG() != null -> UnaryOp.NOT
                        ctx.TILDE() != null -> UnaryOp.BIT_NOT
                        else -> null
                    }
                if (op == null) {
                    ErrorExpr(locOf(ctx))
                } else {
                    UnaryExpr(op, visitExpression(ctx.expression()), locOf(ctx))
                }
            }

            // Cast
            is NoxParser.CastExprContext ->
                CastExpr(visitExpression(ctx.expression()), visitTypeRef(ctx.typeRef()), locOf(ctx))

            // Binary operators
            is NoxParser.MulDivModExprContext -> buildBinaryExpr(ctx, ctx.expression(0), ctx.expression(1))
            is NoxParser.AddSubExprContext -> buildBinaryExpr(ctx, ctx.expression(0), ctx.expression(1))
            is NoxParser.ShiftExprContext -> buildBinaryExpr(ctx, ctx.expression(0), ctx.expression(1))
            is NoxParser.CompareExprContext -> buildBinaryExpr(ctx, ctx.expression(0), ctx.expression(1))
            is NoxParser.EqualityExprContext -> buildBinaryExpr(ctx, ctx.expression(0), ctx.expression(1))
            is NoxParser.BitAndExprContext -> buildBinaryExpr(ctx, ctx.expression(0), ctx.expression(1))
            is NoxParser.BitXorExprContext -> buildBinaryExpr(ctx, ctx.expression(0), ctx.expression(1))
            is NoxParser.BitOrExprContext -> buildBinaryExpr(ctx, ctx.expression(0), ctx.expression(1))
            is NoxParser.LogicAndExprContext -> buildBinaryExpr(ctx, ctx.expression(0), ctx.expression(1))
            is NoxParser.LogicOrExprContext -> buildBinaryExpr(ctx, ctx.expression(0), ctx.expression(1))

            else -> ErrorExpr(locOf(ctx))
        }

    // Function call
    private fun buildFuncCallExpr(ctx: NoxParser.FuncCallExprContext): FuncCallExpr {
        val name = ctx.Identifier().text
        val args = ctx.argumentList()?.expression()?.map { visitExpression(it) } ?: emptyList()
        return FuncCallExpr(name, args, locOf(ctx))
    }

    // Method call
    private fun buildMethodCallExpr(ctx: NoxParser.MethodCallExprContext): MethodCallExpr {
        val target = visitExpression(ctx.expression())
        val methodName = ctx.Identifier().text
        val args = ctx.argumentList()?.expression()?.map { visitExpression(it) } ?: emptyList()
        return MethodCallExpr(target, methodName, args, locOf(ctx))
    }

    // Template literal
    private fun buildTemplateLiteral(ctx: NoxParser.TemplateLiteralContext): TemplateLiteralExpr {
        val parts =
            ctx.templatePart().map { part ->
                when (part) {
                    is NoxParser.TemplateTextPartContext ->
                        TemplatePart.Text(resolveTemplateEscapes(part.TEMPLATE_TEXT().text))
                    is NoxParser.TemplateExprPartContext ->
                        TemplatePart.Interpolation(visitExpression(part.expression()))
                    else -> TemplatePart.ErrorPart
                }
            }
        return TemplateLiteralExpr(parts, locOf(ctx))
    }

    // Array literal
    private fun buildArrayLiteral(ctx: NoxParser.ArrayLiteralContext): ArrayLiteralExpr {
        val elements = ctx.expression().map { visitExpression(it) }
        return ArrayLiteralExpr(elements, locOf(ctx))
    }

    // Struct literal
    private fun buildStructLiteral(ctx: NoxParser.StructLiteralContext): StructLiteralExpr {
        val fields =
            ctx.fieldInit().map { fi ->
                FieldInit(
                    name = fi.Identifier().text,
                    value = visitExpression(fi.expression()),
                    loc = locOf(fi),
                )
            }
        return StructLiteralExpr(fields, locOf(ctx))
    }

    // Binary expression builder maps operator tokens to BinaryOp
    private fun buildBinaryExpr(
        ctx: ParserRuleContext,
        leftCtx: NoxParser.ExpressionContext,
        rightCtx: NoxParser.ExpressionContext,
    ): Expr {
        val op = mapBinaryOp(ctx) ?: return ErrorExpr(locOf(ctx))
        val left = visitExpression(leftCtx)
        val right = visitExpression(rightCtx)
        return BinaryExpr(left, op, right, locOf(ctx))
    }

    private fun mapBinaryOp(ctx: ParserRuleContext): BinaryOp? =
        when (ctx) {
            is NoxParser.MulDivModExprContext ->
                when {
                    ctx.STAR() != null -> BinaryOp.MUL
                    ctx.SLASH() != null -> BinaryOp.DIV
                    ctx.PERCENT() != null -> BinaryOp.MOD
                    else -> null
                }
            is NoxParser.AddSubExprContext ->
                when {
                    ctx.PLUS() != null -> BinaryOp.ADD
                    ctx.MINUS() != null -> BinaryOp.SUB
                    else -> null
                }
            is NoxParser.ShiftExprContext ->
                when {
                    ctx.SHL() != null -> BinaryOp.SHL
                    ctx.SHR() != null -> BinaryOp.SHR
                    ctx.USHR() != null -> BinaryOp.USHR
                    else -> null
                }
            is NoxParser.CompareExprContext ->
                when {
                    ctx.LT() != null -> BinaryOp.LT
                    ctx.LE() != null -> BinaryOp.LE
                    ctx.GT() != null -> BinaryOp.GT
                    ctx.GE() != null -> BinaryOp.GE
                    else -> null
                }
            is NoxParser.EqualityExprContext ->
                when {
                    ctx.EQ() != null -> BinaryOp.EQ
                    ctx.NE() != null -> BinaryOp.NE
                    else -> null
                }
            is NoxParser.BitAndExprContext -> BinaryOp.BIT_AND
            is NoxParser.BitXorExprContext -> BinaryOp.BIT_XOR
            is NoxParser.BitOrExprContext -> BinaryOp.BIT_OR
            is NoxParser.LogicAndExprContext -> BinaryOp.AND
            is NoxParser.LogicOrExprContext -> BinaryOp.OR
            else -> null
        }

    // Map assignment operators
    private fun mapAssignOp(ctx: NoxParser.AssignOpContext): AssignOp? =
        when {
            ctx.ASSIGN() != null -> AssignOp.ASSIGN
            ctx.PLUS_ASSIGN() != null -> AssignOp.ADD_ASSIGN
            ctx.MINUS_ASSIGN() != null -> AssignOp.SUB_ASSIGN
            ctx.STAR_ASSIGN() != null -> AssignOp.MUL_ASSIGN
            ctx.SLASH_ASSIGN() != null -> AssignOp.DIV_ASSIGN
            ctx.PERCENT_ASSIGN() != null -> AssignOp.MOD_ASSIGN
            else -> null
        }

    // Source location extraction
    private fun locOf(ctx: ParserRuleContext) =
        SourceLocation(
            file = fileName,
            line = ctx.start.line,
            column = ctx.start.charPositionInLine,
        )

    // String utilities

    /** Remove surrounding double quotes from a string literal token. */
    private fun unquote(s: String): String = s.substring(1, s.length - 1)

    /** Resolve standard escape sequences in a string literal value. */
    private fun resolveEscapes(s: String): String =
        buildString {
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'n' -> append('\n')
                        't' -> append('\t')
                        'r' -> append('\r')
                        'b' -> append('\b')
                        'f' -> append('\u000C')
                        '"' -> append('"')
                        '\'' -> append('\'')
                        '\\' -> append('\\')
                        'u' -> {
                            if (i + 5 < s.length) {
                                val hex = s.substring(i + 2, i + 6)
                                append(hex.toInt(16).toChar())
                                i += 6
                                continue
                            }
                            append(s[i])
                        }
                        else -> {
                            append(s[i])
                            append(s[i + 1])
                        }
                    }
                    i += 2
                } else {
                    append(s[i])
                    i++
                }
            }
        }

    /** Resolve escape sequences within template literal text segments. */
    private fun resolveTemplateEscapes(s: String): String =
        buildString {
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        '`' -> append('`')
                        '$' -> append('$')
                        '\\' -> append('\\')
                        'n' -> append('\n')
                        't' -> append('\t')
                        else -> {
                            append(s[i])
                            append(s[i + 1])
                        }
                    }
                    i += 2
                } else {
                    append(s[i])
                    i++
                }
            }
        }
}
