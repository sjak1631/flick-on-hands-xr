package com.flickxr.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 手のひらに投影するフリックキーボードの描画。入力判定は [FlickEngine] / [HandKeyboard] が行い、
 * このビューは状態を描くだけ（パネルへのシステムのタッチ入力は無視する）。
 */
class FlickKeyboardView(context: Context, private val engine: FlickEngine) : View(context) {

    data class Cursor(val x: Float, val y: Float, val heightMeters: Float, val touching: Boolean)

    /** 指先カーソル（正規化座標）。null なら非表示。 */
    var cursor: Cursor? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 10, 14, 24) }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 50, 58, 78) }
    private val fnKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 32, 38, 54) }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 64, 140, 255) }
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 240, 244, 252) }
    private val popupSelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 64, 140, 255) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 220, 226, 240)
        textAlign = Paint.Align.CENTER
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val rect = RectF()
    private val kbRect = RectF()

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        kbRect.set(0f, 0f, width.toFloat(), height.toFloat())

        val cw = kbRect.width() / FlickLayout.COLS
        val ch = kbRect.height() / FlickLayout.ROWS
        val corner = minOf(cw, ch) * 0.14f

        canvas.drawRoundRect(kbRect, corner * 1.5f, corner * 1.5f, bgPaint)
        val pad = minOf(cw, ch) * 0.05f
        val press = engine.press
        for ((r, row) in engine.rows.withIndex()) {
            for ((c, key) in row.withIndex()) {
                rect.set(kbRect.left + c * cw + pad, kbRect.top + r * ch + pad, kbRect.left + (c + 1) * cw - pad, kbRect.top + (r + 1) * ch - pad)
                val pressed = press != null && press.row == r && press.col == c
                canvas.drawRoundRect(rect, corner, corner, if (pressed) pressedPaint else if (key.isFunction) fnKeyPaint else keyPaint)
                drawKeyLabel(canvas, key, rect)
            }
        }

        if (press != null) drawPopup(canvas, press, cw, ch, corner)
        cursor?.let { drawCursor(canvas, it, cw) }
    }

    private fun drawKeyLabel(canvas: Canvas, key: FlickKey, r: RectF) {
        val size = minOf(r.height(), r.width() * 1.2f) * if (key.label.length >= 3) 0.26f else 0.42f
        textPaint.textSize = size
        canvas.drawText(key.label, r.centerX(), r.centerY() + size * 0.36f, textPaint)
        if (key.isFunction) return
        // 四辺に候補のヒントを小さく出す（慣れていなくても使えるように）
        hintPaint.textSize = minOf(r.height(), r.width()) * 0.17f
        val off = hintPaint.textSize * 0.36f
        key.labelFor(FlickDir.LEFT)?.let { canvas.drawText(it, r.left + r.width() * 0.13f, r.centerY() + off, hintPaint) }
        key.labelFor(FlickDir.UP)?.let { canvas.drawText(it, r.centerX(), r.top + r.height() * 0.18f + off, hintPaint) }
        key.labelFor(FlickDir.RIGHT)?.let { canvas.drawText(it, r.right - r.width() * 0.13f, r.centerY() + off, hintPaint) }
        key.labelFor(FlickDir.DOWN)?.let { canvas.drawText(it, r.centerX(), r.bottom - r.height() * 0.14f + off, hintPaint) }
    }

    /** 押下中のキーを中心に、上下左右の候補を十字に表示する。 */
    private fun drawPopup(canvas: Canvas, press: FlickEngine.Press, cw: Float, ch: Float, corner: Float) {
        if (press.key.isFunction) return
        val cx = kbRect.left + (press.col + 0.5f) * cw
        val cy = kbRect.top + (press.row + 0.5f) * ch
        val s = minOf(cw, ch) * 0.92f
        val cells = listOf(
            FlickDir.NONE to (0f to 0f), FlickDir.LEFT to (-1f to 0f), FlickDir.UP to (0f to -1f),
            FlickDir.RIGHT to (1f to 0f), FlickDir.DOWN to (0f to 1f),
        )
        for ((dir, off) in cells) {
            val label = press.key.labelFor(dir) ?: continue
            val x = cx + off.first * s
            val y = cy + off.second * s
            rect.set(x - s / 2, y - s / 2, x + s / 2, y + s / 2)
            val selected = dir == press.dir
            canvas.drawRoundRect(rect, corner, corner, if (selected) popupSelPaint else popupPaint)
            textPaint.textSize = s * 0.5f
            textPaint.color = if (selected) Color.WHITE else Color.rgb(20, 24, 36)
            canvas.drawText(label, x, y + textPaint.textSize * 0.36f, textPaint)
        }
        textPaint.color = Color.WHITE
    }

    /** 指先の投影位置。高さが近いほど小さく、接触中は緑。 */
    private fun drawCursor(canvas: Canvas, c: Cursor, cw: Float) {
        val near = (c.heightMeters / 0.04f).coerceIn(0f, 1f)
        val radius = cw * (0.10f + 0.35f * near)
        cursorPaint.strokeWidth = cw * 0.05f
        cursorPaint.color = if (c.touching) Color.rgb(80, 230, 120) else Color.argb((255 * (1f - near * 0.6f)).toInt(), 255, 210, 80)
        canvas.drawCircle(kbRect.left + c.x * kbRect.width(), kbRect.top + c.y * kbRect.height(), radius, cursorPaint)
    }
}
