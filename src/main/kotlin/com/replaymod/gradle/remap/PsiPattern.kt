package com.replaymod.gradle.remap

import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.*
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset

internal class PsiPattern(
        private val parameters: List<String>,
        private val pattern: PsiStatement,
        private val replacement: List<String>
) {
    private fun find(pattern: PsiElement, tree: PsiElement, result: MutableList<Matcher>) {
        tree.accept(object : JavaRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val matcher = Matcher(element)
                if (matcher.match(pattern)) {
                    result.add(matcher)
                } else {
                    super.visitElement(element)
                }
            }
        })
    }

    fun find(statements: Array<PsiStatement>, result: MutableList<Matcher>) {
        for (statement in statements) {
            when (pattern) {
                is PsiReturnStatement -> find(pattern.returnValue!!, statement, result)
                else -> find(pattern, statement, result)
            }
        }
    }

    fun find(expr: PsiExpression, result: MutableList<Matcher>) {
        when (pattern) {
            is PsiReturnStatement -> find(pattern.returnValue!!, expr, result)
            else -> find(pattern, expr, result)
        }
    }

    inner class Matcher(private val root: PsiElement, private val arguments: MutableList<PsiElement> = mutableListOf()) {

        fun toChanges(): List<Pair<TextRange, String>> {
            val sortedArgs = arguments.toList().sortedBy { it.startOffset }
            val changes = mutableListOf<Pair<TextRange, String>>()

            val replacementIter = replacement.iterator()
            var start = root.startOffset
            for (argPsi in sortedArgs) {
                changes.push(Pair(TextRange(start, argPsi.startOffset), replacementIter.next()))
                start = argPsi.endOffset
            }
            changes.push(Pair(TextRange(start, root.endOffset), replacementIter.next()))

            return changes.filterNot { it.first.isEmpty && it.second.isEmpty() }
        }

        fun match(pattern: PsiElement): Boolean = match(pattern, root)

        private fun match(pattern: PsiElement?, expr: PsiElement?): Boolean = when (pattern) {
            null -> expr == null
            is PsiAssignmentExpression -> expr is PsiAssignmentExpression
                    && match(pattern.lExpression, expr.lExpression)
                    && match(pattern.rExpression!!, expr.rExpression!!)
            is PsiBlockStatement -> expr is PsiBlockStatement
                    && pattern.codeBlock.statementCount == expr.codeBlock.statementCount
                    && pattern.codeBlock.statements.asSequence().zip(expr.codeBlock.statements.asSequence())
                    .all { (pattern, expr) -> match(pattern, expr) }
            is PsiReferenceExpression -> expr is PsiExpression
                    && match(pattern, expr)
            is PsiMethodCallExpression -> expr is PsiMethodCallExpression
                    && match(pattern.methodExpression, expr.methodExpression)
                    && match(pattern.argumentList, expr.argumentList)
            is PsiExpressionList -> expr is PsiExpressionList
                    && pattern.expressionCount == expr.expressionCount
                    && pattern.expressions.asSequence().zip(expr.expressions.asSequence())
                    .all { (pattern, expr) -> match(pattern, expr) }
            is PsiExpressionStatement -> expr is PsiExpressionStatement
                    && match(pattern.expression, expr.expression)
            is PsiTypeCastExpression -> expr is PsiTypeCastExpression
                    && match(pattern.operand, expr.operand)
            is PsiParenthesizedExpression -> expr is PsiParenthesizedExpression
                    && match(pattern.expression, expr.expression)
            is PsiNewExpression -> expr is PsiNewExpression
                    && match(pattern.argumentList, expr.argumentList)
            else -> false
        }

        private fun match(pattern: PsiReferenceExpression, expr: PsiExpression): Boolean {
            return if (pattern.firstChild is PsiReferenceParameterList && pattern.referenceName in parameters) {
                val patternType = pattern.type ?: return false
                val exprType = expr.type ?: return false
                if (patternType.isAssignableFrom(exprType)) {
                    arguments.add(expr)
                    true
                } else {
                    false
                }
            }
            else expr is PsiReferenceExpression
                    && pattern.referenceName == expr.referenceName
                    && match(pattern.qualifierExpression, expr.qualifierExpression)
        }
    }
}