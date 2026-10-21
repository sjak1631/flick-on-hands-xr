package com.flickxr.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** 合成した指先の軌跡で、高さのしきい値による判定とスライド判定の違いを確かめる。 */
class KeyDetectorTest {
    private val w = 0.10f
    private val hgt = 0.10f
    private val touch = 0.010f

    // 4x4 グリッドのセル中心
    private fun cx(col: Int) = (col + 0.5f) / FlickLayout.COLS
    private fun cy(row: Int) = (row + 0.5f) / FlickLayout.ROWS

    /** 60 Hz の軌跡。点は (x, y, 高さ m)。最後に十分上で止めて確定させる */
    private fun run(d: KeyDetector, points: List<Triple<Float, Float, Float>>): List<KeyAction> {
        val last = points.last()
        val all = points + List(10) { Triple(last.first, last.second, 0.05f) }
        return all.mapIndexedNotNull { i, (x, y, h) -> d.onFrame(TipFrame(i * 16L, x, y, h, w, hgt, touch)) }
    }

    /** a から b まで n 分割した点 */
    private fun line(a: Triple<Float, Float, Float>, b: Triple<Float, Float, Float>, n: Int) = (1..n).map { i ->
        val t = i.toFloat() / n
        Triple(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t, a.third + (b.third - a.third) * t)
    }

    /** 2 つの判定を同じフリックの境目（キーの 0.4 倍）で比べる */
    private fun both(points: List<Triple<Float, Float, Float>>) = run(ThresholdDetector(), points) to run(SlideDetector(flickRatio = 0.4f), points)

    @Test fun verticalTap() {
        val top = Triple(cx(1), cy(0), 0.04f)
        val bottom = Triple(cx(1), cy(0), 0f)
        val (th, sl) = both(listOf(top) + line(top, bottom, 5) + List(3) { bottom } + line(bottom, top, 5))
        assertEquals(listOf(KeyAction.Text("か")), th)
        assertEquals(listOf(KeyAction.Text("か")), sl)
    }

    @Test fun pressThenShift() {
        val top = Triple(cx(0), cy(0), 0.04f)
        val down = Triple(cx(0), cy(0), 0f)
        val shifted = Triple(cx(0) + 0.15f, cy(0), 0f) // 右に 15 mm
        val (th, sl) = both(listOf(top) + line(top, down, 4) + line(down, shifted, 5) + line(shifted, shifted.copy(third = 0.04f), 4))
        assertEquals(listOf(KeyAction.Text("え")), th)
        assertEquals(listOf(KeyAction.Text("え")), sl)
    }

    /** 着地してすぐ、右へ滑らせながら指を上げる。しきい値の判定は動き切る前に離したことになる */
    @Test fun slideWhileLifting() {
        val top = Triple(cx(0), cy(0), 0.04f)
        val down = Triple(cx(0), cy(0), 0f)
        val off = Triple(cx(0) + 0.18f, cy(0), 0.03f)
        val (th, sl) = both(listOf(top) + line(top, down, 4) + line(down, off, 6))
        assertEquals(listOf(KeyAction.Text("あ")), th)
        assertEquals(listOf(KeyAction.Text("え")), sl)
    }

    /** 「な」に触れてから、押し込みながら右へ大きく滑らせる（記録で多かった動き）。最も低くなるのは滑らせた先だが、キーは触れ始めで決まる */
    @Test fun slideWhilePressingDeeper() {
        val top = Triple(cx(1), cy(1), 0.04f)
        val touch = Triple(cx(1), cy(1), 0.008f)
        val deep = Triple(cx(3), cy(1), -0.01f)
        val (th, sl) = both(listOf(top) + line(top, touch, 3) + line(touch, deep, 6) + line(deep, deep.copy(third = 0.04f), 4))
        assertEquals(listOf(KeyAction.Text("ね")), th)
        assertEquals(listOf(KeyAction.Text("ね")), sl)
    }

    /** キーボードの外で触れてから中に滑り込んだら、キーの上に来た時点から始める */
    @Test fun slideStartsWhenEnteringKeyboard() {
        val outside = Triple(-0.04f, cy(0), 0.005f)
        val inside = Triple(0.08f, cy(0), 0.005f)
        val sl = run(SlideDetector(), line(outside, inside, 4) + List(3) { inside } + line(inside, inside.copy(third = 0.04f), 4))
        assertEquals(listOf(KeyAction.Text("あ")), sl)
    }

    /** しきい値（10 mm）に届かず 15 mm で折り返す単押し。初期設定では拾わず、浅い単押しを有効にすると拾う */
    @Test fun shallowTap() {
        val top = Triple(cx(2), cy(2), 0.035f)
        val bottom = Triple(cx(2), cy(2), 0.015f)
        val path = listOf(top) + line(top, bottom, 5) + line(bottom, top, 5)
        assertEquals(emptyList<KeyAction>(), run(ThresholdDetector(), path))
        assertEquals(emptyList<KeyAction>(), run(SlideDetector(), path))
        assertEquals(listOf(KeyAction.Text("ら")), run(SlideDetector(shallowTaps = true), path))
    }

    /** しきい値まで届いた単押しは、浅い単押しとして二重に数えない */
    @Test fun deepTapIsCountedOnce() {
        val top = Triple(cx(2), cy(2), 0.035f)
        val bottom = Triple(cx(2), cy(2), 0.0f)
        assertEquals(listOf(KeyAction.Text("ら")), run(SlideDetector(shallowTaps = true), listOf(top) + line(top, bottom, 5) + line(bottom, top, 5)))
    }

    /** キーの間を移動しながらの上下や、高いところでの上下は単押しにしない */
    @Test fun hoverBobbingIsNotATap() {
        val a = Triple(cx(0), cy(0), 0.035f)
        val dip = Triple(cx(1), cy(0), 0.015f)
        val b = Triple(cx(2), cy(0), 0.035f)
        assertEquals(emptyList<KeyAction>(), run(SlideDetector(shallowTaps = true), listOf(a) + line(a, dip, 5) + line(dip, b, 3)))
        val high = Triple(cx(1), cy(1), 0.06f)
        val highDip = Triple(cx(1), cy(1), 0.035f)
        assertEquals(emptyList<KeyAction>(), run(SlideDetector(shallowTaps = true), listOf(high) + line(high, highDip, 5) + line(highDip, high, 5)))
    }

    /** 単押しのまま 12 mm 流れても（キー 25 mm の 0.7 倍 = 17.5 mm 未満）フリックにしない */
    @Test fun slideIgnoresSmallDriftOnTap() {
        val top = Triple(cx(1), cy(0), 0.04f)
        val down = Triple(cx(1), cy(0), 0f)
        val drift = Triple(cx(1) - 0.12f, cy(0), 0f)
        val path = listOf(top) + line(top, down, 4) + line(down, drift, 8) + line(drift, drift.copy(third = 0.04f), 4)
        assertEquals(listOf(KeyAction.Text("き")), run(ThresholdDetector(), path))
        assertEquals(listOf(KeyAction.Text("か")), run(SlideDetector(), path))
    }

    /** 「離した」の高さまで上げずに ⌫ を 3 回押す */
    @Test fun repressWithoutLifting() {
        val top = Triple(cx(3), cy(0), 0.04f)
        val low = Triple(cx(3), cy(0), -0.005f)
        val mid = Triple(cx(3), cy(0), 0.008f) // しきい値 10 mm + 8 mm には届かない
        val path = listOf(top) + line(top, low, 4) + line(low, mid, 3) + line(mid, low, 3) + line(low, mid, 3) + line(mid, low, 3) + line(low, top, 4)
        assertEquals(listOf(KeyAction.Backspace), run(ThresholdDetector(), path))
        assertEquals(List(3) { KeyAction.Backspace }, run(SlideDetector(), path))
    }

    /** 「か」を押して、上げきらずに押し直して左へ滑らせる → か、き */
    @Test fun repressThenFlickOnSameKey() {
        val top = Triple(cx(1), cy(0), 0.04f)
        val low = Triple(cx(1), cy(0), -0.005f)
        val mid = Triple(cx(1), cy(0), 0.008f)
        val left = Triple(cx(1) - 0.25f, cy(0), -0.005f)
        val path = listOf(top) + line(top, low, 4) + line(low, mid, 3) + line(mid, low, 3) + line(low, left, 5) + line(left, left.copy(third = 0.04f), 4)
        assertEquals(listOf(KeyAction.Text("か"), KeyAction.Text("き")), run(SlideDetector(), path))
    }

    /** フリックで滑らせている途中の上下では分けない */
    @Test fun noRepressDuringFlick() {
        val top = Triple(cx(1), cy(1), 0.04f)
        val low = Triple(cx(1), cy(1), -0.005f)
        val right = Triple(cx(1) + 0.25f, cy(1), -0.005f)
        val bumpy = line(low, right, 6).mapIndexed { i, p -> if (i == 3) p.copy(third = 0.008f) else p }
        val path = listOf(top) + line(top, low, 4) + bumpy + line(right, right.copy(third = 0.04f), 4)
        assertEquals(listOf(KeyAction.Text("ね")), run(SlideDetector(), path))
    }

    @Test fun slideKeepsModeAcrossStrokes() {
        val d = SlideDetector()
        val top = Triple(cx(3), cy(3), 0.04f)
        val bottom = Triple(cx(3), cy(3), 0f)
        assertEquals(listOf(KeyAction.NextMode), run(d, listOf(top) + line(top, bottom, 4) + line(bottom, top, 4)))
        assertEquals(InputMode.ALPHA, d.mode)
        d.resetMode()
        assertEquals(InputMode.KANA, d.mode)
    }

    @Test fun slideCommitsWhenTrackingIsLost() {
        val d = SlideDetector()
        val top = Triple(cx(2), cy(1), 0.04f)
        val bottom = Triple(cx(2), cy(1), 0f)
        (listOf(top) + line(top, bottom, 4)).forEachIndexed { i, (x, y, h) -> d.onFrame(TipFrame(i * 16L, x, y, h, w, hgt, touch)) }
        assertEquals(KeyAction.Text("は"), d.onFrame(null))
    }
}
