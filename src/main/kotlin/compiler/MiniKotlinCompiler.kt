package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser
import org.antlr.v4.runtime.tree.RuleNode
import org.antlr.v4.runtime.tree.TerminalNode

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {

    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        return """
public class $className {
${visit(program)}
}
        """.trimIndent()
    }

    override fun visitProgram(ctx: MiniKotlinParser.ProgramContext): String {
        var result = ""
        for (funDeclaration in ctx.functionDeclaration()) {
            result += visit(funDeclaration)
        }
        return result
    }

    override fun visitFunctionDeclaration(ctx: MiniKotlinParser.FunctionDeclarationContext): String {
        val modifiers = "public static"
        val funName = visit(ctx.IDENTIFIER())
        val type = visit(ctx.type()) // TODO: this is not CPS compliant
        val block = visit(ctx.block())
        val params = when (val params = ctx.parameterList()) {
            null -> if (funName == "main") "String[] args" else ""
            else -> visit(params)
        }

        return "$modifiers $type $funName($params) $block\n"
    }

    override fun visitParameterList(ctx: MiniKotlinParser.ParameterListContext): String {
        var result = visit(ctx.parameter(0))
        for (param in ctx.parameter().drop(1)) {
            result += ", ${visit(param)}"
        }
        return result
    }

    override fun visitParameter(ctx: MiniKotlinParser.ParameterContext): String {
        val paramName = visit(ctx.IDENTIFIER())
        val type = visit(ctx.type())
        return "$type $paramName"
    }

    override fun visitType(ctx: MiniKotlinParser.TypeContext): String? {
        return when {
            ctx.BOOLEAN_TYPE() != null -> "Boolean"
            ctx.INT_TYPE() != null -> "Integer"
            ctx.STRING_TYPE() != null -> "String"
            ctx.UNIT_TYPE() != null -> "void"
            else -> null
        }
    }

    override fun visitBlock(ctx: MiniKotlinParser.BlockContext): String {
        var result = ""
        for (statement in ctx.statement()) {
            result += "${visit(statement)}\n"
        }
        return "{\n$result}"
    }

    override fun visitStatement(ctx: MiniKotlinParser.StatementContext): String {
        return when {
            ctx.ifStatement() != null || ctx.whileStatement() != null -> visitChildren(ctx)
            else -> "${visitChildren(ctx)};"
        }
    }

    override fun visitVariableDeclaration(ctx: MiniKotlinParser.VariableDeclarationContext): String {
        val varName = visit(ctx.IDENTIFIER())
        val type = visit(ctx.type())
        val expression = visit(ctx.expression())
        return "$type $varName = $expression"
    }

    override fun visitVariableAssignment(ctx: MiniKotlinParser.VariableAssignmentContext): String {
        val varName = visit(ctx.IDENTIFIER())
        val expression = visit(ctx.expression())
        return "$varName = $expression"
    }

    // TODO: make CPS compliant
    override fun visitReturnStatement(ctx: MiniKotlinParser.ReturnStatementContext): String {
        val expression = when (val expressionContext = ctx.expression()) {
            null -> ""
            else -> visit(expressionContext)
        }
        return "return $expression"
    }

    override fun visitEqualityExpr(ctx: MiniKotlinParser.EqualityExprContext): String {
        val left = visit(ctx.expression(0))
        val right = visit(ctx.expression(1))
        val not = if (ctx.NEQ() != null) "!" else ""
        return "${not}java.util.Objects.equals($left,$right)"
    }

    override fun visitTerminal(node: TerminalNode): String = when (node.text) {
        "println" -> "System.out.println" // only known function from outside scope
        else -> node.text
    }

    // override to implement common syntax by default
    override fun visitChildren(node: RuleNode): String {
        var result = ""
        for (i in 0..<node.childCount) {
            if (!shouldVisitNextChild(node, result)) break
            result += visit(node.getChild(i))
        }
        return result
    }
}
