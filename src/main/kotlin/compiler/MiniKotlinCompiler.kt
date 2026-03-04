package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser
import org.antlr.v4.runtime.tree.RuleNode
import org.antlr.v4.runtime.tree.TerminalNode

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {

    val contFunName = "__continuation"
    val contArgName = "arg"
    val expressionFunCalls = mutableListOf<Pair<String, String>>()
    var blockFunCalls = 0

    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        return """
import java.util.Objects;
import java.util.Comparator;
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
        val modifiers = "public static void"
        val funName = visit(ctx.IDENTIFIER())
        val type = visit(ctx.type())
        val block = visit(ctx.block())
        val params = when (val params = ctx.parameterList()) {
            null -> if (funName == "main") "String[] args" else ""
            else -> "${visit(params)}, Continuation<$type> $contFunName"
        }

        return "$modifiers $funName($params) $block\n"
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

        for (i in 0..<blockFunCalls) result += "\n});"
        blockFunCalls = 0
        return "{\n$result}"
    }

    override fun visitStatement(ctx: MiniKotlinParser.StatementContext): String {
        val statement = when {
            ctx.ifStatement() != null || ctx.whileStatement() != null -> visitChildren(ctx)
            ctx.expression() != null -> {
                visitChildren(ctx); "" // the only expression is function call
            }

            else -> "${visitChildren(ctx)};"
        }

        var funCalls = ""
        for (funCall in expressionFunCalls) {
            funCalls += "${funCall.first}(${funCall.second}, ($contArgName$blockFunCalls) -> {\n"
            blockFunCalls++
        }
        expressionFunCalls.clear()

        return "$funCalls$statement"
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

    override fun visitReturnStatement(ctx: MiniKotlinParser.ReturnStatementContext): String {
        val expression = when (val expressionContext = ctx.expression()) {
            null -> ""
            else -> visit(expressionContext)
        }
        return "$contFunName.accept($expression);\nreturn"
    }

    override fun visitFunctionCallExpr(ctx: MiniKotlinParser.FunctionCallExprContext): String {
        val funName = visit(ctx.IDENTIFIER())
        val argList = visit(ctx.argumentList())
        val i = expressionFunCalls.size
        expressionFunCalls.add(Pair(funName, argList))
        return "$contArgName${i}"
    }

    override fun visitComparisonExpr(ctx: MiniKotlinParser.ComparisonExprContext): String {
        val left = visit(ctx.expression(0))
        val right = visit(ctx.expression(1))
        val op = visit(ctx.getChild(1))
        return "Objects.compare($left,$right,Comparator.naturalOrder()) $op 0"
    }

    override fun visitEqualityExpr(ctx: MiniKotlinParser.EqualityExprContext): String {
        val left = visit(ctx.expression(0))
        val right = visit(ctx.expression(1))
        val not = if (ctx.NEQ() != null) "!" else ""
        return "${not}Objects.equals($left,$right)"
    }

    override fun visitTerminal(node: TerminalNode): String = when (node.text) {
        "println" -> "Prelude.println" // only known function from outside scope
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
