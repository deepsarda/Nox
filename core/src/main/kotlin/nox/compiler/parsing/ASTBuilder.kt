package nox.compiler.parsing

import nox.compiler.CompilerErrors
import nox.compiler.ast.*
import nox.compiler.types.*
import nox.parser.NoxParser
import nox.parser.NoxParserBaseVisitor
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.TerminalNode

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
 * - Template literal tokens are combined into [RawTemplateLiteralExpr] parts
 *
 * @property fileName the source file name, attached to every [SourceLocation]
 * @property errors   shared error collector for reporting issues during AST construction
 * @see <a href="file:///docs/compiler/ast.md">AST Design</a>
 */
class ASTBuilder(
    private val fileName: String,
    private val errors: CompilerErrors = CompilerErrors(),
) : NoxParserBaseVisitor<Any>() {
    // RawProgram
    override fun visitProgram(ctx: NoxParser.ProgramContext): RawProgram {
        val headers = ctx.header().map { visitHeader(it) }
        val imports = ctx.importDeclaration().map { visitImportDeclaration(it) }
        val declarations = ctx.topLevelDeclaration().map { visitTopLevelDeclaration(it) }

        val program = RawProgram(fileName, headers, imports, declarations)

        // Populate convenience maps
        for (decl in declarations) {
            when (decl) {
                is RawTypeDef -> {
                    if (program.typesByName.containsKey(decl.name)) {
                        errors.report(
                            decl.nameLoc,
                            "Duplicate type declaration: '${decl.name}'",
                            suggestion = "Rename one of the type definitions or remove the duplicate",
                        )
                    } else {
                        program.typesByName[decl.name] = decl
                    }
                }
                is RawFuncDef -> {
                    if (program.functionsByName.containsKey(decl.name)) {
                        errors.report(
                            decl.nameLoc,
                            "Duplicate function declaration: '${decl.name}'",
                            suggestion = "Rename one of the functions or remove the duplicate",
                        )
                    } else {
                        program.functionsByName[decl.name] = decl
                    }
                }
                is RawMainDef -> Unit
                is RawGlobalVarDecl -> program.globals.add(decl)
                is RawImportDecl -> Unit
                is RawErrorDecl -> Unit
            }
        }

        return program
    }

    // Headers
    override fun visitHeader(ctx: NoxParser.HeaderContext): RawHeader {
        val keyToken = ctx.HEADER_KEY()
        val valToken = ctx.StringLiteral()

        return if (keyToken == null || valToken == null) {
            RawErrorHeader(locOf(ctx))
        } else {
            val rawKey = keyToken.text
            val key = rawKey.removePrefix("@tool:")
            val value = unquote(valToken.text)
            RawHeaderImpl(key, value, locOf(ctx))
        }
    }

    // Imports
    override fun visitImportDeclaration(ctx: NoxParser.ImportDeclarationContext): RawDecl {
        val pathToken = ctx.StringLiteral()
        val idToken = ctx.Identifier()

        return if (pathToken == null || idToken == null) {
            RawErrorDecl(locOf(ctx))
        } else {
            RawImportDecl(unquote(pathToken.text), idToken.text, locOf(ctx))
        }
    }

    // Top-level declarations
    override fun visitTopLevelDeclaration(ctx: NoxParser.TopLevelDeclarationContext): RawDecl =
        when {
            ctx.typeDefinition() != null -> visitTypeDefinition(ctx.typeDefinition())
            ctx.functionDefinition() != null -> visitFunctionDefinition(ctx.functionDefinition())
            ctx.mainDefinition() != null -> visitMainDefinition(ctx.mainDefinition())
            ctx.variableDeclaration() != null -> {
                val varDecl = ctx.variableDeclaration()
                val type = visitTypeRef(varDecl?.typeRef())
                val id = varDecl?.Identifier()
                if (varDecl == null || type == null || id == null) {
                    RawErrorDecl(locOf(ctx))
                } else {
                    val init = visitExpression(varDecl.expression())
                    RawGlobalVarDecl(type, id.text, locOf(id), init, locOf(varDecl))
                }
            }
            else -> RawErrorDecl(locOf(ctx))
        }

    // Type definitions
    override fun visitTypeDefinition(ctx: NoxParser.TypeDefinitionContext): RawDecl {
        val id = ctx.Identifier() ?: return RawErrorDecl(locOf(ctx))
        val fields =
            ctx.fieldDeclaration().map { fd ->
                val fieldType = visitTypeRef(fd.typeRef())
                val fieldId = fd.Identifier()
                if (fieldType == null || fieldId == null) {
                    RawErrorFieldDecl(locOf(fd))
                } else {
                    RawFieldDeclImpl(
                        type = fieldType,
                        name = fieldId.text,
                        nameLoc = locOf(fieldId),
                        loc = locOf(fd),
                    )
                }
            }
        return RawTypeDef(id.text, locOf(id), fields, locOf(ctx))
    }

    // Function definitions
    override fun visitFunctionDefinition(ctx: NoxParser.FunctionDefinitionContext): RawDecl {
        val returnType = visitTypeRef(ctx.typeRef()) ?: return RawErrorDecl(locOf(ctx))
        val id = ctx.Identifier() ?: return RawErrorDecl(locOf(ctx))
        val params = buildParamList(ctx.parameterList())
        val blockCtx = ctx.block()
        val body = visitBlock(blockCtx)
        return RawFuncDef(returnType, id.text, locOf(id), params, body, locOf(ctx))
    }

    // Main definition
    override fun visitMainDefinition(ctx: NoxParser.MainDefinitionContext): RawMainDef {
        val params = buildParamList(ctx.parameterList())
        val blockCtx = ctx.block()
        val body = visitBlock(blockCtx)
        return RawMainDef(params, body, locOf(ctx))
    }

    // Parameters
    private fun buildParamList(ctx: NoxParser.ParameterListContext?): List<RawParam> =
        ctx?.parameter()?.map { visitParameter(it) } ?: emptyList()

    override fun visitParameter(ctx: NoxParser.ParameterContext): RawParam {
        val type = visitTypeRef(ctx.typeRef())
        val id = ctx.Identifier()
        if (type == null || id == null) return RawErrorParam(locOf(ctx))

        val isVarargs = ctx.ELLIPSIS() != null
        val defaultValue = ctx.expression()?.let { visitExpression(it) }

        val actualType = if (isVarargs) type.arrayOf() else type
        return RawParamImpl(actualType, id.text, locOf(id), defaultValue, isVarargs, locOf(ctx))
    }

    // Type references
    override fun visitTypeRef(ctx: NoxParser.TypeRefContext?): TypeRef? {
        if (ctx == null) return null
        val baseName =
            ctx.primitiveType()?.text
                ?: ctx.Identifier()?.text
                ?: run {
                    errors.report(
                        locOf(ctx),
                        "Expected a type name here",
                        suggestion =
                            "Use a built-in type (int, double, boolean, string, json) or a declared struct name",
                    )
                    return null
                }
        val arrayDepth = ctx.LBRACK().size
        return TypeRef(baseName, arrayDepth)
    }

    // RawBlock
    override fun visitBlock(ctx: NoxParser.BlockContext?): RawBlock {
        if (ctx == null) return RawBlock(emptyList(), SourceLocation(fileName, 0, 0))
        val stmts = ctx.statement().map { visitStatement(it) }
        return RawBlock(stmts, locOf(ctx))
    }

    // Statements are dispatched via labeled alternatives
    private fun visitStatement(ctx: NoxParser.StatementContext?): RawStmt {
        if (ctx == null) return RawErrorStmt(SourceLocation(fileName, 0, 0))
        return when (ctx) {
            is NoxParser.VarDeclStmtContext -> {
                val vd = ctx.variableDeclaration()
                val type = visitTypeRef(vd?.typeRef())
                val id = vd?.Identifier()
                if (vd == null || type == null || id == null) {
                    RawErrorStmt(locOf(ctx))
                } else {
                    RawVarDeclStmt(
                        type = type,
                        name = id.text,
                        nameLoc = locOf(id),
                        initializer = visitExpression(vd.expression()),
                        loc = locOf(vd),
                    )
                }
            }
            is NoxParser.AssignStmtContext -> {
                val target = visitExpression(ctx.expression(0))
                val op = mapAssignOp(ctx.assignOp()) ?: return RawErrorStmt(locOf(ctx))
                val value = visitExpression(ctx.expression(1))
                RawAssignStmt(target, op, value, locOf(ctx))
            }
            is NoxParser.IncrementStmtContext -> {
                val target = visitExpression(ctx.expression())
                val op = if (ctx.PLUS_PLUS() != null) PostfixOp.INCREMENT else PostfixOp.DECREMENT
                RawIncrementStmt(target, op, locOf(ctx))
            }
            is NoxParser.IfStmtContext -> visitIfStatement(ctx.ifStatement())
            is NoxParser.WhileStmtContext -> visitWhileStatement(ctx.whileStatement())
            is NoxParser.ForStmtContext -> visitForStatement(ctx.forStatement())
            is NoxParser.ForeachStmtContext -> visitForeachStatement(ctx.foreachStatement())
            is NoxParser.ReturnStmtContext -> {
                val value = ctx.expression()?.let { visitExpression(it) }
                RawReturnStmt(value, locOf(ctx))
            }
            is NoxParser.YieldStmtContext -> RawYieldStmt(visitExpression(ctx.expression()), locOf(ctx))
            is NoxParser.BreakStmtContext -> RawBreakStmt(locOf(ctx))
            is NoxParser.ContinueStmtContext -> RawContinueStmt(locOf(ctx))
            is NoxParser.ThrowStmtContext -> RawThrowStmt(visitExpression(ctx.expression()), locOf(ctx))
            is NoxParser.TryCatchStmtContext -> visitTryCatchStatement(ctx.tryCatchStatement())
            is NoxParser.ExpressionStmtContext -> RawExprStmt(visitExpression(ctx.expression()), locOf(ctx))
            else -> RawErrorStmt(locOf(ctx))
        }
    }

    // If/else-if/else
    override fun visitIfStatement(ctx: NoxParser.IfStatementContext): RawIfStmt {
        val expressions = ctx.expression()
        val blocks = ctx.block()

        val condition = if (expressions.isNotEmpty()) visitExpression(expressions[0]) else RawErrorExpr(locOf(ctx))
        val thenBlock = if (blocks.isNotEmpty()) visitBlock(blocks[0]) else RawBlock(emptyList(), locOf(ctx))

        // else-if branches: expressions[1..n-1] and blocks[1..n-1]
        val hasElse = blocks.size > expressions.size
        val elseIfCount = (expressions.size - 1).coerceAtLeast(0)

        val elseIfs =
            (0 until elseIfCount).mapNotNull { i ->
                if (i + 1 < expressions.size && i + 1 < blocks.size) {
                    RawIfStmt.ElseIf(
                        condition = visitExpression(expressions[i + 1]),
                        body = visitBlock(blocks[i + 1]),
                        loc = locOf(expressions[i + 1]),
                    )
                } else {
                    null
                }
            }

        val elseBlock = if (hasElse && blocks.isNotEmpty()) visitBlock(blocks.last()) else null

        return RawIfStmt(condition, thenBlock, elseIfs, elseBlock, locOf(ctx))
    }

    // While
    override fun visitWhileStatement(ctx: NoxParser.WhileStatementContext): RawWhileStmt =
        RawWhileStmt(visitExpression(ctx.expression()), visitBlock(ctx.block()), locOf(ctx))

    // For
    override fun visitForStatement(ctx: NoxParser.ForStatementContext): RawForStmt {
        val init = ctx.forInit()?.let { buildForInit(it) }
        val condition = ctx.expression()?.let { visitExpression(it) }
        val update = ctx.forUpdate()?.let { buildForUpdate(it) }
        val body = visitBlock(ctx.block())
        return RawForStmt(init, condition, update, body, locOf(ctx))
    }

    private fun buildForInit(ctx: NoxParser.ForInitContext): RawStmt =
        when {
            ctx.variableDeclaration() != null -> {
                val vd = ctx.variableDeclaration()
                val type = visitTypeRef(vd?.typeRef())
                val id = vd?.Identifier()
                if (vd == null || type == null || id == null) {
                    RawErrorStmt(locOf(ctx))
                } else {
                    RawVarDeclStmt(
                        type = type,
                        name = id.text,
                        nameLoc = locOf(id),
                        initializer = visitExpression(vd.expression()),
                        loc = locOf(vd),
                    )
                }
            }
            else -> {
                val target = visitExpression(ctx.expression(0))
                val op = mapAssignOp(ctx.assignOp()) ?: return RawErrorStmt(locOf(ctx))
                val value = visitExpression(ctx.expression(1))
                RawAssignStmt(target, op, value, locOf(ctx))
            }
        }

    private fun buildForUpdate(ctx: NoxParser.ForUpdateContext): RawStmt =
        when {
            ctx.assignOp() != null -> {
                val target = visitExpression(ctx.expression(0))
                val op = mapAssignOp(ctx.assignOp()) ?: return RawErrorStmt(locOf(ctx))
                val value = visitExpression(ctx.expression(1))
                RawAssignStmt(target, op, value, locOf(ctx))
            }
            else -> {
                val target = visitExpression(ctx.expression(0))
                val op = if (ctx.PLUS_PLUS() != null) PostfixOp.INCREMENT else PostfixOp.DECREMENT
                RawIncrementStmt(target, op, locOf(ctx))
            }
        }

    // Foreach
    override fun visitForeachStatement(ctx: NoxParser.ForeachStatementContext): RawStmt {
        val type = visitTypeRef(ctx.typeRef())
        val id = ctx.Identifier()
        val iterable = visitExpression(ctx.expression())
        val body = visitBlock(ctx.block())

        return if (type == null || id == null) {
            RawErrorStmt(locOf(ctx))
        } else {
            RawForEachStmt(type, id.text, iterable, body, locOf(ctx))
        }
    }

    // Try-catch
    override fun visitTryCatchStatement(ctx: NoxParser.TryCatchStatementContext): RawTryCatchStmt {
        val tryBlock = visitBlock(ctx.block())
        val catchClauses = ctx.catchClause().mapNotNull { visitCatchClause(it) }
        return RawTryCatchStmt(tryBlock, catchClauses, locOf(ctx))
    }

    override fun visitCatchClause(ctx: NoxParser.CatchClauseContext): RawCatchClause? {
        val identifiers = ctx.Identifier()
        val block = ctx.block() ?: return null

        return if (identifiers.size == 2) {
            // Typed catch: catch (ExceptionType varName) { ... }
            RawCatchClause(
                exceptionType = identifiers[0].text,
                variableName = identifiers[1].text,
                body = visitBlock(block),
                loc = locOf(ctx),
            )
        } else if (identifiers.size == 1) {
            // Catch-all: catch (varName) { ... }
            RawCatchClause(
                exceptionType = null,
                variableName = identifiers[0].text,
                body = visitBlock(block),
                loc = locOf(ctx),
            )
        } else {
            null
        }
    }

    // Expressions are dispatched via labeled alternatives
    private fun visitExpression(ctx: NoxParser.ExpressionContext?): RawExpr {
        if (ctx == null) return RawErrorExpr(SourceLocation(fileName, 0, 0))
        return when (ctx) {
            // Primaries
            is NoxParser.ParenExprContext -> visitExpression(ctx.expression()) // Desugar: unwrap
            is NoxParser.FuncCallExprContext -> buildFuncCallExpr(ctx)
            is NoxParser.IntLiteralExprContext -> {
                val token = ctx.IntegerLiteral()
                if (token == null) return RawErrorExpr(locOf(ctx))
                try {
                    RawIntLiteralExpr(token.text.toLong(), locOf(ctx))
                } catch (_: NumberFormatException) {
                    errors.report(
                        locOf(ctx),
                        "Integer literal '${token.text}' is too large. Nox integers are 64-bit signed (max: 9,223,372,036,854,775,807)",
                        suggestion = "Use a 'double' literal if you need a larger value",
                    )
                    RawErrorExpr(locOf(ctx))
                }
            }
            is NoxParser.DoubleLiteralExprContext -> {
                val token = ctx.DoubleLiteral()
                if (token == null) return RawErrorExpr(locOf(ctx))
                try {
                    RawDoubleLiteralExpr(token.text.toDouble(), locOf(ctx))
                } catch (_: NumberFormatException) {
                    errors.report(
                        locOf(ctx),
                        "'${token.text}' is not a valid floating-point number",
                        suggestion = "Expected a format like '3.14' or '1.5e10'",
                    )
                    RawErrorExpr(locOf(ctx))
                }
            }
            is NoxParser.BoolLiteralExprContext -> RawBoolLiteralExpr(ctx.TRUE() != null, locOf(ctx))
            is NoxParser.StringLiteralExprContext -> {
                val token = ctx.StringLiteral()
                if (token == null) {
                    RawErrorExpr(locOf(ctx))
                } else {
                    RawStringLiteralExpr(resolveEscapes(unquote(token.text)), locOf(ctx))
                }
            }
            is NoxParser.TemplateLiteralExprContext -> buildTemplateLiteral(ctx.templateLiteral())
            is NoxParser.NullLiteralExprContext -> RawNullLiteralExpr(locOf(ctx))
            is NoxParser.ArrayLiteralExprContext -> buildArrayLiteral(ctx.arrayLiteral())
            is NoxParser.StructLiteralExprContext -> buildStructLiteral(ctx.structLiteral())
            is NoxParser.IdentifierExprContext -> {
                val id = ctx.Identifier()
                if (id == null) {
                    RawErrorExpr(locOf(ctx))
                } else {
                    RawIdentifierExpr(id.text, locOf(ctx))
                }
            }

            // Suffix operators
            is NoxParser.MethodCallExprContext -> buildMethodCallExpr(ctx)
            is NoxParser.FieldAccessExprContext -> {
                val id = ctx.Identifier()
                if (id == null) {
                    RawErrorExpr(locOf(ctx))
                } else {
                    RawFieldAccessExpr(visitExpression(ctx.expression()), id.text, locOf(id), locOf(ctx))
                }
            }
            is NoxParser.IndexAccessExprContext ->
                RawIndexAccessExpr(visitExpression(ctx.expression(0)), visitExpression(ctx.expression(1)), locOf(ctx))
            is NoxParser.PostfixExprContext -> {
                val op = if (ctx.PLUS_PLUS() != null) PostfixOp.INCREMENT else PostfixOp.DECREMENT
                RawPostfixExpr(visitExpression(ctx.expression()), op, locOf(ctx))
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
                    RawErrorExpr(locOf(ctx))
                } else {
                    RawUnaryExpr(op, visitExpression(ctx.expression()), locOf(ctx))
                }
            }

            // Cast
            is NoxParser.CastExprContext -> {
                val type = visitTypeRef(ctx.typeRef())
                if (type == null) {
                    RawErrorExpr(locOf(ctx))
                } else {
                    RawCastExpr(visitExpression(ctx.expression()), type, locOf(ctx))
                }
            }

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

            else -> RawErrorExpr(locOf(ctx))
        }
    }

    // Function call
    private fun buildFuncCallExpr(ctx: NoxParser.FuncCallExprContext): RawExpr {
        val id = ctx.Identifier() ?: return RawErrorExpr(locOf(ctx))
        val args = ctx.argumentList()?.expression()?.map { visitExpression(it) } ?: emptyList()
        return RawFuncCallExpr(id.text, args, locOf(ctx))
    }

    // Method call
    private fun buildMethodCallExpr(ctx: NoxParser.MethodCallExprContext): RawExpr {
        val target = visitExpression(ctx.expression())
        val id = ctx.Identifier() ?: return RawErrorExpr(locOf(ctx))
        val args = ctx.argumentList()?.expression()?.map { visitExpression(it) } ?: emptyList()
        return RawMethodCallExpr(target, id.text, locOf(id), args, locOf(ctx))
    }

    // Template literal
    private fun buildTemplateLiteral(ctx: NoxParser.TemplateLiteralContext): RawTemplateLiteralExpr {
        val parts =
            ctx.templatePart().map { part ->
                when (part) {
                    is NoxParser.TemplateTextPartContext ->
                        RawTemplatePart.Text(resolveTemplateEscapes(part.TEMPLATE_TEXT()?.text ?: ""))
                    is NoxParser.TemplateExprPartContext ->
                        RawTemplatePart.Interpolation(visitExpression(part.expression()))
                    else -> RawTemplatePart.ErrorPart // DEFENSIVE: Unreachable defensive guard
                }
            }
        return RawTemplateLiteralExpr(parts, locOf(ctx))
    }

    // Array literal
    private fun buildArrayLiteral(ctx: NoxParser.ArrayLiteralContext): RawArrayLiteralExpr {
        val elements = ctx.expression().map { visitExpression(it) }
        return RawArrayLiteralExpr(elements, locOf(ctx))
    }

    // Struct literal
    private fun buildStructLiteral(ctx: NoxParser.StructLiteralContext): RawStructLiteralExpr {
        val fields =
            ctx.fieldInit().map { fi ->
                val id = fi.Identifier()
                if (id == null) {
                    RawErrorFieldInit(locOf(fi))
                } else {
                    RawFieldInitImpl(
                        name = id.text,
                        value = visitExpression(fi.expression()),
                        loc = locOf(fi),
                    )
                }
            }
        return RawStructLiteralExpr(fields, locOf(ctx))
    }

    // Binary expression builder maps operator tokens to BinaryOp
    private fun buildBinaryExpr(
        ctx: ParserRuleContext,
        leftCtx: NoxParser.ExpressionContext,
        rightCtx: NoxParser.ExpressionContext,
    ): RawExpr {
        val op = mapBinaryOp(ctx) ?: return RawErrorExpr(locOf(ctx))
        val left = visitExpression(leftCtx)
        val right = visitExpression(rightCtx)
        return RawBinaryExpr(left, op, right, locOf(ctx))
    }

    private fun mapBinaryOp(ctx: ParserRuleContext): BinaryOp? =
        when (ctx) {
            is NoxParser.MulDivModExprContext ->
                when {
                    ctx.STAR() != null -> BinaryOp.MUL
                    ctx.SLASH() != null -> BinaryOp.DIV
                    ctx.PERCENT() != null -> BinaryOp.MOD
                    else -> null // DEFENSIVE: Unreachable defensive guard
                }
            is NoxParser.AddSubExprContext ->
                when {
                    ctx.PLUS() != null -> BinaryOp.ADD
                    ctx.MINUS() != null -> BinaryOp.SUB
                    else -> null // DEFENSIVE: Unreachable defensive guard
                }
            is NoxParser.ShiftExprContext ->
                when {
                    ctx.SHL() != null -> BinaryOp.SHL
                    ctx.SHR() != null -> BinaryOp.SHR
                    ctx.USHR() != null -> BinaryOp.USHR
                    else -> null // DEFENSIVE: Unreachable defensive guard
                }
            is NoxParser.CompareExprContext ->
                when {
                    ctx.LT() != null -> BinaryOp.LT
                    ctx.LE() != null -> BinaryOp.LE
                    ctx.GT() != null -> BinaryOp.GT
                    ctx.GE() != null -> BinaryOp.GE
                    else -> null // DEFENSIVE: Unreachable defensive guard
                }
            is NoxParser.EqualityExprContext ->
                when {
                    ctx.EQ() != null -> BinaryOp.EQ
                    ctx.NE() != null -> BinaryOp.NE
                    else -> null // DEFENSIVE: Unreachable defensive guard
                }
            is NoxParser.BitAndExprContext -> BinaryOp.BIT_AND
            is NoxParser.BitXorExprContext -> BinaryOp.BIT_XOR
            is NoxParser.BitOrExprContext -> BinaryOp.BIT_OR
            is NoxParser.LogicAndExprContext -> BinaryOp.AND
            is NoxParser.LogicOrExprContext -> BinaryOp.OR
            else -> null // DEFENSIVE: Unreachable defensive guard
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
            else -> null // DEFENSIVE: Unreachable defensive guard
        }

    // Source location extraction
    private fun locOf(ctx: ParserRuleContext) =
        SourceLocation(
            file = fileName,
            line = ctx.start.line,
            column = ctx.start.charPositionInLine,
        )

    private fun locOf(node: TerminalNode) =
        SourceLocation(
            file = fileName,
            line = node.symbol.line,
            column = node.symbol.charPositionInLine,
        )

    // String utilities

    /** Remove surrounding double quotes from a string literal token. */
    private fun unquote(s: String): String {
        if (s.length < 2 || s.first() != '"' || s.last() != '"') return s
        return s.substring(1, s.length - 1)
    }

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
                                try {
                                    append(hex.toInt(16).toChar())
                                } catch (_: NumberFormatException) {
                                    append("\\u")
                                    append(hex)
                                }
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
