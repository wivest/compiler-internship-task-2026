package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.tree.RuleNode
import org.antlr.v4.runtime.tree.TerminalNode
import org.antlr.v4.runtime.tree.TerminalNodeImpl

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {

    val contFunName = "__continuation"
    val contArgName = "arg"
    val currentExpressionFunCalls = mutableListOf<Pair<String, String>>()
    val blockFunCalls = mutableListOf<Int>()
    val whileVarName = "while"
    val whileLoopElseBlocks = mutableListOf<MiniKotlinParser.BlockContext>()
    var whileLoops = 0

    val funParams = mutableListOf<String>() // not boxed types

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

    // PROBLEM: declaration allows no parameters, but calling requires at least one argument
    override fun visitFunctionDeclaration(ctx: MiniKotlinParser.FunctionDeclarationContext): String {
        val modifiers = "public static void"
        val funName = visit(ctx.IDENTIFIER())
        val type = visit(ctx.type())
        val params = when (val params = ctx.parameterList()) {
            null -> if (funName == "main") "String[] args" else "Continuation<$type> $contFunName"
            else -> "${visit(params)}, Continuation<$type> $contFunName"
        }
        val block = visit(ctx.block())

        funParams.clear()
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
        funParams.add(paramName)
        return "$type $paramName"
    }

    override fun visitType(ctx: MiniKotlinParser.TypeContext): String? {
        return when {
            ctx.BOOLEAN_TYPE() != null -> "Boolean"
            ctx.INT_TYPE() != null -> "Integer"
            ctx.STRING_TYPE() != null -> "String"
            ctx.UNIT_TYPE() != null -> "Void"
            else -> null
        }
    }

    fun refactorIf(ctx: MiniKotlinParser.BlockContext, ifCtx: MiniKotlinParser.IfStatementContext, i: Int) {
        // move statements and break
        val block = ifCtx.block(0)
        val elseBlock = ifCtx.block(1) ?: run {
            ifCtx.addChild(TerminalNodeImpl(CommonToken(MiniKotlinLexer.ELSE, "else")))

            val elseBlock = MiniKotlinParser.BlockContext(ifCtx, ifCtx.invokingState)
            elseBlock.addChild(TerminalNodeImpl(CommonToken(MiniKotlinParser.LBRACE)))
            elseBlock.addChild(TerminalNodeImpl(CommonToken(MiniKotlinParser.RBRACE)))
            ifCtx.addChild(elseBlock)

            elseBlock
        }

        val remaining = ctx.statement().subList(i + 1, ctx.statement().size)
        if (block.statement().lastOrNull()?.returnStatement() == null)
            block.children.addAll(block.children.size - 1, remaining)
        if (elseBlock.statement().lastOrNull()?.returnStatement() == null)
            elseBlock.children.addAll(elseBlock.children.size - 1, remaining)
        remaining.clear()
    }

    fun addWhileElse(ctx: MiniKotlinParser.BlockContext, i: Int) {
        val remaining = ctx.statement().subList(i + 1, ctx.statement().size)

        val block = MiniKotlinParser.BlockContext(ctx, ctx.invokingState)
        block.addChild(TerminalNodeImpl(CommonToken(MiniKotlinParser.LBRACE, "{")))
        for (r in remaining) block.addChild(r)
        block.addChild(TerminalNodeImpl(CommonToken(MiniKotlinParser.RBRACE, "}")))
        whileLoopElseBlocks.add(block)
        remaining.clear()
    }

    override fun visitBlock(ctx: MiniKotlinParser.BlockContext): String {
        blockFunCalls.add(0) // new block, new function calls
        var result = ""
        for (i in 0..<ctx.statement().size) {
            val statement = ctx.statement(i)

            // move rest of statements into if/else blocks
            val ifCtx = statement.ifStatement()
            if (ifCtx != null) {
                refactorIf(ctx, ifCtx, i)
                result += "${visit(ifCtx)}\n"
                break
            }

            // remove rest of statements to create modified while-else block
            val whileCtx = statement.whileStatement()
            if (whileCtx != null) {
                addWhileElse(ctx, i)
                result += "${visit(whileCtx)}\n"
                break
            }

            result += "${visit(statement)}\n"
        }

        result += "});".repeat(blockFunCalls.removeAt(blockFunCalls.lastIndex))
        return "{\n$result}"
    }

    fun wrapFunCalls(): String {
        var funCalls = ""
        for (i in 0..<currentExpressionFunCalls.size) {
            val funCall = currentExpressionFunCalls[i]
            val argI = blockFunCalls.sum() - currentExpressionFunCalls.size + i
            funCalls += "${funCall.first}(${funCall.second}, ($contArgName${argI}) -> {\n"
        }
        currentExpressionFunCalls.clear()
        return funCalls
    }

    override fun visitStatement(ctx: MiniKotlinParser.StatementContext): String {
        return when {
            ctx.ifStatement() != null -> visit(ctx.ifStatement())
            ctx.whileStatement() != null -> visit(ctx.whileStatement())

            // FunctionCallExpr
            ctx.expression() != null -> {
                visitChildren(ctx)
                wrapFunCalls()
            }

            else -> {
                val children = visitChildren(ctx)
                "${wrapFunCalls()}$children;"
            }
        }
    }

    override fun visitVariableDeclaration(ctx: MiniKotlinParser.VariableDeclarationContext): String {
        val varName = visit(ctx.IDENTIFIER())
        val type = visit(ctx.type())
        val expression = visit(ctx.expression())
        return "final $type[] $varName = {$expression}"
    }

    override fun visitVariableAssignment(ctx: MiniKotlinParser.VariableAssignmentContext): String {
        val varName = visit(ctx.IDENTIFIER())
        val expression = visit(ctx.expression())
        return "$varName[0] = $expression"
    }

    override fun visitIfStatement(ctx: MiniKotlinParser.IfStatementContext): String {
        val expr = visit(ctx.expression())
        val funCalls = wrapFunCalls()
        val block = visit(ctx.block(0))
        val elseBlockCtx = ctx.block(1)
        val elseBlock = if (elseBlockCtx != null) "else${visit(elseBlockCtx)}" else ""
        return "${funCalls}if($expr)$block$elseBlock"
    }

    override fun visitWhileStatement(ctx: MiniKotlinParser.WhileStatementContext): String {
        blockFunCalls.add(0) // we are defining lambda which is a block

        val condition = visit(ctx.expression())
        val funCalls = wrapFunCalls()
        val block = visit(ctx.block())
        val elseBlock = visit(whileLoopElseBlocks.removeAt(whileLoopElseBlocks.lastIndex))
        val funClose = "});".repeat(blockFunCalls.removeAt(blockFunCalls.lastIndex)) // close lambda block

        val whileLoopNoElse = """
Runnable $whileVarName$whileLoops = new Runnable() {
@Override
public void run() {
${funCalls}if($condition)${block}
        """.trimIndent()
        val lines = whileLoopNoElse.lines().toMutableList() // use lines to insert this.run();
        lines.add(lines.size - 1, "this.run();")
        val whileLoop = lines.joinToString("\n") + """else$elseBlock
$funClose
}
};
$whileVarName$whileLoops.run();
        """.trimIndent()

        whileLoops++
        return whileLoop
    }

    override fun visitReturnStatement(ctx: MiniKotlinParser.ReturnStatementContext): String {
        val expression = when (val expressionCtx = ctx.expression()) {
            null -> "null" // void return type
            else -> visit(expressionCtx)
        }
        return "$contFunName.accept($expression);\nreturn"
    }

    override fun visitFunctionCallExpr(ctx: MiniKotlinParser.FunctionCallExprContext): String {
        val funName = visit(ctx.IDENTIFIER())
        val argList = visit(ctx.argumentList())
        currentExpressionFunCalls.add(Pair(funName, argList))
        blockFunCalls[blockFunCalls.lastIndex]++
        return "$contArgName${blockFunCalls.sum() - 1}"
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

    override fun visitIdentifierExpr(ctx: MiniKotlinParser.IdentifierExprContext): String {
        val id = visit(ctx.IDENTIFIER())
        return if (funParams.contains(id) || id == "this") id else "$id[0]"
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
