package com.flickxr.app

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 指先の位置（キーボード面上の正規化座標）と接触状態からフリック操作を判定するステートマシン。
 * XR に依存しないので JVM 単体テストできる。
 *
 * @param widthMeters  キーボードの実寸幅（フリック距離をメートルで判定するため）
 * @param heightMeters キーボードの実寸高さ
 */
class FlickEngine(
    var widthMeters: Float,
    var heightMeters: Float,
    var flickThresholdMeters: Float = 0.010f,
) {
    data class Press(val row: Int, val col: Int, val key: FlickKey, val startX: Float, val startY: Float, val dir: FlickDir)

    var mode: InputMode = InputMode.KANA
        private set
    var press: Press? = null
        private set

    val rows: List<List<FlickKey>> get() = FlickLayout.rows(mode)

    /**
     * 1 フレーム分の入力。指が離れた瞬間に確定したアクションを返す（それ以外は null）。
     * NextMode はエンジン内でモードを切り替えたうえで返す。
     */
    fun onFrame(x: Float, y: Float, touching: Boolean): KeyAction? {
        val p = press
        if (p == null) {
            if (touching) {
                val (row, col) = FlickLayout.cellAt(x, y) ?: return null
                press = Press(row, col, rows[row][col], x, y, FlickDir.NONE)
            }
            return null
        }
        if (touching) {
            press = p.copy(dir = directionOf(x - p.startX, y - p.startY))
            return null
        }
        press = null
        val action = p.key.actionFor(p.dir)
        if (action == KeyAction.NextMode) mode = InputMode.entries[(mode.ordinal + 1) % InputMode.entries.size]
        return action
    }

    /** トラッキングを見失ったときなど、確定せずに押下を破棄する。 */
    fun cancel() {
        press = null
    }

    private fun directionOf(dxNorm: Float, dyNorm: Float): FlickDir {
        val dx = dxNorm * widthMeters
        val dy = dyNorm * heightMeters
        if (hypot(dx, dy) < flickThresholdMeters) return FlickDir.NONE
        return if (abs(dx) > abs(dy)) {
            if (dx < 0) FlickDir.LEFT else FlickDir.RIGHT
        } else {
            if (dy < 0) FlickDir.UP else FlickDir.DOWN
        }
    }
}

/** 入力中の文字列（カーソルは常に末尾）。 */
class TextBuffer {
    private val sb = StringBuilder()
    val text: String get() = sb.toString()

    fun apply(action: KeyAction) {
        when (action) {
            is KeyAction.Text -> sb.append(action.text)
            KeyAction.Backspace -> if (sb.isNotEmpty()) sb.deleteAt(sb.lastIndex)
            KeyAction.Space -> sb.append(if (sb.lastOrNull()?.code?.let { it < 0x3000 } != false) ' ' else '　')
            KeyAction.Enter -> sb.append('\n')
            KeyAction.Modify -> sb.lastOrNull()?.let(KanaModifier::modify)?.let { sb.setCharAt(sb.lastIndex, it) }
            KeyAction.NextMode -> Unit
        }
    }

    fun clear() = sb.clear()
}
