package com.example.pyrunner

import android.text.Editable
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

/**
 * EditText에 붙여서 파이썬 코드에 실시간으로 색을 입혀주는 간단한 문법 하이라이터.
 * 정규식 한 번으로 comment / string / number / keyword / builtin / function-def 를
 * 우선순위대로 매칭해서, 겹치지 않게 ForegroundColorSpan을 적용한다.
 */
class SyntaxHighlighter(
    private val colorKeyword: Int,
    private val colorString: Int,
    private val colorComment: Int,
    private val colorNumber: Int,
    private val colorFunction: Int,
    private val colorBuiltin: Int
) {

    companion object {
        private val KEYWORDS = setOf(
            "False", "None", "True", "and", "as", "assert", "async", "await",
            "break", "class", "continue", "def", "del", "elif", "else", "except",
            "finally", "for", "from", "global", "if", "import", "in", "is",
            "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try",
            "while", "with", "yield"
        )

        private val BUILTINS = setOf(
            "print", "input", "len", "range", "str", "int", "float", "bool",
            "list", "dict", "set", "tuple", "type", "open", "abs", "min", "max",
            "sum", "sorted", "enumerate", "zip", "map", "filter", "isinstance",
            "super", "self"
        )

        // 그룹 이름: COMMENT, STRING, NUMBER, DEFNAME(함수/클래스 이름), WORD(키워드 후보)
        private val TOKEN_PATTERN = Pattern.compile(
            "(?<COMMENT>#[^\\n]*)" +
                "|(?<STRING>\"\"\".*?\"\"\"|'''.*?'''|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')" +
                "|(?<NUMBER>\\b\\d+(\\.\\d+)?\\b)" +
                "|\\b(?:def|class)\\s+(?<DEFNAME>[A-Za-z_][A-Za-z0-9_]*)" +
                "|(?<WORD>\\b[A-Za-z_][A-Za-z0-9_]*\\b)",
            Pattern.DOTALL
        )
    }

    /** editable의 기존 색상 스팬을 지우고 다시 계산해서 적용한다. */
    fun highlight(editable: Editable) {
        val text = editable.toString()

        // 이 클래스가 붙인 스팬만 제거 (다른 용도의 스팬은 건드리지 않음)
        val old = editable.getSpans(0, editable.length, HighlightSpan::class.java)
        for (span in old) editable.removeSpan(span)

        val matcher = TOKEN_PATTERN.matcher(text)
        while (matcher.find()) {
            val color = when {
                matcher.group("COMMENT") != null -> colorComment
                matcher.group("STRING") != null -> colorString
                matcher.group("NUMBER") != null -> colorNumber
                matcher.group("DEFNAME") != null -> {
                    applySpan(editable, matcher.start("DEFNAME"), matcher.end("DEFNAME"), colorFunction)
                    continue
                }
                matcher.group("WORD") != null -> {
                    val word = matcher.group("WORD") ?: continue
                    when {
                        KEYWORDS.contains(word) -> colorKeyword
                        BUILTINS.contains(word) -> colorBuiltin
                        else -> continue
                    }
                }
                else -> continue
            }
            applySpan(editable, matcher.start(), matcher.end(), color)
        }
    }

    private fun applySpan(editable: Editable, start: Int, end: Int, color: Int) {
        if (start < 0 || end > editable.length || start >= end) return
        editable.setSpan(HighlightSpan(color), start, end, Editable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** 하이라이터가 붙인 스팬만 구분해서 지울 수 있도록 하는 마커 서브클래스 */
    private class HighlightSpan(color: Int) : ForegroundColorSpan(color)
}