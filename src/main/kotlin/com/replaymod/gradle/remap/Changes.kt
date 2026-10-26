package com.replaymod.gradle.remap

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.util.TreeMap

internal class Changes(
    val file: PsiFile,
) {
    private val errors = mutableListOf<Pair<Int, String>>()
    val changes = TreeMap<TextRange, String>(compareBy<TextRange> { it.startOffset }.thenBy { it.endOffset })

    fun error(at: PsiElement, message: String) {
        val line = StringUtil.offsetToLineNumber(file.text, at.textOffset)
        errors.add(Pair(line, message))
    }

    fun replace(e: PsiElement, with: String) = replace(e.textRange, with)
    fun replace(textRange: TextRange, with: String) {
        changes.compute(textRange) { _, replacement ->
            if (replacement != null) {
                replacement + with
            } else {
                with
            }
        }
    }

    fun isUnchanged(e: PsiElement): Boolean = isUnchanged(e.textRange)

    fun isUnchanged(range: TextRange): Boolean {
        val before = changes.floorKey(range) ?: TextRange.EMPTY_RANGE
        val after = changes.ceilingKey(range) ?: TextRange.EMPTY_RANGE
        return !before.intersectsStrict(range) && !after.intersectsStrict(range)
    }

    fun getResult(): Pair<String, List<Pair<Int, String>>> {
        var result = file.text
        for ((key, value) in changes.descendingMap()) {
            result = key.replace(result, value)
        }
        return Pair(result, errors)
    }
}
