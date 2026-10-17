package com.flickxr.app

/** フリック方向。NONE はタップ（中央）。 */
enum class FlickDir { NONE, LEFT, UP, RIGHT, DOWN }

sealed interface KeyAction {
    data class Text(val text: String) : KeyAction
    data object Backspace : KeyAction
    data object Space : KeyAction
    data object Enter : KeyAction
    data object NextMode : KeyAction
    /** 直前の文字を 濁点 → 半濁点 → 小書き / 大文字 ↔ 小文字 で切り替える */
    data object Modify : KeyAction
}

/** 1 キー分の定義。flicks に無い方向は中央（タップ）扱い。 */
data class FlickKey(
    val label: String,
    val flicks: Map<FlickDir, KeyAction>,
    val isFunction: Boolean = false,
) {
    fun actionFor(dir: FlickDir): KeyAction = flicks[dir] ?: flicks.getValue(FlickDir.NONE)

    /** 方向ごとの表示文字（候補ポップアップ用）。 */
    fun labelFor(dir: FlickDir): String? = when (val a = flicks[dir]) {
        is KeyAction.Text -> a.text
        null -> null
        else -> if (dir == FlickDir.NONE) label else null
    }
}

enum class InputMode(val label: String) { KANA("かな"), ALPHA("ABC"), NUMBER("123") }

/**
 * 4 列 × 4 行のテンキー型フリック配列。右端の列は機能キー。
 * 文字列は「中央・左・上・右・下」の順（iOS / Android の日本語フリックと同じ並び）。
 */
object FlickLayout {
    const val COLS = 4
    const val ROWS = 4

    private val DIRS = listOf(FlickDir.NONE, FlickDir.LEFT, FlickDir.UP, FlickDir.RIGHT, FlickDir.DOWN)

    private fun chars(vararg items: String, label: String = items.first()): FlickKey =
        FlickKey(label, items.withIndex().filter { it.value.isNotEmpty() }
            .associate { DIRS[it.index] to KeyAction.Text(it.value) })

    private fun kana(s: String) = chars(*s.map { it.toString() }.toTypedArray())

    private fun fn(label: String, action: KeyAction) =
        FlickKey(label, mapOf(FlickDir.NONE to action), isFunction = true)

    private val backspace = fn("⌫", KeyAction.Backspace)
    private val space = fn("空白", KeyAction.Space)
    private val enter = fn("改行", KeyAction.Enter)
    private fun modeKey(mode: InputMode) = fn(InputMode.entries[(mode.ordinal + 1) % InputMode.entries.size].label, KeyAction.NextMode)
    private val modify = FlickKey("゛゜小", mapOf(FlickDir.NONE to KeyAction.Modify), isFunction = true)
    private val caseKey = FlickKey("a/A", mapOf(FlickDir.NONE to KeyAction.Modify), isFunction = true)

    private val kanaRows: List<List<FlickKey>> = listOf(
        listOf(kana("あいうえお"), kana("かきくけこ"), kana("さしすせそ"), backspace),
        listOf(kana("たちつてと"), kana("なにぬねの"), kana("はひふへほ"), space),
        listOf(kana("まみむめも"), chars("や", "「", "ゆ", "」", "よ"), kana("らりるれろ"), enter),
        listOf(modify, chars("わ", "を", "ん", "ー", ""), chars("、", "。", "？", "！", "…"), modeKey(InputMode.KANA)),
    )

    private val alphaRows: List<List<FlickKey>> = listOf(
        listOf(chars("@", "#", "/", "&", "_", label = "@#/&_"), chars("a", "b", "c", label = "abc"), chars("d", "e", "f", label = "def"), backspace),
        listOf(chars("g", "h", "i", label = "ghi"), chars("j", "k", "l", label = "jkl"), chars("m", "n", "o", label = "mno"), space),
        listOf(chars("p", "q", "r", "s", label = "pqrs"), chars("t", "u", "v", label = "tuv"), chars("w", "x", "y", "z", label = "wxyz"), enter),
        listOf(caseKey, chars("'", "\"", "(", ")", "", label = "'\"()"), chars(".", ",", "?", "!", "", label = ".,?!"), modeKey(InputMode.ALPHA)),
    )

    private val numberRows: List<List<FlickKey>> = listOf(
        listOf(chars("1", "☆", "♪", "→", ""), chars("2", "¥", "$", "€", ""), chars("3", "%", "°", "#", ""), backspace),
        listOf(chars("4", "○", "*", "・", ""), chars("5", "+", "×", "÷", ""), chars("6", "<", "=", ">", ""), space),
        listOf(chars("7", "「", "」", ":", ""), chars("8", "〒", "々", "〆", ""), chars("9", "^", "|", "\\", ""), enter),
        listOf(chars("(", ")", "[", "]", ""), chars("0", "〜", "…", "", ""), chars(".", ",", "-", "/", ""), modeKey(InputMode.NUMBER)),
    )

    fun rows(mode: InputMode): List<List<FlickKey>> = when (mode) {
        InputMode.KANA -> kanaRows
        InputMode.ALPHA -> alphaRows
        InputMode.NUMBER -> numberRows
    }

    /** 正規化座標 (x: 左→右, y: 上→下, いずれも 0..1) にあるキーの (row, col)。範囲外なら null。 */
    fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (x !in 0f..1f || y !in 0f..1f) return null
        val col = (x * COLS).toInt().coerceAtMost(COLS - 1)
        val row = (y * ROWS).toInt().coerceAtMost(ROWS - 1)
        return row to col
    }
}
